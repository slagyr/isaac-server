(ns isaac.server.app-spec
  (:require
     [c3kit.apron.refresh :as refresh]
     [isaac.config.runtime :as runtime]
     [isaac.fs :as fs]
     [isaac.comm.delivery.worker :as worker]
     [isaac.logger :as log]
     [isaac.marigold-server :as marigold-server]
     [isaac.server.test-store]
     [isaac.module.loader :as module-loader]
     [isaac.scheduler.runtime :as scheduler-core]
     [isaac.server.app :as sut]
     [isaac.nexus :as nexus]
     [isaac.spec-helper :as helper]
     [org.httpkit.server :as httpkit]
     [speclj.core :refer :all]))

(describe "Server app"

  (marigold-server/with-manifest)
  (helper/with-captured-logs)

  ;; Default stubs so each test boots in microseconds, not seconds. The
  ;; main offender was runtime/watch-service-source — it starts a real
  ;; FSwatcher and sleeps 1s for FSEvents to settle (change_source_bb.clj).
  ;; httpkit / scheduler / workers are stubbed so the suite doesn't bind
  ;; real ports, spawn real thread pools, or start real polling workers.
  ;; Tests that need to capture or assert against a specific stub re-stub
  ;; it locally.
  (redefs-around [runtime/watch-service-source (fn [_] nil)
                  httpkit/run-server          (fn [_ _] (fn [] nil))
                  httpkit/server-port         (fn [_] 7001)
                  httpkit/server-stop!        (fn [_] nil)])

  (after (sut/stop!))

  (it "starts the server and returns a port"
    (let [result (sut/start! {:port 0 :host "127.0.0.1"})]
      (should (pos-int? (:port result)))))

  (it "returns the bound host in the result"
    (let [result (sut/start! {:port 0 :host "127.0.0.1"})]
      (should= "127.0.0.1" (:host result))))

  (it "defaults to 127.0.0.1 when no host given"
    (let [result (sut/start! {:port 0 :cfg {:server {:auth {:token "s3cr3t"}}}})]
      (should= "127.0.0.1" (:host result))))

  (it "resolves the bound port from config when no :port override is given"
    (let [bound (atom nil)]
      (with-redefs [httpkit/run-server   (fn [_ opts] (reset! bound (:port opts)) (fn [] nil))
                    httpkit/server-port  (fn [_] @bound)
                    httpkit/server-stop! (fn [_] nil)]
        (sut/start! {:cfg {:server {:port 8888 :auth {:token "s3cr3t"}}}})
        (sut/stop!))
      (should= 8888 @bound)))

  (it "resolves the bound host from config when no :host override is given"
    (let [bound (atom nil)]
      (with-redefs [httpkit/run-server   (fn [_ opts] (reset! bound (:ip opts)) (fn [] nil))
                    httpkit/server-port  (fn [_] 0)
                    httpkit/server-stop! (fn [_] nil)]
        (sut/start! {:cfg {:server {:host "0.0.0.0" :auth {:token "x"}}}})
        (sut/stop!))
      (should= "0.0.0.0" @bound)))

  (it "reports running? as true after start"
    (sut/start! {:port 0 :host "127.0.0.1"})
    (should (sut/running?)))

  (it "reports running? as false before start"
    (should-not (sut/running?)))

  (it "stops the server"
    (sut/start! {:port 0 :host "127.0.0.1"})
    (sut/stop!)
    (should-not (sut/running?)))

  (it "initializes code refresh and wraps handler in dev mode"
    (let [init-args   (atom nil)
          wrapped-sym (atom nil)
          captured    (atom nil)]
      (with-redefs [refresh/init                        (fn [services ns-prefix excludes]
                                                          (reset! init-args [services ns-prefix excludes]))
                    refresh/refresh-handler             (fn [root-sym]
                                                          (reset! wrapped-sym root-sym)
                                                          (fn [_request] {:status 200}))
                    httpkit/run-server                  (fn [handler _opts]
                                                          (reset! captured handler)
                                                          (fn [] nil))
                    httpkit/server-port                 (fn [_] 7777)
                    httpkit/server-stop!                (fn [_] nil)]
        (sut/start! {:port 0 :host "127.0.0.1" :dev true})
        (sut/stop!))
      (should= "isaac" (second @init-args))
      (should= [] (nth @init-args 2))
      (should= 'isaac.server.http/root-handler @wrapped-sym)
      (should-not-be-nil @captured)))

  (it "logs dev mode enabled when started in dev mode"
    (with-redefs [refresh/init            (fn [_ _ _] nil)
                  refresh/refresh-handler (fn [_] (fn [_request] {:status 200}))
                  httpkit/run-server      (fn [_ _] (fn [] nil))
                  httpkit/server-port     (fn [_] 7001)
                  httpkit/server-stop!    (fn [_] nil)]
      (sut/start! {:port 0 :host "127.0.0.1" :dev true})
      (sut/stop!))
    (let [entry (first (filter #(= :server/dev-mode-enabled (:event %)) @log/captured-logs))]
      (should-not-be-nil entry)
      (should= "127.0.0.1" (:host entry))
      (should= 7001 (:port entry))))

  (it "processes route berth contributions from every declared module at startup"
    (let [seen-indexes (atom [])]
      (with-redefs [httpkit/run-server                  (fn [_ _] (fn [] nil))
                    httpkit/server-port                 (fn [_] 7001)
                    httpkit/server-stop!                (fn [_] nil)
                    module-loader/reconcile-modules!        (fn [_] :started)
                    module-loader/process-manifest-berths! (fn [module-index]
                                                             (swap! seen-indexes conj module-index)
                                                             [])]
        (sut/start! {:host "127.0.0.1"
                     :port 0
                     :cfg  {:module-index
                            {:isaac.fake.pigeon
                             {:manifest {:isaac.server/route [{:method :get :path "/pigeon"
                                                               :handler 'isaac.fake.pigeon/handler}]}}
                             :isaac.fake.crow
                             {:manifest {:isaac.server/route [{:method :get :path "/crow"
                                                               :handler 'isaac.fake.crow/handler}]}}}}})
        (sut/stop!))
      (let [combined (apply merge @seen-indexes)]
        (should-not-be-nil (get combined :isaac.fake.pigeon))
        (should-not-be-nil (get combined :isaac.fake.crow)))))

  (it "starts loaded modules during server boot"
    (let [started (atom nil)]
      (with-redefs [httpkit/run-server           (fn [_ _] (fn [] nil))
                    httpkit/server-port          (fn [_] 7001)
                    httpkit/server-stop!         (fn [_] nil)
                    module-loader/reconcile-modules! (fn [module-index]
                                                   (reset! started module-index)
                                                   :started)]
        (sut/start! {:host "127.0.0.1"
                     :port 0
                     :cfg  {:module-index
                            {:isaac.fake.pigeon
                             {:manifest {:factory 'isaac.fake.pigeon/create-module}}}}})
        (sut/stop!))
      (should (contains? @started :isaac.foundation))
      (should (contains? @started :isaac.fake.pigeon))))

  (it "starts the delivery worker when the server has a state dir"
    (let [started (atom nil)]
      (with-redefs [httpkit/run-server       (fn [_ _] (fn [] nil))
                    httpkit/server-port      (fn [_] 7001)
                    httpkit/server-stop!     (fn [_] nil)
                    scheduler-core/create    (fn [_] ::scheduler)
                    scheduler-core/start!    identity
                    scheduler-core/shutdown! (fn [_] nil)
                    worker/start!            (fn [opts]
                                               (reset! started opts)
                                               ::worker)]
        (sut/start! {:host      "127.0.0.1"
                     :port      0
                     :root "/tmp/isaac"
                     :cfg       {}})
        (sut/stop!))
      (should= {} @started)))

  (it "registers the shared scheduler in isaac.nexus when the server has a state dir"
    (with-redefs [httpkit/run-server       (fn [_ _] (fn [] nil))
                  httpkit/server-port      (fn [_] 7001)
                  httpkit/server-stop!     (fn [_] nil)
                  scheduler-core/create    (fn [_] ::scheduler)
                  scheduler-core/start!    identity
                  scheduler-core/shutdown! (fn [_] nil)
                  worker/start!            (fn [_] ::worker)]
      (sut/start! {:host      "127.0.0.1"
                   :port      0
                   :root "/tmp/isaac"
                   :cfg       {}})
      (should= ::scheduler (nexus/get :scheduler))
      (sut/stop!)))

  (it "passes the configured state dir to the lifecycle reconciler"
    (let [captured (atom nil)]
      (with-redefs [httpkit/run-server   (fn [_ _] (fn [] nil))
                    httpkit/server-port  (fn [_] 7001)
                    httpkit/server-stop! (fn [_] nil)
                    runtime/reconcile! (fn [host _old _new _registry]
                                           (reset! captured host))]
        (sut/start! {:host      "127.0.0.1"
                     :port      0
                     :home      "/tmp/service-home"
                     :root "/tmp/service-home/.isaac"
                     :cfg       {}})
        (sut/stop!))
      (should= "/tmp/service-home/.isaac" (:root @captured))
      (should= nil (:connect-ws! @captured))
      (should (contains? (:module-index @captured) :isaac.foundation))))

  (it "returns nil and does not start services when config validation fails"
    (let [started (atom nil)]
      (with-redefs [runtime/validate-config! (fn [_ _] [{:key "server.port" :value "bad"}])
                    httpkit/run-server    (fn [& _] (reset! started :http))
                    worker/start!         (fn [& _] (reset! started :worker))]
        (should= nil (sut/start! {:cfg {:server {:port 6674}}}))
        (should= nil @started)
        (should-not (sut/running?)))))

  (it "returns nil and logs auth-required when binding non-loopback without a server auth token"
    (with-redefs [httpkit/run-server (fn [& _] (throw (ex-info "should not start http" {})))]
      (should= nil (sut/start! {:cfg  {:server {:host "0.0.0.0"}}
                                :host "0.0.0.0"
                                :port 0})))
    (let [entry (first (filter #(= :server/auth-required (:event %)) @log/captured-logs))]
      (should-not-be-nil entry)
      (should= "0.0.0.0" (:host entry))
      (should-contain ":server :auth :token" (:message entry))))

  (it "skips HTTP startup when :start-http-server? is false"
    (let [started-http (atom nil)
          started      (atom nil)]
      (with-redefs [httpkit/run-server      (fn [& _] (reset! started-http true))
                    scheduler-core/create   (fn [_] ::scheduler)
                    scheduler-core/start!   identity
                    scheduler-core/shutdown! (fn [_] nil)
                    worker/start!           (fn [opts] (reset! started opts) ::worker)
                    worker/stop!            (fn [_] nil)]
        (should= {:port 7777 :host "127.0.0.1"}
                 (sut/start! {:cfg                {}
                              :port               7777
                              :host               "127.0.0.1"
                              :root          "/tmp/isaac"
                              :start-http-server? false}))
        (sut/stop!))
      (should= nil @started-http)
      (should= {} @started)))

  (it "stops the delivery worker with the server"
    (let [stopped (atom nil)]
      (with-redefs [httpkit/run-server      (fn [_ _] (fn [] nil))
                    httpkit/server-port     (fn [_] 7001)
                    httpkit/server-stop!    (fn [_] nil)
                    scheduler-core/create   (fn [_] ::scheduler)
                    scheduler-core/start!   identity
                    scheduler-core/shutdown! (fn [_] nil)
                    worker/start!           (fn [_] ::worker)
                    worker/stop!            (fn [worker]
                                              (reset! stopped worker))]
        (sut/start! {:host      "127.0.0.1"
                     :port      0
                     :root "/tmp/isaac"
                     :cfg       {}})
        (sut/stop!))
       (should= ::worker @stopped)))

  (it "shuts down the shared scheduler with the server"
    (let [stopped (atom nil)]
      (with-redefs [httpkit/run-server       (fn [_ _] (fn [] nil))
                    httpkit/server-port      (fn [_] 7001)
                    httpkit/server-stop!     (fn [_] nil)
                    scheduler-core/create    (fn [_] ::scheduler)
                    scheduler-core/start!    identity
                    scheduler-core/shutdown! (fn [scheduler]
                                                (reset! stopped scheduler))
                    worker/start!            (fn [_] ::worker)
                    worker/stop!             (fn [_] nil)]
        (sut/start! {:host      "127.0.0.1"
                     :port      0
                     :root "/tmp/isaac"
                     :cfg       {}})
        (sut/stop!))
      (should= ::scheduler @stopped)))

  (it "shuts down loaded modules with the server"
    (let [stopped (atom 0)]
      (with-redefs [httpkit/run-server              (fn [_ _] (fn [] nil))
                    httpkit/server-port             (fn [_] 7001)
                    httpkit/server-stop!            (fn [_] nil)
                    module-loader/reconcile-modules!    (fn [_] :started)
                    module-loader/shutdown-modules! (fn []
                                                     (swap! stopped inc)
                                                     :stopped)]
        (sut/start! {:host "127.0.0.1" :port 0 :cfg {}})
        (sut/stop!))
      (should= 1 @stopped)))

  (it "creates and starts a config change source from the state dir"
    (let [created (atom nil)
          started (atom nil)]
      (with-redefs [runtime/watch-service-source (fn [home]
                                                         (reset! created home)
                                                         ::source)
                    runtime/start!               (fn [source]
                                                         (reset! started source)
                                                         source)
                    runtime/stop!                (fn [_] nil)
                    httpkit/run-server                 (fn [_ _] (fn [] nil))
                    httpkit/server-port                (fn [_] 7001)
                    httpkit/server-stop!               (fn [_] nil)]
        (sut/start! {:host "127.0.0.1" :port 0 :root "/tmp/isaac-home/.isaac"})
        (sut/stop!))
      (should= "/tmp/isaac-home/.isaac" @created)
      (should= ::source @started)))

  (it "does not create a config change source when hot reload is disabled"
    (let [created (atom nil)
          started (atom nil)]
      (with-redefs [runtime/watch-service-source (fn [home]
                                                         (reset! created home)
                                                         ::source)
                    runtime/start!               (fn [source]
                                                         (reset! started source)
                                                         source)
                    runtime/stop!                (fn [_] nil)
                    httpkit/run-server                 (fn [_ _] (fn [] nil))
                    httpkit/server-port                (fn [_] 7001)
                    httpkit/server-stop!               (fn [_] nil)]
        (sut/start! {:host "127.0.0.1" :port 0 :root "/tmp/isaac" :cfg {:server {:hot-reload false}}})
        (sut/stop!))
      (should= nil @created)
      (should= nil @started)))

  (it "stops the config change source with the server"
    (let [stopped (atom nil)]
      (with-redefs [runtime/start!     identity
                    runtime/stop!      (fn [source]
                                               (reset! stopped source))
                    httpkit/run-server       (fn [_ _] (fn [] nil))
                    httpkit/server-port      (fn [_] 7001)
                    httpkit/server-stop!     (fn [_] nil)]
        (sut/start! {:host "127.0.0.1" :port 0 :config-change-source ::source})
        (sut/stop!))
      (should= ::source @stopped)))

  )

;; mutation-tested: 2026-05-06
(ns isaac.server.app
  (:require
    [clojure.string :as str]
    [isaac.comm.registry :as comm-registry]
    [isaac.component.protocol :as component]
    [isaac.component.registry :as component-registry]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.config.runtime :as runtime]
    [isaac.config.server-config :as server-config]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [isaac.runner :as runner]
    [isaac.server.http :as http]
    [isaac.server.logging :as server-logging])
  (:import
    (java.time Duration Instant)))

(defonce state (atom nil))
(defonce ^:private stopping? (atom false))

(declare stop!)

(defn- take-running-state! []
  (loop []
    (let [s @state]
      (cond
        (nil? s) nil
        (compare-and-set! state s nil) s
        :else (recur)))))

(defn running? []
  (some? @state))

(defn current-config []
  (loader/snapshot "server/current-config accessor"))

(def ^:private optional-registry-syms
  '[isaac.hail.bands/registry
    isaac.hooks/registry
    isaac.cron.service/registry])

(defn- resolve-var [sym]
  (try (requiring-resolve sym) (catch Throwable _ nil)))

(defn- resolve-registry [sym]
  (when-let [v (resolve-var sym)]
    (if (var? v) @v v)))

(defn registries []
  (vec (keep resolve-registry optional-registry-syms)))

(defn- start-config-reloader! [source root host comm-registry registries]
  ;; The reloader manages the live runtime: runtime/reload! reconciles components
  ;; directly into the (global) nexus, so we must NOT capture+restore a runtime
  ;; snapshot the way bound-runtime-fn does for one-shot deferred work — that
  ;; would discard the reconcile. bound-fn still propagates dynamic var bindings.
  (let [reload! (bound-fn [path]
                  (runtime/reload! {:root          root
                                    :fs            (fs/instance)
                                    :old-config    (loader/snapshot "reload: previous config for the reconcile diff")
                                    :comm-registry comm-registry
                                    :registries    registries
                                    :host          host
                                    :path          path}))]
    (future
      (loop []
        (when-let [path (runtime/poll! source 5000)]
          (reload! path))
        (recur)))))

(defn- host-context [cfg root connect-ws!]
  {:connect-ws! connect-ws!
   :module-index (:module-index cfg)
   :root root})

(defn- start-config-source [opts hot-reload? root]
  (or (:config-change-source opts)
      (when (and hot-reload? root)
        (runtime/watch-service-source root))))

(defn- auth-required? [cfg host start-http-server?]
  (and start-http-server?
       (not (http/loopback-host? host))
       (str/blank? (get-in cfg [:server :auth :token]))))

(defn- reset-server-state!
  [host-ctx comm-registry registries config-source connect-ws! reloader scheduler
   server actual host start-http-server?]
  (reset! state {:host-ctx           host-ctx
                 :registry           comm-registry
                 :registries         registries
                 :config-source      config-source
                 :connect-ws!        connect-ws!
                 :reloader           reloader
                 :scheduler          scheduler
                 :server             server
                 :port               actual
                 :host               host
                 :start-http-server? start-http-server?
                 :started-at         (Instant/now)}))

(defn- before-components!
  [startup {:keys [config module-index opts]}]
  (let [root          (:root opts)
        registries    (:registries @startup)
        host-ctx      (:host-ctx @startup)
        scheduler     (nexus/get :scheduler)
        config-source (start-config-source opts (:hot-reload (server-config/server-config config)) root)]
    (runtime/install! {:config config :registries registries :host host-ctx})
    (runtime/install-config-berths! {:config config :module-index module-index})
    (some-> config-source runtime/start!)
    (swap! startup assoc
           :config-source config-source
           :scheduler scheduler)))

(defn- after-components!
  [startup {:keys [opts]}]
  (let [{:keys [comm-registry config-source host-ctx registries scheduler]} @startup
        root          (:root opts)
        start-http?   (not (false? (:start-http-server? opts)))
        server        (component-registry/instance-for :http)
        actual        (if start-http?
                        (component/bound-port server)
                        (:port opts))
        reloader      (when (and config-source root
                                 (not (false? (:start-config-reloader? opts))))
                        (start-config-reloader! config-source root host-ctx comm-registry registries))]
    (log/info :server/boot-summary (module-loader/boot-stats (:module-index host-ctx)))
    (reset-server-state! host-ctx comm-registry registries config-source
                         (:connect-ws! opts) reloader scheduler server
                         actual (:host @startup) start-http?)
    (reset! (:result @startup) {:port actual :host (:host @startup)})))

(defn start!
  "Boot the server through foundation's process runner. Returns {:port :host},
   or nil if config is invalid or a non-loopback bind lacks an auth token."
  [opts]
  (when (running?)
    (stop!))
  (let [root          (:root opts)
        fs*           (or (:fs opts) (nexus/get :fs) (fs/real-fs))
        load-result   (when (and (not (:cfg opts)) root)
                        (loader/load-config-result {:root root :fs fs*}))
        cfg            (cond-> (or (:cfg opts) (:config load-result) {})
                         root (assoc :root root))
        comm-registry @comm-registry/*registry*
        registries    (registries)
        server-cfg    (server-config/server-config cfg)
        port          (or (:port opts) (:port server-cfg))
        host          (or (:host opts) (:host server-cfg))
        start-http?   (not (false? (:start-http-server? opts)))]
    (cond
      (and load-result (seq (:errors load-result)) (not (:missing-config? load-result)))
      (do (log/error :config/invalid :root root :errors (:errors load-result)) nil)

      (seq (runtime/validate-config! cfg comm-registry))
      nil

      (auth-required? cfg host start-http?)
      (do (log/error :server/auth-required
                     :host host
                     :message "missing :server :auth :token for non-loopback bind")
          nil)

      :else
      (let [_            (when root (root/init-root! root))
            _            (when root (server-logging/configure! root cfg))
            module-index (merge (module-loader/builtin-index) (:module-index cfg))
            host-ctx     (host-context (assoc cfg :module-index module-index) root (:connect-ws! opts))
            result       (atom nil)
            startup      (atom {:comm-registry comm-registry
                                :host          host
                                :host-ctx      host-ctx
                                :registries    registries
                                :result        result})
            runner-opts  (-> opts
                             (assoc :config cfg
                                    :fs fs*
                                    :host host
                                    :module-index module-index
                                    :port port
                                    :root root))]
        (log/info :server/boot-phase :phase :discover :modules (count module-index))
        (log/info :server/boot-phase :phase :load)
        (log/info :server/boot-phase :phase :activate)
        (log/info :server/boot-phase :phase :start)
        (binding [runner/*before-components* #(before-components! startup %)
                  runner/*after-components*  #(after-components! startup %)]
          (runner/start! runner-opts))
        @result))))

(defn- before-stop! [running]
  (log/info :server/shutdown-starting)
  (when-let [registries (:registries running)]
    (log/info :server/shutdown-phase :phase :config-reconcile)
    (let [cfg (loader/snapshot "shutdown: current config for teardown reconcile")]
      (runtime/reconcile! (:host-ctx running) cfg nil registries)
      (runtime/install-config-berths! {:config       nil
                                       :old-config   cfg
                                       :module-index (get-in running [:host-ctx :module-index])}))))

(defn- after-stop! [running]
  (some-> (:reloader running) future-cancel)
  (when-let [config-source (:config-source running)]
    (log/info :server/shutdown-phase :phase :config-source)
    (runtime/stop! config-source))
  (let [uptime-ms (when-let [started-at (:started-at running)]
                    (.toMillis (Duration/between started-at (Instant/now))))]
    (log/info :server/stopped :uptime-ms uptime-ms)))

(defn stop! []
  (when (compare-and-set! stopping? false true)
    (try
      (when-let [running (take-running-state!)]
        (binding [runner/*before-stop* (fn [_] (before-stop! running))
                  runner/*after-stop*  (fn [_] (after-stop! running))]
          (runner/stop!)))
      (finally
        (reset! stopping? false)))))

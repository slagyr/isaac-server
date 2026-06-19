;; mutation-tested: 2026-05-06
(ns isaac.server.app
  (:require
    [c3kit.apron.refresh :as refresh]
    [clojure.string :as str]
    [isaac.comm.registry :as comm-registry]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.config.server-config :as server-config]
    [isaac.config.runtime :as runtime]
    [isaac.comm.delivery.worker :as worker]
    [isaac.fs :as fs]
    [isaac.config.root :as root]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.scheduler.runtime :as scheduler-core]
    [isaac.service.runtime :as service-runtime]
    [isaac.nexus :as nexus]
    [isaac.server.http :as http]
    [org.httpkit.server :as httpkit]))

(defonce state (atom nil))

(declare stop!)

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


(defn- dev-handler [handler-opts]
  (refresh/init refresh/services "isaac" [])
  (let [refreshing (refresh/refresh-handler 'isaac.server.http/root-handler)
        scanning   (fn [request]
                     (log/debug :server/dev-reload-scan
                                 :method (:request-method request)
                                 :uri (:uri request))
                     (refreshing request))]
    (http/wrap-logging (http/wrap-auth handler-opts scanning))))

(defn- start-config-reloader! [source root host comm-registry registries]
  ;; The reloader manages the live runtime: runtime/reload! reconciles components
  ;; directly into the (global) nexus, so we must NOT capture+restore a runtime
  ;; snapshot the way bound-runtime-fn does for one-shot deferred work — that
  ;; would discard the reconcile. bound-fn still propagates dynamic var bindings.
  (let [reload! (bound-fn [path]
                  (runtime/reload! {:root     root
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

(defn- build-handler-opts [opts config-home root]
  (cond-> (dissoc opts :home)
    config-home (assoc :home config-home)
    root   (assoc :root root)
    true        (assoc :cfg-fn (fn [] (loader/snapshot "http handler: ambient config")))))

(defn- start-http-server [dev? start-http-server? handler-opts port host]
  (let [handler (when start-http-server?
                  (if dev?
                    (dev-handler handler-opts)
                    (http/create-handler handler-opts)))
        server  (when start-http-server?
                  (httpkit/run-server handler {:port port :ip host :legacy-return-value? false}))
        actual  (if start-http-server? (httpkit/server-port server) port)]
    {:server server :actual actual}))

(defn- auth-required? [cfg host start-http-server?]
  (and start-http-server?
       (not (http/loopback-host? host))
       (str/blank? (get-in cfg [:server :auth :token]))))

(defn- start-optional-service! [start-sym]
  (when-let [start! (resolve-var start-sym)]
    (start! {})))

(defn- stop-optional-service! [stop-sym instance]
  (when (and instance (resolve-var stop-sym))
    ((resolve-var stop-sym) instance)))

(defn- start-background-services [_opts scheduler]
  (if scheduler
    {:delivery        (worker/start! {})
     :hail-delivery   (start-optional-service! 'isaac.hail.delivery-worker/start!)
     :hail-router     (start-optional-service! 'isaac.hail.router/start!)}
    {}))

(defn- reset-server-state! [host-ctx comm-registry registries config-source connect-ws! reloader scheduler delivery hail-delivery hail-router server actual host start-http-server?]
  (reset! state {:host-ctx           host-ctx
                 :registry           comm-registry
                 :registries         registries
                 :config-source      config-source
                 :connect-ws!        connect-ws!
                 :reloader           reloader
                 :scheduler          scheduler
                 :delivery           delivery
                 :hail-delivery      hail-delivery
                 :hail-router        hail-router
                 :server             server
                 :port               actual
                 :host               host
                 :start-http-server? start-http-server?}))

(defn start!
  "Boot the server. Loads config from :root and commits it (or uses an
   injected :cfg for tests/embedding), validates it, reconciles components, starts
   background services, and binds the HTTP server. dev? comes from :dev (resolved
   by the caller from --dev / ISAAC_DEV), port/host from the config with :port /
   :host overrides. Returns {:port :host}, or nil if config is invalid or a
   non-loopback bind lacks an auth token."
  [opts]
  (when (running?) (stop!))
  (let [root          (:root opts)
        fs                 (or (fs/instance opts) (fs/real-fs))
        load-result        (when (and (not (:cfg opts)) root)
                             (loader/load-config-result {:root root :fs fs}))
        cfg                (cond-> (or (:cfg opts) (:config load-result) {})
                             root (assoc :root root))
        comm-registry      @comm-registry/*registry*
        registries         (registries)
        server-cfg         (server-config/server-config cfg)
        port               (or (:port opts) (:port server-cfg))
        host               (or (:host opts) (:host server-cfg))
        dev?               (true? (:dev opts))
        hot-reload?        (:hot-reload server-cfg)
        start-http-server? (not (false? (:start-http-server? opts)))
        config-home        (some-> root fs/parent)
        connect-ws!        (:connect-ws! opts)]
    (cond
      (and load-result (seq (:errors load-result)) (not (:missing-config? load-result)))
      (do (log/error :config/invalid :root root :errors (:errors load-result)) nil)

      (seq (runtime/validate-config! cfg comm-registry))
      nil

      (auth-required? cfg host start-http-server?)
      (do (log/error :server/auth-required
                     :host host
                     :message "missing :server :auth :token for non-loopback bind")
          nil)

      :else
      (let [_                       (nexus/init! {:fs fs})
            _                       (when root (root/init-root! root))
            module-index            (merge (module-loader/builtin-index) (:module-index cfg))
            scheduler               (when root
                                      (-> (scheduler-core/create {})
                                          scheduler-core/start!))
            _                       (when scheduler
                                      (nexus/register! [:scheduler] scheduler))
            host-ctx                (host-context (assoc cfg :module-index module-index) root connect-ws!)
            _                       (config/dangerously-install-config! cfg "server boot")
            _                       (runtime/install! {:config cfg :registries registries :host host-ctx})
            ;; isaac-8yxs: per-entry berth :factory invocation. Runs
            ;; here (after config commit, before module on-load) so
            ;; the berth registrations are in the nexus by the time
            ;; modules boot. Must run OUTSIDE loader/load-config-result's
            ;; nested-nexus wrap — that wrap restores the prior nexus
            ;; state on exit, which would discard the factories' writes.
            ;;
            ;; Phase 5 of brth (isaac-8v1n): the :isaac.server/route
            ;; berth also flows through here, replacing the explicit
            ;; register-route-extensions! pass.
            _                       (log/info :server/boot-phase :phase :discover
                                                :modules (count module-index))
            _                       (log/info :server/boot-phase :phase :load)
            _                       (module-loader/reconcile-modules! module-index)
            _                       (log/info :server/boot-phase :phase :activate)
            _                       (module-loader/activate-modules! module-index)
            _                       (module-loader/process-manifest-berths! module-index)
            _                       (runtime/install-config-berths! {:config cfg :module-index module-index})
            _                       (log/info :server/boot-phase :phase :start)
            _                       (service-runtime/start-all! module-index)
            config-source           (start-config-source opts hot-reload? root)
            _                       (some-> config-source runtime/start!)
            reloader                (when (and config-source root)
                                      (start-config-reloader! config-source root host-ctx comm-registry registries))
            handler-opts            (build-handler-opts opts config-home root)
            {:keys [server actual]} (start-http-server dev? start-http-server? handler-opts port host)
            {:keys [delivery hail-delivery hail-router]} (start-background-services opts scheduler)]
        (when (and dev? start-http-server?)
          (log/info :server/dev-mode-enabled :host host :port actual))
        (log/info :server/boot-summary (module-loader/boot-stats module-index))
        (reset-server-state! host-ctx comm-registry registries config-source connect-ws! reloader scheduler delivery hail-delivery hail-router server actual host start-http-server?)
        {:port actual :host host}))))

(defn stop! []
  (when-let [{:keys [config-source scheduler delivery hail-delivery hail-router host-ctx registries reloader server]} @state]
    (when delivery
      (worker/stop! delivery))
    (stop-optional-service! 'isaac.hail.delivery-worker/stop! hail-delivery)
    (stop-optional-service! 'isaac.hail.router/stop! hail-router)
    (service-runtime/stop-all!)
    (module-loader/shutdown-modules!)
    (when scheduler
      (scheduler-core/shutdown! scheduler))
    (when registries
      (let [cfg (loader/snapshot "shutdown: current config for teardown reconcile")]
        (runtime/reconcile! host-ctx cfg nil registries)
        (runtime/install-config-berths! {:config     nil
                                         :old-config cfg
                                         :module-index (:module-index host-ctx)})))
    (some-> reloader future-cancel)
    (when config-source
      (runtime/stop! config-source))
    (when server
      (if (fn? server)
        (server)
        (httpkit/server-stop! server)))
    (reset! state nil)))

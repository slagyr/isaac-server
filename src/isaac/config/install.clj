(ns isaac.config.install
  "Single coordinator for turning config into runtime state. Reconciles the
   config-driven components (comms, hail, hooks, cron) directly into the nexus as live
   instances. Entry points call this instead of building config and installing
   components ad hoc.

   The registries to reconcile are injected by the caller so this namespace
   stays free of dependencies on the component implementations (comms, hail,
   hooks, cron)."
  (:require
    [clojure.string :as str]
    [isaac.config.berths :as berth-config]
    [isaac.config.loader :as config]
    [isaac.config.configurator :as configurator]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]))

(defn install!
  "Reconcile an already-committed config into the nexus by reconciling the
   given registries' config slices into the nexus as live
   component instances. The snapshot must already be committed (via load-config!
   or dangerously-install-config!) — install! no longer commits it.

   opts:
     :config      - the committed config map (required)
     :old-config  - previous config for the reconcile diff (nil on boot)
     :registries  - configurator registries to reconcile (default none)
     :host        - host context for reconcile! (module-index, connect-ws!, ...)
   Returns {:config config}."
  [{:keys [config old-config registries host]}]
  (when (seq registries)
    (configurator/reconcile! host old-config config registries))
  {:config config})

(defn install-config-berths!
  "Reconcile config-berth nodes against the nexus — factory on
   appearance, on-config-change! for Reconfigurable instances,
   deregister on removal. Boot passes no :old-config; shutdown passes
   :config nil."
  [{:keys [config old-config module-index]}]
  (when (seq module-index)
    (berth-config/reconcile! {:config       config
                              :old-config   old-config
                              :module-index module-index}))
  {:config config})

(defn load-and-install!
  "Load config (loader), commit it as the snapshot, and install! it into the
   nexus. opts are loader opts plus :registries and :host (see install!).
   Returns {:config :errors :warnings}."
  [{:keys [registries host] :as opts}]
  (let [{:keys [config errors warnings]} (config/load-config-result (dissoc opts :registries :host))]
    (config/set-snapshot! config "load-and-install! coordinator")
    (install! {:config config :registries registries :host host})
    {:config config :errors errors :warnings warnings}))

;; ----- comm-impl validation (boot + reload) -----

(defn- config-error-prefix [path]
  (when-let [[_ kind id] (re-matches #"(crew|models|providers)/([^/]+)\.edn" path)]
    (str kind "." id)))

(defn- reload-failure [path errors]
  (if-let [parse-error (some #(when (= path (:key %)) %) errors)]
    {:reason :parse :error (:value parse-error)}
    (let [prefix          (config-error-prefix path)
          relevant-errors (cond
                            prefix (filter #(str/starts-with? (:key %) prefix) errors)
                            :else  errors)
          formatted-error (->> relevant-errors
                               (map #(str (:key %) " " (:value %)))
                               (str/join "\n"))]
      {:reason :validation :error formatted-error})))

(defn reload!
  "Hot-reload coordinator (server only): re-load config from `root`/`fs`,
   validate it through the loader's schema and contributed checks; on any
   error, log and KEEP the running config (returns nil); on success, commit the
   new snapshot and reconcile `registries` against `old-config`. Returns the new
   config on success, nil if rejected."
  [{:keys [root fs old-config registries host path]}]
  (log/info :config.reload/begin :path path)
  (let [load-result (config/load-config-result {:root root :fs fs :raw-parse-errors? true})
        errors      (:errors load-result)
        new-cfg     (:config load-result)]
    (cond
      (seq errors)
      (let [{:keys [error reason]} (reload-failure path errors)]
        (log/error :config/reload-failed :error error :path path :reason reason)
        nil)

      :else
      (do
        (when (:module-index new-cfg)
          (module-loader/reconcile-modules! (:module-index new-cfg)))
        (config/set-snapshot! new-cfg "config hot reload")
        (install! {:config new-cfg :old-config old-config :registries registries
                   :host (assoc host :module-index (:module-index new-cfg))})
        (install-config-berths! {:config new-cfg :old-config old-config :module-index (:module-index new-cfg)})
        (log/info :config/reloaded :path path)
        new-cfg))))

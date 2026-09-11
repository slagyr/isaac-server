(ns isaac.server.app
  (:require
    [isaac.config.loader :as loader]
    [isaac.runner :as runner]
    [isaac.server.component.runtime :as runtime]))

(defn running? []
  (runner/running?))

(defn current-config []
  (loader/snapshot "server/current-config accessor"))

(defn start! [opts]
  (let [config (or (:config opts) (:cfg opts) {})
        opts*  (cond-> (assoc opts :config config)
                 (:module-index config) (assoc :module-index (:module-index config)))]
    (when (runtime/valid-start? config opts*)
      (runner/start! opts*))))

(defn stop! []
  (runner/stop!))

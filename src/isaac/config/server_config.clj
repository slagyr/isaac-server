(ns isaac.config.server-config
  (:require
    [isaac.config.loader :as loader]))

(defn server-config
  "Resolve :server bind settings from a loaded config."
  [config]
  (let [config (loader/normalize-config config)]
    {:port                (or (get-in config [:server :port]) 6674)
     :host                (or (get-in config [:server :host]) "127.0.0.1")
     :hot-reload          (let [hot-reload (get-in config [:server :hot-reload])]
                            (if (boolean? hot-reload) hot-reload true))
     :suspend-timeout-ms  (or (get-in config [:server :suspend-timeout-ms]) 15000)}))
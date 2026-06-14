(ns isaac.config.server-config
  (:require
    [isaac.config.loader :as loader]))

(defn server-config
  "Resolve :server (or legacy :gateway) bind settings from a loaded config."
  [config]
  (let [config (loader/normalize-config config)]
    {:port       (or (get-in config [:server :port])
                     (get-in config [:gateway :port])
                     6674)
     :host       (or (get-in config [:server :host])
                     (get-in config [:gateway :host])
                     "127.0.0.1")
     :hot-reload (let [hot-reload (get-in config [:server :hot-reload])]
                   (if (boolean? hot-reload) hot-reload true))}))
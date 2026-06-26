(ns isaac.config.change-source-log
  (:require
    [isaac.config.change-source-protocol :as proto]
    [isaac.config.paths :as paths]
    [isaac.logger :as log]))

(defn record-change-detected!
  "Logs :config.watch/change-detected when `raw-path` is a tracked config file.
   Returns the config-relative path when tracked, else nil."
  [home raw-path]
  (when-not (proto/editor-artifact? raw-path)
    (when-let [relative (paths/config-relative home raw-path)]
      (when (paths/config-file? relative)
        (log/debug :config.watch/change-detected :path relative :raw-path raw-path)
        relative))))

(defn record-watch-started!
  [home impl]
  (log/info :config.watch/started
            :root home
            :config-root (paths/config-root home)
            :impl impl))
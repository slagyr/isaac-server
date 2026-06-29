(ns isaac.server.logging
  (:require
    [isaac.log.file :as log-file]
    [isaac.logger :as log]))

(defn configure!
  "Enable the durable server log sink at <root>/logs/server.log.
   Skipped when the harness has routed logging to memory."
  [root config]
  (when-not (= :memory (log/output))
    (log-file/configure-server-sink! root config)
    (log/set-output! :file)
    (log/set-log-file! (log-file/server-log-path root))))
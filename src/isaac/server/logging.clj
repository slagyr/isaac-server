(ns isaac.server.logging
  (:require
    [isaac.log.file :as log-file]))

(defn configure!
  "Initialize the rotating server log at <root>/logs/server.log. Writes go
   through the server sink even when the harness keeps :memory for assertions."
  [root config]
  (log-file/configure-server-sink! root config))
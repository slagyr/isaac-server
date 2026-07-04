(ns isaac.service.protocol)

(defprotocol Service
  (start [this])
  (stop [this]))

(defprotocol Supervised
  "Optional protocol a long-lived Service may implement so the supervisor
   watches its worker thread/loop and restarts it if it dies (isaac-royn).
   Services that do not implement it are started once and left unmonitored."
  (alive? [this]
    "True while the service's worker (thread, future, loop, scheduler task) is
     healthy. False once it has died — the supervisor then restarts the service
     via stop/start with backoff. Implementations decide how to detect liveness
     (a running flag, future-done?, a heartbeat timestamp, …)."))

(defn service?
  [value]
  (satisfies? Service value))

(defn supervised?
  [value]
  (satisfies? Supervised value))

(defn run-start! [this]
  (start this))

(defn run-stop! [this]
  (stop this))
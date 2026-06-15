(ns isaac.service.protocol)

(defprotocol Service
  (start [this])
  (stop [this]))

(defn service?
  [value]
  (satisfies? Service value))

(defn run-start! [this]
  (start this))

(defn run-stop! [this]
  (stop this))
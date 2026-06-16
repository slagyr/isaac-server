(ns isaac.comm.null
  (:require
    [isaac.comm.protocol :as comm]
    [isaac.reconfigurable :as reconfigurable]))

(deftype NullComm []
  comm/Comm
  (on-turn-start [_ _ _] nil)
  (on-text-chunk [_ _ _] nil)
  (on-tool-call [_ _ _] nil)
  (on-tool-cancel [_ _ _] nil)
  (on-tool-result [_ _ _ _] nil)
  (on-compaction-start [_ _ _] nil)
  (on-compaction-success [_ _ _] nil)
  (on-compaction-failure [_ _ _] nil)
  (on-compaction-disabled [_ _ _] nil)
  (on-turn-end [_ _ _] nil)
  (send! [_ _] {:ok false :transient? false})
  reconfigurable/Reconfigurable
  (on-load [_ _] nil)
  (on-config-change! [_ _ _] nil)
  (on-unload [_ _] nil))

(defn make [_host]
  (->NullComm))

(def channel (->NullComm))

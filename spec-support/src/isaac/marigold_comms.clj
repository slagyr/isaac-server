(ns isaac.marigold-comms
  "Marigold's themed comm impls: each themed id instantiates one of the
   real built-in comms. Loaded lazily by isaac.comm.factory/ensure-impl!
   via the contributions' :namespace."
  (:require
    [isaac.comm.cli :as cli-comm]
    [isaac.comm.memory :as memory-comm]
    [isaac.comm.null :as null-comm]
    [isaac.comm.factory :as factory]))

(defmethod factory/create :longwave [node-path _slice]
  (cli-comm/make {:name (last node-path)}))

(defmethod factory/create :skybeam [node-path _slice]
  (null-comm/make {:name (last node-path)}))

(defmethod factory/create :logbook [node-path _slice]
  (memory-comm/make {:name (last node-path)}))

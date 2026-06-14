(ns isaac.comm.memory
  (:require
    [isaac.comm.protocol :as comm]))

(defn- append! [events event]
  (swap! events conj event))

(deftype MemoryComm [events]
  comm/Comm
  (on-turn-start [_ session-key input]
    (append! events {:event "turn-start" :session session-key :input input}))
  (on-text-chunk [_ session-key text]
    (let [text (some-> text str)]
      (when (seq text)
        (append! events {:event "text-chunk" :session session-key :text text}))))
  (on-tool-call [_ session-key tool-call]
    (append! events {:event "tool-call" :session session-key :tool {:name (:name tool-call)}}))
  (on-tool-cancel [_ session-key tool-call]
    (append! events {:event "tool-cancel" :session session-key :tool {:name (:name tool-call)}}))
  (on-tool-result [_ session-key tool-call result]
    (append! events {:event "tool-result" :session session-key :tool {:name (:name tool-call)} :result result}))
  (on-compaction-start [_ session-key payload]
    (append! events (assoc payload :event "compaction-start" :session session-key)))
  (on-compaction-success [_ session-key payload]
    (append! events (assoc payload :event "compaction-success" :session session-key)))
  (on-compaction-failure [_ session-key payload]
    (append! events (assoc payload :event "compaction-failure" :session session-key)))
  (on-compaction-disabled [_ session-key payload]
    (append! events (assoc payload :event "compaction-disabled" :session session-key)))
  (on-turn-end [_ session-key result]
    (append! events {:event "turn-end" :session session-key :result result}))
  (send! [_ record]
    (append! events {:event "send" :record record})
    {:ok true}))

(defn make [host]
  (->MemoryComm (or (:events host) (atom []))))

(defn channel [events]
  (->MemoryComm events))

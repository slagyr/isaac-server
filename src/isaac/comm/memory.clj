(ns isaac.comm.memory
  (:require
    [isaac.comm.protocol :as comm]
    [isaac.comm.render :as render]))

(defn- append! [events event]
  (swap! events conj event))

(defn- cycle-n [cycle]
  (when (map? cycle) (:n cycle)))

(defn- chunk-text [chunk]
  (render/chunk-text chunk))

(deftype MemoryComm [events])

(extend MemoryComm
  comm/Comm
  (merge comm/defaults
         {:on-turn-start
          (fn [this session-key input]
            (append! (.-events this) {:event "turn-start" :session session-key :input input}))

          :on-turn-end
          (fn [this session-key result]
            (append! (.-events this) {:event "turn-end" :session session-key :result result}))

          :on-cycle-start
          (fn [this session-key cycle]
            (append! (.-events this) {:event "cycle-start" :session session-key :cycle (cycle-n cycle)}))

          :on-cycle-end
          (fn [this session-key cycle outcome]
            (append! (.-events this) {:event   "cycle-end"
                                      :session session-key
                                      :cycle   (cycle-n cycle)
                                      :outcome (some-> (:outcome outcome) name)}))

          :on-chatter
          (fn [this session-key cycle chunk]
            (let [plain (chunk-text chunk)]
              (when (seq plain)
                (append! (.-events this) (cond-> {:event   "chatter"
                                                  :session session-key
                                                  :cycle   (cycle-n cycle)
                                                  :text    plain}
                                          (render/preformatted? chunk) (assoc :format render/preformatted))))))

          :on-reckoning
          (fn [this session-key cycle chunk]
            (let [plain (chunk-text chunk)]
              (when (seq plain)
                (append! (.-events this) {:event   "reckoning"
                                          :session session-key
                                          :cycle   (cycle-n cycle)
                                          :text    plain}))))

          :on-aside
          (fn [this session-key cycle text]
            (let [plain (chunk-text text)]
              (when (seq plain)
                (append! (.-events this) {:event   "aside"
                                          :session session-key
                                          :cycle   (cycle-n cycle)
                                          :text    plain}))))

          :on-reply
          (fn [this session-key text]
            (let [plain (chunk-text text)]
              (when (seq plain)
                (append! (.-events this) {:event "reply" :session session-key :text plain}))))

          :on-tool-call
          (fn [this session-key tool-call]
            (append! (.-events this) {:event "tool-call" :session session-key :tool {:name (:name tool-call)}}))

          :on-tool-cancel
          (fn [this session-key tool-call]
            (append! (.-events this) {:event "tool-cancel" :session session-key :tool {:name (:name tool-call)}}))

          :on-tool-result
          (fn [this session-key tool-call result]
            (append! (.-events this) {:event   "tool-result"
                                      :session session-key
                                      :tool    {:name (:name tool-call)}
                                      :result  result}))

          :on-tool-progress
          (fn [this session-key tool-call chunk]
            (append! (.-events this) {:event   "tool-progress"
                                      :session session-key
                                      :tool    {:name (:name tool-call)}
                                      :text    (chunk-text chunk)}))

          :on-bulletin
          (fn [this session-key bulletin]
            (let [kind     (or (:kind bulletin) (:kind (:payload bulletin)))
                  kind-str (if (keyword? kind) (subs (str kind) 1) (str kind))]
              (append! (.-events this) (merge (dissoc bulletin :kind)
                                              {:event   "bulletin"
                                               :session session-key
                                               :kind    kind-str}))))

          :send!
          (fn [this record]
            (append! (.-events this) {:event "send" :record record})
            {:ok true})}))

(defn make [host]
  (->MemoryComm (or (:events host) (atom []))))

(defn channel [events]
  (->MemoryComm events))

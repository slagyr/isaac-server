(ns isaac.session.schema
  (:require
    [c3kit.apron.schema :as schema]))

(def ^:dynamic *config*
  "Config bound around session conform so crew validation resolves the known
   crew set from a value rather than the ambient snapshot. Unbound means no crew
   constraint — reads don't reject; bind it on write paths to validate. Mirrors
   isaac.config.loader/*config*."
  nil)

(defn- mutable [spec]
  (assoc spec :mutable? true :system-managed? false))

(defn- immutable [spec]
  (assoc spec :mutable? false :system-managed? false))

(defn- system-managed [spec]
  (assoc spec :mutable? false :system-managed? true))

(defn- known-crew? [crew]
  (let [crews (:crew *config*)]
    (or (nil? crew)
        (nil? crews)
        (empty? crews)
        (contains? crews crew))))

(defn kebabize-legacy-keys [entry]
  (cond-> entry
    (contains? entry :createdAt) (-> (assoc :created-at (:createdAt entry)) (dissoc :createdAt))
    (contains? entry :chatType)  (-> (assoc :chat-type (:chatType entry)) (dissoc :chatType))))

(def Origin
  {:name   :session-origin
   :type   :map
   :schema {:kind       (immutable {:type :keyword})
            :channel-id (immutable {:type :string})
            :guild-id   (immutable {:type :string})
            :name       (immutable {:type :string})}})

(def CompactionState
  {:name   :session-compaction-state
  :type   :map
   :schema {:async?               (mutable {:type :boolean})
            :consecutive-failures (system-managed {:type :int})
            :strategy             (mutable {:type :keyword})
            :head                 (mutable {:type :double})
            :threshold            (mutable {:type :double})}})

(def Session
  {:name   :session
   :type   :map
   :schema {:id                  (immutable {:type :string :required? true :validate schema/present? :message "must be present"})
            :key                 (immutable {:type :string})
            :name                (mutable {:type :string :required? true :validate schema/present? :message "must be present"})
            :sessionId           (immutable {:type :string})
            :session-file        (immutable {:type :string})
            :nonce               (mutable {:type :string})
            :origin              (immutable {:type :map :schema (:schema Origin)})
            :crew                (mutable {:type :string
                                           :validate known-crew?
                                           :message "crew does not exist"})
            :model               (mutable {:type :string})
            :provider            (mutable {:type :string})
            :tags                (mutable {:type :ignore
                                           :set-type? true
                                           :validate #(or (nil? %) (and (set? %) (every? keyword? %)))
                                           :message "must be a set of keywords"})
            :effort              (mutable {:type :int})
            :context-mode        (mutable {:type :keyword})
            :channel             (mutable {:type :string})
            :chat-type           (mutable {:type :string})
            :cwd                 (mutable {:type :string})
            :created-at          (immutable {:type :string})
            :updated-at          (system-managed {:type :string})
            :last-channel        (system-managed {:type :string})
            :last-to             (system-managed {:type :string})
            :compaction-count    (system-managed {:type :int})
            :compaction-disabled (mutable {:type :boolean})
            :compaction          (mutable {:type :map :schema (:schema CompactionState)})
            :history-retention   (mutable {:type :keyword})
            :effective-history-offset (system-managed {:type :long})
            :input-tokens        (system-managed {:type :int})
            :output-tokens       (system-managed {:type :int})
            :total-tokens        (system-managed {:type :int})
            :last-input-tokens   (system-managed {:type :int})
            :cache-read          (system-managed {:type :int})
            :cache-write         (system-managed {:type :int})}})

(defn conform-read [entry]
  (let [result (schema/conform Session (kebabize-legacy-keys entry))]
    (if (schema/error? result)
      result
      (update result :tags #(or % #{})))))

(defn conform! [entry]
  (schema/conform! Session (kebabize-legacy-keys entry)))

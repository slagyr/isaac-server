(ns isaac.session.store.memory
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.config.resolve :as resolve]
    [isaac.logger :as log]
    [isaac.naming :as naming]
    [isaac.session.schema :as session-schema]
    [isaac.session.store.spi :as store]
    [isaac.session.store.impl-common :as c]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus])
  (:import
    (java.time Instant ZoneOffset)
    (java.time.format DateTimeFormatter)))

;; region ----- Helpers -----

(def ^:private ts-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))

(defn- now-iso []
  (.format ts-formatter (.atOffset (Instant/now) ZoneOffset/UTC)))


(defn- get-val [m k]
  (or (get m k) (get m (name k))))

(defn- effective-config [passed-config]
  (or passed-config
      (loader/snapshot "session store config — ambient fallback when caller passes no :config")
      {}))

;; endregion

;; region ----- MemorySessionStore -----
;;
;; state atom shape: {:sessions   {id entry}
;;                    :transcripts {id [transcript-entries]}}
;;
;; Transcript entries match the file store wire format exactly so that
;; specs using get-transcript can check :type, :message, :id etc. without
;; modification.

(deftype MemorySessionStore [root state]
  store/SessionStore

  (open-session! [_ name opts]
    (let [opts      (c/entry-defaults opts)
          retention (resolve/resolve-history-retention (effective-config (:config opts))
                                                      (or (:crew opts) "main")
                                                      (:history-retention opts))
          name      (or name
                        (when root
                          (naming/generate (store/ensure-naming-strategy! root (fs/instance)))))
          id        (c/session-id (or name "session"))
          existing  (get-in @state [:sessions id])]
      (cond
        (and existing (some? name) (not= name (:name existing)))
        (throw (ex-info (str "session already exists: " id)
                        {:name name :session-id id}))

        existing
        (do
          (log/info :session/opened :sessionId id)
          existing)

        :else
        (let [now          (now-iso)
              header-id    (c/new-id)
              session-file (str id ".jsonl")
              header       {:type      "session"
                            :id        header-id
                            :timestamp now
                            :version   3
                            :cwd       (or (:cwd opts) (System/getProperty "user.dir"))}
              entry        {:id                id
                            :key               id
                            :name              (or name id)
                            :nonce             (or (:nonce opts) (c/new-nonce))
                            :session-file      session-file
                             :created-at        now
                             :updated-at        now
                             :crew              (:crew opts)
                             :tags              (or (:tags opts) #{})
                             :channel           (:channel opts)
                             :chat-type         (:chat-type opts)
                            :cwd               (or (:cwd opts) (System/getProperty "user.dir"))
                            :origin            (or (:origin opts) {:kind :cli})
                            :history-retention retention
                            :compaction-count  0
                            :input-tokens      0
                            :last-input-tokens 0
                            :output-tokens     0
                            :total-tokens      0}]
          (swap! state #(-> %
                            (assoc-in [:sessions id] entry)
                            (assoc-in [:transcripts id] [header])))
          (log/info :session/created :sessionId id)
          entry))))

  (delete-session! [_ name]
    (let [id (c/session-id name)]
      (when (get-in @state [:sessions id])
        (swap! state #(-> % (update :sessions dissoc id) (update :transcripts dissoc id)))
        true)))

  (list-sessions [_]
    (->> (vals (:sessions @state)) (sort-by :id) vec))

  (list-sessions-by-agent [_ agent]
    (->> (vals (:sessions @state))
         (sort-by :id)
         (filter #(= agent (:crew %)))
         vec))

  (most-recent-session [_]
    (->> (vals (:sessions @state)) (sort-by :updated-at) last))

  (get-session [_ name]
    (get-in @state [:sessions (c/session-id name)]))

  (get-transcript [_ name]
    (let [id (c/session-id name)]
      (when (get-in @state [:sessions id])
        (get-in @state [:transcripts id] []))))

  (active-transcript [_ name]
    (let [id (c/session-id name)]
      (when (get-in @state [:sessions id])
        (let [transcript (get-in @state [:transcripts id] [])]
          (if-let [offset (get-in @state [:sessions id :effective-history-offset])]
            (subvec transcript offset)
            transcript)))))

  (update-session! [_ name updates]
    (let [id      (c/session-id name)
          updates (session-schema/kebabize-legacy-keys updates)]
      (swap! state update-in [:sessions id]
             (fn [entry]
               (let [updates (if-let [compaction (:compaction updates)]
                               (assoc updates :compaction (merge (or (:compaction entry) {}) compaction))
                               updates)]
                 (merge entry updates))))
      (get-in @state [:sessions id])))

  (append-message! [_ name message]
    (let [id             (c/session-id name)
          transcript     (get-in @state [:transcripts id] [])
          parent-id      (c/last-entry-id transcript)
          msg-id         (c/new-id)
          now            (now-iso)
          session        (get-in @state [:sessions id])
          resolved-agent (or (:crew message)
                             (when (#{"assistant" "error" "toolResult"} (:role message)) (:crew session))
                             (when (= "assistant" (:role message)) "main"))
          normalized-msg (c/normalize-message (cond-> message
                                                resolved-agent (assoc :crew resolved-agent)))
          entry          (cond-> {:type      "message"
                                  :id        msg-id
                                  :parentId  parent-id
                                  :timestamp now
                                  :message   normalized-msg}
                           (:tokens message) (assoc :tokens (:tokens message)))]
      (swap! state (fn [s]
                     (-> s
                         (update-in [:transcripts id] (fnil conj []) entry)
                         (update-in [:sessions id]
                                    (fn [sess]
                                      (cond-> (assoc sess :updated-at now)
                                        (get-val message :channel) (assoc :last-channel (get-val message :channel))
                                        (get-val message :to)      (assoc :last-to (get-val message :to))
                                        resolved-agent             (assoc :crew resolved-agent)))))))
      entry))

  (append-error! [_ name error-entry]
    (let [id        (c/session-id name)
          transcript (get-in @state [:transcripts id] [])
          parent-id (c/last-entry-id transcript)
          error-id  (c/new-id)
          now       (now-iso)
          entry     (cond-> {:type      "error"
                              :id        error-id
                              :parentId  parent-id
                              :timestamp now
                              :content   (:content error-entry)
                              :error     (:error error-entry)
                              :model     (:model error-entry)
                              :provider  (:provider error-entry)}
                      (:ex-class error-entry) (assoc :ex-class (:ex-class error-entry)))]
      (swap! state (fn [s]
                     (-> s
                         (update-in [:transcripts id] (fnil conj []) entry)
                         (assoc-in [:sessions id :updated-at] now))))
      entry))

  (append-compaction! [_ name {:keys [summary firstKeptEntryId tokensBefore]}]
    (let [id            (c/session-id name)
          transcript    (get-in @state [:transcripts id] [])
          parent-id     (c/last-entry-id transcript)
          compaction-id (c/new-id)
          now           (now-iso)
          entry         {:type             "compaction"
                         :id               compaction-id
                         :parentId         parent-id
                         :timestamp        now
                         :summary          summary
                         :firstKeptEntryId firstKeptEntryId
                         :tokensBefore     tokensBefore}]
      (swap! state (fn [s]
                     (-> s
                         (update-in [:transcripts id] (fnil conj []) entry)
                         (update-in [:sessions id]
                                    #(-> % (assoc :updated-at now) (update :compaction-count inc))))))
      entry))

  (splice-compaction! [_ name {:keys [compactedEntryIds firstKeptEntryId summary tokensBefore]}]
    (let [id               (c/session-id name)
          transcript       (get-in @state [:transcripts id] [])
          retention        (or (get-in @state [:sessions id :history-retention]) resolve/default-history-retention)
          compacted-ids    (set compactedEntryIds)
          removable-ids    (->> transcript
                                (filter #(and (= "message" (:type %))
                                              (contains? compacted-ids (:id %))))
                                (map :id)
                                set)
          first-kept-idx   (when firstKeptEntryId
                             (some (fn [[idx e]]
                                     (when (= firstKeptEntryId (:id e)) idx))
                                   (map-indexed vector transcript)))
          insert-at        (case retention
                             :retain (or first-kept-idx (count transcript))
                             (or (some (fn [[idx e]]
                                         (when (contains? removable-ids (:id e)) idx))
                                       (map-indexed vector transcript))
                                 (count transcript)))
          before           (subvec transcript 0 insert-at)
          compaction-id    (c/new-id)
          now              (now-iso)
          compaction-entry {:type             "compaction"
                            :id               compaction-id
                            :parentId         (:id (last before))
                            :timestamp        now
                            :summary          summary
                            :firstKeptEntryId firstKeptEntryId
                            :tokensBefore     tokensBefore}
          after            (->> (subvec transcript (or first-kept-idx (count transcript)))
                                ((fn [entries]
                                   (if (= :retain retention)
                                     entries
                                     (remove #(contains? removable-ids (:id %)) entries))))
                                (mapv (fn [e]
                                        (if (contains? removable-ids (:parentId e))
                                          (assoc e :parentId compaction-id)
                                          e))))
          new-transcript   (c/drop-orphan-toolcalls
                             (into before (cons compaction-entry after)))]
      (swap! state (fn [s]
                     (-> s
                          (assoc-in [:transcripts id] new-transcript)
                          (update-in [:sessions id]
                                     #(-> %
                                          (assoc :updated-at now)
                                          ((fn [entry]
                                             (if (= :retain retention)
                                               (assoc entry :effective-history-offset insert-at)
                                               (dissoc entry :effective-history-offset))))
                                          (update :compaction-count inc))))))
      compaction-entry))

  (truncate-after-compaction! [_ name]
    (let [id         (c/session-id name)
          transcript (get-in @state [:transcripts id] [])
          compaction (->> transcript (filter #(= "compaction" (:type %))) last)]
      (when compaction
        (let [first-kept-id  (:firstKeptEntryId compaction)
              compaction-id  (:id compaction)
              removed-ids    (loop [remaining transcript ids #{}]
                               (if (empty? remaining)
                                 ids
                                 (let [e (first remaining)]
                                   (cond
                                     (= (:id e) compaction-id)                       ids
                                     (and first-kept-id (= (:id e) first-kept-id))   ids
                                     (= "message" (:type e))                         (recur (rest remaining) (conj ids (:id e)))
                                     :else                                            (recur (rest remaining) ids)))))
              remap          (loop [remaining transcript last-kept nil mapping {}]
                               (if (empty? remaining)
                                 mapping
                                 (let [e (first remaining)]
                                   (if (contains? removed-ids (:id e))
                                     (recur (rest remaining) last-kept (assoc mapping (:id e) last-kept))
                                     (recur (rest remaining) (:id e) mapping)))))
              new-transcript (into []
                                   (keep (fn [e]
                                           (when-not (contains? removed-ids (:id e))
                                             (if-let [new-parent (get remap (:parentId e))]
                                               (assoc e :parentId new-parent)
                                               e))))
                                   transcript)]
          (swap! state assoc-in [:transcripts id] new-transcript))))))

;; endregion

(defn create-store
  ([]
   (create-store nil))
  ([root]
   (->MemorySessionStore root (atom {:sessions {} :transcripts {}}))))

(defn store-state [^MemorySessionStore store]
  @(.-state store))

(store/register-factory! :memory #'create-store)

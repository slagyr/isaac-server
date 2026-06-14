(ns isaac.session.store.impl-common
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.set :as set]
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.config.resolve :as resolve]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.naming :as naming]
    [isaac.session.schema :as session-schema]
    [isaac.session.store.spi :as session-store])
  (:import
    (java.nio.charset StandardCharsets)
    (java.time Instant ZoneOffset)
    (java.time.format DateTimeFormatter)
    (java.util UUID)))

;; region ----- Helpers -----

(defn new-id []
  (subs (str (UUID/randomUUID)) 0 8))

(defn new-nonce []
  (str "N0NCE-" (subs (str (UUID/randomUUID)) 0 12)))

(defn parse-long-safe [s]
  (try
    (when (string? s) (Long/parseLong s))
    (catch Exception _ nil)))

(defn normalize-timestamp [ms->iso-fn ts]
  (cond
    (number? ts) (ms->iso-fn ts)
    (string? ts) (if-let [n (parse-long-safe ts)] (ms->iso-fn n) ts)
    :else        ts))

(defn read-json [s] (json/parse-string s true))
(defn write-json [v] (json/generate-string v))

(defn write-edn [v]
  (binding [*print-namespace-maps* false]
    (str (pr-str v) "\n")))

(defn keywordize-map [m]
  (into {} (map (fn [[k v]] [(if (keyword? k) k (keyword k)) v]) m)))

(defn- text-blocks? [content]
  (and (vector? content)
       (every? map? content)
       (every? #(contains? % :type) content)))

(def text-content-roles #{"user"})

(defn normalize-message-content [role content]
  (if (contains? text-content-roles role)
    (cond
      (string? content) [{:type "text" :text content}]
      (text-blocks? content) content
      :else content)
    content))

(defn normalize-message [message]
  (let [role (:role message)]
    (cond-> (assoc message :content (normalize-message-content role (:content message)))
      (keyword? (:error message)) (update :error str))))

(defn- normalize-existing-id [id id-map]
  (if (and (string? id) (re-matches #"[a-f0-9]{8}" id))
    [id id-map false]
    (if-let [mapped (get id-map id)]
      [mapped id-map true]
      (let [new (new-id)]
        [new (assoc id-map id new) true]))))

(defn normalized-id [id id-map]
  (if (nil? id)
    (let [new (new-id)] [new id-map true])
    (normalize-existing-id id id-map)))

(defn normalized-parent-id [parent-id id-map]
  (if (nil? parent-id)
    [nil id-map false]
    (normalize-existing-id parent-id id-map)))

(defn slugify [s]
  (let [slug (-> (or s "")
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))]
    (if (str/blank? slug) "session" slug)))

(defn session-id [identifier]
  (slugify identifier))

(defn entry-defaults [opts]
  (merge {:crew      (or (:crew opts) "main")
          :channel   (:channel opts)
          :chat-type (or (:chat-type opts) (:chatType opts))}
         (into {} (remove (comp nil? val) opts))))

(defn effective-config [passed-config]
  (or passed-config
      (loader/snapshot "session store config — ambient fallback when caller passes no :config")
      {}))

(defn resolve-history-retention [opts]
  (resolve/resolve-history-retention (effective-config (:config opts))
                                    (or (:crew opts) "main")
                                    (:history-retention opts)))

(defn conform-session-read [entry]
  (-> entry
      session-schema/conform-read
      session-schema/conform-read))

(defn conform-session! [entry]
  (session-schema/conform! entry))

(defn exists?* [fs path] (fs/exists? fs path))
(defn slurp* [fs path] (fs/slurp fs path))
(defn spit*! [fs path content & options] (apply fs/spit fs path content options))
(defn children* [fs path] (fs/children fs path))
(defn mkdirs*! [fs path] (fs/mkdirs fs path))
(defn delete*! [fs path] (fs/delete fs path))

;; endregion ^^^^^ Helpers ^^^^^

;; region ----- Paths -----

(defn sessions-dir [root]
  (str root "/sessions"))

(defn sidecar-path [root session-id]
  (str (sessions-dir root) "/" session-id ".edn"))

(defn index-path [root]
  (str (sessions-dir root) "/index.edn"))

(defn transcript-path [root session-file]
  (str (sessions-dir root) "/" session-file))

;; endregion ^^^^^ Paths ^^^^^

;; region ----- Transcript -----

(defn read-transcript-raw [root session-file fs]
  (let [path (transcript-path root session-file)]
    (if (exists?* fs path)
      (->> (str/split-lines (slurp* fs path))
           (remove str/blank?)
           (mapv read-json))
      [])))

(defn write-transcript! [root session-file entries fs]
  (let [path (transcript-path root session-file)]
    (mkdirs*! fs (fs/parent path))
    (spit*! fs path (str (str/join "\n" (map write-json entries)) "\n"))))

(defn transcript-byte-offset [entries]
  (->> entries
       (map #(str (write-json %) "\n"))
       (map #(.getBytes ^String % StandardCharsets/UTF_8))
       (reduce (fn [total bytes] (+ total (alength bytes))) 0)))

(defn read-transcript-from-offset [root session-file offset fs]
  (let [path (transcript-path root session-file)]
    (if (exists?* fs path)
      (let [bytes  (.getBytes ^String (slurp* fs path) StandardCharsets/UTF_8)
            offset (min (max 0 (long offset)) (alength bytes))
            text   (String. bytes offset (- (alength bytes) offset) StandardCharsets/UTF_8)]
        (->> (str/split-lines text)
             (remove str/blank?)
             (mapv read-json)))
      [])))

(defn append-entry! [root session-file entry fs]
  (let [path (transcript-path root session-file)]
    (spit*! fs path (str (write-json entry) "\n") :append true)))

(defn normalize-transcript-entry [normalize-ts entry id-map]
  (let [[id id-map id-changed?]            (normalized-id (:id entry) id-map)
        [parent-id id-map parent-changed?] (normalized-parent-id (:parentId entry) id-map)
        ts                                 (normalize-ts (:timestamp entry))
        ts-changed?                        (not= ts (:timestamp entry))
        base                               (-> entry
                                               (assoc :id id :parentId parent-id :timestamp ts))
        normalized                         (case (:type base)
                                             "session" (-> base
                                                           (assoc :version (or (:version base) 3))
                                                           (assoc :cwd (or (:cwd base) (System/getProperty "user.dir"))))
                                             "message" (update base :message normalize-message)
                                             base)]
    [normalized id-map (or id-changed? parent-changed? ts-changed? (not= normalized entry))]))

(defn normalize-transcript [normalize-ts entries]
  (loop [remaining entries id-map {} out [] changed? false]
    (if (empty? remaining)
      [out changed?]
      (let [[normalized next-id-map entry-changed?] (normalize-transcript-entry normalize-ts (first remaining) id-map)]
        (recur (rest remaining) next-id-map (conj out normalized) (or changed? entry-changed?))))))

(defn migrate-transcript! [normalize-ts root session-file fs]
  (let [raw                   (read-transcript-raw root session-file fs)
        [normalized changed?] (normalize-transcript normalize-ts raw)]
    (when changed?
      (write-transcript! root session-file normalized fs))
    normalized))

;; endregion ^^^^^ Transcript ^^^^^

;; region ----- Session defaults & index helpers -----

(defn with-session-defaults [now-fn normalize-ts-fn entry]
  (let [entry (session-schema/conform-read entry)
        id    (or (:id entry) (:key entry))]
    (conform-session-read
      (-> entry
          (assoc :id id :key (or (:key entry) id))
          (update :name #(or % id))
          (update :origin #(or % {:kind :cli}))
          (update :cwd #(or % (System/getProperty "user.dir")))
          (update :created-at #(some-> % normalize-ts-fn))
          (update :updated-at #(or (some-> % normalize-ts-fn) (now-fn)))
          (update :tags #(or % #{}))
          (update :compaction-disabled #(if (nil? %) false %))
          (update :compaction-count #(or % 0))
          (update :input-tokens #(or % 0))
          (update :last-input-tokens #(or % 0))
          (update :output-tokens #(or % 0))
          (update :total-tokens #(or % 0))))))

(defn sidecar-session-id [file-name]
  (subs file-name 0 (- (count file-name) (count ".edn"))))

(defn read-sidecar-entry [with-session-defaults-fn root file-name fs]
  (let [sid   (sidecar-session-id file-name)
        path  (sidecar-path root sid)
        raw   (edn/read-string (slurp* fs path))
        entry (if (map? raw) (keywordize-map raw) {})]
    [sid (with-session-defaults-fn (assoc entry :id sid))]))

(defn read-sidecar-store [with-session-defaults-fn root fs]
  (let [dir (sessions-dir root)]
    (->> (or (children* fs dir) [])
         (filter #(str/ends-with? % ".edn"))
         (remove #(= "index.edn" %))
         (map #(read-sidecar-entry with-session-defaults-fn root % fs))
         (into {}))))

(defn normalize-index-store [with-session-defaults-fn raw]
  (cond
    (map? raw)
    (reduce-kv (fn [store key-str entry]
                 (let [id         (if (keyword? key-str) (name key-str) (str key-str))
                       entry      (if (map? entry) (keywordize-map entry) {})
                       normalized (with-session-defaults-fn (assoc entry :id id))]
                   (assoc store id normalized)))
               {}
               raw)

    (sequential? raw)
    (reduce (fn [store entry]
              (let [entry      (if (map? entry) (keywordize-map entry) {})
                    id         (or (:id entry) (:key entry))
                    normalized (with-session-defaults-fn (assoc entry :id id))]
                (if (str/blank? id)
                  store
                  (assoc store id normalized))))
            {}
            raw)

    :else
    {}))

(defn resolve-entry-id [store identifier]
  (cond
    (nil? identifier) nil
    (contains? store identifier) identifier
    :else (let [id (session-id identifier)] (when (contains? store id) id))))

(defn create-session! [read-session-fn write-fn now-iso-fn normalize-ts-fn root identifier opts fs]
  (let [opts               (entry-defaults opts)
        store              (read-session-fn root fs)
        name               (or identifier (naming/generate (session-store/ensure-naming-strategy! root fs)))
        id                 (session-id name)
        existing           (get store id)
        transcript-exists? (when (and existing (:session-file existing))
                             (exists?* fs (transcript-path root (:session-file existing))))]
    (cond
      (and existing transcript-exists? (not= name (:name existing)))
      (throw (ex-info (str "session already exists: " id)
                      {:name name :session-id id}))

      (and existing transcript-exists?)
      (do
        (log/info :session/opened :sessionId id)
        existing)

      :else
      (let [session-file  (str id ".jsonl")
            now           (or (normalize-ts-fn (:updated-at opts)) (now-iso-fn))
            retention     (resolve-history-retention opts)
            transcript-id (new-id)
            header        {:type      "session"
                           :id        transcript-id
                           :timestamp now
                           :version   3
                           :cwd       (System/getProperty "user.dir")}
            entry         (with-session-defaults now-iso-fn normalize-ts-fn
                            {:id                id
                             :key               id
                             :name              name
                             :nonce             (or (:nonce opts) (new-nonce))
                             :sessionId         transcript-id
                             :session-file      session-file
                             :origin            (:origin opts)
                             :history-retention retention
                             :created-at        now
                             :updated-at        now
                             :cwd               (or (:cwd opts) (System/getProperty "user.dir"))
                             :crew              (:crew opts)
                             :tags              (:tags opts)
                             :channel           (:channel opts)
                             :chat-type         (or (:chat-type opts) (:chatType opts))
                             :compaction-count  0
                             :input-tokens      0
                             :last-input-tokens 0
                             :output-tokens     0
                             :total-tokens      0})]
        (write-transcript! root session-file [header] fs)
        (write-fn store id (conform-session! entry))
        (log/info :session/created :sessionId id)
        entry))))

;; endregion ^^^^^ Session defaults & index helpers ^^^^^

;; region ----- Backup -----

(def ^:private bak-ts-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS"))

(def max-backup-count 8)

(defn session-base [session-file]
  (subs session-file 0 (- (count session-file) (count ".jsonl"))))

(defn backup-transcript! [root session-file fs]
  (let [path     (transcript-path root session-file)
        dir      (sessions-dir root)
        base     (session-base session-file)
        ts       (.format bak-ts-formatter (.atOffset (Instant/now) ZoneOffset/UTC))
        bak-path (str dir "/" base "." ts ".bak.jsonl")]
    (when (exists?* fs path)
      (spit*! fs bak-path (slurp* fs path))
      (let [backups (->> (children* fs dir)
                         (filter #(and (str/starts-with? % (str base "."))
                                       (str/ends-with? % ".bak.jsonl")))
                         sort
                         reverse
                         (drop max-backup-count))]
        (doseq [name backups]
          (delete*! fs (str dir "/" name)))))))

;; endregion ^^^^^ Backup ^^^^^

;; region ----- Toolcall helpers -----

(defn entry-toolcall-ids [entry]
  (let [message (get entry :message)
        content (:content message)]
    (cond
      (= "toolCall" (:type message))
      (keep :id [message])

      (sequential? content)
      (->> content
           (filter #(= "toolCall" (:type %)))
           (keep :id))

      :else
      nil)))

(defn drop-orphan-toolcalls [transcript]
  (let [tool-call-ids   (->> transcript
                             (filter #(= "message" (:type %)))
                             (mapcat entry-toolcall-ids)
                             set)
        tool-result-ids (->> transcript
                             (filter #(= "toolResult" (get-in % [:message :role])))
                             (keep #(or (get-in % [:message :toolCallId])
                                        (get-in % [:message :id])
                                        (:id %)))
                             set)
        orphans         (set/difference tool-call-ids tool-result-ids)]
    (if (empty? orphans)
      transcript
      (let [remove?     (fn [entry]
                          (and (= "message" (:type entry))
                               (seq (set/intersection orphans (set (entry-toolcall-ids entry))))))
            removed-ids (->> transcript (filter remove?) (map :id) set)
            kept        (vec (remove remove? transcript))
            remap       (loop [remaining transcript last-kept nil mapping {}]
                          (if (empty? remaining)
                            mapping
                            (let [e (first remaining)]
                              (if (contains? removed-ids (:id e))
                                (recur (rest remaining) last-kept (assoc mapping (:id e) last-kept))
                                (recur (rest remaining) (:id e) mapping)))))]
        (mapv (fn [entry]
                (if-let [new-parent (get remap (:parentId entry))]
                  (assoc entry :parentId new-parent)
                  entry))
              kept)))))

(defn last-entry-id [transcript]
  (:id (last transcript)))

;; endregion ^^^^^ Toolcall helpers ^^^^^

;; region ----- Shared public API -----

(defn list-sessions [read-store-fn root crew-id fs]
  (let [sessions (->> (vals (read-store-fn root fs))
                      (sort-by :id)
                      vec)]
    (if crew-id
      (->> sessions (filter #(= crew-id (:crew %))) vec)
      sessions)))

(defn most-recent-session [read-store-fn root crew-id fs]
  (->> (list-sessions read-store-fn root crew-id fs)
       (sort-by :updated-at)
       last))

(defn get-session [read-store-fn root identifier fs]
  (let [store (read-store-fn root fs)]
    (when-let [id (resolve-entry-id store identifier)]
      (get store id))))

(defn get-transcript [get-session-fn migrate-fn root identifier fs]
  (when-let [entry (get-session-fn root identifier fs)]
    (migrate-fn root (:session-file entry) fs)))

(defn active-transcript [get-session-fn migrate-fn root identifier fs]
  (when-let [entry (get-session-fn root identifier fs)]
    (migrate-fn root (:session-file entry) fs)
    (if-let [offset (:effective-history-offset entry)]
      (read-transcript-from-offset root (:session-file entry) offset fs)
      (read-transcript-raw root (:session-file entry) fs))))

(defn truncate-after-compaction! [get-session-fn root identifier fs]
  (let [entry      (get-session-fn root identifier fs)
        transcript (read-transcript-raw root (:session-file entry) fs)
        compaction (->> transcript (filter #(= "compaction" (:type %))) last)]
    (when compaction
      (let [first-kept-id  (:firstKeptEntryId compaction)
            compaction-id  (:id compaction)
            removed-ids    (loop [remaining transcript ids #{}]
                             (if (empty? remaining)
                               ids
                               (let [e (first remaining)]
                                 (cond
                                   (= (:id e) compaction-id) ids
                                   (and first-kept-id (= (:id e) first-kept-id)) ids
                                   (= "message" (:type e)) (recur (rest remaining) (conj ids (:id e)))
                                   :else (recur (rest remaining) ids)))))
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
        (when (pos? (count removed-ids))
          (write-transcript! root (:session-file entry) new-transcript fs)
          (count removed-ids))))))

(defn update-session! [update-entry-fn normalize-ts-fn root identifier updates fs]
  (update-entry-fn root identifier
                   (fn [entry]
                     (let [updates (if-let [compaction (:compaction updates)]
                                     (assoc updates :compaction (merge (or (:compaction entry) {}) compaction))
                                     updates)]
                       (-> (merge entry updates)
                           (assoc :key (:id entry))
                           (update :updated-at normalize-ts-fn))))
                   fs))

(defn append-message! [get-session-fn migrate-fn update-entry-fn now-fn root identifier message fs]
  (let [entry            (get-session-fn root identifier fs)
        transcript       (migrate-fn root (:session-file entry) fs)
        parent-id        (last-entry-id transcript)
        msg-id           (new-id)
        now              (now-fn)
        resolved-agent   (or (:crew message)
                             (when (#{"assistant" "error" "toolResult"} (:role message)) (:crew entry))
                             (when (= "assistant" (:role message)) "main"))
        normalized-msg   (normalize-message (cond-> message
                                              resolved-agent (assoc :crew resolved-agent)))
        transcript-entry (cond-> {:type     "message"
                                  :id       msg-id
                                  :parentId parent-id
                                  :timestamp now
                                  :message  normalized-msg}
                           (:tokens message) (assoc :tokens (:tokens message)))]
    (append-entry! root (:session-file entry) transcript-entry fs)
    (update-entry-fn root identifier
                     (fn [e]
                       (cond-> (assoc e :updated-at now)
                         (:channel message) (assoc :last-channel (:channel message))
                         (:to message)      (assoc :last-to (:to message))
                         resolved-agent     (assoc :crew resolved-agent)))
                     fs)
    transcript-entry))

(defn append-error! [get-session-fn migrate-fn update-entry-fn now-fn root identifier error-entry fs]
  (let [entry            (get-session-fn root identifier fs)
        transcript       (migrate-fn root (:session-file entry) fs)
        parent-id        (last-entry-id transcript)
        error-id         (new-id)
        now              (now-fn)
        transcript-entry (cond-> {:type      "error"
                                  :id        error-id
                                  :parentId  parent-id
                                  :timestamp now
                                  :content   (:content error-entry)
                                  :error     (:error error-entry)
                                  :model     (:model error-entry)
                                  :provider  (:provider error-entry)}
                           (:ex-class error-entry) (assoc :ex-class (:ex-class error-entry)))]
    (append-entry! root (:session-file entry) transcript-entry fs)
    (update-entry-fn root identifier #(assoc % :updated-at now) fs)
    transcript-entry))

(defn append-compaction! [get-session-fn migrate-fn update-entry-fn now-fn root identifier {:keys [summary firstKeptEntryId tokensBefore]} fs]
  (let [entry         (get-session-fn root identifier fs)
        transcript    (migrate-fn root (:session-file entry) fs)
        parent-id     (last-entry-id transcript)
        compaction-id (new-id)
        now           (now-fn)
        compaction    {:type             "compaction"
                       :id               compaction-id
                       :parentId         parent-id
                       :timestamp        now
                       :summary          summary
                       :firstKeptEntryId firstKeptEntryId
                       :tokensBefore     tokensBefore}]
    (append-entry! root (:session-file entry) compaction fs)
    (update-entry-fn root identifier
                     (fn [e]
                       (-> e
                           (assoc :updated-at now)
                           (update :compaction-count inc)))
                     fs)
    compaction))

(defn splice-compaction! [get-session-fn migrate-fn update-entry-fn now-fn root identifier {:keys [compactedEntryIds firstKeptEntryId summary tokensBefore]} fs]
  (let [entry            (get-session-fn root identifier fs)
        transcript       (migrate-fn root (:session-file entry) fs)
        retention        (or (:history-retention entry) resolve/default-history-retention)
        compacted-ids    (set compactedEntryIds)
        removable-ids    (->> transcript
                               (filter #(and (= "message" (:type %))
                                             (contains? compacted-ids (:id %))))
                               (map :id)
                               set)
        first-kept-index (when firstKeptEntryId
                           (some (fn [[idx e]]
                                   (when (= firstKeptEntryId (:id e)) idx))
                                 (map-indexed vector transcript)))
        insert-at        (case retention
                           :retain (or first-kept-index (count transcript))
                           (or (some (fn [[idx e]]
                                       (when (contains? removable-ids (:id e)) idx))
                                     (map-indexed vector transcript))
                               (count transcript)))
        before           (subvec transcript 0 insert-at)
        compaction-id    (new-id)
        now              (now-fn)
        compaction-entry {:type             "compaction"
                          :id               compaction-id
                          :parentId         (:id (last before))
                          :timestamp        now
                          :summary          summary
                          :firstKeptEntryId firstKeptEntryId
                          :tokensBefore     tokensBefore}
        after            (->> (subvec transcript (or first-kept-index (count transcript)))
                              ((fn [entries]
                                 (if (= :retain retention)
                                   entries
                                   (remove #(contains? removable-ids (:id %)) entries))))
                              (mapv (fn [e]
                                      (if (contains? removable-ids (:parentId e))
                                        (assoc e :parentId compaction-id)
                                        e))))
        new-transcript   (drop-orphan-toolcalls (into before (cons compaction-entry after)))]
    (backup-transcript! root (:session-file entry) fs)
    (write-transcript! root (:session-file entry) new-transcript fs)
    (update-entry-fn root identifier
                     (fn [e]
                       (-> e
                           (assoc :updated-at now)
                           ((fn [entry]
                              (if (= :retain retention)
                                (assoc entry :effective-history-offset (transcript-byte-offset before))
                                (dissoc entry :effective-history-offset))))
                           (update :compaction-count inc)))
                     fs)
    compaction-entry))

;; endregion ^^^^^ Shared public API ^^^^^

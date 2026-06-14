(ns isaac.tool.memory
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.session.store.spi :as store]
    [isaac.tool.fs-bounds :as bounds]))

(def ^:dynamic *now* nil)

(defn now []
  (or *now* (java.time.Instant/now)))

(defn- string-key-map [m]
  (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]) m)))

(defn- crew-id [args]
  (let [args        (string-key-map args)
        session-key (get args "session_key")]
    (or (some->> session-key (store/get-session (bounds/session-store args)) :crew)
        (get-in (loader/snapshot "tool memory: default crew") [:defaults :crew])
        "main")))

(defn- memory-dir [root crew-id]
  (str root "/crew/" crew-id "/memory"))

(defn- date-str [instant]
  (str (.toLocalDate (java.time.ZonedDateTime/ofInstant instant java.time.ZoneOffset/UTC))))

(defn- today-path [root crew-id]
  (str (memory-dir root crew-id) "/" (date-str (now)) ".md"))

(defn- lines [content]
  (cond
    (string? content) [content]
    (and (sequential? content) (every? string? content)) (vec content)
    :else nil))

(defn memory-write-tool
  [args]
  (let [args        (string-key-map args)
        content     (get args "content")
        root   (bounds/root args)
        fs*         (bounds/filesystem args)]
    (if-let [entries (lines content)]
      (let [crew-id   (crew-id args)
            path      (today-path root crew-id)
            existing? (fs/exists? fs* path)
            prefix    (when (and existing? (seq (fs/slurp fs* path))) "\n")]
        (fs/mkdirs fs* (fs/parent path))
        (fs/spit fs* path (str prefix (str/join "\n" entries)) :append existing?)
        {:result (str "wrote " path)})
      {:isError true :error "content must be a string or vector of strings"})))

(defn- parse-date [s]
  (java.time.LocalDate/parse s))

(defn- date-range [start end]
  (loop [current start out []]
    (if (.isAfter current end)
      out
      (recur (.plusDays current 1) (conj out current)))))

(defn memory-get-tool
  [args]
  (let [args        (string-key-map args)
        end-time    (get args "end_time")
        start-time  (get args "start_time")
        root   (bounds/root args)
        fs*         (bounds/filesystem args)
        start       (parse-date start-time)
        end         (parse-date end-time)
        crew-id     (crew-id args)
        result      (->> (date-range start end)
                          (map #(str (memory-dir root crew-id) "/" % ".md"))
                          (filter #(fs/exists? fs* %))
                          (map #(fs/slurp fs* %))
                          (str/join "\n"))]
    {:result result}))

(defn- matching-lines [fs* query path]
  (let [pattern (re-pattern (str "(?i)" query))]
    (->> (str/split-lines (or (fs/slurp fs* path) ""))
         (keep-indexed (fn [idx line]
                         (when (re-find pattern line)
                           (str (.getName (java.io.File. path)) ":" (inc idx) ":" line)))))))

(defn memory-search-tool
  [args]
  (let [args        (string-key-map args)
        query       (get args "query")
        root   (bounds/root args)
        fs*         (bounds/filesystem args)
        crew-id     (crew-id args)
        dir         (memory-dir root crew-id)
        matches     (->> (or (fs/children fs* dir) [])
                          sort
                          (mapcat #(matching-lines fs* query (str dir "/" %))))]
    {:result (if (seq matches)
               (str/join "\n" matches)
               "no matches")}))

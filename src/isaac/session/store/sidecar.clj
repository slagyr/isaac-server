;; mutation-tested: 2026-05-06
(ns isaac.session.store.sidecar
  (:require
    [clojure.edn :as edn]
    [isaac.fs :as fs]
    [isaac.session.store.spi :as store]
    [isaac.session.store.impl-common :as c])
  (:import
    (java.time Instant ZoneOffset)
    (java.time.format DateTimeFormatter)))

;; region ----- Impl-specific -----

(def ^:private ts-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))

(defn- now-iso []
  (.format ts-formatter (.atOffset (Instant/now) ZoneOffset/UTC)))

(defn- ms->iso [ms]
  (.format ts-formatter (.atOffset (Instant/ofEpochMilli ms) ZoneOffset/UTC)))

(defn- normalize-timestamp [ts] (c/normalize-timestamp ms->iso ts))

(defn- runtime-fs! []
  (or (fs/instance) (throw (ex-info "sidecar session store requires explicit fs or installed runtime :fs" {}))))

;; endregion ^^^^^ Impl-specific ^^^^^

;; region ----- Helpers -----

(defn session-id [identifier] (c/session-id identifier))

(defn- with-session-defaults [entry]
  (c/with-session-defaults now-iso normalize-timestamp entry))

(defn- migrate-transcript! [root session-file fs]
  (c/migrate-transcript! normalize-timestamp root session-file fs))

(defn- normalize-index-store [raw]
  (c/normalize-index-store with-session-defaults raw))

(defn- read-sidecar-store [root fs]
  (c/read-sidecar-store with-session-defaults root fs))

;; endregion ^^^^^ Helpers ^^^^^

;; region ----- Storage -----

(defn- write-sidecar! [root {:keys [id] :as entry} fs]
  (let [path (c/sidecar-path root id)]
    (c/mkdirs*! fs (fs/parent path))
    (c/spit*! fs path (c/write-edn entry))))

(defn- read-legacy-index-store [root fs]
  (let [path (c/index-path root)
        raw  (if (c/exists?* fs path) (edn/read-string (c/slurp* fs path)) {})]
    (normalize-index-store raw)))

(defn- migrate-legacy-index! [root fs]
  (let [legacy-store  (read-legacy-index-store root fs)
        sidecar-store (read-sidecar-store root fs)]
    (doseq [[id entry] legacy-store
            :when (not (contains? sidecar-store id))]
      (when (and (:session-file entry)
                 (c/exists?* fs (c/transcript-path root (:session-file entry))))
        (migrate-transcript! root (:session-file entry) fs))
      (write-sidecar! root entry fs))))

(defn- read-session-store [root fs]
  (migrate-legacy-index! root fs)
  (read-sidecar-store root fs))

(defn- update-sidecar-entry! [root identifier updater fs]
  (let [store (read-session-store root fs)]
    (when-let [id (c/resolve-entry-id store identifier)]
      (let [entry (c/conform-session! (updater (get store id)))]
        (write-sidecar! root entry fs)
        entry))))

;; endregion ^^^^^ Storage ^^^^^

;; region ----- Public API -----

(defn create-session!
  ([root identifier]
   (create-session! root identifier {} (runtime-fs!)))
  ([root identifier opts]
   (create-session! root identifier opts (runtime-fs!)))
  ([root identifier opts fs]
   (c/create-session! read-session-store
                      (fn [_store _id entry] (write-sidecar! root entry fs))
                      now-iso normalize-timestamp
                      root identifier opts fs)))

(defn- get-session [root identifier fs]
  (c/get-session read-session-store root identifier fs))

(defn- get-transcript [root identifier fs]
  (c/get-transcript get-session migrate-transcript! root identifier fs))

(defn- delete-session! [root identifier fs]
  (let [store (read-session-store root fs)]
    (when-let [id (c/resolve-entry-id store identifier)]
      (let [entry (get store id)
            path  (c/transcript-path root (:session-file entry))
            meta  (c/sidecar-path root id)]
        (when (c/exists?* fs meta) (c/delete*! fs meta))
        (when (c/exists?* fs path) (c/delete*! fs path))
        true))))

(defn update-tokens!
  ([root identifier updates]
   (update-tokens! root identifier updates (runtime-fs!)))
  ([root identifier {:keys [cache-read cache-write] :as updates} fs]
   (let [input-tokens  (:input-tokens updates)
         output-tokens (:output-tokens updates)]
     (update-sidecar-entry! root identifier
                            (fn [entry]
                              (cond-> (-> entry
                                          (update :input-tokens + (or input-tokens 0))
                                          (assoc :last-input-tokens (or input-tokens 0))
                                          (update :output-tokens + (or output-tokens 0))
                                          (assoc :total-tokens (+ (+ (:input-tokens entry) (or input-tokens 0))
                                                                  (+ (:output-tokens entry) (or output-tokens 0)))))
                                cache-read  (update :cache-read (fnil + 0) cache-read)
                                cache-write (update :cache-write (fnil + 0) cache-write)))
                            fs))))

;; endregion ^^^^^ Public API ^^^^^

;; region ----- Store type -----

(deftype SidecarSessionStore [root fs]
  store/SessionStore
  (open-session! [_ name opts]
    (create-session! root name opts fs))
  (delete-session! [_ name]
    (delete-session! root name fs))
  (list-sessions [_]
    (c/list-sessions read-session-store root nil fs))
  (list-sessions-by-agent [_ agent]
    (c/list-sessions read-session-store root agent fs))
  (most-recent-session [_]
    (c/most-recent-session read-session-store root nil fs))
  (get-session [_ name]
    (get-session root name fs))
  (get-transcript [_ name]
    (get-transcript root name fs))
  (active-transcript [_ name]
    (c/active-transcript get-session migrate-transcript! root name fs))
  (update-session! [_ name updates]
    (c/update-session! update-sidecar-entry! normalize-timestamp root name updates fs))
  (append-message! [_ name message]
    (c/append-message! get-session migrate-transcript! update-sidecar-entry! now-iso root name message fs))
  (append-error! [_ name error]
    (c/append-error! get-session migrate-transcript! update-sidecar-entry! now-iso root name error fs))
  (append-compaction! [_ name compaction]
    (c/append-compaction! get-session migrate-transcript! update-sidecar-entry! now-iso root name compaction fs))
  (splice-compaction! [_ name compaction]
    (c/splice-compaction! get-session migrate-transcript! update-sidecar-entry! now-iso root name compaction fs))
  (truncate-after-compaction! [_ name]
    (c/truncate-after-compaction! get-session root name fs)))

(defn create-store
  ([root]
   (create-store root (runtime-fs!)))
  ([root fs*]
   (->SidecarSessionStore root fs*)))

(store/register-factory! :jsonl-edn-sidecar #'create-store)

;; endregion ^^^^^ Store type ^^^^^

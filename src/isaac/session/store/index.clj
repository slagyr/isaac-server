(ns isaac.session.store.index
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
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS"))

(defn- now-iso []
  (.format ts-formatter (.atOffset (Instant/now) ZoneOffset/UTC)))

(defn- ms->iso [ms]
  (.format ts-formatter (.atOffset (Instant/ofEpochMilli ms) ZoneOffset/UTC)))

(defn- normalize-timestamp [ts] (c/normalize-timestamp ms->iso ts))

(defn- runtime-fs! []
  (or (fs/instance) (throw (ex-info "index session store requires explicit fs or installed runtime :fs" {}))))

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

(defn- write-index! [root store fs]
  (let [path (c/index-path root)]
    (c/mkdirs*! fs (fs/parent path))
    (c/spit*! fs path (c/write-edn store))))

(defn- read-session-store [root fs]
  (let [path  (c/index-path root)
        store (if (c/exists?* fs path)
                (normalize-index-store (edn/read-string (c/slurp* fs path)))
                (let [sidecars (read-sidecar-store root fs)]
                  (when (seq sidecars)
                    (write-index! root sidecars fs))
                  sidecars))]
    store))

(defn- update-index-entry! [root identifier updater fs]
  (let [store (read-session-store root fs)]
    (when-let [id (c/resolve-entry-id store identifier)]
      (let [entry     (c/conform-session! (updater (get store id)))
            new-store (assoc store id entry)]
        (write-index! root new-store fs)
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
                      (fn [store id entry] (write-index! root (assoc store id entry) fs))
                      now-iso normalize-timestamp
                      root identifier opts fs)))

(defn- get-session [root identifier fs]
  (c/get-session read-session-store root identifier fs))

(defn- get-transcript [root identifier fs]
  (c/get-transcript get-session migrate-transcript! root identifier fs))

(defn- delete-session! [root identifier fs]
  (let [store (read-session-store root fs)]
    (when-let [id (c/resolve-entry-id store identifier)]
      (let [entry     (get store id)
            path      (c/transcript-path root (:session-file entry))
            new-store (dissoc store id)]
        (write-index! root new-store fs)
        (when (c/exists?* fs path)
          (c/delete*! fs path))
        true))))

;; endregion ^^^^^ Public API ^^^^^

;; region ----- Store type -----

(deftype IndexSessionStore [root fs]
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
    (c/update-session! update-index-entry! normalize-timestamp root name updates fs))
  (append-message! [_ name message]
    (c/append-message! get-session migrate-transcript! update-index-entry! now-iso root name message fs))
  (append-error! [_ name error]
    (c/append-error! get-session migrate-transcript! update-index-entry! now-iso root name error fs))
  (append-compaction! [_ name compaction]
    (c/append-compaction! get-session migrate-transcript! update-index-entry! now-iso root name compaction fs))
  (splice-compaction! [_ name compaction]
    (c/splice-compaction! get-session migrate-transcript! update-index-entry! now-iso root name compaction fs))
  (truncate-after-compaction! [_ name]
    (c/truncate-after-compaction! get-session root name fs)))

(defn create-store
  ([root]
   (create-store root (runtime-fs!)))
  ([root fs*]
   (->IndexSessionStore root fs*)))

(store/register-factory! :jsonl-edn-index #'create-store)

;; endregion ^^^^^ Store type ^^^^^

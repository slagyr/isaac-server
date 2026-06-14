(ns isaac.comm.delivery.queue
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.tool.memory :as memory])
  (:import
    (java.util UUID)))

(defn- write-edn [value]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint value))))

(defn- runtime-root []
  (or (loader/root) (throw (ex-info "delivery queue requires :root" {}))))

(defn- filesystem []
  (or (fs/instance) (throw (ex-info "comm.delivery.queue requires :fs in system" {}))))

(defn- delivery-dir []
  (str (runtime-root) "/comm/delivery"))

(defn- pending-dir []
  (str (delivery-dir) "/pending"))

(defn- failed-dir []
  (str (delivery-dir) "/failed"))

(defn- pending-path [id]
  (str (pending-dir) "/" id ".edn"))

(defn- failed-path [id]
  (str (failed-dir) "/" id ".edn"))

(defn- new-id []
  (-> (str (UUID/randomUUID))
      (str/replace "-" "")
      (subs 0 4)))

(defn- normalize-record [record]
  (-> record
      (update :id #(or % (new-id)))
      (update :attempts #(or % 0))
      (update :created-at #(or % (str (memory/now))))))

(defn- read-record [path]
  (let [fs* (filesystem)]
    (when (fs/exists? fs* path)
      (let [record (edn/read-string (fs/slurp fs* path))]
      (if (map? record)
        (into {} (map (fn [[k v]] [(if (keyword? k) k (keyword k)) v]) record))
        record)))))

(defn enqueue! [record]
  (let [fs*    (filesystem)
        record (normalize-record record)
        path   (pending-path (:id record))]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path (write-edn record))
    record))

(defn update-pending! [id attrs]
  (let [fs*     (filesystem)
        path    (pending-path id)
        current (or (read-record path) {:id id})
        updated (merge current attrs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path (write-edn updated))
    updated))

(defn read-pending [id]
  (read-record (pending-path id)))

(defn read-failed [id]
  (read-record (failed-path id)))

(defn delete-pending! [id]
  (fs/delete (filesystem) (pending-path id)))

(defn move-to-failed! [id attrs]
  (let [fs*    (filesystem)
        record (merge (or (read-pending id) {:id id}) attrs)
        path   (failed-path id)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path (write-edn record))
    (delete-pending! id)
    record))

(defn list-pending []
  (let [fs* (filesystem)
        dir (pending-dir)]
    (if-let [children (fs/children fs* dir)]
      (->> children
            (map #(read-record (str dir "/" %)))
           (remove nil?)
           (sort-by :id)
           vec)
      [])))

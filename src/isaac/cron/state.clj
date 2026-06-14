(ns isaac.cron.state
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]))

(defn- cron-state-path [root]
  (str root "/cron.edn"))

(defn- write-edn [value]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint value))))

(defn- runtime-root []
  (or (loader/root) (throw (ex-info "cron state requires :root" {}))))

(defn- runtime-fs! []
  (or (fs/instance) (throw (ex-info "cron state requires :fs in system" {}))))

(defn read-state
  ([]
   (read-state (runtime-root)))
  ([root]
   (let [fs*  (runtime-fs!)
          path (cron-state-path root)]
     (if (fs/exists? fs* path)
       (or (edn/read-string (fs/slurp fs* path)) {})
        {}))))

(defn write-job-state!
  ([job-name attrs]
   (write-job-state! (runtime-root) job-name attrs))
  ([root job-name attrs]
   (let [fs*     (runtime-fs!)
          path    (cron-state-path root)
          current (read-state root)
         updated (update current (str job-name) #(merge (or % {}) attrs))]
     (fs/mkdirs fs* (fs/parent path))
     (fs/spit fs* path (write-edn updated))
     updated)))

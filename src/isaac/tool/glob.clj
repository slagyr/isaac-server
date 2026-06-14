;; mutation-tested: 2026-05-06
(ns isaac.tool.glob
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.tool.fs-bounds :as bounds])
  (:import
    [java.io File]
    [java.nio.file FileSystems Files LinkOption]))

(def ^:dynamic *default-head-limit* 100)

(defn- glob-root [args]
  (let [path        (get args "path")
        session-cwd (bounds/session-workdir args)]
    (or (bounds/resolve-path path session-cwd)
        (bounds/root args)
        (System/getProperty "user.dir"))))

(defn- normalize-relative-path [path]
  (str/replace (.toString path) File/separator "/"))

(defn- glob-candidates [root-path]
  (if (Files/isRegularFile root-path (make-array LinkOption 0))
    [root-path]
    (with-open [stream (Files/walk root-path (into-array java.nio.file.FileVisitOption []))]
      (->> (iterator-seq (.iterator stream))
           (filter #(Files/isRegularFile % (make-array LinkOption 0)))
           vec))))

(defn- glob-matches [root pattern]
  (let [root-path (-> root io/file .toPath)
        matcher   (.getPathMatcher (FileSystems/getDefault) (str "glob:" pattern))]
    (->> (glob-candidates root-path)
         (map (fn [path]
                (let [relative (if (= path root-path)
                                 (.getFileName path)
                                 (.relativize root-path path))]
                  {:path    path
                   :display (normalize-relative-path relative)
                   :mtime   (.toMillis (Files/getLastModifiedTime path (make-array LinkOption 0)))})))
         (filter #(when-let [display (:display %)]
                    (.matches matcher (.getPath (FileSystems/getDefault) display (make-array String 0)))))
         (sort-by (juxt (comp - :mtime) :display)))))

(defn- glob-result [matches head-limit]
  (let [total      (count matches)
        truncated? (and (pos? head-limit) (> total head-limit))
        shown      (if (pos? head-limit) (take head-limit matches) matches)
        lines      (mapv :display shown)
        lines      (cond-> lines
                     truncated? (conj (str "Results truncated. " total " total matches.")))]
    {:result (str/join "\n" lines)}))

(defn glob-tool
  "List files matching a shell-style glob pattern.
   Args: pattern, path, head_limit."
  [args]
  (let [args       (bounds/string-key-map args)
        pattern    (get args "pattern")
        head-limit (bounds/arg-int args "head_limit" nil)
        root       (glob-root args)]
    (or (bounds/ensure-path-allowed args root)
        (let [matches (glob-matches root pattern)]
          (if (seq matches)
            (glob-result matches (or head-limit *default-head-limit*))
            {:result "no matches"})))))

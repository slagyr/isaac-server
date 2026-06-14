;; mutation-tested: 2026-05-06
(ns isaac.tool.file
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.tool.fs-bounds :as bounds])
  (:import (java.util.regex Pattern)))

(def ^:dynamic *default-read-limit* 2000)
(def ^:private binary-check-window 8192)

(defn- binary-content? [^String content]
  (let [len (min (count content) binary-check-window)]
    (loop [i 0]
      (cond
        (>= i len) false
        (= \u0000 (.charAt content i)) true
        :else (recur (inc i))))))

(defn- format-file-content [file-path content offset limit]
  (cond
    (binary-content? content)
    {:isError true :error (str "binary file: " file-path)}

    (= "" content)
    {:result "<empty file>"}

    :else
    (let [all-lines (str/split-lines content)
          total     (count all-lines)
          start     (if offset (max 0 (dec offset)) 0)
          effective (or limit *default-read-limit*)
          end       (min total (+ start effective))
          selected  (subvec (vec all-lines) start end)
          numbered  (map-indexed (fn [i line] (str (+ start i 1) ": " line)) selected)
          lines     (cond-> (vec numbered)
                            (< end total)
                            (conj (str "... (truncated: showing " (count selected)
                                       " of " total " lines)")))]
      {:result (str/join "\n" lines)})))

(defn read-tool
  "Read file contents or list a directory.
   Args: file_path, offset, limit."
  [args]
  (let [args        (bounds/string-key-map args)
        fs*         (bounds/filesystem args)
        session-cwd (bounds/session-workdir args)
        file-path   (bounds/resolve-path (get args "file_path") session-cwd)
        offset      (bounds/arg-int args "offset" nil)
        limit       (bounds/arg-int args "limit" nil)]
    (or (bounds/ensure-path-allowed args file-path)
        (cond
          (not (fs/exists? fs* file-path))
          {:isError true :error (str "not found: " file-path)}

          (when-let [entries (fs/children fs* file-path)]
            (seq entries))
          {:result (str/join "\n" (sort (fs/children fs* file-path)))}

          :else
          (format-file-content file-path (or (fs/slurp fs* file-path) "") offset limit)))))

(defn write-tool
  "Write content to a file, creating parent directories as needed.
   Args: file_path, content."
  [args]
  (let [args        (bounds/string-key-map args)
        fs*         (bounds/filesystem args)
        session-cwd (bounds/session-workdir args)
        file-path   (bounds/resolve-path (get args "file_path") session-cwd)
        content     (get args "content")]
    (or (bounds/ensure-path-allowed args file-path)
        (try
          (fs/mkdirs fs* (fs/parent file-path))
          (fs/spit fs* file-path content)
          {:result (str "wrote " file-path)}
          (catch Exception e
            {:isError true :error (.getMessage e)})))))

(defn edit-tool
  "Replace text in a file.
   Args: file_path, old_string, new_string, replace_all."
  [args]
  (let [args        (bounds/string-key-map args)
        fs*         (bounds/filesystem args)
        session-cwd (bounds/session-workdir args)
        file-path   (bounds/resolve-path (get args "file_path") session-cwd)
        old-string  (get args "old_string")
        new-string  (get args "new_string")
        replace-all (bounds/arg-bool args "replace_all" false)]
    (or (bounds/ensure-path-allowed args file-path)
        (if-not (fs/exists? fs* file-path)
          {:isError true :error (str "not found: " file-path)}
          (let [content (or (fs/slurp fs* file-path) "")
                count   (-> old-string Pattern/quote Pattern/compile (re-seq content) count)]
            (cond
              (= 0 count)
              {:isError true :error (str "not found: " old-string)}

              (and (> count 1) (not replace-all))
              {:isError true :error (str "multiple matches for: " old-string)}

              :else
              (let [new-content (str/replace content old-string new-string)]
                (fs/spit fs* file-path new-content)
                {:result (str "edited " file-path)})))))))

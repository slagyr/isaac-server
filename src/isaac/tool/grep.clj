;; mutation-tested: 2026-05-06
(ns isaac.tool.grep
  (:require
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [isaac.tool.fs-bounds :as bounds]
    [isaac.util.shell :as shell]))

(def ^:dynamic *default-head-limit* 250)

(def ^:private grep-type->globs
  {"clj"  ["*.clj" "*.cljc" "*.cljs"]
   "edn"  ["*.edn"]
   "java" ["*.java"]
   "js"   ["*.js" "*.jsx"]
   "json" ["*.json"]
   "md"   ["*.md"]
   "py"   ["*.py"]
   "ts"   ["*.ts" "*.tsx"]
   "yaml" ["*.yaml" "*.yml"]
   "yml"  ["*.yml" "*.yaml"]})

(defn available? []
  (shell/cmd-available? "rg"))

(defn- grep-globs [args]
  (concat
    (when-let [glob (get args "glob")]
      [glob])
    (get grep-type->globs (get args "type") [])))

(defn- grep-command [args]
  (let [mode          (or (get args "output_mode") "content")
         line-numbers? (bounds/arg-bool args "-n" (= mode "content"))
         command       (cond-> ["rg" "--color=never"]
                         (= mode "content")                  (conj "--with-filename")
                         (and (= mode "content") line-numbers?) (conj "-n")
                         (bounds/arg-bool args "-i" false)     (conj "-i")
                         (bounds/arg-int args "-A" nil)        (conj "-A" (str (bounds/arg-int args "-A" nil)))
                         (bounds/arg-int args "-B" nil)        (conj "-B" (str (bounds/arg-int args "-B" nil)))
                        (bounds/arg-int args "-C" nil)        (conj "-C" (str (bounds/arg-int args "-C" nil)))
                        (bounds/arg-bool args "multiline" false) (conj "--multiline")
                        (= mode "files_with_matches")         (conj "-l")
                        (= mode "count")                      (conj "-c")
                        (contains? #{"files_with_matches" "count"} mode) (conj "--sort" "path"))]
    (-> command
        (into (mapcat (fn [glob] ["-g" glob]) (grep-globs args)))
        (conj (get args "pattern"))
        (conj (get args "path")))))

(defn- grep-result [output offset head-limit]
  (let [lines       (cond->> (str/split-lines output)
                       (pos? offset) (drop offset))
        truncated?  (and (pos? head-limit) (> (count lines) head-limit))
        shown-lines (if (pos? head-limit) (take head-limit lines) lines)
        shown-lines (cond-> (vec shown-lines)
                      truncated? (conj "Results truncated."))]
    {:result (str/join "\n" shown-lines)}))

(defn -run-rg [cmd]
  (apply sh/sh cmd))

(defn grep-tool
  "Search file contents with ripgrep.
   Args: pattern, path, glob, type, -i, -n, -A, -B, -C, multiline, output_mode, head_limit, offset."
  [args]
  (let [args      (bounds/string-key-map args)
        path      (bounds/resolve-path
                    (get args "path")
                    (bounds/session-workdir args))
        args      (cond-> args path (assoc "path" path))]
    (or (bounds/ensure-path-allowed args path)
        (if-not (available?)
          {:isError true :error "rg not found on PATH"}
          (let [offset           (or (bounds/arg-int args "offset" 0) 0)
                head-limit       (or (bounds/arg-int args "head_limit" *default-head-limit*) *default-head-limit*)
                {:keys [exit out err]} (-run-rg (grep-command args))]
            (cond
              (and (= 1 exit) (str/blank? out) (str/blank? err))
              {:result "no matches"}

              (zero? exit)
              (grep-result out offset head-limit)

              :else
              {:isError true
               :error   (str/trim (or (not-empty err)
                                      (not-empty out)
                                      (str "rg failed with exit " exit)))}))))))

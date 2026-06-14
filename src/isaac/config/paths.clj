(ns isaac.config.paths
  "Filesystem layout knowledge for Isaac config. Pure path construction — no
   I/O. The root directory is the canonical home for config and runtime data:
   config lives at <root>/config and runtime data (crew, sessions, memory)
   under <root>. In production the root defaults to ~/.isaac, but any
   directory is valid (see isaac.config.root/default-root)."
  (:require [clojure.string :as str]))

(def ^:private entity-file-pattern #"[^/]+/[^/]+\.edn")
(def ^:private markdown-file-pattern #"(crew|cron|hooks)/[^/]+\.md")

(def root-filename "isaac.edn")

(defn config-root [root]
  (str root "/config"))

(defn config-path [root relative]
  (str (config-root root) "/" relative))

(defn root-config-file [root]
  (config-path root root-filename))

(defn entity-relative [kind id]
  (str (name kind) "/" id ".edn"))

(defn soul-relative [id]
  (str "crew/" id ".md"))

(defn cron-relative [id]
  (str "cron/" id ".md"))

(defn hook-relative [id]
  (str "hooks/" id ".md"))

(defn config-relative [root path]
  (let [root-prefix (str (config-root root) "/")]
    (when (str/starts-with? path root-prefix)
      (subs path (count root-prefix)))))

(defn config-file? [relative-path]
  (and (string? relative-path)
       (or (= root-filename relative-path)
           (boolean (re-matches entity-file-pattern relative-path))
           (boolean (re-matches markdown-file-pattern relative-path)))))

;; mutation-tested: 2026-05-06
(ns isaac.config.cli.common
  "Shared helpers for the isaac.config.cli.* subcommand namespaces."
  (:require
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [clojure.walk :as walk]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.config.root :as root]))

;; region ----- Option parsing -----

(def help-option-spec
  [["-h" "--help" "Show help"]])

(defn parse-option-map [args option-spec & parse-args]
  (let [{:keys [arguments errors options]} (apply tools-cli/parse-opts args option-spec parse-args)]
    {:arguments arguments
     :errors    errors
     :options   (->> options
                     (remove (comp nil? val))
                     (into {}))}))

(defn pad-right [text width]
  (let [needed (- width (count text))]
    (if (pos? needed)
      (str text (apply str (repeat needed " ")))
      text)))

(defn- option-prefix [[short-name long-name & _]]
  (if short-name
    (str "  " short-name ", " long-name)
    (str "      " long-name)))

(defn option-help-section
  "Render an 'Options:' block from a tools.cli option-spec. Each row lines up
   at the description column using the widest option prefix as the anchor."
  [option-spec]
  (let [prefixes (mapv option-prefix option-spec)
        max-w    (apply max 0 (map count prefixes))]
    (str "Options:\n"
         (str/join "\n"
           (map (fn [prefix [_ _ desc]]
                  (str (pad-right prefix (+ max-w 2)) desc))
                prefixes
                option-spec)))))

(defn- titled-section [[title body]]
  (when body (str title ":\n" body)))

(defn render-help
  "Render a standard subcommand help page from parts. Keys:
     :command         command phrase, e.g. 'isaac config get'
     :params          positional-arg summary appended to command, e.g. '[config-path]'
     :description     paragraph body (no trailing newline)
     :arguments       optional pre-formatted Arguments body (no 'Arguments:' header)
     :option-spec     tools.cli option-spec (rendered as the Options block)
     :examples        optional pre-formatted Examples body
     :pre-sections    optional seq of [title body] pairs rendered BEFORE Options
     :post-sections   optional seq of [title body] pairs rendered AFTER Examples

   Order: Usage, description, pre-sections, Arguments, Options, Examples, post-sections."
  [{:keys [command params description arguments option-spec examples pre-sections post-sections]}]
  (let [usage-line (str "Usage: " command (when params (str " " params)))
        blocks     (concat
                     [usage-line
                      description]
                     (map titled-section pre-sections)
                     [(titled-section ["Arguments" arguments])
                      (when option-spec (option-help-section option-spec))
                      (titled-section ["Examples" examples])]
                     (map titled-section post-sections))]
    (str/join "\n\n" (remove nil? blocks))))

;; endregion ^^^^^ Option parsing ^^^^^

;; region ----- Paths -----

(defn resolve-root [{:keys [root]}]
  (or root
      (root/default-root)))

(defn- keyword-safe-segment? [s]
  (and (not (str/blank? s))
       (some? (re-matches #"[A-Za-z*+!_?-][A-Za-z0-9*+!_?-]*" s))))

(defn- segment->expr [first? seg]
  (cond
    (and first? (keyword-safe-segment? seg)) seg
    (keyword-safe-segment? seg)              (str "." seg)
    :else                                    (str "[\"" seg "\"]")))

(defn normalize-path
  "When path-str begins with '/', treat '/' as the only separator and '.' as a
   literal character inside each segment. Otherwise return path-str unchanged."
  [path-str]
  (if (and (string? path-str) (str/starts-with? path-str "/"))
    (let [segs (remove str/blank? (str/split (subs path-str 1) #"/"))]
      (apply str (map-indexed (fn [idx s] (segment->expr (zero? idx) s)) segs)))
    path-str))

(defn path-prefix [path-str]
  (when-not (or (nil? path-str) (str/blank? path-str))
    (vec (str/split path-str #"\."))))

;; endregion ^^^^^ Paths ^^^^^

;; region ----- Printing -----

(defn stdout-tty? []
  (and (some? (System/console))
       (not (instance? java.io.StringWriter *out*))))

(defn print-lines! [lines]
  (doseq [line lines]
    (println line)))

(defn print-edn! [value]
  (if (coll? value)
    (binding [pprint/*print-right-margin* 20]
      (pprint/pprint value))
    (pprint/pprint value)))

(defn print-errors! [entries label]
  (binding [*out* *err*]
    (doseq [{:keys [bad-value file key valid-values value]} entries]
      (println (str label ": " key " - " value
                    (when file
                      (str " [file: " file "]"))
                    (when bad-value
                      (str " [bad value: " bad-value "]"))
                    (when (seq valid-values)
                      (str " [valid: " (str/join ", " valid-values) "]")))))))

(defn print-warnings! [entries]
  (binding [*out* *err*]
    (doseq [{:keys [bad-value file key valid-values value]} entries]
      (println (str "warning: :" key " - " value
                    (when file
                      (str " [file: " file "]"))
                    (when bad-value
                      (str " [bad value: " bad-value "]"))
                    (when (seq valid-values)
                      (str " [valid: " (str/join ", " valid-values) "]")))))))

(defn print-cli-errors! [errors]
  (binding [*out* *err*]
    (doseq [error errors]
      (println error)))
  1)

(defn print-cli-error! [message]
  (binding [*out* *err*]
    (println message))
  1)

;; endregion ^^^^^ Printing ^^^^^

;; region ----- Reveal / env substitution -----

(defn reveal-confirmed? []
  (binding [*out* *err*]
    (print "type REVEAL to confirm: ")
    (flush))
  (= "REVEAL" (some-> (read-line) str/trim)))

(defn print-reveal-refused! []
  (binding [*out* *err*]
    (println "Refusing to reveal config.")))

(defn- env-token [value]
  (when (and (string? value)
             (re-matches #"\$\{[^}]+\}" value))
    (second (re-matches #"\$\{([^}]+)\}" value))))

(defn redact-env-values [raw resolved]
  (cond
    (and (map? raw) (map? resolved))
    (into {} (map (fn [k] [k (redact-env-values (get raw k) (get resolved k))]) (set (concat (keys raw) (keys resolved)))))

    (and (sequential? raw) (sequential? resolved))
    (mapv redact-env-values raw resolved)

    :else
    (if-let [token (env-token raw)]
      (str "<" token ":" (if (= raw resolved) "UNRESOLVED" "redacted") ">")
      resolved)))

(defn resolve-env-values [value]
  (cond
    (map? value)        (into {} (map (fn [[k v]] [k (resolve-env-values v)]) value))
    (sequential? value) (mapv resolve-env-values value)
    :else               (if-let [token (env-token value)]
                          (or (loader/env token) value)
                          value)))

;; endregion ^^^^^ Reveal / env substitution ^^^^^

;; region ----- Config access -----

(defn queryable-config [config]
  (walk/postwalk
    (fn [node]
      (if (map? node)
        (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v]) node))
        node))
    config))

(defn value-present? [value]
  (not (nil? value)))

(defn present-identifiers [value]
  (walk/postwalk
    (fn [node]
      (if (map? node)
        (cond-> node
          (string? (:crew node))     (assoc :crew (keyword (:crew node)))
          (string? (:model node))    (assoc :model (keyword (:model node)))
          (string? (:provider node)) (assoc :provider (keyword (:provider node))))
        node))
    value))

(defn load-result [opts]
  (loader/load-config-result {:root (resolve-root opts)
                              :fs   (or (fs/instance opts) (fs/real-fs))}))

(defn load-raw-result [opts]
  (loader/load-config-result {:root            (resolve-root opts)
                              :fs              (or (fs/instance opts) (fs/real-fs))
                              :substitute-env? false}))

(defn printable-config [opts reveal?]
  (let [raw      (load-raw-result opts)
        resolved (assoc raw :config (resolve-env-values (:config raw)))]
    (if reveal?
      resolved
      (assoc resolved :config (redact-env-values (:config raw) (:config resolved))))))

;; endregion ^^^^^ Config access ^^^^^

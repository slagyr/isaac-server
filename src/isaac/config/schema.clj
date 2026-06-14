(ns isaac.config.cli.schema
  "isaac config schema — print the schema for a schema path."
  (:require
    [c3kit.apron.schema.path :as schema-path]
    [clojure.string :as str]
    [isaac.config.cli.common :as common]
    [isaac.config.schema.root :as config-schema]
    [isaac.config.schema.term :as schema-term]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.module.loader :as module-loader]
    [isaac.server.module :as server-module]))

(def option-spec
  [[nil  "--tree" "Expand every named sub-schema as its own section"]
   ["-h" "--help" "Show help"]])

(def ^:private examples
  (str "  isaac config schema\n"
        "  isaac config schema crew\n"
        "  isaac config schema providers.value\n"
        "  isaac config schema crew.value.model\n"
        "  isaac config schema providers.value.api-key\n"
        "  isaac config schema --tree"))

(defn help []
  (common/render-help
    {:command     "isaac config schema"
     :params      "[schema-path] [options]"
     :description (str "Print the config schema for a schema path. Schema paths use literal\n"
                       "'key' and 'value' segments to address the key/value types of a map —\n"
                       "for example 'crew.value' is the schema of a single crew entry,\n"
                       "'crew.value.soul' drills into the soul field on that entry.")
     :option-spec option-spec
     :examples    examples}))

(defn- guidance []
  (str "\nTry:\n" examples))

(defn- schema-context [opts]
  (let [result              (common/load-result opts)
        config              (:config result)
        builtin-index       (module-loader/builtin-index)
        declared-module-ids (into (set (keys builtin-index)) (keys (or (:modules config) {})))
        discovered-index    (or (get-in result [:config :module-index]) builtin-index)
        module-index        (select-keys discovered-index declared-module-ids)]
    {:config        config
     :module-index  module-index
     :root          (schema-compose/effective-root-schema module-index)}))

(defn- comm-resolver [module-index]
  (let [module-index (or module-index (module-loader/builtin-index))]
    (if module-index
      #(server-module/comm-kinds module-index)
      server-module/comm-kinds)))

(def ^:private collection-surfaces #{"comms" "providers"})

(defn- substituted-path
  "When `path-str` targets a map-of surface via a literal slot-id segment
   followed by further drilling (e.g. `comms.discord.token`), rewrite the
   slot segment as `.value` so apron's standard walker descends into the
   value-spec. Requires at least three segments so a two-segment typo
   (e.g. `providers.valued`) is not silently rewritten to `providers.value`.
   Returns nil when no substitution applies."
  [path-str]
  (let [segments (some-> path-str (str/split #"\."))]
    (when (and (<= 3 (count segments))
               (contains? collection-surfaces (first segments))
               (not (#{"value" "key"} (second segments))))
      (str/join "." (cons (first segments) (cons "value" (drop 2 segments)))))))

(defn- resolve-path [root path-str]
  (if (str/blank? path-str)
    root
    (try
      (or (schema-path/schema-at root path-str)
          (some-> path-str substituted-path (->> (schema-path/schema-at root))))
      (catch Exception _ nil))))

(defn- print-schema! [opts path-str tree?]
  (let [{:keys [config module-index root]} (schema-context opts)
        spec (resolve-path root path-str)]
    (if spec
      (let [root?  (or (nil? path-str) (str/blank? path-str))
            output (schema-term/spec->term spec {:color?            (common/stdout-tty?)
                                                 :config            config
                                                 :module-index      module-index
                                                 :path-prefix       (common/path-prefix path-str)
                                                 :deep?             (boolean tree?)
                                                 :width             80
                                                 :options-resolvers {:comms (comm-resolver module-index)}})]
        (if output
          (do
            (println (if root? (str output (guidance)) output))
            0)
          (do
            (binding [*out* *err*]
              (println (str "Path not found in config schema: " path-str)))
            1)))
      (do
        (binding [*out* *err*]
          (println (str "Path not found in config schema: " path-str)))
        1))))

(defn run [opts arguments options]
  (print-schema! opts (common/normalize-path (first arguments)) (:tree options)))

(def subcommand
  {:option-spec option-spec
   :runner      run
   :help-text   help})

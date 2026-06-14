(ns isaac.config.cli.command
  "Dispatcher for isaac config — routes to per-subcommand namespaces."
  (:require
    [isaac.cli.api :as cli-api]
    [clojure.string :as str]
    [isaac.config.cli.common :as common]
    [isaac.config.cli.get :as get-cmd]
    [isaac.config.cli.schema :as schema-cmd]
    [isaac.config.cli.set :as set-cmd]
    [isaac.config.cli.sources :as sources-cmd]
    [isaac.config.cli.unset :as unset-cmd]
    [isaac.config.cli.validate :as validate-cmd]))

(def option-spec
  [["-h" "--help" "Show help"]])

(def ^:private subcommands
  {"get"      get-cmd/subcommand
   "schema"   schema-cmd/subcommand
   "set"      set-cmd/subcommand
   "sources"  sources-cmd/subcommand
   "unset"    unset-cmd/subcommand
   "validate" validate-cmd/subcommand})

(def ^:private subcommands-summary
  (str "  get [config-path]         Print the resolved config, or a subtree\n"
       "  help <subcommand>         Print usage details on a subcommand\n"
       "  schema [schema-path]      Print the config schema for a schema path\n"
       "  set <config-path> <value> Set a value at a config path\n"
       "  sources                   List contributing config files\n"
       "  unset <config-path>       Remove a value at a config path\n"
       "  validate                  Validate config"))

(def ^:private paths-section
  (str "  config path  addresses a value in the resolved config\n"
       "               e.g. crew.marvin.soul, providers.anthropic.api-key\n"
       "  schema path  addresses a node in the schema tree, using literal\n"
       "               'key' and 'value' segments for map key/value types\n"
       "               e.g. crew.value.soul, providers.value.api-key\n\n"
       "  Separators:\n"
       "    default      '.' splits segments. Brackets are used for unfriendly keys.\n"
       "                 e.g. crew[\"Almighty Bob\"].model\n"
       "    slash-mode   Lead with a / for the shell-friendly / separator.\n"
       "                 e.g. /crew/Almighty Bob/model"))

(defn config-help []
  (common/render-help
    {:command       "isaac config"
     :params        "[subcommand] [options]"
     :description   "Manage Isaac configuration"
     :pre-sections  [["Subcommands" subcommands-summary]]
     :option-spec   option-spec
     :post-sections [["Paths" paths-section]]}))

(defn- print-help! []
  (println (config-help))
  0)

(defn- print-subcommand-help! [help-fn]
  (println (if help-fn (help-fn) (config-help)))
  0)

(defn- run-parsed-subcommand [opts sub-args {:keys [option-spec parse-args runner help-text]}]
  (let [{:keys [arguments errors options]} (apply common/parse-option-map sub-args option-spec parse-args)]
    (cond
      (:help options) (print-subcommand-help! help-text)
      (seq errors)    (common/print-cli-errors! errors)
      :else           (runner opts arguments options))))

(defn- run-default [_opts args]
  (let [{:keys [errors]} (common/parse-option-map args option-spec :in-order true)]
    (if (seq errors)
      (common/print-cli-errors! errors)
      (print-help!))))

(defn run [opts args]
  (cond
    (and (= "help" (first args)) (get subcommands (second args)))
    (print-subcommand-help! (:help-text (get subcommands (second args))))

    (get subcommands (first args))
    (run-parsed-subcommand opts (rest args) (get subcommands (first args)))

    (and (first args) (not (str/starts-with? (first args) "-")))
    (common/print-cli-error! (str "Unknown config subcommand: " (first args)))

    :else
    (run-default opts args)))

(defn run-fn [{:keys [_raw-args] :as opts}]
  (run opts (or _raw-args [])))

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :config [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :config [_id]
  option-spec)

(defmethod cli-api/help :config [_id]
  (config-help))

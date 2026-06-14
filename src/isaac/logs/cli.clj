(ns isaac.logs.cli
  (:require
    [isaac.cli.api :as cli-api]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.common :as cli-common]
    [isaac.fs :as fs]
    [isaac.log-viewer :as viewer]
    [isaac.logger :as log]))

(def ^:private default-limit 20)

(def option-spec
  [[nil  "--file PATH" "Log file path (overrides configured path)"]
   ["-f" "--follow" "Follow the file for new entries (default: read and exit)"]
   ["-n" "--limit N" (str "Show last N entries; 0 = all (default: " default-limit ")")
    :default default-limit
    :parse-fn #(Long/parseLong %)]
   [nil  "--no-color" "Disable color output"]
   [nil  "--zebra" "Enable alternating row background"]
   [nil  "--plain" "Raw passthrough — no parsing, color, or zebra"]
   ["-h" "--help" "Show help"]])

(defn- resolve-path [file root]
  (cond
    (nil? file)                         nil
    (str/starts-with? file "/")         file
    (and root (seq root))     (str root "/" file)
    :else                               file))

(defn- config-log-path [root fs*]
  (when root
    (let [config-file (str root "/config/isaac.edn")]
      (when (fs/exists? fs* config-file)
        (try
          (get-in (edn/read-string (fs/slurp fs* config-file)) [:log :output])
          (catch Exception _ nil))))))

(defn run [{:keys [file follow limit no-color zebra plain root]}]
  (let [log-path (or (resolve-path file root)
                     (resolve-path (config-log-path root (fs/instance)) root)
                     (log/log-file))]
    (viewer/tail! log-path
                  {:color?  (not no-color)
                   :zebra?  (boolean zebra)
                   :follow? (boolean follow)
                   :plain?  (boolean plain)
                   :limit   limit})))

(defn run-fn [opts]
  (cli-common/standard-run-fn "logs"
                               #(tools-cli/parse-opts % option-spec)
                               run
                               opts))

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :logs [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :logs [_id]
  option-spec)

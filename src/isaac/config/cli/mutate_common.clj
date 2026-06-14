(ns isaac.config.cli.mutate-common
  "Shared helpers for 'config set' and 'config unset'."
  (:require
    [c3kit.apron.schema.path :as path]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.config.cli.common :as common]
    [isaac.config.loader :as loader]
    [isaac.config.mutate :as mutate]
    [isaac.config.nav :as nav]
    [isaac.config.schema.root :as config-schema]
    [isaac.logger :as log]))

(defn target-spec-for [path-str]
  (config-schema/schema-for-data-path path-str))

(defn parse-set-value [spec raw-value]
  (cond
    (re-matches #"-?\d+" raw-value)
    (parse-long raw-value)

    (#{"false" "nil" "true"} raw-value)
    (edn/read-string raw-value)

    (str/starts-with? raw-value ":")
    (edn/read-string raw-value)

    (and spec (= :id (:type spec)) (re-matches #"[A-Za-z_][A-Za-z0-9_-]*" raw-value))
    (keyword raw-value)

    :else
    raw-value))

(defn read-stdin-value []
  (try
    {:value (edn/read-string (slurp *in*))}
    (catch Exception _
      {:error "stdin must contain valid EDN"})))

(defn- format-errors [errors]
  (str/join "; " (map (fn [{:keys [key value]}] (str key " - " value)) errors)))

(defn log-mutation! [level event file path-str & kvs]
  (apply log/log* level event file 0 :path path-str kvs))

(defn- print-status-error! [status path-str]
  (binding [*out* *err*]
    (println (case status
               :missing-path      "missing path"
               :missing-entity-id "missing entity id"
               :invalid-path      (str "invalid path: " path-str)
               :not-found         (str "not found: " path-str)
               (str "config error: " (name status))))))

(defn target-root+path! [opts path-arg]
  (if (str/blank? path-arg)
    (do
      (print-status-error! :missing-path nil)
      nil)
    {:root (common/resolve-root opts)
     :path-str  (common/normalize-path path-arg)}))

(defn handle-mutate-result! [operation path-str result value]
  (common/print-warnings! (:warnings result))
  (case (:status result)
    :ok
    (do
      (let [file (or (:file result) "config")]
        (case operation
          :set   (log-mutation! :info :config/set   file path-str :value value)
          :unset (log-mutation! :info :config/unset file path-str)))
      0)

    :invalid
    (do
      (common/print-errors! (:errors result) "error")
      (when (= :set operation)
        (log-mutation! :error :config/set-failed "config" path-str :error (format-errors (:errors result))))
      1)

    :invalid-config
    (do
      (common/print-errors! (:errors result) "error")
      1)

    (do
      (print-status-error! (:status result) path-str)
      1)))

;; region ----- Set-typed helpers -----

(defn- parent-path [path-str]
  (str/join "." (butlast (str/split path-str #"\."))))

(defn- current-config-value [root path-str]
  (let [result (loader/load-config-result {:root root})
        config (common/queryable-config (:config result))]
    (path/data-at config path-str)))

(defn- set-member! [root path-str member]
  (let [pp          (parent-path path-str)
        current-set (or (current-config-value root pp) #{})
        new-set     (conj current-set member)
        result      (mutate/set-config root pp new-set :skip-ref-validation? true)]
    (handle-mutate-result! :set path-str result member)))

(defn- unset-member! [root path-str member]
  (let [pp          (parent-path path-str)
        current-set (or (current-config-value root pp) #{})
        new-set     (disj current-set member)
        result      (if (empty? new-set)
                      (mutate/unset-config root pp)
                      (mutate/set-config root pp new-set :skip-ref-validation? true))]
    (handle-mutate-result! :unset path-str result nil)))

;; endregion ^^^^^ Set-typed helpers ^^^^^

(defn set-config! [root path-str raw-value]
  (let [path-result (nav/path->spec config-schema/root path-str)]
    (if-not (:ok? path-result)
      (do
        (binding [*out* *err*]
          (println (:error path-result)))
        (log-mutation! :error :config/set-failed "config" path-str :error (:error path-result))
        1)
      (if-let [member (:member path-result)]
        (set-member! root path-str member)
        (if (nil? raw-value)
          (common/print-cli-error! "missing value")
          (let [value-result (if (= "-" raw-value)
                               (read-stdin-value)
                               {:value (parse-set-value (target-spec-for path-str) raw-value)})]
            (if (:error value-result)
              (do
                (binding [*out* *err*]
                  (println (:error value-result)))
                (log-mutation! :error :config/set-failed "config" path-str :error (:error value-result))
                1)
              (let [value  (:value value-result)
                    result (mutate/set-config root path-str value :skip-ref-validation? true)]
                (handle-mutate-result! :set path-str result value)))))))))

(defn unset-config! [root path-str]
  (let [path-result (nav/path->spec config-schema/root path-str)]
    (if-let [member (:member path-result)]
      (unset-member! root path-str member)
      (handle-mutate-result! :unset path-str (mutate/unset-config root path-str) nil))))

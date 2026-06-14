(ns isaac.session.cli
  (:require
    [isaac.cli.api :as cli-api]
    [c3kit.apron.schema :as schema]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.registry :as cli]
    [isaac.cli.common :as cli-common]
    [isaac.cli.table :as table]
    [isaac.config.nav :as nav]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.config.runtime :as runtime]
    [isaac.fs :as fs]
    [isaac.bridge.status :as bridge]
    [isaac.session.context :as session-ctx]
    [isaac.session.schema :as session-schema]
    [isaac.session.store.spi :as store]
    [isaac.nexus :as nexus]
    [isaac.tool.memory :as memory])
  (:import
    (java.time.format DateTimeFormatter)
    (java.time ZoneOffset)))

(def option-spec
  [["-c" "--crew NAME"          "Filter to a specific crew member"]
   [nil  "--color MODE"         "Color output: auto, always, never" :default "auto"]
   [nil  "--json"               "Output result as JSON"]
   [nil  "--edn"                "Output result as EDN"]
   [nil  "--tag TAG"            "Filter to sessions carrying this tag (repeatable)"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil  "--in-flight"          "Show only in-flight sessions"]
   [nil  "--not-in-flight"      "Show only idle sessions"]
   [nil  "--no-color"           "Disable color output"]
   ["-h" "--help"               "Show help"]])

(defn- text-tags [tags]
  (if (seq tags)
    (->> tags (sort-by str) (map pr-str) (str/join " "))
    ""))

(defn- parse-option-map [raw-args]
  (let [{:keys [options errors]} (tools-cli/parse-opts raw-args option-spec)]
    {:options (->> options (remove (comp nil? val)) (into {}))
     :errors  errors}))

;; region ----- Formatting -----

(defn format-age
  "Format age in milliseconds as a compact relative string."
  [age-ms]
  (let [secs (quot age-ms 1000)
        mins (quot secs 60)
        hrs  (quot mins 60)
        days (quot hrs 24)]
    (cond
      (>= days 1)  (str days "d")
      (>= hrs 1)   (str hrs "h")
      (>= mins 1)  (str mins "m")
      :else        (str secs "s"))))

(defn- parse-iso [ts]
  (try
    (-> (java.time.LocalDateTime/parse ts (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))
        (.toInstant ZoneOffset/UTC))
    (catch Exception _ nil)))

(defn age-ms [updated-at]
  (when-let [inst (parse-iso updated-at)]
    (- (System/currentTimeMillis) (.toEpochMilli inst))))

(defn format-context
  "Format token usage as 'tokens / context-window (pct%)'."
  [tokens context-window]
  (let [tokens (or tokens 0)
        pct    (if (pos? context-window)
                 (int (Math/round (* 100.0 (/ tokens context-window))))
                 0)]
    (str (format "%,d" tokens) " / " (format "%,d" context-window) " (" pct "%)")))

(def ^:private session-columns
  [{:key :name   :header "SESSION" :align :left}
   {:key :age    :header "AGE"     :align :right}
   {:key :used   :header "USED"    :align :right
    :format #(format "%,d" (or % 0))}
   {:key :window :header "WINDOW"  :align :right
    :format #(format "%,d" (or % 0))}
   {:key :pct    :header "PCT"     :align :right
     :format #(str (or % 0) "%")
     :color-fn (fn [p]
                 (let [p (or p 0)]
                   (cond (> p 100) :red (>= p 80) :yellow :else nil)))}])

(def ^:private default-session-columns
  (conj session-columns
        {:key :crew :header "CREW" :align :left}))

(def ^:private tagged-session-columns
  [{:key :name   :header "Name"    :align :left}
   {:key :age    :header "AGE"     :align :right}
   {:key :used   :header "USED"    :align :right
    :format #(format "%,d" (or % 0))}
   {:key :window :header "WINDOW"  :align :right
    :format #(format "%,d" (or % 0))}
   {:key :pct    :header "PCT"     :align :right
    :format #(str (or % 0) "%")
    :color-fn (fn [p]
                (let [p (or p 0)]
                  (cond (> p 100) :red (>= p 80) :yellow :else nil)))}
   {:key :crew   :header "Crew"    :align :left}
   {:key :tags   :header "Tags"    :align :left}])

(defn- session->row [entry context-window session-store]
  (let [tokens (or (:last-input-tokens entry) 0)
        pct    (if (pos? context-window)
                  (int (Math/round (* 100.0 (/ tokens context-window)))) 0)
        name   (or (:key entry) (:id entry))]
    {:name   (str name (when (store/in-flight? session-store (:id entry)) " ✈️"))
      :age    (if-let [ms (age-ms (:updated-at entry))] (format-age ms) "-")
      :used   tokens
      :window context-window
      :pct    pct
      :crew   (or (:crew entry) "main")
      :tags   (text-tags (:tags entry))}))

(defn- effective-color? [options]
  (cond
    (:no-color options)            false
    (= "always" (:color options))  true
    (= "never"  (:color options))  false
    :else                          nil))   ; nil → table auto-detects TTY

;; endregion ^^^^^ Formatting ^^^^^

;; region ----- Data -----

(defn- session-store [explicit-store]
  (or explicit-store (nexus/get-in [:sessions :store])))

(defn- session->payload [entry]
  (-> entry
      (assoc :name (or (:key entry) (:id entry)))
      (update :tags #(or % #{}))))

(defn list-all
  "Returns a vector of sessions sorted alphabetically by name. When
    crew-filter is provided, only sessions for that crew member are
    included. Sessions without an explicit crew are treated as 'main'."
  ([crew-filter]
   (list-all (nexus/get-in [:sessions :store]) crew-filter))
  ([explicit-store crew-filter]
   (let [session-store (session-store explicit-store)]
     (->> (store/list-sessions session-store)
          (filter #(if crew-filter (= crew-filter (or (:crew %) "main")) true))
          (sort-by #(or (:key %) (:id %)))
          vec))))

;; endregion ^^^^^ Data ^^^^^

;; region ----- Output -----

(defn- resolve-context-window [cfg crew-id]
  (let [cfg       (loader/normalize-config cfg)
        crew      (get-in cfg [:crew crew-id])
        model-id  (or (:model crew) (get-in cfg [:defaults :model]))
        model-cfg (get-in cfg [:models model-id])]
    (or (:context-window model-cfg) 32768)))

(defn- print-session-table [sessions cfg crew-filter color?]
  (let [session-store (or (store/registered-store) (nexus/get-in [:sessions :store]))
        ;; Per-session context-window resolution: each row uses its own
        ;; crew's window. (When a --crew filter is set, every session
        ;; resolves through the same crew, which matches prior behavior.)
        rows          (mapv (fn [entry]
                              (let [cw (resolve-context-window cfg (or (:crew entry) "main"))]
                                (session->row entry cw session-store)))
                            sessions)
        columns       (cond
                        (some (comp seq :tags) sessions) tagged-session-columns
                        crew-filter                      session-columns
                        :else                            default-session-columns)]
    (println (table/render {:columns columns
                            :rows    rows
                            :zebra?  true
                            :color?  color?}))))

(defn- print-session-data [value opts]
  (cond
    (:json opts) (cli-common/print-json! value)
    (:edn opts)  (cli-common/print-edn! value)
    :else        nil))

;; endregion ^^^^^ Output ^^^^^

(defn- resolve-root [opts]
  (root/default-root opts))

(defn- install-cli!
  "Load config, resolve the state dir, and install it into the nexus (snapshot +
   session store + tree). Returns {:config :root :store}."
  [opts]
  (let [root  (resolve-root opts)
        loaded-cfg (loader/load-config! root (fs/instance) "session cli command")]
    (runtime/install! {:config loaded-cfg})
    {:config loaded-cfg :root root :store (store/registered-store)}))

(defn- run-show [opts session-id]
  (if (str/blank? session-id)
    (do (println "Usage: isaac sessions show <session-id>") 1)
    (let [{:keys [root store]} (install-cli! opts)
          session     (store/get-session store session-id)]
      (if (nil? session)
        (do (println (str "session not found: " session-id)) 1)
        (if (or (:json opts) (:edn opts))
          (do
            (print-session-data (session->payload session) opts)
            0)
          (try
            (let [ctx    (assoc (session-ctx/resolve-behavior session-id {})
                                :boot-files (session-ctx/read-boot-files (:cwd session))
                                :root root)
                  status (bridge/status-data session-id ctx)]
              (println (bridge/format-status status))
              0)
            (finally
              (config/dangerously-install-config! nil "clear ambient config after CLI command"))))))))

(defn- run-delete [opts session-id]
  (if (str/blank? session-id)
    (do (println "Usage: isaac sessions delete <session-id>") 1)
    (let [{session-store :store} (install-cli! opts)]
      (if (store/delete-session! session-store session-id)
        (do (println (str "deleted: " session-id)) 0)
        (do (println (str "session not found: " session-id)) 1)))))

(defn- print-mutation-error! [message]
  (binding [*out* *err*]
    (println message))
  1)

(defn- parse-mutation-target [raw-path]
  (if-let [dot-index (str/index-of raw-path ".")]
    {:session-id (subs raw-path 0 dot-index)
     :path-str   (subs raw-path (inc dot-index))}
    {:error (str "invalid path: " raw-path)}))

(defn- parse-set-value [_spec raw-value]
  (cond
    (re-matches #"-?\d+" raw-value) (parse-long raw-value)
    (contains? #{"false" "nil" "true"} raw-value) (edn/read-string raw-value)
    (or (str/starts-with? raw-value "[")
        (str/starts-with? raw-value "{")
        (str/starts-with? raw-value ":")
        (str/starts-with? raw-value "\"")) (edn/read-string raw-value)
    :else raw-value))

(defn- mutable-error [path-str spec]
  (cond
    (:system-managed? spec) (str "system-managed field: " path-str)
    :else                   (str "immutable field: " path-str)))

(defn- path-message [path-str result value]
  (let [segments (mapv keyword (str/split path-str #"\."))
        top-key  (first segments)
        message  (or (get-in (schema/message-map result) segments)
                     (get-in (schema/message-map result) [top-key]))]
    (or (when (and (= :crew top-key) message)
          (str message ": " value))
        message
        (first (schema/message-seq result))
        (str "invalid value for " path-str))))

(defn- run-mutation [opts operation raw-path raw-value]
  (let [{session-store :store loaded-cfg :config} (install-cli! opts)
        target                 (parse-mutation-target raw-path)]
    (if-let [error (:error target)]
      (print-mutation-error! error)
      (let [{:keys [session-id path-str]} target
            session (store/get-session session-store session-id)]
        (cond
          (nil? session)
          (print-mutation-error! (str "session not found: " session-id))

          :else
          (let [path-result (nav/path->spec session-schema/Session path-str)]
            (cond
              (not (:ok? path-result))
              (print-mutation-error! (:error path-result))

              (not (:mutable? (:spec path-result)))
              (print-mutation-error! (mutable-error path-str (:spec path-result)))

              (and (= :set operation) (nil? (:member path-result)) (nil? raw-value))
              (print-mutation-error! "missing value")

              :else
              (try
                (let [nav-result (case operation
                                     :set   (nav/set-value session-schema/Session session path-str
                                                           (when-not (:member path-result)
                                                             (parse-set-value (:spec path-result) raw-value)))
                                     :unset (nav/unset-value session-schema/Session session path-str))]
                    (if-not (:ok? nav-result)
                      (print-mutation-error! (:error nav-result))
                      (let [top-key       (keyword (first (str/split path-str #"\.")))
                            updated-value (get-in (:config nav-result) [top-key])
                            current-value (get-in session [top-key])]
                        (if (= current-value updated-value)
                          0
                          (let [conformed (binding [session-schema/*config* loaded-cfg]
                                            (session-schema/conform-read (:config nav-result)))]
                            (if (schema/error? conformed)
                              (print-mutation-error! (path-message path-str conformed updated-value))
                              (do
                                (store/update-session! session-store session-id {top-key       updated-value
                                                                                 :updated-at (str (memory/now))})
                                0)))))))
                  (finally
                    (config/dangerously-install-config! nil "clear ambient config after CLI command"))))))))))

;; region ----- Command -----

(defn run [opts]
  (let [injected-crew (when (map? (:crew opts)) (:crew opts))
        injected-agents (when (map? (:agents opts)) (:agents opts))
        {loaded-cfg :config session-store :store} (install-cli! opts)
        crew-filter   (when (string? (:crew opts)) (:crew opts))
        cfg           (if (or injected-crew injected-agents)
                          (cli-common/build-cfg (or injected-crew injected-agents) (:models opts))
                          loaded-cfg)]
    (if (and (:in-flight opts) (:not-in-flight opts))
      (do
        (binding [*out* *err*]
          (println "--in-flight and --not-in-flight are mutually exclusive"))
        1)
      (if (and crew-filter
               (not (contains? (:crew (loader/normalize-config cfg)) crew-filter)))
        (do
          (binding [*out* *err*]
            (println (str "unknown crew: " crew-filter)))
          1)
        (let [required-tags (set (map keyword (:tag opts)))
              sessions      (->> (store/list-sessions session-store)
                                 (filter #(if crew-filter (= crew-filter (or (:crew %) "main")) true))
                                 (filter #(every? (fn [tag] (store/has-tag? % tag)) required-tags))
                                 (filter #(if (:in-flight opts) (store/in-flight? session-store (:id %)) true))
                                 (filter #(if (:not-in-flight opts) (not (store/in-flight? session-store (:id %))) true))
                                 (sort-by #(or (:key %) (:id %)))
                                 vec)
              color?        (effective-color? opts)]
          (cond
            (or (:json opts) (:edn opts))
            (print-session-data
              (mapv #(session->payload (assoc % :crew (or (:crew %) "main"))) sessions)
              opts)

            (empty? sessions)
            (println "no sessions found")

            :else
            (print-session-table sessions cfg crew-filter color?))
          0)))))

(defn run-fn [{:keys [_raw-args] :as opts}]
  (let [subcmd (first _raw-args)]
    (cond
      (= "show" subcmd)
      (let [{:keys [options errors]} (parse-option-map (drop 2 _raw-args))]
        (if (seq errors)
          (do
            (doseq [error errors] (println error))
            1)
          (run-show (merge (dissoc opts :_raw-args) options) (second _raw-args))))

      (= "delete" subcmd)
      (run-delete opts (second _raw-args))

      (= "set" subcmd)
      (run-mutation opts :set (second _raw-args) (nth _raw-args 2 nil))

      (= "unset" subcmd)
      (run-mutation opts :unset (second _raw-args) nil)

      :else
      (let [{:keys [options errors]} (parse-option-map (or _raw-args []))]
        (cond
          (:help options)
          (do
            (println (cli/command-help (cli/get-command "sessions")))
            0)

          (seq errors)
          (do
            (doseq [error errors] (println error))
            1)

          :else
          (run (merge (dissoc opts :_raw-args) options)))))))

;; endregion ^^^^^ Command ^^^^^

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :sessions [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :sessions [_id]
  option-spec)

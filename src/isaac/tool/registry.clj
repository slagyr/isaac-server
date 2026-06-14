(ns isaac.tool.registry
  (:require
    [isaac.config.loader :as loader]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [isaac.tool.fs-bounds :as fs-bounds]
    [isaac.tool.output-cap :as output-cap]))

;; region ----- State -----

(defn- registry-atom []
  (or (nexus/get :tool-registry)
      (let [registry* (atom {})]
        (nexus/register! [:tool-registry] registry*)
        registry*)))

(defn- normalize-allowed-tools [allowed-tools]
  (when (some? allowed-tools)
    (->> allowed-tools
         (map (fn [tool]
                (cond
                  (keyword? tool) (name tool)
                  (string? tool)  tool
                  :else           (str tool))))
         set)))

(defn- allowed-tool? [allowed-tools name]
  (when-let [allowed-tools (normalize-allowed-tools allowed-tools)]
    (contains? allowed-tools name)))

;; endregion ^^^^^ State ^^^^^

;; region ----- Registration -----

(defn register! [{:keys [name] :as tool}]
  (swap! (registry-atom) assoc name tool))

(defn register-tool-entry!
  "Per-entry factory for the :isaac.server/tools berth (phase 6 of
   the berth epic). The berth processor passes `[tool-id entry-map]`
   for :map-shaped contributions; this fn resolves the entry's
   :factory symbol, applies the user-config slot for the tool, and
   installs the resulting spec into the registry (with :name set
   from tool-id)."
  [[tool-id entry]]
  (let [tool-name (name tool-id)
        factory   (some-> (:factory entry) requiring-resolve var-get)
        user-cfg  (or (module-loader/user-config :tools tool-name) {})
        spec      (factory user-cfg)]
    (register! (assoc spec :name tool-name))))

(defn unregister! [name]
  (swap! (registry-atom) dissoc name))

(defn clear! []
  (reset! (registry-atom) {}))

(defn lookup [name]
  (get @(registry-atom) name))

(defn- activate-missing-tool! [module-index name]
  ;; Phase 6 (isaac-w7o5): tool installation is a berth-side concern.
  ;; After activating the providing module (for its bootstrap + non-tool
  ;; extensions), call the berth's per-entry factory directly so the
  ;; single tool lands in the registry without paying for a full
  ;; process-manifest-berths! sweep.
  (when-let [module-id (module-loader/supporting-module-id module-index :isaac.server/tools name)]
    (module-loader/activate! module-id module-index)
    (let [tool-kw (keyword name)
          entry   (get-in module-index [module-id :manifest :isaac.server/tools tool-kw])]
      (when entry
        (register-tool-entry! [tool-kw entry])))
    (lookup name)))

(defn all-tools
  "With no args, returns every registered tool.
   With an allowed-tools collection, returns only the tools in the allow list.
   A nil allowed-tools with the 1-arity denies every tool (default-deny)."
  ([]
   (vec (vals @(registry-atom))))
  ([allowed-tools]
   (->> (vals @(registry-atom))
         (filter #(allowed-tool? allowed-tools (:name %)))
         vec)))

;; endregion ^^^^^ Registration ^^^^^

;; region ----- Execution -----

(defn- result-metadata [result]
  {:result-chars (count (str result))
   :result-type  (cond
                   (string? result) :string
                   (map? result)    :map
                   (vector? result) :vector
                   (sequential? result) :seq
                   (nil? result)    :nil
                   :else            :other)})

(defn- log-arguments [arguments]
  (into {}
        (keep (fn [[k v]]
                (let [kw (if (string? k) (keyword k) k)]
                  (when (not= kw :session_key)
                    [kw v]))))
        arguments))

(defn- tool-cwd [arguments]
  (fs-bounds/session-workdir (or (get arguments "session_key")
                                 (get arguments :session_key))))

(defn- snapshot-caps []
  (let [cfg (or (loader/snapshot "tool output caps — ambient fallback when caller passes no caps") {})]
    {:max-lines (get-in cfg [:tools :defaults :max-lines])
     :max-bytes (get-in cfg [:tools :defaults :max-bytes])}))

;; caps is {:max-lines _ :max-bytes _} resolved from config at the turn boundary
;; and threaded in as a value; nil falls back to the ambient snapshot.
(defn- cap-output [caps s]
  (let [caps*     (or caps (snapshot-caps))
        max-lines (or (:max-lines caps*) output-cap/default-max-output-lines)
        max-bytes (or (:max-bytes caps*) output-cap/default-max-output-bytes)]
    (output-cap/cap-result (str s) max-lines max-bytes)))

(defn- unknown-tool-error [name]
  (log/error :tool/execute-failed :tool name :error (str "unknown tool: " name))
  {:isError true :error (str "unknown tool: " name)})

(defn- run-handler [name arguments caps]
  (if-let [tool (lookup name)]
    (let [cwd      (tool-cwd arguments)
          log-args (log-arguments arguments)]
      (log/debug :tool/start :tool name :arguments log-args :cwd cwd)
      (try
        (let [result ((:handler tool) arguments)]
          (cond
            (:isError result)
            (do (log/error :tool/execute-failed :tool name :arguments log-args :cwd cwd :error (:error result))
                result)

            (nil? result)
            (do (log/error :tool/execute-failed :tool name :arguments log-args :cwd cwd :error "tool returned nil")
                {:isError true :error "tool returned nil"})

            (and (map? result) (contains? result :result))
            (let [capped (cap-output caps (:result result))]
              (log/debug :tool/result (assoc (result-metadata capped) :tool name :cwd cwd))
              (assoc result :result capped))

            :else
            (let [capped (cap-output caps result)]
              (log/debug :tool/result (assoc (result-metadata capped) :tool name :cwd cwd))
              {:result capped})))
        (catch Exception e
          (log/error :tool/execute-failed :tool name :arguments log-args :cwd cwd :error (.getMessage e))
          {:isError true :error (.getMessage e)})))
    (unknown-tool-error name)))

(defn execute
  ([name arguments]
   (run-handler name arguments nil))
  ([name arguments allowed-tools]
   (if (allowed-tool? allowed-tools name)
      (run-handler name arguments nil)
      (unknown-tool-error name)))
  ([name arguments allowed-tools module-index]
   (execute name arguments allowed-tools module-index nil))
  ([name arguments allowed-tools module-index caps]
   (if (allowed-tool? allowed-tools name)
     (do
       (when (and module-index (not (lookup name)))
         (activate-missing-tool! module-index name))
       (run-handler name arguments caps))
     (unknown-tool-error name))))

(defn- result->string [{:keys [result error isError]}]
  (if isError
    (str "Error: " error)
    result))

(defn tool-fn
  "Returns a function compatible with chat-with-tools that dispatches to the registry."
  ([]
   (fn [name arguments]
     (result->string (execute name arguments))))
  ([allowed-tools]
   (fn [name arguments]
      (result->string (execute name arguments allowed-tools))))
  ([allowed-tools module-index]
   (fn [name arguments]
     (result->string (execute name arguments allowed-tools module-index))))
  ([allowed-tools module-index caps]
   (fn [name arguments]
     (result->string (execute name arguments allowed-tools module-index caps)))))

;; endregion ^^^^^ Execution ^^^^^

;; region ----- Prompt Definitions -----

(defn tool-definitions
  "Returns tool definitions suitable for inclusion in an LLM prompt (no handler fn)."
  ([]
   (mapv #(dissoc % :handler) (all-tools)))
  ([allowed-tools]
   (mapv #(dissoc % :handler) (all-tools allowed-tools)))
  ([allowed-tools module-index]
   (doseq [tool-name allowed-tools]
     (when-not (lookup tool-name)
       (activate-missing-tool! module-index tool-name)))
   (mapv #(dissoc % :handler) (all-tools allowed-tools))))

;; endregion ^^^^^ Prompt Definitions ^^^^^

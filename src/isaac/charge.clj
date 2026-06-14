(ns isaac.charge
  (:refer-clojure :exclude [agent])
  (:require
    [clojure.string :as str]
    [isaac.bridge.cancellation :as cancellation]
    [isaac.config.loader :as loader]
    [isaac.config.resolve :as resolve]
    [isaac.llm.provider :as llm-provider]
    [isaac.nexus :as nexus]
    [isaac.session.context :as session-ctx]
    [isaac.session.store.spi :as store]))

(def charge-schema
  {:name   :charge
   :type   :map
   :schema {:session-key       {:type :string :description "Session identifier"}
            :input             {:type :string :description "User input string"}
            :comm              {:type :ignore :description "Communication channel"}
            :config            {:type :ignore :description "Config snapshot for this charge"}
            :crew              {:type :string :description "Resolved crew/agent id"}
            :crew-members      {:type :ignore :description "Full crew config map (all members)"}
            :models            {:type :ignore :description "All configured models map"}
            :module-index      {:type :ignore :description "Module index map"}
            :context-window    {:type :long :description "Context window token limit"}
            :model             {:type :string :description "Resolved model id"}
            :model-cfg         {:type :ignore :description "Model configuration map"}
            :provider          {:type :ignore :description "Resolved LLM provider Api instance"}
            :provider-cfg      {:type :ignore :description "Provider configuration map"}
            :crew-cfg          {:type :ignore :description "Resolved crew configuration map"}
            :compaction        {:type :ignore :description "Resolved compaction policy map"}
            :context-mode      {:type :keyword :description "Compaction/prompt-building mode (:full or :reset)"}
            :effort            {:type :long :description "Resolved per-turn effort budget"}
            :cwd               {:type :string :description "Session working directory"}
            :soul              {:type :string :description "System prompt"}
            :nonce             {:type :string :description "Session-scoped trusted-block nonce"}
            :guidance          {:type :string :description "Per-turn trusted guidance injected into the current user turn"}
            :origin            {:type :ignore :description "Inbound origin metadata"}
            :charge/type       {:type :keyword :description "Charge type marker (:charge)"}
            :charge/unresolved {:type :boolean :description "True when crew/model could not be resolved"}
            :charge/reason     {:type :keyword :description "Reason for unresolved charge"}}})

;; region ----- Predicates -----

(defn charge? [x]
  (= :charge (:charge/type x)))

(defn slash?
  "True when the charge carries a slash-command input."
  [charge]
  (and (string? (:input charge))
       (str/starts-with? (:input charge) "/")))

(defn unresolved?
  "True when the charge could not be fully resolved (unknown crew, no model, etc.)."
  [charge]
  (true? (:charge/unresolved charge)))

(defn cancelled?
  "True when the session has been cancelled via the bridge cancellation registry."
  [charge]
  (cancellation/cancelled? (:session-key charge)))

;; endregion ^^^^^ Predicates ^^^^^

;; region ----- Accessors -----

(defn channel
  "Returns the comm channel for the charge."
  [charge]
  (:comm charge))

(defn agent
  "Returns the resolved crew/agent id."
  [charge]
  (:crew charge))

(defn transcript
  "Returns the active session transcript from the session store."
  [charge]
  (when-let [ss (nexus/get-in [:sessions :store])]
    (store/active-transcript ss (:session-key charge))))

;; endregion ^^^^^ Accessors ^^^^^

;; region ----- Construction -----

(defn- ensure-provider [provider cfg]
  (cond
    (nil? provider) nil
    (string? provider) (let [prov-cfg     (resolve/resolve-provider cfg provider)
                             enriched-cfg (merge (or prov-cfg {})
                                                 {:providers    (:providers cfg)
                                                  :module-index (:module-index cfg)})]
                         (llm-provider/make-provider provider enriched-cfg))
    :else provider))

(defn- unresolved-charge [base reason]
  (assoc base
    :charge/type :charge
    :charge/unresolved true
    :charge/reason reason))

(defn build
  "Build a charge from a request map.

   Reads from the global config snapshot and resolves the crew's full agent
   context (soul, model, model-cfg, provider, context-window, compaction,
   context-mode, effort). On resolution failure (unknown crew error or no
   model) returns a charge marked :charge/unresolved with a :charge/reason
   keyword."
  [{:keys [session-key input comm crew config model model-ref model-override model-cfg
           provider provider-cfg context-window soul soul-prepend guidance origin dispatch-error]}]
  (let [config*         (or (when (map? config) config) (loader/snapshot "charge build fallback — no :config passed (entry seed)") {})
        ss*             (store/registered-store)
        session-entry   (when (and ss* session-key (satisfies? store/SessionStore ss*))
                          (store/get-session ss* session-key))
        crew-id         (or crew (:crew session-entry) (get-in config* [:defaults :crew]) "main")
        model-ref*      (or model-override model-ref model (:model session-entry))
        known-crews     (or (:crew config*) {})
        default-crew    (get-in config* [:defaults :crew])
        unknown?        (and (seq known-crews)
                             (not (or (= crew-id "main")
                                      (contains? known-crews crew-id)
                                      (= crew-id default-crew))))
        session-context (delay (session-ctx/resolve-behavior session-key {:crew crew-id :model model-ref* :config config*}))
        model*          (delay (or model (get-in @session-context [:model-cfg :model]) (:model @session-context)))
        base            {:session-key   session-key
                         :input         input
                         :comm          comm
                         :config        config*
                         :crew          crew-id
                         :crew-members  known-crews
                         :models        (:models config*)
                         :module-index  (:module-index config*)
                         :guidance      guidance
                         :origin        origin}]
    (cond (:error dispatch-error) (unresolved-charge base (:error dispatch-error))
          unknown? (unresolved-charge base :unknown-crew)
          (nil? @model*) (unresolved-charge base :no-model)
          :else (merge base {:charge/type    :charge
                             :crew-cfg       (:crew-cfg @session-context)
                             :context-window (or context-window (:context-window @session-context))
                             :context-mode   (:context-mode @session-context)
                             :compaction     (:compaction @session-context)
                             :effort         (:effort @session-context)
                             :cwd            (:cwd @session-context)
                             :model          @model*
                             :model-cfg      (or model-cfg (:model-cfg @session-context))
                             :nonce          (:nonce @session-context)
                             :provider       (ensure-provider (or provider (:provider @session-context)) config*)
                             :provider-cfg   (or provider-cfg (:provider-cfg @session-context))
                             :soul           (or soul (cond-> (:soul @session-context)
                                                              soul-prepend (str "\n\n" soul-prepend)))}))))

;; endregion ^^^^^ Construction ^^^^^

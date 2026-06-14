(ns isaac.drive.turn
  (:require
    [c3kit.apron.schema :as schema]
    [clojure.string :as str]
    [isaac.bridge.cancellation :as bridge]
    [isaac.comm.protocol :as comm]
    [isaac.comm.cli :as cli-comm]
    [isaac.drive.dispatch :as dispatch]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.provider :as llm-provider]
    [isaac.llm.tool-loop :as tool-loop]
     [isaac.logger :as log]
     [isaac.nexus :as nexus]
     [isaac.session.compaction :as compaction]
     [isaac.session.context :as session-ctx]
     [isaac.session.store.spi :as store]
     [isaac.tool.builtin :as builtin]
     [isaac.tool.registry :as tool-registry])
  (:import (clojure.lang ExceptionInfo)))

;; region ----- Error Formatting -----

(defn- body-error-message [result]
  (let [body       (:body result)
        body-error (:error body)]
    (cond
      (map? body-error) (str (or (:type body-error) (name (:error result)))
                             ": "
                             (or (:message body-error) body-error))
      (string? body-error) body-error
      (map? body) (pr-str body))))

(defn error-message [result]
  (or (:message result)
      (body-error-message result)
      (when (:status result)
        (str "HTTP " (:status result) " " (name (:error result))
             (when-let [body (:body result)]
               (str " - " (pr-str body)))))
      (let [error (:error result)]
        (if (keyword? error) (name error) (str error)))))

;; endregion ^^^^^ Error Formatting ^^^^^

;; region ----- Token Accounting -----

(defn- usage-input-tokens [usage]
  (or (:input-tokens usage)
      (:input_tokens usage)))

(defn- usage-output-tokens [usage]
  (or (:output-tokens usage)
      (:output_tokens usage)))

(defn- usage-cache-read [usage]
  (or (:cache-read usage)
      (:cached-tokens usage)
      (get-in usage [:input_tokens_details :cached_tokens])))

(defn- usage-cache-write [usage]
  (or (:cache-write usage)
      (:cache_creation_input_tokens usage)))

(defn- usage-reasoning-tokens [usage]
  (get-in usage [:output_tokens_details :reasoning_tokens]))

(defn- response-usage [result]
  (merge (or (get-in result [:response :response :usage])
             (get-in result [:response :usage])
             {})
         (or (:usage result) {})))

(defn extract-tokens [result]
  (let [resp  (:response result)
        usage (or (:token-counts result) (response-usage result))]
    {:input-tokens  (or (usage-input-tokens usage) (:prompt_eval_count resp) 0)
     :output-tokens (or (usage-output-tokens usage) (:eval_count resp) 0)
     :cache-read    (usage-cache-read usage)
     :cache-write   (usage-cache-write usage)}))

(defn normalize-usage [result]
  (let [tokens           (extract-tokens result)
        raw-usage        (response-usage result)
        input-tokens     (:input-tokens tokens 0)
        output-tokens    (:output-tokens tokens 0)
        cache-read       (or (:cache-read tokens) 0)
        cache-write      (or (:cache-write tokens) 0)
        reasoning-tokens (usage-reasoning-tokens raw-usage)]
    (cond-> {:input-tokens  input-tokens
             :output-tokens output-tokens
             :total-tokens  (+ input-tokens output-tokens)
             :cache-read    cache-read
             :cache-write   cache-write}
            reasoning-tokens (assoc :reasoning-tokens reasoning-tokens))))

;; endregion ^^^^^ Token Accounting ^^^^^

;; region ----- Response Persistence -----

(defonce in-flight-compactions (atom {}))

(defn- normalize-ctx [ctx-or-root]
  (merge (nexus/necho)
         (if (map? ctx-or-root) ctx-or-root {:root ctx-or-root})))

(defn clear-async-compactions! []
  (reset! in-flight-compactions {}))

(defn- active-compaction-state [session-key]
  (get @in-flight-compactions session-key))

(defn async-compaction-in-flight? [session-key]
  (boolean (active-compaction-state session-key)))

(defn await-async-compaction! [session-key]
  (when-let [state (get @in-flight-compactions session-key)]
    (when-let [splice-ready (:splice-ready state)]
      (deliver splice-ready true))
    (let [future* (:future state)
          result  (deref future* 30000 ::timeout)]
      (when (= ::timeout result)
        (throw (ex-info "async compaction did not complete within 30 seconds" {:session session-key})))
      (swap! in-flight-compactions dissoc session-key)
      result)))

(defn- with-transcript-lock [session-key f]
  (if-let [lock (:lock (active-compaction-state session-key))]
    (locking lock (f))
    (f)))

(defn- append-message! [ctx session-key message]
  (with-transcript-lock session-key #(store/append-message! (or (:session-store ctx) (nexus/get-in [:sessions :store])) session-key message)))

(defn- append-error! [ctx session-key error-entry]
  (with-transcript-lock session-key #(store/append-error! (or (:session-store ctx) (nexus/get-in [:sessions :store])) session-key error-entry)))

(defn run-tool-calls!
  ([session-key tool-results]
    (run-tool-calls! {} session-key tool-results))
  ([ctx-or-root session-key tool-results]
   (let [ctx (normalize-ctx ctx-or-root)]
     (doseq [[tc result] tool-results]
       (append-message! ctx session-key
                        {:role    "assistant"
                         :content [{:type      "toolCall"
                                    :id        (:id tc)
                                    :name      (:name tc)
                                    :arguments (:arguments tc)}]})
       (let [error? (str/starts-with? result "Error:")]
         (append-message! ctx session-key
                          (cond-> {:role "toolResult" :id (:id tc) :content result}
                            error? (assoc :isError true))))))))

(defn- normalized-error [err]
  (if (string? err) (keyword err) err))

(defn- persisted-error [err]
  (let [normalized (normalized-error err)]
    (if (keyword? normalized) (str normalized) normalized)))

(defn- store-error! [ctx session-key result {:keys [model provider]}]
  (try
    (append-error! ctx session-key
                    {:content  (error-message result)
                     :error    (persisted-error (:error result))
                     :model    model
                     :provider provider})
    (catch Exception e
      (log/warn :chat/error-not-stored
                :session session-key
                :provider provider
                :error (.getMessage e)))))

(defn- log-response-failed! [session-key provider result]
  (log/error :chat/response-failed
             :session session-key
             :provider provider
             :error (:error result)
             :message (error-message result)))

(defn- report-error! [ctx session-key provider result opts]
  (log-response-failed! session-key provider result)
  (store-error! ctx session-key result opts)
  result)

(defn- response-model [result model]
  (or (get-in result [:response :model]) model))

(defn- store-response! [ctx session-key result {:keys [model provider]}]
  (let [ss             (or (:session-store ctx) (nexus/get-in [:sessions :store]))
        tokens         (extract-tokens result)
        usage          (normalize-usage result)
        total-tokens   (+ (:input-tokens tokens 0) (:output-tokens tokens 0))
        resolved-model (response-model result model)
        reasoning      (or (get-in result [:response :reasoning])
                           (get-in result [:response :response :reasoning]))
        stop-reason    (or (get-in result [:response :stop_reason])
                           (get-in result [:response :done_reason]))
        session-entry  (or (store/get-session ss session-key) {})
        input-tokens   (:input-tokens tokens 0)
        output-tokens  (:output-tokens tokens 0)
        cache-read     (:cache-read tokens)
        cache-write    (:cache-write tokens)]
    (log/debug :session/message-stored
               :session session-key
               :model resolved-model
               :tokens (select-keys tokens [:input-tokens :output-tokens]))
    (append-message! ctx session-key
                      (cond-> {:role     "assistant"
                               :content  (or (:content result)
                                             (get-in result [:response :message :content]))
                              :model    resolved-model
                              :provider provider
                              :tokens   total-tokens}
                             usage (assoc :usage usage)
                             stop-reason (assoc :stopReason stop-reason)
                             reasoning (assoc :reasoning reasoning)))
    (store/update-session! ss session-key
                           (cond-> {:input-tokens      (+ (or (:input-tokens session-entry) 0) input-tokens)
                                    :last-input-tokens input-tokens
                                    :output-tokens     (+ (or (:output-tokens session-entry) 0) output-tokens)
                                    :total-tokens      (+ (+ (or (:input-tokens session-entry) 0) input-tokens)
                                                          (+ (or (:output-tokens session-entry) 0) output-tokens))}
                                   cache-read (assoc :cache-read (+ (or (:cache-read session-entry) 0) cache-read))
                                   cache-write (assoc :cache-write (+ (or (:cache-write session-entry) 0) cache-write))))
    nil))

(defn- process-response* [ctx session-key result {:keys [model provider]}]
  (if (:error result)
    (report-error! ctx session-key provider result {:model model :provider provider})
    (store-response! ctx session-key result {:model model :provider provider})))

(defn process-response!
  ([session-key result {:keys [model provider]}]
   (process-response* (nexus/necho) session-key result {:model model :provider provider}))
  ([ctx-or-root session-key result opts]
   (process-response* (normalize-ctx ctx-or-root)
                       session-key result opts)))

;; endregion ^^^^^ Response Persistence ^^^^^

;; region ----- Streaming -----

(defn- chunk-content [chunk]
  (let [content (or (get-in chunk [:message :content])
                    (get-in chunk [:delta :text])
                    (get-in chunk [:choices 0 :delta :content]))]
    (cond
      (string? content) content
      (vector? content) (apply str content)
      (nil? content) nil
      :else (str content))))

(defn- chunk-piece [full-content chunk]
  (when-let [content (chunk-content chunk)]
    (if (and (:done chunk)
             (seq full-content)
             (str/starts-with? content full-content))
      (subs content (count full-content))
      content)))

(defn stream-response! [p request on-chunk]
  (let [full-content (atom "")
        final-resp   (atom nil)
        result       (dispatch/dispatch-chat-stream p request
                                                    (fn [chunk]
                                                      (when-let [piece (chunk-piece @full-content chunk)]
                                                        (when (seq piece)
                                                          (swap! full-content str piece)
                                                          (on-chunk piece)))
                                                      (when (:done chunk)
                                                        (reset! final-resp chunk))))]
    (if (:error result)
      result
      {:content  (or (not-empty @full-content) (get-in result [:message :content]) "")
       :response (or @final-resp result)})))


(defn- emit-response-content! [channel-impl session-key response]
  (let [content (get-in response [:message :content])
        chunks  (cond
                  (vector? content) (mapv str content)
                  (string? content) [content]
                  (nil? content) []
                  :else [(str content)])]
    (doseq [chunk chunks]
      (comm/on-text-chunk channel-impl session-key chunk))
    (apply str chunks)))

(defn- stream-supports-tool-calls? [provider-config]
  (let [raw (or (get provider-config :streamSupportsToolCalls)
                (get provider-config :stream-supports-tool-calls))]
    (cond
      (nil? raw) true
      (boolean? raw) raw
      (string? raw) (not (#{"false" "0" "no" "off"} (str/lower-case raw)))
      :else (boolean raw))))

(defn- unwrap-stream-result
  "stream-response! returns {:content streamed-text :response chat-response}.
   The tool loop wants the inner chat-response so it can read :message and :tool-calls."
  [result]
  (cond
    (:error result) result
    (:response result) (:response result)
    :else result))

(defn- chat-fn-for
  "Pick the LLM-call hook the tool-loop should use this turn.

   - Tools requested, streaming supports tools: stream deltas via Comm callbacks.
   - Otherwise (no tools, or tools but streaming not supported): one-shot chat,
     emit content as a single Comm chunk."
  [channel-impl session-key p request]
  (cond
    (and (:tools request) (stream-supports-tool-calls? (api/config p)))
    (fn [req] (unwrap-stream-result
                (stream-response! p req
                                  (fn [chunk] (comm/on-text-chunk channel-impl session-key chunk)))))

    :else
    (fn [req] (let [result (dispatch/dispatch-chat p req)]
                (if (:error result)
                  result
                  (let [joined (emit-response-content! channel-impl session-key result)]
                    (assoc-in result [:message :content] joined)))))))

(defn- canned-loop-exhausted-message [result]
  (let [content-blank? (str/blank? (or (:content result)
                                       (get-in result [:response :message :content])
                                       (get-in result [:response :content])))]
    (if (and (:loop-request? result) content-blank?)
      (let [message "I ran several tools but did not reach a conclusion before hitting the tool loop limit. Ask me to continue if you want me to keep digging."]
        (-> result
            (assoc :content message)
            (assoc-in [:response :message :content] message)))
      result)))

(def ^:private loop-exhausted-summary-instruction
  "You have hit the tool loop limit. Do not call any more tools. Write a concise assistant reply for the user using what you learned so far. If you still cannot fully answer, summarize the useful findings and what remains unresolved.")

(defn- loop-summary-request [request response]
  (let [assistant-msg (or (:message response)
                          {:role    "assistant"
                           :content (or (:content response) "")})]
    (-> request
        (assoc :messages (conj (vec (:messages request))
                               assistant-msg
                               {:role "user" :content loop-exhausted-summary-instruction}))
        (assoc :tools []))))

(defn- merge-response-tokens [token-counts response]
  (let [usage (:usage response)]
    (merge-with + token-counts
                {:input-tokens  (or (usage-input-tokens usage) (:prompt_eval_count response) 0)
                 :output-tokens (or (usage-output-tokens usage) (:eval_count response) 0)
                 :cache-read    (or (usage-cache-read usage) 0)
                 :cache-write   (or (usage-cache-write usage) 0)})))

(defn- final-loop-summary [result chat-fn current-request]
  (let [content (or (:content result)
                    (get-in result [:response :message :content])
                    (get-in result [:response :content]))]
    (if (or (not (:loop-request? result))
            (not (str/blank? content)))
      result
      (let [summary-response (chat-fn (loop-summary-request current-request (:response result)))
            summary-content  (get-in summary-response [:message :content])]
        (if (or (:error summary-response)
                (str/blank? summary-content))
          result
          (-> result
              (assoc :content summary-content)
              (assoc :response summary-response)
              (assoc :token-counts (merge-response-tokens (:token-counts result) summary-response))))))))

;; endregion ^^^^^ Streaming ^^^^^

;; region ----- Context Compaction -----

(defn- session-entry
  ([ctx session-key]
   (store/get-session (or (:session-store ctx) (nexus/get-in [:sessions :store])) session-key)))

(def ^:private max-compaction-attempts 5)

(defn- consecutive-compaction-failures [entry]
  (or (get-in entry [:compaction :consecutive-failures]) 0))

(defn- reserve-async-compaction! [session-key]
  (let [lock     (Object.)
        claimed? (atom false)]
    (swap! in-flight-compactions
           (fn [state]
             (if (contains? state session-key)
               state
               (do
                 (reset! claimed? true)
                 (assoc state session-key {:lock lock})))))
    (when @claimed? lock)))

(declare run-compaction-check!)

(defn- perform-compaction! [session-key attempt prompt-tokens {:keys [compaction-llm-done context-window model provider soul splice-ready transcript-lock] ch :comm :as opts}]
  (let [provider-name (api/display-name provider)]
    (cond
      (> attempt max-compaction-attempts)
      (log/warn :session/compaction-stopped
                :session session-key
                :provider provider-name
                :model model
                :reason :max-attempts
                :attempt attempt
                :total-tokens prompt-tokens
                :context-window context-window)

      :else
      (let [started-at (System/currentTimeMillis)]
        (log/info :session/compaction-started
                  :session session-key
                  :provider provider-name
                  :model model
                  :total-tokens prompt-tokens
                  :context-window context-window)
        (when ch
          (comm/on-compaction-start ch session-key {:provider       provider-name
                                                    :model          model
                                                    :total-tokens   prompt-tokens
                                                    :context-window context-window}))
        (let [result (compaction/compact! session-key
                                          {:model               model
                                           :api                 provider
                                           :soul                soul
                                           :root           (:root opts)
                                           :session-store       (:session-store opts)
                                           :context-window      context-window
                                           :transcript-lock     transcript-lock
                                           :compaction-llm-done compaction-llm-done
                                           :splice-ready        splice-ready
                                           :chat-fn             (partial dispatch/dispatch-chat-with-tools provider)})]
          (if (:error result)
            (let [failures (inc (consecutive-compaction-failures (session-entry opts session-key)))]
              (store/update-session! (or (:session-store opts) (nexus/get-in [:sessions :store])) session-key {:compaction {:consecutive-failures failures}})
              (when ch
                (comm/on-compaction-failure ch session-key {:consecutive-failures failures
                                                            :error                (:error result)
                                                            :message              (:message result)}))
              (when (>= failures max-compaction-attempts)
                (store/update-session! (or (:session-store opts) (nexus/get-in [:sessions :store])) session-key {:compaction-disabled true})
                (when ch
                  (comm/on-compaction-disabled ch session-key {:reason :too-many-failures}))
                (log/warn :session/compaction-stopped
                          :session session-key
                          :provider provider-name
                          :model model
                          :reason :too-many-failures
                          :attempt attempt
                          :total-tokens prompt-tokens
                          :context-window context-window))
              (log/error :session/compaction-failed
                         :session session-key
                         :provider provider-name
                         :model model
                         :error (:error result)
                         :message (:message result)))
            (do
              (store/update-session! (or (:session-store opts) (nexus/get-in [:sessions :store])) session-key {:compaction-disabled false
                                                                                              :compaction          {:consecutive-failures 0}})
              (when ch
                (comm/on-compaction-success ch session-key {:summary      (:summary result)
                                                            :tokens-saved (max 0 (- prompt-tokens (:last-input-tokens (session-entry opts session-key) 0)))
                                                            :duration-ms  (- (System/currentTimeMillis) started-at)}))
              (when-not (:chunked result)
                (let [updated-total (:last-input-tokens (session-entry opts session-key) 0)]
                  (if (>= updated-total prompt-tokens)
                    (log/warn :session/compaction-stopped
                              :session session-key
                              :provider provider-name
                              :model model
                              :reason :no-progress
                              :attempt attempt
                              :total-tokens updated-total
                              :context-window context-window)
                    (run-compaction-check! session-key
                                           {:comm            ch
                                            :context-window  context-window
                                            :model           model
                                            :provider        provider
                                            :root       (:root opts)
                                            :session-store   (:session-store opts)
                                            :soul            soul
                                            :transcript-lock transcript-lock}
                                           (inc attempt)
                                           false)))))))))))

(defn- start-async-compaction! [session-key opts]
  (when-let [lock (reserve-async-compaction! session-key)]
    (let [compaction-llm-done (promise)
          splice-ready        (promise)
          task                (bound-fn []
                                (run-compaction-check! session-key
                                                       (assoc opts
                                                         :transcript-lock lock
                                                         :compaction-llm-done compaction-llm-done
                                                         :splice-ready splice-ready)
                                                       1 false))
          future*             (future (task))]
      (swap! in-flight-compactions assoc session-key {:future              future*
                                                      :lock                lock
                                                      :compaction-llm-done compaction-llm-done
                                                      :splice-ready        splice-ready})
      future*)))

(defn- run-compaction-check! [session-key {:keys [context-window model provider] :as opts} attempt allow-async?]
  (let [entry        (session-entry opts session-key)
        _failures    (consecutive-compaction-failures entry)
        total-tokens (:last-input-tokens entry 0)
        config       (or (:compaction opts)
                         (compaction/resolve-config entry context-window))
        prov-name    (when provider (api/display-name provider))]
    (log/debug :session/compaction-check
               :session session-key
               :provider prov-name
               :model model
               :total-tokens total-tokens
               :context-window context-window)
    (cond
      (= :reset (:context-mode opts))
      (log/info :session/compaction-skipped
                :session session-key
                :provider prov-name
                :model model
                :total-tokens total-tokens
                :context-window context-window
                :reason :context-reset)

      (:compaction-disabled entry)
      (log/info :session/compaction-skipped
                :session session-key
                :provider prov-name
                :model model
                :total-tokens total-tokens
                :context-window context-window
                :reason :disabled)

      (compaction/should-compact? (assoc entry :compaction config) context-window)
      (if (and allow-async? (:async? config))
        (start-async-compaction! session-key opts)
        (perform-compaction! session-key attempt total-tokens opts)))))

(defn check-compaction!
  ([session-key opts]
   (run-compaction-check! session-key (merge (nexus/necho) opts) 1 true))
  ([ctx-or-root session-key opts]
   (run-compaction-check! session-key (merge opts (normalize-ctx ctx-or-root)) 1 true)))

;; endregion ^^^^^ Context Compaction ^^^^^

;; region ----- Request Building -----

(defn- tool-name [tool]
  (or (:name tool)
      (get-in tool [:function :name])))

(defn- allowed-tool-names [crew-members crew-id]
  (when-let [crew (get crew-members crew-id)]
    (when (contains? crew :tools)
      (->> (get-in crew [:tools :allow])
           (mapv (fn [tool]
                   (cond
                     (keyword? tool) (name tool)
                     (string? tool) tool
                     :else (str tool))))
           set))))

(defn- active-tools [_p allowed-tools module-index]
  (not-empty (if module-index
               (tool-registry/tool-definitions allowed-tools module-index)
               (tool-registry/tool-definitions allowed-tools))))

(defn- merge-allowed-tools [crew-tools auto-tools]
  (not-empty (into (set (or crew-tools [])) auto-tools)))

(defn- ensure-default-tools-registered! []
  (builtin/register-all!))

(defn build-chat-request [p {:keys [boot-files effort guidance model nonce origin rules-text skill-menu-text soul transcript tools]}]
  (let [prompt-out (api/build-prompt p {:boot-files boot-files
                                        :guidance   guidance
                                        :model      model
                                        :nonce      nonce
                                        :origin     origin
                                        :rules-text rules-text
                                        :skill-menu-text skill-menu-text
                                        :soul       soul
                                        :transcript transcript
                                        :tools      tools})]
    (cond-> {:model (:model prompt-out) :messages (:messages prompt-out)}
            (:system prompt-out) (assoc :system (:system prompt-out))
            (:max_tokens prompt-out) (assoc :max_tokens (:max_tokens prompt-out))
            (:tools prompt-out) (assoc :tools (:tools prompt-out))
            (some? effort) (assoc :effort effort))))

;; endregion ^^^^^ Request Building ^^^^^

;; region ----- Public API -----

(defn- augment-provider
  "Wrap an upstream Api with per-turn runtime values (root,
   session-key, context-window, and model-cfg overrides) merged into
   its config. Returns a new Api instance — the upstream one is unchanged."
  [root p session-key context-window model-cfg-overrides]
  (when p
    (let [cfg (merge (or (api/config p) {})
                     model-cfg-overrides
                     {:root      root
                      :session-key    session-key
                      :context-window context-window})]
      (llm-provider/make-provider (api/display-name p) cfg))))

(def turn-schema
  {:name   :turn
   :type   :map
   :schema {:charge        {:type :ignore  :description "Resolved charge — the comm-supplied inputs"}
            :session-store {:type :ignore  :description "Session store backing this turn (derived from charge or root)"}
            :root     {:type :string  :description "Isaac state directory"}
            :effort        {:type :long    :description "Per-turn effort budget, nil when model disallows effort"}
            :allowed-tools {:type :ignore  :description "Set of tool keywords allowed for this turn's crew"}
            :boot-files    {:type :ignore  :description "Boot-file contents read from the session cwd"}
            :rules-text    {:type :ignore  :description "Always-on prepared rule bodies read from global/project roots"}
            :skill-menu-text {:type :ignore :description "Advertised skill descriptions injected into the cached system prompt"}
            :provider      {:type :ignore  :description "Tools-augmented LLM provider for this turn"}}})

(defn- build-turn
  "Wraps a resolved charge with per-turn derived state. Charge already holds
   the resolved behavior (model, provider, soul, compaction, effort, etc.),
   so this only computes the genuinely per-turn fields and the
   tools-augmented provider that drive needs."
  [charge]
  (let [{:keys [session-key crew crew-members context-window
                model model-cfg provider]} charge
        root      (or (nexus/get :root) (get-in charge [:config :root]))
        session-store* (nexus/get-in [:sessions :store])
        session        (store/get-session session-store* session-key)
        skill-disclosure (or (session-ctx/read-skill-disclosure (:config charge) root (:cwd session))
                             {:menu-text nil :tool-names #{}})
        allowed-tools  (merge-allowed-tools (allowed-tool-names crew-members crew)
                                            (:tool-names skill-disclosure))
        boot-files     (session-ctx/read-boot-files (:cwd session))
        rules-text     (session-ctx/read-rules-text (:config charge) root (:cwd session))
        augmented      (augment-provider root provider session-key context-window
                                         (select-keys (or model-cfg {})
                                                      [:thinking-budget-max :think-mode]))]
    (log/debug :turn/context-resolved
               :session session-key
               :crew crew
               :model model
               :provider (some-> provider api/display-name)
               :effort (:effort charge)
               :context-window context-window
               :crew-keys (vec (keys crew-members))
               :crew-cfg-keys (some-> (:crew-cfg charge) keys vec)
               :allowed-tools-count (count allowed-tools)
               :allowed-tools (some-> allowed-tools sort vec)
               :cwd (:cwd session))
    (schema/conform! turn-schema
                     {:charge        charge
                      ;; convenience accessors for storage helpers — same value, derived via session-store helper
                      :session-store session-store*
                      :root     root
                      :effort        (when (get (or model-cfg {}) :allows-effort true)
                                       (:effort charge))
                      :allowed-tools allowed-tools
                      :boot-files    boot-files
                      :rules-text    rules-text
                      :skill-menu-text (:menu-text skill-disclosure)
                      :provider      augmented})))

(defn- finish-turn! [ch session-key result]
  (comm/on-turn-end ch session-key result)
  result)

(defn- record-tool-call!
  "Wrap a tool invocation with comm callbacks, cancellation tracking, and
   accumulation into the executed-tools atom for later transcript persistence."
  [{:keys [session-key allowed-tools module-index executed-tools caps] ch :comm} name arguments]
  (let [tc         {:id (str (java.util.UUID/randomUUID)) :name name :arguments arguments :type "toolCall"}
        tool-state (atom :pending)
        cancel!    #(when (compare-and-set! tool-state :pending :cancelled)
                      (comm/on-tool-cancel ch session-key tc))]
    (comm/on-tool-call ch session-key tc)
    (bridge/on-cancel! session-key cancel!)
    (let [tool-fn* #_{:clj-kondo/ignore [:invalid-arity]} (tool-registry/tool-fn allowed-tools module-index caps)
          result   (tool-fn*
                     name
                     (assoc arguments "session_key" session-key))]
      (when (= :cancelled (:error result))
        (cancel!)
        (throw (ex-info "cancelled" {:type :cancelled})))
      (when (compare-and-set! tool-state :pending :completed)
        (swap! executed-tools conj [tc result])
        (comm/on-tool-result ch session-key tc result))
      result)))

(defn- execute-llm-turn!
  "Build the chat request, drive the tool-loop, persist tool pairs and the
   final assistant response. Returns the final result map."
  [session-key input ctx]
  (let [{:keys [provider allowed-tools effort boot-files rules-text skill-menu-text]} ctx
        {:keys [guidance model module-index nonce origin soul context-mode comm config]} (:charge ctx)
        caps {:max-lines (get-in config [:tools :defaults :max-lines])
              :max-bytes (get-in config [:tools :defaults :max-bytes])}
        ch (or comm cli-comm/channel)
        p  provider]
    (append-message! ctx session-key {:role "user" :content input})
    (let [transcript      (with-transcript-lock session-key #(store/active-transcript (or (:session-store ctx) (nexus/get-in [:sessions :store])) session-key))
          transcript      (if (= :reset context-mode)
                            (if-let [current-user (last transcript)] [current-user] [])
                            transcript)
          tools           (active-tools p allowed-tools module-index)
          tool-reason     (cond
                            (empty? allowed-tools)          :no-allowed-tools
                            (empty? tools)                  :no-registered-tools
                            :else                           nil)
          request         (build-chat-request p {:boot-files boot-files
                                                 :effort     effort
                                                 :guidance   guidance
                                                 :model      model
                                                 :nonce      nonce
                                                 :origin     origin
                                                 :rules-text rules-text
                                                 :skill-menu-text skill-menu-text
                                                 :soul       soul
                                                 :transcript transcript
                                                 :tools      tools})
          _               (log/debug :turn/request-built
                                     :session session-key
                                     :provider (api/display-name p)
                                     :model (:model request)
                                     :effort (:effort request)
                                     :messages-count (count (:messages request))
                                     :allowed-tools-count (count allowed-tools)
                                     :selected-tools-count (count tools)
                                     :selected-tools (some->> tools (map tool-name) sort vec)
                                     :tool-selection-reason tool-reason
                                     :request-keys (-> request keys sort vec))
          current-request (atom request)
          executed-tools  (atom [])
          tool-fn         (partial record-tool-call! {:comm           ch
                                                       :session-key    session-key
                                                       :allowed-tools  allowed-tools
                                                      :module-index   module-index
                                                      :caps           caps
                                                      :executed-tools executed-tools})]
      (when-let [done (:compaction-llm-done (active-compaction-state session-key))]
        (deref done 5000 nil))
      (let [chat-fn     (chat-fn-for ch session-key p request)
            followup-fn (fn [req response tool-calls tool-results]
                          (let [messages (api/followup-messages p req response tool-calls tool-results)]
                            (reset! current-request (assoc req :messages messages))
                            messages))
            result      (-> (tool-loop/run chat-fn followup-fn request tool-fn
                                           {:cancelled? #(bridge/cancelled? session-key)})
                            (final-loop-summary chat-fn @current-request)
                            canned-loop-exhausted-message)]
        (log/debug :turn/model-response-summary
                   :session session-key
                   :provider (api/display-name p)
                   :error (:error result)
                   :assistant-content-chars (count (or (get-in result [:message :content]) ""))
                   :tool-calls-count (count (:tool-calls result))
                   :executed-tools-count (count @executed-tools))
        (cond
          (or (= :cancelled (:error result))
              (bridge/cancelled-response? result)
              (bridge/cancelled? session-key))
          (do
            (when (seq @executed-tools)
              (run-tool-calls! ctx session-key @executed-tools))
            (bridge/cancelled-result))

          :else
          (do
            (when-not (:error result)
              (log/debug :chat/stream-completed :session session-key))
            (when (seq @executed-tools)
              (run-tool-calls! ctx session-key @executed-tools))
            (or (process-response! ctx session-key result {:model model :provider (api/display-name p)})
                 result)))))))

(defn- run-turn-body!
  "The successful-path pipeline. Returns the result that finish-turn! should
   wrap. Each branch is a single call into a focused helper.

   Unresolved charges (unknown crew, no model) are rejected upstream in
   bridge/route-charge!, so we only see resolved charges here."
  [session-key input ctx]
  (let [{:keys [boot-files provider]} ctx
        {:keys [crew comm compaction context-mode model soul context-window]} (:charge ctx)]
    (cond
      (bridge/cancelled? session-key)
      (bridge/cancelled-result)

      :else
      (do
        (log/info :drive/turn-accepted {:session session-key :crew crew})
        (check-compaction! ctx session-key {:boot-files     boot-files
                                            :compaction     compaction
                                            :context-mode   context-mode
                                            :model          model
                                            :soul           soul
                                            :context-window context-window
                                            :provider       provider
                                            :comm           comm})
        (if (bridge/cancelled? session-key)
          (bridge/cancelled-result)
          (execute-llm-turn! session-key input ctx))))))

(defn- record-exception! [session-key e ctx]
  (let [{:keys [provider]} ctx
        model              (:model (:charge ctx))]
    (append-error! ctx session-key {:content  (.getMessage e)
                                    :error    "exception"
                                    :ex-class (.getName (class e))
                                    :model    model
                                    :provider (when provider (api/display-name provider))})))

(defn run-turn!
  "Drives a single turn from a resolved charge. The bridge rejects unresolved
   charges before they reach here."
  [charge]
  (let [session-key (:session-key charge)
        input       (:input charge)
        ctx         (build-turn charge)
        ch          (or (:comm charge) cli-comm/channel)
        turn-id     (bridge/begin-turn! session-key)
        finish!     #(finish-turn! ch session-key %)]
    (try
      (comm/on-turn-start ch session-key input)
      (ensure-default-tools-registered!)
      (finish! (run-turn-body! session-key input ctx))
      (catch ExceptionInfo e
        (if (= :cancelled (:type (ex-data e)))
          (finish! (bridge/cancelled-result))
          (do (record-exception! session-key e ctx) (throw e))))
      (catch Exception e
        (if (bridge/cancelled? session-key)
          (finish! (bridge/cancelled-result))
          (do (record-exception! session-key e ctx) (throw e))))
      (finally
        (bridge/end-turn! session-key turn-id)))))

;; endregion ^^^^^ Public API ^^^^^

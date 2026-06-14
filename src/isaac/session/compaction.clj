(ns isaac.session.compaction
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.llm.api.protocol :as llm]
    [isaac.logger :as log]
    [isaac.session.context :as session-ctx]
    [isaac.session.compaction-schema :as compaction-schema]
    [isaac.nexus :as nexus]
    [isaac.session.store.spi :as store]
    [isaac.session.transcript :as transcript]
    [isaac.tool.builtin :as builtin]
    [isaac.tool.registry :as tool-registry]))

;; region ----- Policy / Schema -----

(def config-schema compaction-schema/config-schema)

(defn default-threshold [window]
  (session-ctx/default-threshold window))

(defn default-head [window]
  (session-ctx/default-head window))

(defn resolve-config [session-entry context-window]
  (session-ctx/resolve-compaction-config {} session-entry {:crew-cfg {} :model-cfg {} :provider-cfg {}} context-window))

(defn should-compact? [session-entry context-window]
  (let [total     (:last-input-tokens session-entry 0)
        {:keys [threshold]} (resolve-config session-entry context-window)]
    (>= total (* threshold context-window))))

(defn compaction-target [entries {:keys [strategy head]} context-window]
  (let [tokens*     (mapv :tokens entries)
        head-tokens (* head context-window)]
    (case strategy
      :rubberband
      {:compact-count        (count entries)
       :first-kept-entry-id  nil
       :tokens-before        (reduce + 0 tokens*)}

      :slinky
      (loop [idx        (dec (count entries))
             head-size  0]
        (if (or (neg? idx) (>= head-size head-tokens))
          (let [compact-count (inc idx)
                compacted     (subvec entries 0 (max 0 compact-count))
                first-kept    (nth entries compact-count nil)]
            {:compact-count       (max 0 compact-count)
             :first-kept-entry-id (:id first-kept)
             :tokens-before       (reduce + 0 (map :tokens compacted))})
          (recur (dec idx) (+ head-size (:tokens (nth entries idx)))))))))

;; endregion ^^^^^ Policy / Schema ^^^^^

;; region ----- Orchestration -----

(defonce ^:private last-compaction-request* (atom nil))

(defn last-compaction-request []
  @last-compaction-request*)

(defn- last-compaction [transcript]
  (->> transcript
       (filter #(= "compaction" (:type %)))
       last))

(defn- messages-from-entry-id [transcript entry-id]
  (let [keep? (atom false)]
    (->> transcript
         (keep (fn [entry]
                 (when (= (:id entry) entry-id)
                   (reset! keep? true))
                 (when (and @keep? (= "message" (:type entry)))
                   entry)))
         vec)))

(defn- messages-after-compaction [transcript compaction-id]
  (let [after? (atom false)]
    (->> transcript
         (keep (fn [entry]
                 (if (= (:id entry) compaction-id)
                   (do (reset! after? true) nil)
                   (when (and @after? (= "message" (:type entry)))
                     entry))))
         vec)))

(defn- effective-history-entries [transcript]
  (if-let [compaction (last-compaction transcript)]
    (into [compaction]
          (if-let [first-kept-id (:firstKeptEntryId compaction)]
            (messages-from-entry-id transcript first-kept-id)
            (messages-after-compaction transcript (:id compaction))))
    (->> transcript
         (filter #(= "message" (:type %)))
         vec)))

(defn- message-text [content]
  (transcript/content->text content))

(defn- ->compact-message [entry context-window]
  (if (= "compaction" (:type entry))
    {:role "user" :content (:summary entry)}
    (let [{:keys [content role]} (:message entry)
          text                  (message-text content)]
      (when (and (contains? #{"user" "assistant" "toolResult"} role)
                 (string? text)
                 (not (str/blank? text)))
        {:role    (if (= "toolResult" role) "user" role)
         :content (if (= "toolResult" role)
                    (transcript/truncate-tool-result text context-window)
                    text)}))))

(defn- tool-call-content [entry]
  (some-> entry :message transcript/first-tool-call))

(defn- tool-result-id [entry]
  (or (get-in entry [:message :toolCallId])
      (get-in entry [:message :id])))

(defn- tool-pair-message [tool-call-entry tool-result-entry context-window]
  (let [tool-call   (tool-call-content tool-call-entry)
        result-text (message-text (get-in tool-result-entry [:message :content]))]
    (when (and tool-call result-text)
      {:role    "assistant"
       :content (str "I called tool " (:name tool-call)
                      " with id " (:id tool-call)
                      " and arguments " (pr-str (:arguments tool-call))
                      ". The tool result was: " (transcript/truncate-tool-result result-text context-window))})))

(declare message-token-count)

(defn- compactables [history-entries context-window]
  (loop [remaining history-entries
          result    []]
    (if-let [entry (first remaining)]
      (if-let [tool-call (tool-call-content entry)]
        (let [next-entry (second remaining)]
          (if (and (= "message" (:type next-entry))
                   (= "toolResult" (get-in next-entry [:message :role]))
                   (= (:id tool-call) (tool-result-id next-entry)))
            (if-let [message (tool-pair-message entry next-entry context-window)]
              (recur (nnext remaining)
                     (conj result {:id      (:id entry)
                                    :ids     [(:id entry) (:id next-entry)]
                                   :entry   entry
                                   :message message
                                   :tokens  (+ (or (:tokens entry) 0)
                                               (or (:tokens next-entry) 0))}))
              (recur (nnext remaining) result))
            (recur (rest remaining) result)))
        (if-let [message (->compact-message entry context-window)]
          (recur (rest remaining)
                 (conj result {:id      (:id entry)
                               :ids     [(:id entry)]
                               :entry   entry
                               :message message
                               :tokens  (message-token-count entry message)}))
          (recur (rest remaining) result)))
      (vec result))))

(defn- message-token-count [entry message]
  (or (:tokens entry)
      (llm/estimate-tokens {:messages [message]})))

(def ^:private memory-tool-names #{"memory_get" "memory_search" "memory_write"})

(def ^:private compaction-system-prompt
  (str "Review this conversation. Call memory_write for anything durable the user will want later. "
       "Then produce a concise summary of what happened. Use first person ('I') for actions taken by the assistant, "
       "refer to the user as 'the user', and preserve who asked, who acted, and who verified each step. "
       "Output only the summary, no preamble."))

(defn- ensure-memory-tools-registered! []
  (doseq [tool-name memory-tool-names]
    (when-not (tool-registry/lookup tool-name)
      (builtin/register-all! memory-tool-names)
      (reduced nil))))

(defn- compaction-tool-fn [key-str]
  (fn [ctx name arguments]
    (let [result (tool-registry/execute name (assoc arguments "session_key" key-str "state_dir" (:root ctx) "session_store" (:session-store ctx)) memory-tool-names)]
      (if (:isError result)
        (str "Error: " (:error result))
        (:result result)))))

(defn- response-error [response]
  (or (:error response)
      (get-in response [:response :error])))

(defn- response-content [response]
  (or (get-in response [:response :message :content])
      (get-in response [:message :content])))

(defn- chunk-budget [context-window]
  ;; Chunk against the full compaction request size, not raw message token sums.
  ;; The provider rejects based on prompt size, so use the model window directly.
  (max 1 context-window))

(defn- compactable-log-data [compactable]
  {:content-chars (count (str (get-in compactable [:message :content] "")))
   :id            (:id compactable)
   :role          (get-in compactable [:message :role])
   :tokens        (:tokens compactable)
   :type          (:type (:entry compactable))})

(defn- chunk-plan [model api compactables context-window tool-defs]
  (let [budget (chunk-budget context-window)]
    (loop [remaining compactables
            current   []
            chunks    []]
      (if-let [compactable (first remaining)]
        (let [candidate  (conj current compactable)
              messages   (mapv :message candidate)
              req-tokens (llm/estimate-tokens (llm/build-summary-request api model compaction-system-prompt messages tool-defs))]
          (cond
            (<= req-tokens budget)
            (recur (rest remaining) candidate chunks)

            (seq current)
            (recur remaining [] (conj chunks (mapv :message current)))

            :else
            {:budget  budget
             :chunks  nil
             :failure {:compactable              (compactable-log-data compactable)
                       :candidate-request-tokens req-tokens
                       :reason                   :oversized-single}}))
        {:budget budget
         :chunks (cond-> chunks
                   (seq current) (conj (mapv :message current)))}))))

(defn- feasible-chunks [model api compactables context-window tool-defs]
  (let [plan   (chunk-plan model api compactables context-window tool-defs)
        chunks (:chunks plan)]
    (assoc plan :chunks (when (and chunks (> (count chunks) 1)) chunks))))

(defn- summarize-messages [chat-fn tool-fn model api messages tool-defs]
  (let [request (llm/build-summary-request api model compaction-system-prompt messages tool-defs)]
    (reset! last-compaction-request* request)
    (chat-fn request tool-fn)))

(defn- chunked-response [ctx key-str chat-fn model api chunks tool-defs]
  (let [tool-fn (partial (compaction-tool-fn key-str) ctx)]
    (log/info :session/compaction-chunked :session key-str :model model :chunks (count chunks))
    (loop [remaining chunks
           summaries  []]
      (if-let [chunk (first remaining)]
        (let [response (summarize-messages chat-fn tool-fn model api chunk tool-defs)]
          (if (response-error response)
            response
            (recur (rest remaining) (conj summaries (response-content response)))))
        (if (> (count summaries) 1)
          (summarize-messages chat-fn tool-fn model api (mapv (fn [summary] {:role "user" :content summary}) summaries) tool-defs)
          {:message {:content (first summaries)}})))))

(defn compact!
  "Compact a session's conversation history into a summary.
   Sends the conversation to the LLM for summarization, then appends
   a compaction entry to the transcript.
     Options:
       :api     - Api instance for provider-specific request formatting (optional)
       :chat-fn - (fn [request tool-fn]) to call the LLM (required)
        :transcript-lock - optional lock used only for the final transcript splice
        :compaction-llm-done - optional promise delivered after LLM call completes
        :splice-ready - optional promise waited on before performing the splice"
  [key-str {:keys [boot-files chat-fn compaction-llm-done context-window model api soul splice-ready transcript-lock root session-store]}]
  (let [root      (or root (loader/root))
        session-store  (or session-store (nexus/get-in [:sessions :store]))
        ctx            {:root root :session-store session-store}
        behavior       (session-ctx/resolve-behavior key-str {:context-window context-window})
        transcript      (store/get-transcript session-store key-str)
        history-entries (effective-history-entries transcript)
        compactables    (compactables history-entries context-window)
        messages        (mapv :message compactables)
        strategy        (:compaction behavior)
        {:keys [compact-count first-kept-entry-id tokens-before]}
        (compaction-target compactables strategy context-window)
        compactable-head (subvec compactables 0 compact-count)
        compacted-ids   (vec (mapcat :ids compactable-head))
        compacted       (subvec messages 0 compact-count)
        _               (ensure-memory-tools-registered!)
        tool-defs       (tool-registry/tool-definitions memory-tool-names)
        summary-prompt  (llm/build-summary-request api model compaction-system-prompt compacted tool-defs)
        summary-prompt-tokens (llm/estimate-tokens summary-prompt)
        needs-chunking? (or (> tokens-before context-window)
                             (> summary-prompt-tokens context-window))
        chunks          (when needs-chunking?
                          (feasible-chunks model api compactable-head context-window tool-defs))
        chunk-messages  (:chunks chunks)
        chunked?        (seq chunk-messages)
        chunk-request-tokens (mapv #(llm/estimate-tokens (llm/build-summary-request api model compaction-system-prompt % tool-defs)) chunk-messages)
        _               (log/debug :session/compaction-analysis
                                    :compact-count compact-count
                                    :compactable-count (count compactables)
                                    :context-window context-window
                                    :first-kept-entry-id first-kept-entry-id
                                    :history-entry-count (count history-entries)
                                    :model model
                                    :needs-chunking needs-chunking?
                                    :session key-str
                                    :strategy (:strategy strategy)
                                    :summary-prompt-tokens summary-prompt-tokens
                                    :tokens-before tokens-before)
        _               (when needs-chunking?
                          (log/debug :session/compaction-chunk-plan
                                     :budget (:budget chunks)
                                     :chunk-count (count chunk-messages)
                                     :chunk-message-counts (mapv count chunk-messages)
                                     :chunk-request-tokens chunk-request-tokens
                                     :failure (:failure chunks)
                                     :model model
                                     :session key-str))
        _               (when (and needs-chunking? (not chunked?))
                          (log/warn :session/compaction-chunk-infeasible
                                    :context-window context-window
                                    :failure (:failure chunks)
                                    :model model
                                    :session key-str
                                    :summary-prompt-tokens summary-prompt-tokens
                                    :tokens-before tokens-before))
        _               (reset! last-compaction-request* nil)
        response        (if chunked?
                          (chunked-response ctx key-str chat-fn model api chunk-messages tool-defs)
                          (summarize-messages chat-fn (partial (compaction-tool-fn key-str) ctx) model api compacted tool-defs))]
    (when compaction-llm-done
      (deliver compaction-llm-done true))
    (if (response-error response)
      response
      (let [summary          (response-content response)
            spliced-transcript (atom nil)
            splice!          (fn []
                               (let [compaction-entry (store/splice-compaction! session-store key-str
                                                                                 {:summary           summary
                                                                                  :firstKeptEntryId  first-kept-entry-id
                                                                                  :tokensBefore      tokens-before
                                                                                  :compactedEntryIds compacted-ids})]
                                 (reset! spliced-transcript (store/get-transcript session-store key-str))
                                 compaction-entry))
            _                (when splice-ready
                               (deref splice-ready 30000 nil))
            compaction-entry (cond-> (if transcript-lock
                                       (locking transcript-lock (splice!))
                                       (splice!))
                               chunked? (assoc :chunked true))
            system-text      (if boot-files (str soul "\n\n" boot-files) soul)
            new-total        (llm/estimate-tokens {:messages [{:role "system" :content system-text}
                                                               {:role "user"   :content summary}]})]
        (store/update-session! session-store key-str {:last-input-tokens new-total})
        compaction-entry))))

;; endregion ^^^^^ Orchestration ^^^^^

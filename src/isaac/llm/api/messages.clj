(ns isaac.llm.api.messages
  (:require
    [clojure.string :as str]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.api.openai.shared :as shared]
    [isaac.llm.followup :as followup]
    [isaac.llm.http :as llm-http]
    [isaac.llm.prompt.builder :as builder]))

;; region ----- Auth -----

(defn- missing-auth-error [provider-name config]
  (when (str/blank? (shared/resolve-api-key provider-name config))
    (let [env-var (shared/provider-env-var provider-name)
          label   (or provider-name "anthropic")]
      {:error   :auth-missing
       :message (str "No API key for " label "."
                     (when env-var (str " Set " env-var " in the environment"))
                     (when provider-name (str " or :api-key in providers/" provider-name ".edn"))
                     ".")})))

(defn- auth-headers [provider-name config]
  {"x-api-key"         (shared/resolve-api-key provider-name config)
   "anthropic-version" "2023-06-01"
   "content-type"      "application/json"})

;; endregion ^^^^^ Auth ^^^^^

;; region ----- Prompt Building -----

(defn- build-system [system-text]
  [{:type          "text"
    :text          system-text
    :cache_control {:type "ephemeral"}}])

(defn- extract-messages [transcript nonce guidance origin]
  (->> (builder/build-transcript-messages transcript nil builder/filter-messages-anthropic nonce guidance origin)
       (mapv #(select-keys % [:role :content]))))

(defn- penultimate-user-index [messages]
  (let [user-indices (->> messages
                          (map-indexed vector)
                          (filter #(= "user" (:role (second %))))
                          (map first))]
    (when (>= (count user-indices) 2)
      (nth user-indices (- (count user-indices) 2)))))

(defn- apply-cache-breakpoints [messages]
  (if-let [idx (penultimate-user-index messages)]
    (update messages idx
            (fn [msg]
              (let [content (:content msg)]
                (if (string? content)
                  (assoc msg :content [{:type          "text"
                                        :text          content
                                        :cache_control {:type "ephemeral"}}])
                  (let [last-idx (dec (count content))]
                    (assoc msg :content
                           (update content last-idx
                                   #(assoc % :cache_control {:type "ephemeral"}))))))))
    messages))

(defn build-tools
  "Format tool definitions into Anthropic Messages-API shape."
  [tools]
  (when (seq tools)
    (mapv (fn [tool]
            {:name         (:name tool)
             :description  (:description tool)
             :input_schema (:parameters tool)})
          tools)))

(defn build
  "Build an Anthropic Messages API request body."
  [{:keys [boot-files guidance model nonce origin rules-text skill-menu-text soul transcript tools max-tokens]
     :or   {max-tokens 4096}}]
  (let [system-text (builder/build-system-text soul boot-files rules-text skill-menu-text nonce)
        messages    (-> (extract-messages transcript nonce guidance origin)
                      vec
                      apply-cache-breakpoints)]
    (cond-> {:model      model
              :max_tokens max-tokens
              :system     (build-system system-text)
              :messages   messages}
      (seq tools) (assoc :tools (build-tools tools)))))

;; endregion ^^^^^ Prompt Building ^^^^^

;; region ----- SSE Event Processing -----

(defn process-sse-event
  "Accumulate an Anthropic SSE event into the running state."
  [data accumulated]
  (case (:type data)
    "content_block_delta"
    (update accumulated :content str (get-in data [:delta :text]))

    "message_delta"
    (update accumulated :usage merge (:usage data))

    "message_start"
    (assoc accumulated
      :model (get-in data [:message :model])
      :usage (get-in data [:message :usage]))

    ;; Other events: pass through
    accumulated))

;; endregion ^^^^^ SSE Event Processing ^^^^^

;; region ----- Response Parsing -----

(defn- extract-text [content-blocks]
  (->> content-blocks
       (filter #(= "text" (:type %)))
       (map :text)
       (str/join "")))

(defn- extract-tool-calls [content-blocks]
  (->> content-blocks
       (filter #(= "tool_use" (:type %)))
       (mapv (fn [block]
               {:type      "toolCall"
                :id        (:id block)
                :name      (:name block)
                :arguments (:input block)}))))

(defn- parse-usage [usage]
  {:input-tokens  (or (:input_tokens usage) 0)
   :output-tokens (or (:output_tokens usage) 0)
   :cache-read    (or (:cache_read_input_tokens usage) 0)
   :cache-write   (or (:cache_creation_input_tokens usage) 0)})

;; endregion ^^^^^ Response Parsing ^^^^^

;; region ----- Effort Translation -----

(defn- effort->thinking [effort budget-max]
  (when (and effort (pos? effort))
    {:type          "enabled"
     :budget_tokens (int (* effort (/ (or budget-max 32000) 10)))}))

;; endregion ^^^^^ Effort Translation ^^^^^

;; region ----- Public API -----

(defn- http-opts [config]
  (cond-> {}
    (:session-key config)       (assoc :session-key (:session-key config))
    (:simulate-provider config) (assoc :simulate-provider (:simulate-provider config))))

(defn chat
  "Send a non-streaming Messages API request."
  [request provider-name cfg]
  (let [url      (str (or (:base-url cfg) "https://api.anthropic.com") "/v1/messages")
        auth-err (missing-auth-error provider-name cfg)]
    (if auth-err
      auth-err
      (let [headers  (auth-headers provider-name cfg)
            thinking (effort->thinking (:effort request) (:thinking-budget-max cfg))
            body     (cond-> (dissoc request :effort)
                       thinking (assoc :thinking thinking))
            resp     (llm-http/post-json! url headers body (http-opts cfg))]
        (if (:error resp)
          resp
          (let [content (:content resp)
                text    (extract-text content)
                tools   (extract-tool-calls content)
                usage   (parse-usage (:usage resp))]
            {:message     (cond-> {:role "assistant" :content text}
                                  (seq tools) (assoc :tool_calls (mapv (fn [tc]
                                                                         {:function {:name      (:name tc)
                                                                                     :arguments (:arguments tc)}})
                                                                       tools)))
             :model       (:model resp)
             :tool-calls  tools
             :usage       usage
             :_headers    headers
             :stop_reason (:stop_reason resp)}))))))

(defn chat-stream
  "Send a streaming Messages API request via SSE."
  [request on-chunk provider-name cfg]
  (let [url      (str (or (:base-url cfg) "https://api.anthropic.com") "/v1/messages")
        auth-err (missing-auth-error provider-name cfg)]
    (if auth-err
      auth-err
      (let [headers  (auth-headers provider-name cfg)
            thinking (effort->thinking (:effort request) (:thinking-budget-max cfg))
            body     (cond-> (-> request (dissoc :effort) (assoc :stream true))
                       thinking (assoc :thinking thinking))
            initial  {:role "assistant" :content "" :usage {}}
            result   (llm-http/post-sse! url headers body on-chunk process-sse-event initial (http-opts cfg))]
        (if (:error result)
          result
          (let [usage (parse-usage (:usage result))]
            {:message  {:role "assistant" :content (:content result)}
             :model    (:model result)
             :usage    usage
             :_headers headers}))))))

(defn followup-messages
  "Build the next iteration's :messages vector for the Anthropic Messages API.
   Pairs tool_use blocks (in an assistant message) with tool_result blocks
   (in a single user message)."
  [request _response tool-calls tool-results]
  (let [assistant-msg {:role    "assistant"
                       :content (mapv (fn [tc]
                                        {:type  "tool_use"
                                         :id    (:id tc)
                                         :name  (:name tc)
                                         :input (:arguments tc)})
                                      tool-calls)}
        tool-result   {:role    "user"
                       :content (followup/map-tool-results tool-calls tool-results
                                                           (fn [tc result]
                                                             {:type        "tool_result"
                                                              :tool_use_id (:id tc)
                                                              :content     result}))}]
    (followup/append-followup-messages request assistant-msg [tool-result])))

(deftype MessagesAPI [provider-name cfg]
  api/Api
  (chat [_ req] (chat req provider-name cfg))
  (chat-stream [_ req on-chunk] (chat-stream req on-chunk provider-name cfg))
  (followup-messages [_ req resp tcs trs] (followup-messages req resp tcs trs))
  (config [_] cfg)
  (display-name [_] provider-name)
  (format-tools [_ tools] (build-tools tools))
  (build-prompt [_ opts] (build opts)))

(defn make [name cfg]
  (->MessagesAPI name (cond-> cfg
                         (not (contains? cfg :stream-supports-tool-calls))
                         (assoc :stream-supports-tool-calls false))))

;; endregion ^^^^^ Public API ^^^^^

(ns isaac.llm.api.grover
  "Built-in test Api stub. Grover tries his best but isn't very sharp.
   Default mode: echoes the last user message content.
   Scripted mode: consumes pre-queued responses in order."
  (:require
    [isaac.bridge.cancellation :as bridge]
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.followup :as followup]
    [isaac.llm.prompt.builder :as prompt]))

;; region ----- Response Queue -----

(defonce ^:private queue (atom []))
(defonce ^:private delay-enabled* (atom false))
(defonce ^:private delay-started* (atom nil))
(defonce ^:private delay-release* (atom nil))
(defonce ^:private delay-complete* (atom nil))
(defonce ^:private last-request* (atom nil))
(defonce ^:private last-provider-request* (atom nil))
(defonce ^:private provider-requests* (atom []))
(defonce ^:private wait-gates* (atom {}))

(defn enqueue! [responses]
  (swap! queue into responses))

(defn reset-queue! []
  (reset! queue [])
  (reset! delay-enabled* false)
  (reset! delay-started* nil)
  (reset! delay-release* nil)
  (reset! delay-complete* nil)
  (reset! last-request* nil)
  (reset! last-provider-request* nil)
  (reset! provider-requests* [])
  (reset! wait-gates* {}))

(defn enable-delay! []
  (reset! delay-enabled* true))

(defn set-delay-ms! [delay-ms]
  (reset! delay-enabled* (pos? delay-ms)))

(defn last-request []
  @last-request*)

(defn last-provider-request []
  @last-provider-request*)

(defn provider-requests []
  @provider-requests*)

(defn clear-provider-requests! []
  (reset! last-provider-request* nil)
  (reset! provider-requests* []))

(defn waiting? [session-key]
  (contains? @wait-gates* session-key))

(defn release-wait! [session-key]
  (some-> (get @wait-gates* session-key) (deliver true)))

(defn- dequeue! []
  (let [resp (first @queue)]
    (when resp (swap! queue subvec 1))
    resp))

(defn- await-promise [atom*]
  (let [p @atom*]
    (when p @p)))

(defn await-delay-start    [] (await-promise delay-started*))
(defn release-delay!       [] (some-> @delay-release* (deliver true)))
(defn await-delay-complete [] (await-promise delay-complete*))

(defn- maybe-delay! [session-key]
  (when @delay-enabled*
    (let [started  (promise)
          release  (promise)
          complete (promise)]
      (reset! delay-started* started)
      (reset! delay-release* release)
      (reset! delay-complete* complete)
      (deliver started true)
      (or (when (nil? session-key)
            @release)
          (loop []
            (cond
              (realized? release)
              @release

              (bridge/cancelled? session-key)
              :cancelled

              :else
              (let [result (deref release 1 ::timeout)]
                (if (= ::timeout result)
                  (recur)
                  result)))))
      (deliver complete true)
      (when (bridge/cancelled? session-key)
        {:error :cancelled}))))

(defn- maybe-wait! [session-key]
  (let [release (promise)]
    (swap! wait-gates* assoc session-key release)
    (try
      (loop []
        (cond
          (realized? release)
          @release

          (bridge/cancelled? session-key)
          :cancelled

          :else
          (let [result (deref release 1 ::timeout)]
            (if (= ::timeout result)
              (recur)
              result))))
      (finally
        (swap! wait-gates* dissoc session-key)))
    (when (bridge/cancelled? session-key)
      {:error :cancelled})))

;; endregion ^^^^^ Response Queue ^^^^^

;; region ----- Response Building -----

(def ^:private token-counts {:prompt_eval_count 25 :eval_count 12})

(defn- echo-response [messages model]
  (let [last-user (->> messages
                       (filter #(= "user" (:role %)))
                       last
                       :content)]
    (merge {:model   model
            :message {:role "assistant" :content (or last-user "...")}
            :done    true
            :done_reason "stop"}
           token-counts)))

(defn- scripted-response [scripted model]
  (let [resp-model     (if (contains? scripted :model) (:model scripted) model)
        input-tokens   (or (get-in scripted [:usage :input_tokens]) (:prompt_eval_count scripted) (:prompt_tokens scripted) (:input_tokens scripted) (:usage.input_tokens scripted) (:prompt_eval_count token-counts))
        output-tokens  (or (get-in scripted [:usage :output_tokens]) (:eval_count scripted) (:completion_tokens scripted) (:output_tokens scripted) (:usage.output_tokens scripted) (:eval_count token-counts))
        token-overrides {:prompt_eval_count input-tokens
                         :eval_count        output-tokens}
        metadata       (cond-> {}
                         (:reasoning scripted) (assoc :reasoning (:reasoning scripted))
                         (:usage scripted)     (assoc :usage (:usage scripted)))]
    (cond
      (= "exception" (:type scripted))
      (throw (Exception. (or (:content scripted) "grover exception")))

      (= "error" (:type scripted))
      {:error :llm-error :message (:content scripted) :model resp-model}

      (:tool_call scripted)
      (merge {:model   resp-model
              :message {:role       "assistant"
                        :content    ""
                        :tool_calls [{:function {:name      (:tool_call scripted)
                                                  :arguments (:arguments scripted)}}]}
              :done    true
              :done_reason "stop"}
             metadata
              token-counts
              token-overrides)

      :else
      (merge {:model   resp-model
              :message {:role "assistant" :content (:content scripted)}
              :done    true
              :done_reason "stop"}
              metadata
               token-counts
               token-overrides))))

(defn- context-window-error [request cfg]
  (let [enforce?       (:enforce-context-window cfg)
        context-window (:context-window cfg)]
    (when (and enforce? context-window (> (api/estimate-tokens request) context-window))
      {:error :llm-error :message "context length exceeded" :model (:model request)})))

(defn- provider-response [body provider-config]
  (or (context-window-error body provider-config)
      (let [model    (:model body)
            scripted (dequeue!)]
        (if scripted
          (scripted-response scripted model)
          (echo-response (or (:messages body) (:input body)) model)))))

(defn- capture-provider-request! [provider url headers body]
  (let [request {:provider provider
                 :url      url
                 :headers  headers
                 :body     body}]
    (reset! last-provider-request* request)
    (swap! provider-requests* conj request)))

(defn- chat-completions-json [response]
  {:choices [{:message {:role    "assistant"
                        :content (get-in response [:message :content])}}]
   :model   (:model response)
   :usage   {:prompt_tokens (:prompt_eval_count response)
             :completion_tokens (:eval_count response)}})

(defn- messages-json [response]
  (let [tool-call (first (get-in response [:message :tool_calls]))
        content   (cond-> [{:type "text" :text (or (get-in response [:message :content]) "")}]
                    tool-call (conj {:type  "tool_use"
                                     :id    (or (:id tool-call) "tc_grover")
                                     :name  (get-in tool-call [:function :name])
                                     :input (get-in tool-call [:function :arguments])}))]
    {:content     content
     :model       (:model response)
     :stop_reason (if tool-call "tool_use" "end_turn")
     :usage       (merge {:input_tokens  (:prompt_eval_count response)
                          :output_tokens (:eval_count response)}
                         (:usage response))}))

(defn- responses-json [response]
  {:output [{:type    "message"
             :role    "assistant"
             :content [{:type "output_text" :text (get-in response [:message :content])}]}]
   :model  (:model response)
   :usage  (merge {:input_tokens (:prompt_eval_count response)
                   :output_tokens (:eval_count response)}
                  (:usage response))
   :reasoning (:reasoning response)})

(defn- function-call-item [response]
  (let [tool-call (first (get-in response [:message :tool_calls]))]
    {:id   (or (:id tool-call) "fc_grover")
     :type "function_call"
     :name (get-in tool-call [:function :name])}))

(defn- function-call-arguments [response]
  (let [tool-call (first (get-in response [:message :tool_calls]))
        args      (get-in tool-call [:function :arguments])]
    (if (string? args) args (json/generate-string args))))

(defn post-json!
  [provider url headers body]
  (capture-provider-request! provider url headers body)
  (let [response (provider-response body nil)]
    (cond
      (str/ends-with? url "/responses") (responses-json response)
      (str/ends-with? url "/messages")  (messages-json response)
      :else                             (chat-completions-json response))))

(defn- content-chunks [content]
  (cond
    (vector? content) content
    (seq content)     (str/split content #"(?<=\s)")
    :else             [""]))

(defn- reduce-provider-events [events on-chunk process-event initial]
  (reduce (fn [acc evt]
            (on-chunk evt)
            (process-event evt acc))
          initial
          events))

(defn post-sse!
  [provider url headers body on-chunk process-event initial]
  (capture-provider-request! provider url headers body)
  (let [response (provider-response body nil)]
    (if (:error response)
      response
      (if (str/ends-with? url "/responses")
        (let [tool-call-item (function-call-item response)
              events (if-let [_tool-call (first (get-in response [:message :tool_calls]))]
                       [{:type "response.output_item.added"
                         :item tool-call-item}
                        {:type    "response.function_call_arguments.delta"
                         :item_id (:id tool-call-item)
                         :delta   (function-call-arguments response)}
                        {:type    "response.function_call_arguments.done"
                         :item_id (:id tool-call-item)}
                         {:type     "response.completed"
                          :response (cond-> {:model (:model response)
                                             :usage (merge {:input_tokens  (:prompt_eval_count response)
                                                            :output_tokens (:eval_count response)}
                                                           (:usage response))}
                                      (:reasoning response) (assoc :reasoning (:reasoning response)))}]
                       (concat (map (fn [chunk]
                                      {:type "response.output_text.delta"
                                       :delta chunk})
                                    (content-chunks (get-in response [:message :content])))
                                [{:type     "response.completed"
                                  :response (cond-> {:model (:model response)
                                                     :usage (merge {:input_tokens  (:prompt_eval_count response)
                                                                    :output_tokens (:eval_count response)}
                                                                   (:usage response))}
                                              (:reasoning response) (assoc :reasoning (:reasoning response)))}]))]
          (reduce-provider-events events on-chunk process-event initial))
        (let [events (concat (map (fn [chunk]
                                    {:model   (:model response)
                                     :choices [{:delta {:content chunk}}]})
                                  (content-chunks (get-in response [:message :content])))
                             [{:usage   {:prompt_tokens     (:prompt_eval_count response)
                                         :completion_tokens (:eval_count response)}
                               :choices [{:delta {}}]}])]
          (reduce-provider-events events on-chunk process-event initial))))))

;; endregion ^^^^^ Response Building ^^^^^

;; region ----- Public API (matches ollama interface) -----

(defn- boolean-option [value default]
  (cond
    (nil? value)     default
    (boolean? value) value
    (string? value)  (not (#{"false" "0" "no" "off"} (str/lower-case value)))
    :else            (boolean value)))

(defn- stream-supports-tool-calls? [cfg]
  (boolean-option (:stream-supports-tool-calls cfg) true))

(defn chat
  "Synchronous chat. Returns a response map instantly."
  [request provider-name cfg]
  (reset! last-request* request)
  (let [session-key  (:session-key cfg)
        delayed?     @delay-enabled*
        delay-error  (when delayed? (maybe-delay! session-key))
        window-error (context-window-error request cfg)]
    (or delay-error
        window-error
        (let [model    (:model request)
              scripted (dequeue!)]
          (if scripted
            (or (when (:wait scripted)
                  (maybe-wait! session-key))
                (scripted-response scripted model))
            (echo-response (:messages request) model))))))

(defn chat-stream
  "Streaming chat. Calls on-chunk with synthetic chunks, returns final."
  [request on-chunk provider-name cfg]
  (let [response (chat request provider-name cfg)]
    (if (:error response)
      response
      (let [supports-tool-calls? (stream-supports-tool-calls? cfg)
            content              (get-in response [:message :content])
            words                (cond
                                   (vector? content) content
                                   (seq content)     (str/split content #"(?<=\s)")
                                   :else             [""])]
        ;; Emit word-by-word chunks
        (doseq [w words]
          (on-chunk {:message {:role "assistant" :content w} :done false}))
        ;; Final chunk
        (let [final-content (if (vector? content) (apply str content) content)
              final         (cond-> (-> response
                                        (assoc-in [:message :content] final-content)
                                        (assoc :done true))
                              (not supports-tool-calls?) (update :message dissoc :tool_calls))]
          (on-chunk final)
          final)))))

(defn followup-messages
  "Build the next iteration's :messages vector for the Grover test provider.
   Mirrors Ollama's wire shape (raw tool_calls on assistant, role=tool replies)."
  [request response tool-calls tool-results]
  (followup/raw-tool-call-followup-messages
    request
    {:role       "assistant"
     :content    (get-in response [:message :content])
     :tool_calls (get-in response [:message :tool_calls])}
    tool-calls
    tool-results))

(deftype GroverAPI [provider-name cfg]
  api/Api
  (chat [_ req] (chat req provider-name cfg))
  (chat-stream [_ req on-chunk] (chat-stream req on-chunk provider-name cfg))
  (followup-messages [_ req resp tcs trs] (followup-messages req resp tcs trs))
  (config [_] cfg)
  (display-name [_] provider-name)
  (format-tools [_ tools] (when (seq tools) (mapv api/wrapped-function-tool tools)))
  (build-prompt [_ opts] (prompt/build opts)))

(defn make [name cfg]
  (->GroverAPI name cfg))

;; endregion ^^^^^ Public API ^^^^^

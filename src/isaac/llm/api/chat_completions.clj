(ns isaac.llm.api.chat-completions
  "OpenAI Chat Completions API adapter — non-oauth providers (openai, grok).
   Uses /chat/completions endpoint with Bearer API-key auth."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.effort :as effort]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.api.openai.shared :as shared]
    [isaac.llm.http :as llm-http]
    [isaac.llm.prompt.builder :as prompt]))

(defn- extract-tool-calls [tool-calls]
  (when (seq tool-calls)
    (mapv (fn [tc]
            {:type      "toolCall"
             :id        (:id tc)
             :name      (get-in tc [:function :name])
             :arguments (let [args (get-in tc [:function :arguments])]
                          (if (string? args)
                            (json/parse-string args true)
                            args))})
          tool-calls)))

(defn process-sse-event
  "Accumulate an OpenAI Chat Completions SSE event into the running state."
  [data accumulated]
  (let [delta (get-in data [:choices 0 :delta])]
    (cond-> accumulated
      (:content delta) (update :content str (:content delta))
      (:model data)    (assoc :model (:model data))
      (:usage data)    (assoc :usage (:usage data)))))

(defn- chat-with-completions-api [config base-url headers request]
  (let [url     (str base-url "/chat/completions")
        request (if-let [level (effort/effort->string (:effort request))]
                  (-> request (assoc :reasoning_effort level) (dissoc :effort))
                  (dissoc request :effort))
        resp    (llm-http/post-json! url headers request (shared/llm-http-opts config))]
    (if (:error resp)
      resp
      (let [choice     (first (:choices resp))
            msg        (:message choice)
            tool-calls (extract-tool-calls (:tool_calls msg))
            usage      (shared/parse-usage (:usage resp))]
        {:message    (cond-> {:role "assistant" :content (or (:content msg) "")}
                             (seq tool-calls) (assoc :tool_calls (mapv (fn [tc]
                                                                          {:function {:name      (:name tc)
                                                                                      :arguments (:arguments tc)}})
                                                                        tool-calls)))
         :model      (:model resp)
         :tool-calls tool-calls
         :usage      usage
         :_headers   headers}))))

(defn- chat-stream-with-completions-api [config base-url headers request on-chunk]
  (let [url     (str base-url "/chat/completions")
        request (if-let [level (effort/effort->string (:effort request))]
                  (-> request (assoc :reasoning_effort level) (dissoc :effort))
                  (dissoc request :effort))
        body    (assoc request :stream true)
        initial {:role "assistant" :content "" :model nil :usage {}}
        result  (llm-http/post-sse! url headers body on-chunk process-sse-event initial (shared/llm-http-opts config))]
    (if (:error result)
      result
      {:message  {:role "assistant" :content (:content result)}
       :model    (:model result)
       :usage    (shared/parse-usage (:usage result))
       :_headers headers})))

(defn chat
  "Send a non-streaming Chat Completions request."
  [request provider-name cfg]
  (let [base-url (shared/provider-base-url cfg)
        auth-err (shared/missing-auth-error provider-name cfg)]
    (if auth-err
      auth-err
      (chat-with-completions-api cfg base-url (shared/auth-headers provider-name cfg) request))))

(defn chat-stream
  "Send a streaming Chat Completions request via SSE."
  [request on-chunk provider-name cfg]
  (let [base-url (shared/provider-base-url cfg)
        auth-err (shared/missing-auth-error provider-name cfg)]
    (if auth-err
      auth-err
      (chat-stream-with-completions-api cfg base-url (shared/auth-headers provider-name cfg) request on-chunk))))

(defn followup-messages
  "Build the next iteration's :messages vector for Chat Completions."
  [request response tool-calls tool-results]
  (shared/followup-messages request response tool-calls tool-results))

(deftype ChatCompletionsAPI [provider-name cfg]
  api/Api
  (chat [_ req] (chat req provider-name cfg))
  (chat-stream [_ req on-chunk] (chat-stream req on-chunk provider-name cfg))
  (followup-messages [_ req resp tcs trs] (followup-messages req resp tcs trs))
  (config [_] cfg)
  (display-name [_] provider-name)
  (format-tools [_ tools] (when (seq tools) (mapv api/wrapped-function-tool tools)))
  (build-prompt [_ opts] (prompt/build (assoc opts :filter-fn prompt/filter-messages-openai))))

(defn make [name config]
  (->ChatCompletionsAPI name config))

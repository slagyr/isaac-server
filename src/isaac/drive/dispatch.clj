(ns isaac.drive.dispatch
  (:require
    [isaac.llm.api.protocol :as api]
    [isaac.llm.registry :as registry]
    [isaac.llm.tool-loop :as tool-loop]
    [isaac.logger :as log]))

(def built-in-providers registry/built-in-providers)

(defonce ^:private last-request* (atom nil))

(defn last-request []
  @last-request*)

(defn clear-last-request! []
  (reset! last-request* nil))

(defn- response-preview [result]
  (let [content    (or (get-in result [:message :content])
                       (get-in result [:response :message :content]))
        tool-calls (or (get-in result [:message :tool_calls])
                       (get-in result [:response :message :tool_calls]))]
    (cond-> {}
      (string? content) (assoc :content-chars (count content))
      tool-calls (assoc :tool-calls-count (count tool-calls)))))

(defn- log-dispatch-result [provider result error-event response-event]
  (if (:error result)
    (log/error error-event :provider provider :error (:error result) :status (:status result))
    (log/debug response-event (merge {:provider provider :model (:model result)}
                                     (response-preview result))))
  result)

(defn dispatch-chat [p request]
  (let [name (api/display-name p)]
    (reset! last-request* request)
    (log/debug :chat/request :provider name :model (:model request))
    (log-dispatch-result name (api/chat p request) :chat/error :chat/response)))

(defn dispatch-chat-stream [p request on-chunk]
  (let [name (api/display-name p)]
    (reset! last-request* request)
    (log/debug :chat/stream-request :provider name :model (:model request))
    (log-dispatch-result name (api/chat-stream p request on-chunk)
                         :chat/stream-error :chat/stream-response)))

(defn dispatch-chat-with-tools
  "Run a tool-call loop for this api. Composed from Api/chat
   and Api/followup-messages."
  [p request tool-fn]
  (let [name (api/display-name p)]
    (reset! last-request* request)
    (log/debug :chat/request-with-tools :provider name :model (:model request))
    (log-dispatch-result name
                         (tool-loop/run #(api/chat p %)
                                        #(api/followup-messages p %1 %2 %3 %4)
                                         request
                                         tool-fn)
                         :chat/error :chat/response)))

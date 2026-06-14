(ns isaac.llm.api.ollama
  (:require
    [isaac.llm.api.protocol :as api]
    [isaac.llm.followup :as followup]
    [isaac.llm.http :as llm-http]
    [isaac.llm.prompt.builder :as prompt]))

;; region ----- Effort Translation -----

(defn- effort->think [effort think-mode]
  (case (or think-mode :bool)
    :bool   (when (some? effort) (pos? effort))
    :levels (cond
              (or (nil? effort) (zero? effort)) nil
              (<= 1 effort 3)                   "low"
              (<= 4 effort 6)                   "medium"
              :else                             "high")))

;; endregion ^^^^^ Effort Translation ^^^^^

;; region ----- Public API -----

(def ^:private default-headers {"Content-Type" "application/json"})

(def ^:private default-timeout 300000)

(defn- http-opts [cfg]
  (cond-> {:timeout (or (:timeout cfg) default-timeout)}
    (:session-key cfg)       (assoc :session-key (:session-key cfg))
    (:simulate-provider cfg) (assoc :simulate-provider (:simulate-provider cfg))))

(defn chat
  "Send a chat request to Ollama. Returns the parsed response or error map."
  [request provider-name cfg]
  (let [url   (str (or (:base-url cfg) "http://localhost:11434") "/api/chat")
        think (effort->think (:effort request) (:think-mode cfg))
        body  (cond-> (-> request (dissoc :effort) (assoc :stream false))
                (some? think) (assoc :think think))]
    (llm-http/post-json! url default-headers body (http-opts cfg))))

(defn chat-stream
  "Send a streaming chat request to Ollama. Calls on-chunk for each chunk.
   Returns the final response or error map."
  [request on-chunk provider-name cfg]
  (let [url   (str (or (:base-url cfg) "http://localhost:11434") "/api/chat")
        think (effort->think (:effort request) (:think-mode cfg))
        body  (cond-> (-> request (dissoc :effort) (assoc :stream true))
                (some? think) (assoc :think think))]
    (llm-http/post-ndjson-stream! url default-headers body on-chunk (http-opts cfg))))

;; endregion ^^^^^ Public API ^^^^^

;; region ----- Tool Call Loop -----

(defn followup-messages
  "Build the next iteration's :messages vector for Ollama's /api/chat.
   Assistant message carries the raw tool_calls; tool responses are role=tool."
  [request response tool-calls tool-results]
  (followup/raw-tool-call-followup-messages
    request
    {:role       "assistant"
     :content    (or (get-in response [:message :content]) "")
     :tool_calls (get-in response [:message :tool_calls])}
    tool-calls
    tool-results))

(deftype OllamaAPI [provider-name cfg]
  api/Api
  (chat [_ req] (chat req provider-name cfg))
  (chat-stream [_ req on-chunk] (chat-stream req on-chunk provider-name cfg))
  (followup-messages [_ req resp tcs trs] (followup-messages req resp tcs trs))
  (config [_] cfg)
  (display-name [_] provider-name)
  (format-tools [_ tools] (when (seq tools) (mapv api/wrapped-function-tool tools)))
  (build-prompt [_ opts] (prompt/build opts)))

(defn make [name cfg]
  (->OllamaAPI name cfg))

;; endregion ^^^^^ Tool Call Loop ^^^^^

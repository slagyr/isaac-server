(ns isaac.util.jsonrpc
  "Generic JSON-RPC 2.0 message builders, predicates, stream I/O,
   and server-side handler dispatch."
  (:require
    [cheshire.core :as json])
  (:import
    (clojure.lang ArityException ExceptionInfo)))

(def VERSION "2.0")

(def PARSE_ERROR -32700)
(def INVALID_REQUEST -32600)
(def METHOD_NOT_FOUND -32601)
(def INVALID_PARAMS -32602)
(def INTERNAL_ERROR -32603)

;; region ----- Message constructors -----

(defn request
  ([id method]
   (request id method nil nil))
  ([id method params]
   (request id method params nil))
  ([id method params prompt]
   (cond-> {:jsonrpc VERSION
            :id id
            :method method}
     (some? params) (assoc :params params)
     (some? prompt) (assoc-in [:params :prompt] prompt))))

(defn notification
  ([method]
   (notification method nil))
  ([method params]
   (cond-> {:jsonrpc VERSION
            :method method}
     (some? params) (assoc :params params))))

(defn result [id value]
  {:jsonrpc VERSION
   :id id
   :result value})

(defn method-not-found [id]
  {:jsonrpc VERSION
   :id id
   :error {:code METHOD_NOT_FOUND
           :message "Method not found"}})

(defn invalid-params [id]
  {:jsonrpc VERSION
   :id id
   :error {:code INVALID_PARAMS
           :message "Invalid params"}})

(defn parse-error []
  {:jsonrpc VERSION
   :id nil
   :error {:code PARSE_ERROR
           :message "Parse error"}})

(defn invalid-request [id]
  {:jsonrpc VERSION
   :id id
   :error {:code INVALID_REQUEST
           :message "Invalid Request"}})

(defn internal-error [id]
  {:jsonrpc VERSION
   :id id
   :error {:code INTERNAL_ERROR
           :message "Internal error"}})

;; endregion

;; region ----- Dispatch -----


;; region ----- Newline-delimited line builders -----

(defn request-line
  ([id method]
   (request-line id method nil nil))
  ([id method params]
   (request-line id method params nil))
  ([id method params prompt]
   (str (json/generate-string (request id method params prompt)) "\n")))

(defn result-line [id value]
  (str (json/generate-string (result id value)) "\n"))

(defn notification-line
  ([method]
   (notification-line method nil))
  ([method params]
   (str (json/generate-string (notification method params)) "\n")))

;; endregion

;; region ----- Predicates on parsed messages -----

(defn notification? [message]
  (not (contains? message :id)))

(defn- has-keys? [message & keys]
  (and (map? message) (every? #(contains? message %) keys)))

(defn result? [message] (has-keys? message :id :result))
(defn error?  [message] (has-keys? message :jsonrpc :error))

;; endregion

;; region ----- Stream I/O -----

(def ^:private parse-error-marker ::parse-error)

(defn parse-message
  "Parse a single JSON-RPC line. Returns the message map on success or
   ::parse-error on failure."
  [line]
  (try
    (json/parse-string line true)
    (catch Exception _
      parse-error-marker)))

(defn parse-error? [message]
  (= parse-error-marker message))

(defn write-message!
  "Write a message as a single newline-terminated JSON line. `writer`
   may be either a java.io.Writer or a function (str -> any) for
   non-blocking transports like websockets."
  [writer message]
  (let [line (json/generate-string message)]
    (if (ifn? writer)
      (writer line)
      (do
        (.write writer line)
        (.write writer "\n")
        (.flush writer)))))

;; endregion

;; region ----- Dispatch -----

(defn- envelope? [value]
  (and (map? value)
       (or (contains? value :response)
           (contains? value :notifications))))

(defn- normalize-envelope [id notify? envelope]
  (let [response      (when-not notify?
                        (or (:response envelope)
                            (when (contains? envelope :result)
                              (result id (:result envelope)))))
        notifications (vec (or (:notifications envelope) []))]
    (cond
      (and response (seq notifications)) {:response response :notifications notifications}
      response response
      (seq notifications) {:notifications notifications}
      :else nil)))

(defn- invoke-handler [handler params message]
  (try
    (handler params message)
    (catch ArityException _
      (handler params))))

(defn- invalid-params-exception? [error]
  (= :invalid-params (:type (ex-data error))))

(defn- maybe-normalize-envelope [result message]
  (when (envelope? result)
    (normalize-envelope (:id message) (notification? message) result)))

(defn maybe-result [message handler-result]
  (when-not (notification? message)
    (result (:id message) handler-result)))

(defn dispatch [handlers message]
  (let [handler (get handlers (:method message))]
    (cond
      (not (map? message))
      (invalid-request nil)

      (nil? handler)
      (when-not (notification? message)
        (method-not-found (:id message)))

      :else
      (let [handler-result (try
                              (invoke-handler handler (:params message) message)
                              (catch IllegalArgumentException _
                                (invalid-params (:id message))))]
        (or (maybe-normalize-envelope handler-result message)
            (when (result? handler-result) handler-result)
            (when (error? handler-result) handler-result)
            (maybe-result message handler-result))))))

(defn handle-line [handlers line]
  (let [message (parse-message line)]
    (if (parse-error? message)
      (parse-error)
      (try
        (dispatch handlers message)
        (catch ExceptionInfo e
          (if (invalid-params-exception? e)
            {:jsonrpc VERSION
             :id      (:id message)
             :error   {:code    INVALID_PARAMS
                       :message (or (ex-message e) "Invalid params")}}
            (internal-error (:id message))))
        (catch Exception _
          (internal-error (:id message)))))))

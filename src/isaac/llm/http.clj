;; mutation-tested: 2026-05-06
(ns isaac.llm.http
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.llm.api.grover :as grover]
    [isaac.logger :as log]
    [isaac.bridge.cancellation :as bridge])
  (:import (java.net ConnectException)))

(def ^:private pending ::pending)
(defonce ^:private outbound-requests* (atom []))

(defn clear-outbound-requests! []
  (reset! outbound-requests* []))

(defn outbound-requests []
  @outbound-requests*)

(defn- simulated-provider? [opts]
  (boolean (:simulate-provider opts)))

(defn- body-chars [body]
  (count (pr-str body)))

(defn- body-keys [body]
  (when (map? body)
    (sort (keys body))))

(defn- header-keys [headers]
  (when (seq headers)
    (sort (keys headers))))

(defn- cancellable-call [session-key f]
  (let [runner (future (f))]
    (loop []
      (let [result (deref runner 50 pending)]
        (if (= pending result)
          (if (bridge/cancelled? session-key)
            (do
              (future-cancel runner)
              {:error :cancelled})
            (recur))
          result)))))

(defn- cancelled-result [session-key]
  (when (bridge/cancelled? session-key)
    {:error :cancelled}))

(defn- close-body! [body]
  (try
    (.close body)
    (catch Exception _ nil)))

(defn- register-cancel-close! [session-key body]
  (let [closed? (atom false)
        close!  #(when (compare-and-set! closed? false true)
                   (close-body! body))]
    (bridge/on-cancel! session-key close!)
    close!))

(defn- log-http-request! [url headers body opts stream?]
  (swap! outbound-requests* conj {:body body :headers headers :stream stream? :url url})
  (log/debug :llm/http-request
             :body-chars (body-chars body)
             :body-keys  (body-keys body)
             :header-keys (header-keys headers)
             :session-key (:session-key opts)
             :simulate-provider (:simulate-provider opts)
             :stream stream?
             :timeout (:timeout opts)
             :url url))

(defn- log-http-response! [url headers _body stream? status response-body]
  (log/debug :llm/http-response
             :header-keys (header-keys headers)
             :response-body-chars (body-chars response-body)
             :response-body-keys  (body-keys response-body)
             :status status
             :stream stream?
             :url url))

(defn- log-http-error! [url headers body stream? result]
  (log/error :llm/http-error
             :body-chars (body-chars body)
             :body-keys  (body-keys body)
             :error (:error result)
             :header-keys (header-keys headers)
             :message (:message result)
             :response-body-chars (body-chars (:body result))
             :response-body-keys  (body-keys (:body result))
             :status (:status result)
             :stream stream?
             :url url))

(defn post-json!
  "POST JSON to a URL with headers. Returns parsed response or error map.
   Checks HTTP status codes: 401 -> :auth-failed, 4xx/5xx -> :api-error."
  [url headers body & [{:keys [session-key simulate-provider timeout] :or {timeout 120000}}]]
  (if (simulated-provider? {:simulate-provider simulate-provider})
    (grover/post-json! simulate-provider url headers body)
    (cancellable-call session-key
                      #(try
                         (log-http-request! url headers body {:session-key session-key :simulate-provider simulate-provider :timeout timeout} false)
                         (let [resp   (http/post url {:body    (json/generate-string body)
                                                      :headers headers
                                                      :timeout timeout
                                                      :throw   false})
                               parsed (json/parse-string (:body resp) true)]
                           (if (>= (:status resp) 400)
                             (let [result {:error    (if (= 401 (:status resp)) :auth-failed :api-error)
                                           :status   (:status resp)
                                           :body     parsed
                                           :_headers headers}]
                               (log-http-error! url headers body false result)
                               result)
                             (do
                               (log-http-response! url headers body false (:status resp) parsed)
                               parsed)))
                        (catch ConnectException _
                          (let [result {:error :connection-refused :message (str "Could not connect to " url)}]
                            (log-http-error! url headers body false result)
                            result))
                        (catch IllegalArgumentException _
                          (let [result {:error :connection-refused :message (str "Could not connect to " url)}]
                            (log-http-error! url headers body false result)
                            result))
                        (catch Exception e
                          (let [result {:error :unknown :message (.getMessage e)}]
                            (log-http-error! url headers body false result)
                            result))))))

(defn process-sse-lines
  "Process SSE lines, calling on-chunk and accumulating via process-event.
   Returns the final accumulated value. Pure data transformation over lines."
  [lines on-chunk process-event initial]
  (reduce
    (fn [accumulated line]
      (cond
        (= "[DONE]" (str/trim (subs line 6)))
        (reduced accumulated)

        :else
        (let [data (json/parse-string (subs line 6) true)]
          (on-chunk data)
          (process-event data accumulated))))
    initial
    (filter #(str/starts-with? % "data: ") lines)))

(defn post-sse!
  "POST and process SSE stream. Calls on-chunk for each parsed event.
   process-event is (fn [data accumulated] -> accumulated) for custom accumulation."
  [url headers body on-chunk process-event initial & [{:keys [session-key simulate-provider timeout] :or {timeout 120000}}]]
  (if (simulated-provider? {:simulate-provider simulate-provider})
    (grover/post-sse! simulate-provider url headers body on-chunk process-event initial)
    (cancellable-call session-key
                      #(try
                         (log-http-request! url headers body {:session-key session-key :simulate-provider simulate-provider :timeout timeout} true)
                         (let [resp (http/post url {:body    (json/generate-string body)
                                                    :headers headers
                                                    :timeout timeout
                                                    :as      :stream
                                                    :throw   false})]
                           (if (>= (:status resp) 400)
                             (let [result {:error    (if (= 401 (:status resp)) :auth-failed :api-error)
                                           :status   (:status resp)
                                           :body     (try (json/parse-string (slurp (:body resp)) true)
                                                          (catch Exception _ nil))
                                           :_headers headers}]
                               (log-http-error! url headers body true result)
                               result)
                             (let [body-stream (:body resp)
                                   close!      (register-cancel-close! session-key body-stream)]
                               (with-open [rdr (io/reader body-stream)]
                                 (let [result (process-sse-lines (line-seq rdr) on-chunk process-event initial)]
                                   (close!)
                                   (or (cancelled-result session-key)
                                       (do
                                         (log-http-response! url headers body true (:status resp) result)
                                         result)))))))
                         (catch ConnectException _
                           (let [result {:error :connection-refused :message (str "Could not connect to " url)}]
                             (log-http-error! url headers body true result)
                             result))
                         (catch Exception e
                           (or (cancelled-result session-key)
                               (let [result {:error :unknown :message (.getMessage e)}]
                                 (log-http-error! url headers body true result)
                                 result)))))))

(defn post-ndjson-stream!
  "POST and process newline-delimited JSON stream (Ollama-style).
   Calls on-chunk for each parsed line. Returns the final chunk."
  [url headers body on-chunk & [{:keys [session-key timeout] :or {timeout 120000}}]]
  (cancellable-call session-key
                    #(try
                       (let [resp (http/post url {:body    (json/generate-string body)
                                                  :headers headers
                                                  :timeout timeout
                                                  :as      :stream
                                                  :throw   false})]
                         (if (>= (:status resp) 400)
                           {:error    (if (= 401 (:status resp)) :auth-failed :api-error)
                            :status   (:status resp)
                            :body     (try (json/parse-string (slurp (:body resp)) true)
                                           (catch Exception _ nil))
                            :_headers headers}
                           (let [body-stream (:body resp)
                                 close!      (register-cancel-close! session-key body-stream)]
                             (with-open [rdr (io/reader body-stream)]
                               (let [result (loop [last-chunk nil]
                                              (if-let [line (.readLine rdr)]
                                                (if (str/blank? line)
                                                  (recur last-chunk)
                                                  (let [chunk (json/parse-string line true)]
                                                    (on-chunk chunk)
                                                    (recur chunk)))
                                                last-chunk))]
                                 (close!)
                                 (or (cancelled-result session-key)
                                     result))))))
                       (catch ConnectException _
                         {:error :connection-refused :message (str "Could not connect to " url)})
                       (catch IllegalArgumentException _
                         {:error :connection-refused :message (str "Could not connect to " url)})
                       (catch Exception e
                         (or (cancelled-result session-key)
                             {:error :unknown :message (.getMessage e)})))))

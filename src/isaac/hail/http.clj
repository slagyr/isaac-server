(ns isaac.hail.http
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [isaac.hail.queue :as queue]))

(defn- read-body [request]
  (let [body (:body request)]
    (cond
      (nil? body)    ""
      (string? body) body
      :else          (slurp body))))

(defn- request-content-type [request]
  (or (get-in request [:headers "content-type"])
      (get-in request [:headers "Content-Type"])
      ""))

(defn- json-content-type? [request]
  (str/includes? (request-content-type request) "application/json"))

(defn- edn-content-type? [request]
  (str/includes? (request-content-type request) "application/edn"))

(defn- response-format [request]
  (if (edn-content-type? request) :edn :json))

(defn- render-body [format body]
  (case format
    :edn  (binding [*print-namespace-maps* false] (pr-str body))
    :json (json/generate-string body)))

(defn- response-content-type [format]
  (case format
    :edn  "application/edn"
    :json "application/json"))

(defn- response [status format body]
  {:status  status
   :headers {"Content-Type" (response-content-type format)}
   :body    (render-body format body)})

(defn- error-response
  ([status format error]
   (error-response status format error nil))
  ([status format error hint]
   (response status format (cond-> {:error error}
                             hint (assoc :hint hint)))))

(defn- parse-body [request]
  (let [body (read-body request)]
    (cond
      (json-content-type? request)
      (try
        (json/parse-string body true)
        (catch Exception _
          ::parse-error))

      (edn-content-type? request)
      (try
        (edn/read-string body)
        (catch Exception _
          ::parse-error))

      :else
      ::unsupported)))

(defn- ->keyword [value]
  (cond
    (keyword? value) value
    (string? value)  (keyword value)
    :else            value))

(defn- keywordize* [values]
  (mapv ->keyword values))

(defn- keyword-set* [values]
  (into #{} (map ->keyword) values))

(defn- normalize-frequency [frequency]
  (let [frequency (some-> frequency walk/keywordize-keys)]
    (cond-> frequency
      (:crew frequency)         (update :crew keywordize*)
      (:session frequency)      (update :session keywordize*)
      (:crew-tags frequency)    (update :crew-tags keyword-set*)
      (:session-tags frequency) (update :session-tags keyword-set*)
      (:reach frequency)        (update :reach ->keyword))))

(defn- direct-addressing? [frequency]
  (and (map? frequency)
       (boolean (some #(contains? frequency %)
                      [:crew :session :crew-tags :session-tags]))))

(defn- has-addressing? [frequency]
  (and (map? frequency)
       (boolean (some #(contains? frequency %)
                      [:band :crew :session :crew-tags :session-tags]))))

(defn- validate-record [record]
  (let [frequency (:frequency record)]
    (cond
      (not (has-addressing? frequency))
      {:error "missing frequency"
       :hint  "include :frequency with at least one field"}

      (and (direct-addressing? frequency)
           (not (contains? frequency :band))
           (str/blank? (:prompt record)))
      {:error "missing prompt"
       :hint  "include :prompt for non-band hails"}

      (and (:reach frequency) (not (direct-addressing? frequency)))
      {:error "invalid reach"
       :hint  "include direct addressing when using :reach"})))

(defn- build-record [payload]
  (cond-> {:frequency (normalize-frequency (:frequency payload))
           :from      :http}
    (contains? payload :payload) (assoc :payload (:payload payload))
    (contains? payload :prompt)  (assoc :prompt (:prompt payload))))

(defn handler [request]
  (let [format  (response-format request)
        payload (parse-body request)]
    (cond
      (= ::unsupported payload)
      (error-response 415 :json "unsupported media type" "use application/json or application/edn")

      (= ::parse-error payload)
      (error-response 400 format "invalid body" "request body could not be parsed")

      :else
      (let [record (build-record payload)]
        (if-let [{:keys [error hint]} (validate-record record)]
          (error-response 400 format error hint)
          (let [record (queue/send! record)]
            {:status  201
             :headers {"Content-Type" (response-content-type format)
                       "Location"     (str "/hail/" (:id record))}
             :body    (render-body format record)}))))))

;; mutation-tested: 2026-05-06
(ns isaac.tool.web-fetch
  (:require
    [babashka.http-client :as http]
    [clojure.string :as str])
  (:import
    [java.net URI]))

(def ^:dynamic *default-limit* 2000)

(def ^:private default-timeout 30000)
(def ^:private max-web-redirects 5)

(defn- web-header [headers name]
  (or (get headers name)
      (get headers (str/lower-case name))
      (get headers (str/capitalize name))))

(defn- allowed-content-type? [content-type]
  (let [content-type (some-> content-type str/lower-case)]
    (or (nil? content-type)
        (str/starts-with? content-type "text/")
        (contains? #{"application/json" "application/xml" "application/xhtml+xml"} content-type))))

(defn- redirect? [status]
  (contains? #{301 302 303 307 308} status))

(defn- absolute-location [url location]
  (str (.resolve (URI. url) location)))

(defn- http-get! [url timeout]
  (http/get url {:timeout          timeout
                 :throw            false
                 :follow-redirects false}))

(defn- fetch-response [url timeout redirects-left]
  (let [response (http-get! url timeout)
        status   (:status response)
        location (web-header (:headers response) "location")]
    (cond
      (and (redirect? status) location (pos? redirects-left))
      (recur (absolute-location url location) timeout (dec redirects-left))

      (and (redirect? status) location)
      {:isError true :error "too many redirects"}

      :else
      response)))

(defn- strip-html [body]
  (-> body
      (str/replace #"(?is)<!--.*?-->" " ")
      (str/replace #"(?is)<script\b[^>]*>.*?</script>" " ")
      (str/replace #"(?is)<style\b[^>]*>.*?</style>" " ")
      (str/replace #"(?is)<[^>]+>" "\n")
      (str/replace #"[ \t\x0B\f\r]+" " ")
      (str/split-lines)
      (->> (map str/trim)
           (remove str/blank?)
           (str/join "\n"))))

(defn- extract-body [body format]
  (if (= "raw" format)
    body
    (strip-html body)))

(defn- truncate-lines [text limit]
  (let [lines      (str/split-lines (or text ""))
        total      (count lines)
        truncated? (and (pos? limit) (> total limit))
        shown      (if (pos? limit) (take limit lines) lines)
        shown      (cond-> (vec shown)
                     truncated? (conj (str "Results truncated. " total " total lines.")))]
    (str/join "\n" shown)))

(defn web-fetch-tool
  "Fetch URL content via HTTP GET.
   Args: url, format, timeout."
  [args]
  (let [args    (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]) args))
        url     (get args "url")
        format  (get args "format")
        timeout (let [value (get args "timeout")]
                  (cond
                    (integer? value) value
                    (string? value)  (parse-long value)
                    :else            nil))]
    (if-not (re-matches #"https?://.+" (or url ""))
      {:isError true :error (str "unsupported URL: " url)}
      (let [response (fetch-response url (or timeout default-timeout) max-web-redirects)]
        (if (:isError response)
          response
          (let [status       (:status response)
                headers      (:headers response)
                content-type (web-header headers "content-type")
                content-type (some-> content-type (str/split #";") first)
                body         (str (:body response))]
            (cond
              (not (allowed-content-type? content-type))
              {:isError true :error (str "binary content-type: " content-type)}

              (>= status 400)
              {:isError true :error (str "HTTP " status) :status status}

              :else
              {:status status
               :result (truncate-lines (extract-body body (or format "text")) *default-limit*)})))))))

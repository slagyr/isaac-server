(ns isaac.mcp-bridge.cli
  "stdio MCP server that proxies JSON-RPC to POST /mcp/turns/{turn-id}."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.api :as cli-api]
    [isaac.cli.common :as cli-common]
    [org.httpkit.client :as http]))

(def PROTOCOL_VERSION "2025-06-18")

(def option-spec
  [[nil "--turn ID" "Turn id registered on the running server"]
   [nil "--server URL" "Base URL of the Isaac HTTP server"]
   [nil "--token TOKEN" "Bearer token (falls back to ISAAC_SERVER_TOKEN)"]
   ["-h" "--help" "Show help"]])

(defn- parse-option-map [raw-args]
  (let [{:keys [options errors]} (tools-cli/parse-opts raw-args option-spec)]
    {:options (->> options
                   (remove (comp nil? val))
                   (into {}))
     :errors  errors}))

(defn- initialize-result []
  {:protocolVersion PROTOCOL_VERSION
   :capabilities    {:tools {}}
   :serverInfo      {:name "isaac" :version "0.1.0"}})

(defn local-handle
  "Handle a parsed JSON-RPC message locally. Returns a response map, or nil
   when the line should be forwarded (or dropped, for notifications)."
  [message]
  (cond
    (not (map? message)) nil

    (= "initialize" (:method message))
    {:jsonrpc "2.0"
     :id      (:id message)
     :result  (initialize-result)}

    (not (contains? message :id))
    nil

    :else nil))

(defn- notification? [message]
  (and (map? message) (not (contains? message :id))))

(defn- parse-line [line]
  (try
    (json/parse-string line true)
    (catch Exception _
      {:jsonrpc "2.0" :id nil :error {:code -32700 :message "Parse error"}})))

(defn- post-url [server turn-id]
  (str (str/replace server #"/+$" "") "/mcp/turns/" turn-id))

(defn- bearer [token]
  (or token (System/getenv "ISAAC_SERVER_TOKEN")))

(defn- post-message! [{:keys [server turn token]} line]
  (let [resp @(http/post (post-url server turn)
                         {:headers {"Content-Type"  "application/json"
                                    "Authorization" (str "Bearer " (bearer token))}
                          :body    line
                          :as      :text})]
    (or (:body resp) "")))

(defn- write-line! [line]
  (when (seq (str/trim (str line)))
    (print (str/trim-newline line))
    (print "\n")
    (flush)))

(defn- handle-line! [opts line]
  (let [parsed (parse-line line)]
    (cond
      (and (map? parsed) (contains? parsed :error) (nil? (:method parsed)))
      (write-line! (json/generate-string parsed))

      (notification? parsed)
      nil

      :else
      (if-let [local (local-handle parsed)]
        (write-line! (json/generate-string local))
        (write-line! (post-message! opts line))))))

(defn run [{:keys [turn server token] :as opts}]
  (cond
    (str/blank? (str turn))
    (do (println "mcp-bridge: --turn is required") 1)

    (str/blank? (str server))
    (do (println "mcp-bridge: --server is required") 1)

    :else
    (let [reader (java.io.BufferedReader. *in*)]
      (loop []
        (when-let [line (.readLine reader)]
          (handle-line! opts line)
          (recur)))
      0)))

(defn run-fn [opts]
  (let [raw-args (or (:_raw-args opts) [])]
    (cli-common/standard-run-fn "mcp-bridge" parse-option-map run opts)))

(defmethod cli-api/run :mcp-bridge [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :mcp-bridge [_id]
  option-spec)

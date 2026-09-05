(ns isaac.server.mcp
  "HTTP surface for the per-turn MCP registry in isaac-agent."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]))

(defn handle-turn
  "Delegate to isaac.mcp.turns/handle. Resolved at call time so the server
   pins the agent SHA that ships the registry."
  [turn-id message]
  (let [handle (requiring-resolve 'isaac.mcp.turns/handle)]
    (handle turn-id message)))

(defn- read-body [body]
  (cond
    (string? body) body
    (nil? body)    ""
    :else          (slurp (io/reader body))))

(defn handle [request]
  (let [turn-id (or (get-in request [:route-params :turn-id])
                    (get-in request [:params :turn-id]))
        message (read-body (:body request))
        result  (handle-turn turn-id message)]
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (json/generate-string (or result {}))}))

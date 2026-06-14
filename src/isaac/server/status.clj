(ns isaac.server.status
  (:require [cheshire.core :as json]))

(defn handle [_request]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (json/generate-string {:status   "ok"
                                   :services {:isaac "running"}})})

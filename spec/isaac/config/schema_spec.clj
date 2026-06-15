(ns isaac.config.schema-spec
  (:require
    [c3kit.apron.schema :as schema]
    [clojure.edn :as edn]
    [speclj.core :refer :all]))

(defn- server-schema []
  (-> "resources/isaac-manifest.edn"
      slurp
      edn/read-string
      (get-in [:isaac.config/schema :server :schema])))

(describe "config schema"

  (it "server conforms"
    (should= {:host "localhost" :port 8080}
             (schema/conform (server-schema) {:host "localhost" :port 8080})))

  (it "server conforms with nested auth token"
    (should= {:host "localhost" :auth {:token "s3cr3t"}}
             (schema/conform (server-schema) {:host "localhost" :auth {:token "s3cr3t"}}))))

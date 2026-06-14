(ns isaac.server.manifest-spec
  (:require
    [clojure.edn :as edn]
    [speclj.core :refer :all]))

(describe "server manifest"

  (it "declares server CLI command contributions"
    (let [commands (->> "resources/isaac-manifest.edn"
                        slurp
                        edn/read-string
                        :isaac/cli
                        keys
                        (map name)
                        set)]
      (should= #{"auth" "config" "crew" "hail" "logs" "prompt" "server" "service" "sessions"}
               commands)))

  (it "is a builtin module"
    (let [manifest (edn/read-string (slurp "resources/isaac-manifest.edn"))]
      (should= :isaac.server (:id manifest))
      (should (true? (:builtin? manifest))))))
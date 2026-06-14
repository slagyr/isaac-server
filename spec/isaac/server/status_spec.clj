(ns isaac.server.status-spec
  (:require
    [cheshire.core :as json]
    [isaac.server.status :as sut]
    [speclj.core :refer :all]))

(describe "Status handler"

  (it "returns HTTP 200"
    (let [response (sut/handle {})]
      (should= 200 (:status response))))

  (it "returns JSON content-type"
    (let [response (sut/handle {})]
      (should= "application/json" (get-in response [:headers "Content-Type"]))))

  (it "returns body with status ok"
    (let [response (sut/handle {})
          body     (json/parse-string (:body response) true)]
      (should= "ok" (:status body))))

  (it "returns services map in body"
    (let [response (sut/handle {})
          body     (json/parse-string (:body response) true)]
      (should-not-be-nil (:services body))))

  (it "reports isaac service as running"
    (let [response (sut/handle {})
          body     (json/parse-string (:body response) true)]
      (should= "running" (get-in body [:services :isaac]))))

  )

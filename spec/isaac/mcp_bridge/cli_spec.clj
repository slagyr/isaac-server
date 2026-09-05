(ns isaac.mcp-bridge.cli-spec
  (:require
    [cheshire.core :as json]
    [isaac.mcp-bridge.cli :as sut]
    [isaac.util.jsonrpc :as jrpc]
    [speclj.core :refer :all]))

(describe "mcp-bridge local handlers"

  (it "answers initialize locally with serverInfo name isaac"
    (let [response (sut/local-handle (jrpc/request 1 "initialize" {:protocolVersion "2025-06-18"}))]
      (should= "isaac" (get-in response [:result :serverInfo :name]))
      (should= 1 (:id response))))

  (it "drops notifications"
    (should-be-nil (sut/local-handle (jrpc/notification "notifications/initialized"))))

  (it "does not answer tools/list locally"
    (should-be-nil (sut/local-handle (jrpc/request 2 "tools/list"))))
  )

(describe "mcp-bridge option parsing"

  (it "parses --turn, --server and --token"
    (let [{:keys [options errors]} (#'sut/parse-option-map ["--turn" "t-stdio"
                                                            "--server" "http://127.0.0.1:6674"
                                                            "--token" "s3cr3t"])]
      (should= [] (or errors []))
      (should= "t-stdio" (:turn options))
      (should= "http://127.0.0.1:6674" (:server options))
      (should= "s3cr3t" (:token options))))
  )
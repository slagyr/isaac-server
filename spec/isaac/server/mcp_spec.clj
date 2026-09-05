(ns isaac.server.mcp-spec
  (:require
    [cheshire.core :as json]
    [isaac.server.mcp :as sut]
    [speclj.core :refer :all]))

(describe "MCP turn route"

  (it "returns 200 JSON-RPC wrapping the handler result for a registered turn"
    (with-redefs [sut/handle-turn (fn [turn-id message]
                                    (should= "t-route" turn-id)
                                    (should= "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}" message)
                                    {:jsonrpc "2.0"
                                     :id      1
                                     :result  {:tools [{:name "exec__run"}]}})]
      (let [response (sut/handle {:request-method :post
                                  :uri            "/mcp/turns/t-route"
                                  :route-params   {:turn-id "t-route"}
                                  :body           "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"})
            body     (json/parse-string (:body response) true)]
        (should= 200 (:status response))
        (should= "application/json" (get-in response [:headers "Content-Type"]))
        (should= "exec__run" (get-in body [:result :tools 0 :name])))))

  (it "returns HTTP 200 with JSON-RPC -32001 for an unknown turn"
    (with-redefs [sut/handle-turn (fn [_turn-id _message]
                                    {:jsonrpc "2.0"
                                     :id      2
                                     :error   {:code -32001 :message "turn not active"}})]
      (let [response (sut/handle {:request-method :post
                                  :uri            "/mcp/turns/t-ghost"
                                  :route-params   {:turn-id "t-ghost"}
                                  :body           "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"}"})
            body     (json/parse-string (:body response) true)]
        (should= 200 (:status response))
        (should= -32001 (get-in body [:error :code])))))

  (it "reads a stream body"
    (with-redefs [sut/handle-turn (fn [_turn-id message]
                                    (should= "{\"jsonrpc\":\"2.0\",\"id\":1}" message)
                                    {:jsonrpc "2.0" :id 1 :result {}})]
      (let [response (sut/handle {:request-method :post
                                  :uri            "/mcp/turns/t-x"
                                  :route-params   {:turn-id "t-x"}
                                  :body           (java.io.ByteArrayInputStream.
                                                    (.getBytes "{\"jsonrpc\":\"2.0\",\"id\":1}" "UTF-8"))})]
        (should= 200 (:status response)))))
  )
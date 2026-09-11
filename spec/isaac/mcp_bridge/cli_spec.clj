(ns isaac.mcp-bridge.cli-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.logger :as log]
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

(describe "mcp-bridge Claude route"

  (it "appends the turn id to the caller-supplied route"
    (should= "http://127.0.0.1:6674/claude/turns/t-route"
             (#'sut/post-url "http://127.0.0.1:6674/claude/turns/" "t-route")))
  )

(describe "mcp-bridge option parsing"

  (it "parses --turn, --server and --token"
    (let [{:keys [options errors]} (#'sut/parse-option-map ["--turn" "t-stdio"
                                                            "--server" "http://127.0.0.1:6674/claude/turns"
                                                            "--token" "s3cr3t"])]
      (should= [] (or errors []))
      (should= "t-stdio" (:turn options))
      (should= "http://127.0.0.1:6674/claude/turns" (:server options))
      (should= "s3cr3t" (:token options))))
  )

(describe "mcp-bridge auth failure on tools/list"

  (it "writes a JSON-RPC unauthorized error, never the plain text Unauthorized"
    (let [out (java.io.StringWriter.)]
      (log/capture-logs
        (with-redefs [org.httpkit.client/post
                      (fn [_url _opts]
                        (future {:status 401 :body "Unauthorized"}))]
          (binding [*out* out]
            (#'sut/handle-line! {:server "http://127.0.0.1:6674" :turn "t-x" :token "wrong"}
                                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")))
        (let [line   (str/trim (str out))
              parsed (json/parse-string line true)
              entry  (first (filter #(= :mcp-bridge/unauthorized (:event %)) @log/captured-logs))]
          (should= 2 (:id parsed))
          (should= -32001 (get-in parsed [:error :code]))
          (should= "unauthorized" (get-in parsed [:error :message]))
          (should-not (str/includes? line "Unauthorized"))
          (should-not-be-nil entry))))))
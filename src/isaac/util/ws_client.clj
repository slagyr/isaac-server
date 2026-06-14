(ns isaac.util.ws-client
  (:require
    [clojure.string :as str]
    [isaac.logger :as log])
  (:import
    (java.net URI)
    (java.net.http HttpClient WebSocket WebSocket$Listener)
    (java.util.concurrent CompletableFuture LinkedBlockingQueue TimeUnit)))

(def ^:private closed-sentinel ::closed)
(def timeout ::timeout)

(defprotocol WsConnection
  (ws-send! [this message])
  (ws-receive! [this] [this timeout-ms])
  (ws-close! [this])
  (ws-close-payload [this]))

(defn- completed-future []
  (CompletableFuture/completedFuture nil))

(defn- queue-message! [queue message]
  (.put queue message))

(defn- queue-closed! [queue]
  (.offer queue closed-sentinel))

(defn- receive-queue-message [queue closed? timeout-ms]
  (let [message (if (some? timeout-ms)
                  (.poll queue timeout-ms TimeUnit/MILLISECONDS)
                  (.take queue))]
    (cond
      (= closed-sentinel message) nil
      (and (nil? message) @closed?) nil
      (nil? message) timeout
      :else message)))

(deftype LoopbackWs [incoming outgoing closed?]
  WsConnection
  (ws-send! [_ message]
    (when-not @closed?
      (queue-message! outgoing message))
    nil)
  (ws-receive! [_]
    (receive-queue-message incoming closed? nil))
  (ws-receive! [_ timeout-ms]
    (receive-queue-message incoming closed? timeout-ms))
  (ws-close! [_]
    (reset! closed? true)
    (queue-closed! incoming)
    (queue-closed! outgoing)
    nil)
  (ws-close-payload [_] nil))

(defn loopback-pair []
  (let [client-incoming (LinkedBlockingQueue.)
        server-incoming (LinkedBlockingQueue.)
        client-closed?  (atom false)
        server-closed?  (atom false)]
    {:client (->LoopbackWs client-incoming server-incoming client-closed?)
     :server (->LoopbackWs server-incoming client-incoming server-closed?)}))

(defn- drain-queue! [queue]
  (loop []
    (when (.poll ^LinkedBlockingQueue queue)
      (recur))))

(defrecord ReconnectableLoopback [accept-queue connected-queue active-client active-server dropped? permanent?])

(defn reconnectable-loopback []
  (->ReconnectableLoopback (LinkedBlockingQueue.) (LinkedBlockingQueue.) (atom nil) (atom nil) (atom false) (atom false)))

(defn connect-loopback! [transport _url]
  (when @(:permanent? transport)
    (throw (ex-info "loopback unavailable" {:type :loopback/unavailable})))
  (when @(:dropped? transport)
    (throw (ex-info "loopback dropped" {:type :loopback/dropped})))
  (let [{:keys [client server]} (loopback-pair)]
    (reset! (:active-client transport) client)
    (reset! (:active-server transport) server)
    (.put ^LinkedBlockingQueue (:accept-queue transport) server)
    (.put ^LinkedBlockingQueue (:connected-queue transport) server)
    client))

(defn accept-loopback! [transport]
  (.poll ^LinkedBlockingQueue (:accept-queue transport) 1000 TimeUnit/MILLISECONDS))

(defn await-loopback-connection! [transport timeout-ms]
  (or @(:active-server transport)
      (.poll ^LinkedBlockingQueue (:connected-queue transport) timeout-ms TimeUnit/MILLISECONDS)))

(defn- close-loopback-connections! [transport]
  (some-> @(:active-client transport) ws-close!)
  (some-> @(:active-server transport) ws-close!)
  (drain-queue! (:connected-queue transport)))

(defn drop-loopback! [transport]
  (reset! (:dropped? transport) true)
  (close-loopback-connections! transport)
  nil)

(defn restore-loopback! [transport]
  (reset! (:dropped? transport) false)
  (reset! (:active-client transport) nil)
  (reset! (:active-server transport) nil)
  (drain-queue! (:connected-queue transport))
  nil)

(defn drop-loopback-permanently! [transport]
  (reset! (:dropped? transport) true)
  (reset! (:permanent? transport) true)
  (close-loopback-connections! transport)
  nil)

(deftype RealWs [websocket incoming closed? close-payload]
  WsConnection
  (ws-send! [_ message]
    (.join (.sendText websocket message true))
    nil)
  (ws-receive! [_]
    (receive-queue-message incoming closed? nil))
  (ws-receive! [_ timeout-ms]
    (receive-queue-message incoming closed? timeout-ms))
  (ws-close! [_]
    (reset! closed? true)
    (.join (.sendClose websocket WebSocket/NORMAL_CLOSURE "bye"))
    (queue-closed! incoming)
    nil)
  (ws-close-payload [_] @close-payload))

(defn request-ws-next!
  "Calls WebSocket.request(1) to release backpressure for the next frame.
   Extracted as a named function so tests can redef it."
  [ws]
  (.request ^WebSocket ws 1))

(defn ws-listener [incoming closed? close-payload]
  (let [partial (StringBuilder.)]
    (reify WebSocket$Listener
      (onOpen [_ ws]
        (request-ws-next! ws)
        (completed-future))
      (onText [_ ws data last?]
        (locking partial
          (.append partial data)
          (when last?
            (queue-message! incoming (.toString partial))
            (.setLength partial 0)))
        (request-ws-next! ws)
        (completed-future))
      (onBinary [_ ws _data _last?]
        (request-ws-next! ws)
        (completed-future))
      (onPing [_ ws _data]
        (request-ws-next! ws)
        (completed-future))
      (onPong [_ ws _data]
        (request-ws-next! ws)
        (completed-future))
      (onClose [_ _ws status-code reason]
        (reset! closed? true)
        (reset! close-payload {:status-code status-code :reason reason})
        (queue-closed! incoming)
        (completed-future))
      (onError [_ _ws error]
        (reset! closed? true)
        (log/error :ws/error :throwable error)
        (queue-message! incoming {:error error})
        nil))))

(defn connect!
  ([url]
   (connect! url {}))
  ([url {:keys [headers]}]
   (let [incoming      (LinkedBlockingQueue.)
         closed?       (atom false)
         close-payload (atom nil)
         listener      (ws-listener incoming closed? close-payload)
         builder       (.newWebSocketBuilder (HttpClient/newHttpClient))]
     (doseq [[header value] headers]
       (.header builder header value))
     (let [websocket (.join (.buildAsync builder (URI/create url) listener))]
       (->RealWs websocket incoming closed? close-payload)))))

(defn written-lines [writer]
  (->> (str/split-lines (str writer))
       (remove str/blank?)
       vec))

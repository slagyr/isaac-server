(ns isaac.config.change-source
  (:require
    [isaac.config.change-source-log :as change-log]
    [isaac.config.change-source-protocol :as proto]
    [isaac.config.paths :as paths]
    #?@(:bb  [[isaac.config.change-source-bb :as platform]]
        :clj [[isaac.config.change-source-watch :as platform]]))
  (:import
    (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def editor-artifact? proto/editor-artifact?)

(defn- enqueue-change! [queue home path]
  (when-let [relative (change-log/record-change-detected! home path)]
    (.offer queue relative)))

(deftype MemoryChangeSource [home queue]
  proto/ConfigChangeSource
  (proto/-start! [_]
    (change-log/record-watch-started! home :memory)
    nil)
  (proto/-stop! [_] nil)
  (proto/-poll! [_ timeout-ms]
    (if (pos? timeout-ms)
      (.poll queue timeout-ms TimeUnit/MILLISECONDS)
      (.poll queue)))
  (proto/-notify-path! [_ path]
    (enqueue-change! queue home path)
    nil))

(deftype NoopWatchServiceChangeSource [_home]
  proto/ConfigChangeSource
  (proto/-start! [_] nil)
  (proto/-stop! [_] nil)
  (proto/-poll! [_ timeout-ms]
    (when (pos? timeout-ms)
      (java.lang.Thread/sleep timeout-ms))
    nil)
  (proto/-notify-path! [_ _] nil))

(defn watch-service-source [home]
  (platform/watch-service-source home))

(defn memory-source [home]
  (->MemoryChangeSource home (LinkedBlockingQueue.)))

(defn start! [source]
  (proto/-start! source)
  source)

(defn stop! [source]
  (proto/-stop! source)
  nil)

(defn poll!
  ([source]
   (poll! source 0))
  ([source timeout-ms]
   (proto/-poll! source timeout-ms)))

(defn notify-path! [source path]
  (proto/-notify-path! source path)
  nil)

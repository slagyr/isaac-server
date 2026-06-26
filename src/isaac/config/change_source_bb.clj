(ns isaac.config.change-source-bb
  "Babashka file-watcher backed by org.babashka/fswatcher (Go fsnotify).
   Uses FSEvents on macOS and inotify on Linux — event-driven, not polling."
  (:require
    [isaac.config.change-source-log :as change-log]
    [isaac.config.change-source-protocol :as proto])
  (:import
    (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn- enqueue-change! [queue home {:keys [path]}]
  (when-let [rel (change-log/record-change-detected! home path)]
    (.offer queue rel)))

(deftype FswatcherChangeSource [home queue state]
  proto/ConfigChangeSource
  (proto/-start! [_]
    (let [config-root (java.io.File. (paths/config-root home))]
      (when (and (nil? @state) (.isDirectory config-root))
        (when-not (find-ns 'pod.babashka.fswatcher)
          ((requiring-resolve 'babashka.pods/load-pod) 'org.babashka/fswatcher "0.0.7"))
        (let [watch-fn (requiring-resolve 'pod.babashka.fswatcher/watch)
              watcher  (watch-fn (str config-root)
                                 (fn [event] (enqueue-change! queue home event))
                                 {:recursive true})]
          (reset! state {:watcher watcher})
          (change-log/record-watch-started! home :bb-fswatcher)
          ;; FSEvents on macOS needs a moment to start tracking a new directory.
          (Thread/sleep 1000))))
    nil)
  (proto/-stop! [_]
    (when-let [{:keys [watcher]} @state]
      ((requiring-resolve 'pod.babashka.fswatcher/unwatch) watcher)
      (reset! state nil))
    nil)
  (proto/-poll! [_ timeout-ms]
    (if (pos? timeout-ms)
      (.poll queue timeout-ms TimeUnit/MILLISECONDS)
      (.poll queue)))
  (proto/-notify-path! [_ path]
    (enqueue-change! queue home {:path path})
    nil))

(defn watch-service-source [home]
  (->FswatcherChangeSource home (LinkedBlockingQueue.) (atom nil)))

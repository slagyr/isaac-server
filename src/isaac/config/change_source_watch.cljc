(ns isaac.config.change-source-watch
  "JVM-only file-system watcher (java.nio.file.WatchService).
   Babashka uses isaac.config.change-source-bb instead.
   Wrapped in #?(:clj ...) so bb's tools.namespace scan can parse this
   file without choking on JDK classes SCI doesn't whitelist."
  (:require
    [clojure.string :as str]
    #?@(:bb  []
        :clj [[isaac.config.change-source-log :as change-log]
              [isaac.config.change-source-protocol :as proto]
              [isaac.config.paths :as paths]]))
  #?@(:bb  []
      :clj [(:import
              (java.io File)
              (java.nio.file Files LinkOption Path Paths StandardWatchEventKinds WatchEvent$Kind WatchService))]))

#?(:bb  nil
   :clj (do

          (defn- ->path [path]
            (Paths/get path (make-array String 0)))

          (defn- relative-config-path [home path]
            (let [config-root (.normalize (->path (paths/config-root home)))
                  full-path   (.normalize (->path path))]
              (when (.startsWith full-path config-root)
                (str/replace (str (.relativize config-root full-path)) "\\" "/"))))

          (defn- enqueue-change! [queue home path]
            (when-let [relative (change-log/record-change-detected! home path)]
              (.offer queue relative)))

          (defn- register-dir! [watch-service keys dir]
            (let [key (.register dir
                                 watch-service
                                 (into-array WatchEvent$Kind
                                             [StandardWatchEventKinds/ENTRY_CREATE
                                              StandardWatchEventKinds/ENTRY_DELETE
                                              StandardWatchEventKinds/ENTRY_MODIFY]))]
              (swap! keys assoc key dir)))

          (defn- register-tree! [watch-service keys root]
            (doseq [file (file-seq (File. (str root)))
                    :when (.isDirectory file)]
              (register-dir! watch-service keys (.toPath file))))

          (defn- watch-loop! [watch-service keys home queue]
            (try
              (loop []
                (when-let [key (.take watch-service)]
                  (when-let [dir (get @keys key)]
                    (doseq [event (.pollEvents key)]
                      (let [kind (.kind event)]
                        (when-not (= kind StandardWatchEventKinds/OVERFLOW)
                          (let [child      (.resolve ^Path dir ^Path (.context event))
                                directory? (Files/isDirectory child (make-array LinkOption 0))]
                            (when (and (= kind StandardWatchEventKinds/ENTRY_CREATE) directory?)
                              (register-tree! watch-service keys child))
                            (when-not directory?
                              (enqueue-change! queue home (str child)))))))
                    (if (.reset key)
                      (recur)
                      (do
                        (swap! keys dissoc key)
                        (recur))))))
              (catch Exception _
                nil)))

          (deftype WatchServiceChangeSource [home queue state]
            proto/ConfigChangeSource
            (proto/-start! [_]
              (let [config-root (.normalize (->path (paths/config-root home)))]
                (when (and (nil? @state)
                           (Files/isDirectory config-root (make-array LinkOption 0)))
                  (let [watch-service (.newWatchService (.getFileSystem config-root))
                        keys          (atom {})
                        thread        (doto (Thread. #(watch-loop! watch-service keys home queue)
                                                     "isaac-config-change-source")
                                        (.setDaemon true))]
                    (register-tree! watch-service keys config-root)
                    (.start thread)
                    (change-log/record-watch-started! home :jvm-watch-service)
                    (reset! state {:keys keys :thread thread :watch-service watch-service}))))
              nil)
            (proto/-stop! [_]
              (when-let [{:keys [watch-service]} @state]
                (.close ^WatchService watch-service)
                (reset! state nil))
              nil)
            (proto/-poll! [_ timeout-ms]
              (if (pos? timeout-ms)
                (.poll queue timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
                (.poll queue)))
            (proto/-notify-path! [_ path]
              (enqueue-change! queue home path)
              nil))

          (defn watch-service-source [home]
            (->WatchServiceChangeSource home (java.util.concurrent.LinkedBlockingQueue.) (atom nil)))

          ))

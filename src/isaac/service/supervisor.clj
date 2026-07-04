(ns isaac.service.supervisor
  "Generic supervision for long-lived server subsystems (isaac-royn). Watches
   every started Service that implements protocol/Supervised: when a worker's
   liveness probe reports dead, it logs at ERROR, restarts the service
   (stop+start) with exponential backoff and a circuit breaker, and records
   health so the /status surface reports a wedged subsystem instead of green.

   Each supervised service is restarted independently — a crash in one never
   touches another (isolation)."
  (:require
    [isaac.logger :as log]
    [isaac.service.protocol :as protocol])
  (:import
    (java.time Instant)))

(def default-poll-ms 1000)
(def default-max-restarts 5)
(def ^:private stable-polls 3)       ; consecutive healthy polls that reset the breaker
(def ^:private base-backoff-ms 1000)
(def ^:private max-backoff-ms 30000)

(defn- backoff-ms [restarts]
  (min max-backoff-ms (* base-backoff-ms (long (Math/pow 2 (max 0 (dec restarts)))))))

(defonce ^:private health* (atom {}))

(defn health
  "Snapshot of per-subsystem supervision health:
   {service-id {:status :up|:restarting|:down :restarts n :last-error s}}."
  []
  @health*)

(defn reset-health!
  "Clear supervision health. For boot/tests."
  []
  (reset! health* {}))

(defn- entry-for [id]
  (get @health* id {:status :up :restarts 0 :ok-streak 0}))

(defn- backoff-elapsed? [next-attempt-at now]
  (or (nil? next-attempt-at)
      (not (.isAfter (Instant/parse next-attempt-at) now))))

(defn- restart! [instance]
  ;; best-effort stop, then start; either half may throw and is reported by the caller
  (try (protocol/run-stop! instance) (catch Throwable _ nil))
  (protocol/run-start! instance))

(defn- record-alive! [id restarts ok-streak]
  (let [ok (inc (or ok-streak 0))]
    (if (and (pos? (or restarts 0)) (>= ok stable-polls))
      (swap! health* assoc id {:status :up :restarts 0 :ok-streak 0})     ; recovered → reset breaker
      (swap! health* assoc id {:status    :up
                               :restarts  (or restarts 0)
                               :ok-streak (if (pos? (or restarts 0)) ok 0)}))))

(defn- record-death! [id instance restarts now]
  (let [restarts' (inc (or restarts 0))]
    (if (> restarts' default-max-restarts)
      (do
        (swap! health* assoc id {:status :down :restarts restarts' :ok-streak 0})
        (log/error :service/supervision-exhausted :service (name id) :restarts restarts'))
      (do
        (log/error :service/died :service (name id) :restarts restarts')
        (let [err  (try (restart! instance) nil
                        (catch Throwable t (.getMessage t)))
              next (str (.plusMillis now (backoff-ms restarts')))]
          (when err
            (log/error :service/restart-failed :service (name id) :restarts restarts' :error err))
          (swap! health* assoc id (cond-> {:status         :restarting
                                           :restarts       restarts'
                                           :ok-streak      0
                                           :next-attempt-at next}
                                    err (assoc :last-error err))))))))

(defn- supervise-service! [{:keys [id instance]} now]
  (let [{:keys [status restarts ok-streak next-attempt-at]} (entry-for id)]
    (cond
      (= :down status)                     nil                    ; circuit-broken; stay down
      (protocol/alive? instance)           (record-alive! id restarts ok-streak)
      (not (backoff-elapsed? next-attempt-at now)) nil            ; dead but waiting out backoff
      :else                                (record-death! id instance restarts now))))

(defn supervise-once!
  "One monitoring pass over the supervised subset of `started-services` (each
   `{:id :instance}`). Never throws — a failure supervising one service must not
   stop the others."
  [started-services now]
  (doseq [{:keys [id instance] :as entry} started-services]
    (when (protocol/supervised? instance)
      (try
        (supervise-service! entry now)
        (catch Throwable t
          (log/error :service/supervise-failed :service (name id) :error (.getMessage t)))))))

(defonce ^:private supervisor* (atom nil))

(defn running? []
  (some? @supervisor*))

(defn start!
  "Start the background supervision loop. `services-fn` returns the current
   started-service entries (`{:id :instance}`); the loop polls every poll-ms.
   The loop itself is crash-proof: any error in a pass is logged and the loop
   continues."
  ([services-fn] (start! services-fn default-poll-ms))
  ([services-fn poll-ms]
   (when-not @supervisor*
     (let [running? (atom true)
           thread   (Thread.
                      ^Runnable
                      (fn []
                        (while @running?
                          (try
                            (supervise-once! (services-fn) (Instant/now))
                            (catch Throwable t
                              (log/error :service/supervisor-loop-error :error (.getMessage t))))
                          (try (Thread/sleep (long poll-ms))
                               (catch InterruptedException _ nil))))
                      "isaac-service-supervisor")]
       (.setDaemon thread true)
       (.start thread)
       (reset! supervisor* {:thread thread :running? running?})
       :started))))

(defn stop! []
  (when-let [{:keys [thread running?]} @supervisor*]
    (reset! running? false)
    (.interrupt ^Thread thread)
    (reset! supervisor* nil))
  :stopped)

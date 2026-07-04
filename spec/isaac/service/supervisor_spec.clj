(ns isaac.service.supervisor-spec
  (:require
    [isaac.logger :as log]
    [isaac.service.protocol :as protocol]
    [isaac.service.supervisor :as sut]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all])
  (:import
    (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-07-04T10:00:00Z"))

(defn- at [seconds] (.plusSeconds t0 seconds))

;; A fake supervised worker: `alive` drives the liveness probe, `starts` counts
;; how many times the supervisor restarted it.
(defn- fake-service [alive starts]
  (reify
    protocol/Service
    (start [_] (swap! starts inc))
    (stop [_] nil)
    protocol/Supervised
    (alive? [_] @alive)))

(defn- events [] (map :event @log/captured-logs))

(describe "service supervisor"

  (helper/with-captured-logs)

  ;; health* is a process-global atom — reset before AND after each example so
  ;; no leftover :down entry leaks into other specs (e.g. the /status route).
  (before (sut/reset-health!))
  (after (sut/reset-health!))

  (it "leaves a healthy supervised service untouched"
    (let [starts (atom 0)
          svc    (fake-service (atom true) starts)]
      (sut/supervise-once! [{:id :worker :instance svc}] t0)
      (should= 0 @starts)
      (should= :up (:status (get (sut/health) :worker)))))

  (it "ignores services that are not Supervised"
    (let [svc (reify protocol/Service (start [_]) (stop [_]))]
      (sut/supervise-once! [{:id :plain :instance svc}] t0)
      (should= {} (sut/health))))

  (it "restarts a supervised service that dies and logs at ERROR"
    (let [alive  (atom true)
          starts (atom 0)
          svc    (fake-service alive starts)
          entries [{:id :worker :instance svc}]]
      (sut/supervise-once! entries t0)
      (should= 0 @starts)
      (reset! alive false)
      (sut/supervise-once! entries t0)
      (should= 1 @starts)
      (should= :restarting (:status (get (sut/health) :worker)))
      (should-contain :service/died (events))))

  (it "resets the breaker after the service recovers and stays healthy"
    (let [alive  (atom false)
          starts (atom 0)
          svc    (fake-service alive starts)
          entries [{:id :worker :instance svc}]]
      (sut/supervise-once! entries t0)                 ; death 1 -> restart
      (should= 1 (:restarts (get (sut/health) :worker)))
      (reset! alive true)
      (sut/supervise-once! entries (at 60))            ; healthy again
      (sut/supervise-once! entries (at 60))
      (sut/supervise-once! entries (at 60))            ; >= stable-polls -> reset
      (should= {:status :up :restarts 0}
               (select-keys (get (sut/health) :worker) [:status :restarts]))))

  (it "waits out the backoff before restarting again"
    (let [starts (atom 0)
          svc    (fake-service (atom false) starts)
          entries [{:id :w :instance svc}]]
      (sut/supervise-once! entries t0)                 ; death 1 -> restart, backoff 1s
      (should= 1 @starts)
      (sut/supervise-once! entries (.plusMillis t0 500))  ; still dead, backoff not elapsed
      (should= 1 @starts)
      (sut/supervise-once! entries (.plusMillis t0 1500)) ; backoff elapsed -> restart 2
      (should= 2 @starts)))

  (it "gives up after the max restarts and marks the subsystem down"
    (let [starts (atom 0)
          svc    (fake-service (atom false) starts)
          entries [{:id :w :instance svc}]]
      (doseq [n (range 8)]
        (sut/supervise-once! entries (at (* n 60))))   ; each pass past its backoff window
      (should= sut/default-max-restarts @starts)
      (should= :down (:status (get (sut/health) :w)))
      (should-contain :service/supervision-exhausted (events))))

  (it "restarts only the dead service, leaving healthy siblings untouched (isolation)"
    (let [dead-starts (atom 0)
          ok-starts   (atom 0)
          entries [{:id :dead :instance (fake-service (atom false) dead-starts)}
                   {:id :ok   :instance (fake-service (atom true)  ok-starts)}]]
      (sut/supervise-once! entries t0)
      (should= 1 @dead-starts)
      (should= 0 @ok-starts)
      (should= :restarting (:status (get (sut/health) :dead)))
      (should= :up (:status (get (sut/health) :ok)))))

  (it "keeps supervising the others when one service's probe throws"
    (let [ok-starts (atom 0)
          boom      (reify
                      protocol/Service (start [_]) (stop [_])
                      protocol/Supervised (alive? [_] (throw (ex-info "probe boom" {}))))
          entries [{:id :boom :instance boom}
                   {:id :dead :instance (fake-service (atom false) ok-starts)}]]
      (sut/supervise-once! entries t0)
      (should-contain :service/supervise-failed (events))
      (should= 1 @ok-starts))))

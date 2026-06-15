(ns isaac.service.runtime-spec
  (:require
    [isaac.logger :as log]
    [isaac.marigold-server :as marigold-server]
    [isaac.service.fixture.alpha]
    [isaac.service.fixture.bravo]
    [isaac.service.fixture.events :as events]
    [isaac.service.fixture.widget]
    [isaac.service.protocol :as protocol]
    [isaac.service.registry :as registry]
    [isaac.service.runtime :as sut]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all]))

(defn- alpha-entry []
  {:manifest {:deps {:isaac.service.bravo {}}
              :isaac.server/service {:alpha {:namespace 'isaac.service.fixture.alpha}}}})

(defn- bravo-entry []
  {:manifest {:isaac.server/service {:bravo {:namespace 'isaac.service.fixture.bravo}}}})

(defn- widget-entry []
  {:manifest {:isaac.server/service {:widget {:namespace 'isaac.service.fixture.widget}}}})

(describe "service runtime"

  (marigold-server/with-manifest)
  (helper/with-captured-logs)

  (before
    (events/clear!)
    (sut/reset-state!)
    (reset! registry/*registry* (registry/fresh-registry)))

  (it "starts services in module topological order"
    (let [module-index {:isaac.service.bravo (bravo-entry)
                        :isaac.service.alpha (alpha-entry)}]
      (sut/start-all! module-index)
      (should= [[:bravo :start] [:alpha :start]] (events/events))
      (sut/stop-all!)
      (should= [[:bravo :start] [:alpha :start] [:alpha :stop] [:bravo :stop]] (events/events))))

  (it "logs :service/started for each started service"
    (let [module-index {:isaac.service.widget (widget-entry)}]
      (sut/start-all! module-index)
      (sut/stop-all!)
      (should (some #(= :service/started (:event %)) @log/captured-logs))
      (should (some #(= :service/stopped (:event %)) @log/captured-logs))))

  (it "rolls back already-started services when a later start fails"
    (let [module-index {:isaac.service.bravo (bravo-entry)
                        :isaac.service.alpha (alpha-entry)}
          real-run-start! protocol/run-start!
          start-count     (atom 0)]
      (with-redefs [protocol/run-start! (fn [instance]
                                          (swap! start-count inc)
                                          (if (= 2 @start-count)
                                            (throw (ex-info "boom" {}))
                                            (real-run-start! instance)))]
        (should-throw (sut/start-all! module-index))
        (should= [[:bravo :start] [:bravo :stop]] (events/events))
        (should= [] (sut/started-services))
        (should= nil (registry/instance-for :bravo))))))
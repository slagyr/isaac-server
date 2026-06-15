(ns isaac.service.runtime-spec
  (:require
    [isaac.logger :as log]
    [isaac.marigold-server :as marigold-server]
    [isaac.service.fixture.alpha]
    [isaac.service.fixture.bravo]
    [isaac.service.fixture.events :as events]
    [isaac.service.fixture.widget]
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
    (sut/reset-state!))

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
      (should (some #(= :service/stopped (:event %)) @log/captured-logs)))))
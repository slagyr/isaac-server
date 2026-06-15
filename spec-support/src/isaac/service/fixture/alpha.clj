(ns isaac.service.fixture.alpha
  (:require
    [isaac.service.factory :as factory]
    [isaac.service.fixture.events :as events]
    [isaac.service.protocol :as protocol]))

(defn- service []
  (reify protocol/Service
    (start [_]
      (events/record! :alpha :start))
    (stop [_]
      (events/record! :alpha :stop))))

(defmethod factory/create :alpha [_ _ctx]
  (service))
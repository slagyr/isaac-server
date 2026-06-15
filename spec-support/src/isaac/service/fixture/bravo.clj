(ns isaac.service.fixture.bravo
  (:require
    [isaac.service.factory :as factory]
    [isaac.service.fixture.events :as events]
    [isaac.service.protocol :as protocol]))

(defn- service []
  (reify protocol/Service
    (start [_]
      (events/record! :bravo :start))
    (stop [_]
      (events/record! :bravo :stop))))

(defmethod factory/create :bravo [_ _ctx]
  (service))
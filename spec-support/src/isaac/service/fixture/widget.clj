(ns isaac.service.fixture.widget
  (:require
    [isaac.service.factory :as factory]
    [isaac.service.fixture.events :as events]
    [isaac.service.protocol :as protocol]))

(defn- service []
  (reify protocol/Service
    (start [_]
      (events/record! :widget :start))
    (stop [_]
      (events/record! :widget :stop))))

(defmethod factory/create :widget [_ _ctx]
  (service))
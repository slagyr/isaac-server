(ns isaac.service.factory-spec
  (:require
    [isaac.service.factory :as sut]
    [isaac.service.fixture.widget]
    [isaac.service.protocol :as protocol]
    [isaac.schema.registered-in :as registered-in]
    [speclj.core :refer :all]))

(describe "service factory"

  (it "creates a service instance for a contributed id"
    (binding [registered-in/*module-index*
              {:isaac.service.widget
               {:manifest {:isaac.server/service
                            {:widget {:namespace 'isaac.service.fixture.widget}}}}}]
      (should (satisfies? protocol/Service
                          (sut/create! :widget {:service-id :widget}))))))
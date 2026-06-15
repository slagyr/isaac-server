(ns isaac.service.runtime-steps
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.comm.registry :as comm-registry]
    [isaac.foundation.root-steps :as froot]
    [isaac.server.app :as app]
    [isaac.service.fixture.alpha]
    [isaac.service.fixture.bravo]
    [isaac.service.fixture.events :as fixture-events]
    [isaac.service.fixture.widget]
    [isaac.service.registry :as service-registry]
    [isaac.service.runtime :as service-runtime]))

(helper! isaac.service.runtime-steps)

(def ^:private fixture-modules
  {:isaac.service.widget
   {:coord {}
    :manifest {:id :isaac.service.widget
               :factory 'isaac.module.protocol/module
               :isaac.server/service {:widget {:namespace 'isaac.service.fixture.widget}}}
    :path nil}
   :isaac.service.bravo
   {:coord {}
    :manifest {:id :isaac.service.bravo
               :factory 'isaac.module.protocol/module
               :isaac.server/service {:bravo {:namespace 'isaac.service.fixture.bravo}}}
    :path nil}
   :isaac.service.alpha
   {:coord {}
    :manifest {:id :isaac.service.alpha
               :factory 'isaac.module.protocol/module
               :deps {:isaac.service.bravo {}}
               :isaac.server/service {:alpha {:namespace 'isaac.service.fixture.alpha}}}
    :path nil}})

(defn reset-service-state!
  ([]
   (reset-service-state! nil))
  ([_root]
   (fixture-events/clear!)
   (reset! service-registry/*registry* (service-registry/fresh-registry))
   (service-runtime/reset-state!)
   (reset! comm-registry/*registry* (comm-registry/fresh-registry))))

(froot/register-root-setup-hook! reset-service-state!)

(g/before-scenario #(reset-service-state!))

(defn- inject-modules! [module-ids]
  (g/update! :server-config
             #(assoc (or % {}) :inject-module-index
                     (select-keys fixture-modules module-ids))))

(defn widget-test-service-is-registered []
  (inject-modules! [:isaac.service.widget]))

(defn topo-test-services-are-registered []
  (inject-modules! [:isaac.service.bravo :isaac.service.alpha]))

(defn service-start-order-is [expected]
  (let [started (->> (fixture-events/events)
                     (filter #(= :start (second %)))
                     (map first)
                     vec)]
    (g/should= (mapv keyword (str/split expected #"\s*,\s*")) started)))

(defn service-stop-order-is [expected]
  (let [stopped (->> (fixture-events/events)
                     (filter #(= :stop (second %)))
                     (map first)
                     vec)]
    (g/should= (mapv keyword (str/split expected #"\s*,\s*")) stopped)))

(defn isaac-server-is-stopped []
  (app/stop!)
  (g/dissoc! :server-port))

(defgiven "the widget test service module is registered" isaac.service.runtime-steps/widget-test-service-is-registered
  "Injects the widget fixture module into :module-index (implementations
   are on the spec-support classpath).")

(defgiven "the alpha and bravo test service modules are registered" isaac.service.runtime-steps/topo-test-services-are-registered
  "Injects fixture modules with :deps so bravo precedes alpha.")

(defthen "the service start order is {expected:string}" isaac.service.runtime-steps/service-start-order-is)

(defthen "the service stop order is {expected:string}" isaac.service.runtime-steps/service-stop-order-is)

(defwhen "the Isaac server is stopped" isaac.service.runtime-steps/isaac-server-is-stopped)
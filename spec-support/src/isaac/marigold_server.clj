(ns isaac.marigold-server
  "Server half of the Marigold test world: HTTP host manifest and
   foundation+server manifest index rebinding. Themed crew/provider names
   and aboard helpers live in foundation's `isaac.marigold`."
  (:require
    [isaac.config.schema-compose :as schema-compose]
    [isaac.config.schema.root :as config-schema]
    [isaac.marigold :as marigold]
    [isaac.module.loader :as module-loader]
    [speclj.core :as speclj]))

(def baseline-server-manifest
  "HTTP host manifest — route berth and :server schema."
  {:id       :isaac.server
   :version  "0.1.0"
   :builtin? true
   :factory  'isaac.server.module/create-module

   :berths   {:isaac.server/route {:description "HTTP routes."
                                   :schema      {:type :seq
                                                 :spec {:type    :map
                                                        :factory 'isaac.server.routes/register-route-entry!
                                                        :schema  {:method  {:type :keyword :validations [:present?]}
                                                                  :path    {:type :string :validations [:present?]}
                                                                  :handler {:type :symbol :validations [:present?]}}}}}}

   :isaac.config/schema (select-keys config-schema/contributions [:server])})

(def baseline-manifest baseline-server-manifest)

(def ^:private baseline-foundation-index
  {:isaac.foundation {:coord {} :manifest marigold/baseline-foundation-manifest :path nil}
   :isaac.server     {:coord {} :manifest baseline-server-manifest :path nil}})

(defn- reset-module-state! []
  (module-loader/clear-activations!))

(defn with-manifest
  "Inside a `(describe ...)` block, swaps builtin manifests for Marigold's
   foundation + server manifests for each example."
  []
  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (speclj/around [example]
    (binding [module-loader/*foundation-index-override* baseline-foundation-index]
      (schema-compose/clear-cache!)
      (reset-module-state!)
      (try
        (example)
        (finally
          (schema-compose/clear-cache!)
          (reset-module-state!))))))

(defn with-real-manifest*
  [thunk]
  (binding [module-loader/*foundation-index-override* nil]
    (reset-module-state!)
    (module-loader/activate-foundation!)
    (thunk)))

(defmacro with-real-manifest
  [& body]
  `(with-real-manifest* (fn [] ~@body)))
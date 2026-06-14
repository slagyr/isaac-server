(ns isaac.config.checks
  (:require
    [c3kit.apron.schema :as cs]
    [isaac.config.berths :as berths]
    [isaac.config.schema-base :as schema-base]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.config.validation :as validation]))

(defn- ->id [value]
  (schema-base/->id value))

(def ^:private manifest-schema-kinds
  [:isaac.server/comm :isaac.server/provider-template :isaac.server/slash-commands :isaac.server/tools])

(defn- verify-manifest-schema-fragment [module-id field-schema]
  (try
    (cs/verify-schema-lexes field-schema)
    []
    (catch Throwable t
      [{:key   (str "modules." (->id module-id))
        :value (if-let [ref (or (:ref (ex-data t))
                                (:lex (ex-data t)))]
                 (str "unregistered ref " ref)
                 (.getMessage t))}])))

(defn- manifest-ref-errors [module-index]
  (mapcat (fn [[module-id entry]]
            (mapcat (fn [kind]
                      (mapcat (fn [[_ extension]]
                                (when-let [field-schema (or (:extra-schema extension) (:schema extension))]
                                  (verify-manifest-schema-fragment module-id field-schema)))
                              (get-in entry [:manifest kind])))
                    manifest-schema-kinds))
          module-index))

(defn- comm-reserved-schema-errors [module-index]
  (mapcat (fn [[module-id entry]]
            (keep (fn [[extension-id extension]]
                    (when (contains? (:extra-schema extension) :type)
                      {:key   (str "modules." (->id module-id))
                       :value (str ":type is the slot discriminator, not a field"
                                   " (comm " (name extension-id) ")")}))
                  (get-in entry [:manifest :isaac.server/comm])))
          module-index))

(defn check-resolved-providers
  [{:keys [config raw-providers effective-schema]}]
  (let [resolve-provider (requiring-resolve 'isaac.config.resolve/resolve-provider)
        provider-schema  (schema-compose/provider-entity-schema effective-schema)]
    {:errors (vec
               (mapcat (fn [[provider-id provider-cfg]]
                         (when (or (:type provider-cfg) (:from provider-cfg))
                           (when-let [resolved (resolve-provider config provider-id)]
                             (validation/annotation-errors* nil ["providers" (->id provider-id)] provider-schema resolved resolved nil))))
                       raw-providers))
     :warnings []}))

(defn check-manifest-refs
  [{:keys [module-index]}]
  {:errors (vec (manifest-ref-errors module-index))
   :warnings []})

(defn check-comm-reserved-schema
  [{:keys [module-index]}]
  {:errors (vec (comm-reserved-schema-errors module-index))
   :warnings []})
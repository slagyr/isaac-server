(ns isaac.config.schema-base
  (:require
    [isaac.schema.lexicon :as lexicon]))

(def ->id lexicon/->id)

(defn schema-fields [spec]
  (:schema spec))

(defn strip-validation-annotations [node]
  (cond
    (map? node)
    (let [node (dissoc node :validations)]
      (into {} (map (fn [[k v]] [k (strip-validation-annotations v)])) node))

    (vector? node)
    (mapv strip-validation-annotations node)

    :else
    node))

(def base-root
  {:name        :isaac
   :type        :map
   :description "Isaac's root level schema"
   :schema      {:modules {:type        :map
                           :key-spec    {:type :keyword}
                           :value-spec  {:type :map}
                           :message     "must be a map of id to coordinate (legacy vector shape)"
                           :description "Declared modules as a map of module id to tools.deps coordinate"}}})
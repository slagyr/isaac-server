(ns isaac.session.compaction-schema)

(def config-schema
  {:strategy       {:type        :keyword
                    :validations [[:one-of? :rubberband :slinky]]}
   :threshold      {:type        :double
                    :validations [[:percentage? "e.g. 0.8 for 80% of context-window"]]}
   :head           {:type        :double
                    :validations [[:percentage? "e.g. 0.3 for 30% of context-window"]]}
   :async?         {:type :boolean}
   :head-threshold {:type        :ignore
                    :validations [[:less-than? :head :threshold]]
                    :description "Derived: head must stay below threshold"}})

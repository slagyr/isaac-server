(ns isaac.crew.store)

(defn tags-of [crew-cfg]
  (or (:tags crew-cfg) #{}))

(defn has-tag? [crew-cfg tag]
  (contains? (tags-of crew-cfg) tag))

(defn by-tags [crew-map tag-set]
  (into {}
        (filter (fn [[_ crew-cfg]]
                  (every? #(has-tag? crew-cfg %) tag-set)))
        crew-map))

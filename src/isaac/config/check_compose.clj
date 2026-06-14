(ns isaac.config.check-compose
  (:require
    [isaac.module.loader :as module-loader]))

(def ^:private berth-key :isaac.config/check)

(defn- id-str [value]
  (cond
    (keyword? value) (str value)
    (symbol? value)  (str value)
    :else            (pr-str value)))

(defn- collision-error [check-id contributors]
  (ex-info (str "config-check collision at " check-id)
           {:check-id     check-id
            :contributors contributors
            :type         :config-check/collision}))

(defn contribution-entries [module-index]
  (->> module-index
       (mapcat (fn [[module-id entry]]
                 (for [[check-id descriptor] (sort-by key (get-in entry [:manifest berth-key] {}))]
                   {:check-id   check-id
                    :descriptor descriptor
                    :module-id  module-id})))
       (sort-by (juxt #(id-str (:module-id %)) #(id-str (:check-id %))))))

(defn- resolve-check-fn [{:keys [fn fn-sym]}]
  (let [sym      (or fn fn-sym
                     (throw (ex-info "config-check missing :fn symbol"
                                     {:type :config-check/missing-fn})))
        resolved (cond
                   (symbol? sym) sym
                   (keyword? sym) (symbol (namespace sym) (name sym))
                   (string? sym) (symbol sym)
                   :else sym)
        value    (let [v (requiring-resolve resolved)]
                   (if (var? v) @v v))]
    (if (fn? value)
      value
      (throw (ex-info (str "config-check :fn must resolve to a function: " resolved)
                      {:fn resolved :type :config-check/invalid-fn :value value})))))

(defn- merge-contributions [module-index]
  (reduce
    (fn [{:keys [checks owners]} entry]
      (let [{:keys [check-id descriptor module-id]} entry
            check-fn (resolve-check-fn descriptor)]
        (if-let [existing (get owners check-id)]
          (throw (collision-error check-id [existing {:module-id module-id}]))
          {:checks (conj checks {:check-id check-id :fn check-fn})
           :owners (assoc owners check-id {:module-id module-id})})))
    {:checks [] :owners {}}
    (contribution-entries module-index)))

(defn run-checks
  ([ctx]
   (run-checks (module-loader/builtin-index) ctx))
  ([module-index ctx]
   (reduce (fn [{:keys [errors warnings]} {:keys [fn]}]
             (let [{check-errors :errors check-warnings :warnings}
                   (fn ctx)]
               {:errors   (into errors check-errors)
                :warnings (into warnings check-warnings)}))
           {:errors [] :warnings []}
           (:checks (merge-contributions module-index)))))
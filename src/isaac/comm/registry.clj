(ns isaac.comm.registry)

(def ^:dynamic *registry*
  (atom {:path  [:comms]
         :impls {}}))

(defn- ->name [x]
  (cond
    (string? x)  x
    (keyword? x) (name x)
    :else        (str x)))

(defn register-factory!
  "Register a factory function for impl-name. Factory is (fn [host] -> Reconfigurable)."
  [impl-name factory]
  (let [n (->name impl-name)]
    (swap! *registry* assoc-in [:impls n] factory)
    n))

(defn registered? [impl-name]
  (let [n (->name impl-name)]
    (contains? (:impls @*registry*) n)))

(defn factory-for [impl-name]
  (get-in @*registry* [:impls (->name impl-name)]))

(defn registered-names []
  (set (keys (:impls @*registry*))))

(defn fresh-registry
  "Returns a registry map suitable for binding *registry* in tests."
  ([] (fresh-registry [:comms]))
  ([path] {:path path :impls {}}))

(defn snapshot []
  @*registry*)

;; Live-instance registry — keyed by impl name (e.g. "discord").
;; Stored under :instances in the same dynamic atom so test bindings
;; also isolate instance state.

(defn register-instance!
  "Record the live Comm instance for `impl-name`. Called by the configurator
   after on-startup! so the delivery worker can find it."
  [impl-name instance]
  (swap! *registry* assoc-in [:instances (->name impl-name)] instance))

(defn deregister-instance!
  "Remove the live Comm instance for `impl-name`."
  [impl-name]
  (swap! *registry* update :instances dissoc (->name impl-name)))

(defn comm-for
  "Return the live Comm instance registered for `impl-name`, or nil."
  [impl-name]
  (get-in @*registry* [:instances (->name impl-name)]))

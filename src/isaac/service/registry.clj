(ns isaac.service.registry)

(def ^:dynamic *registry*
  (atom {:instances     {}
         :registrations {}}))

(defn- ->name [x]
  (cond
    (string? x)  x
    (keyword? x) (name x)
    :else        (str x)))

(defn register-instance!
  [service-id instance]
  (swap! *registry* assoc-in [:instances (->name service-id)] instance))

(defn deregister-instance!
  [service-id]
  (swap! *registry* update :instances dissoc (->name service-id)))

(defn instance-for [service-id]
  (get-in @*registry* [:instances (->name service-id)]))

(defn register!
  "Register a component with a service. Used when a config slice loads
   (child isaac-bju6); the service holds registrations until start."
  [service-id registration]
  (let [n (->name service-id)]
    (swap! *registry* update-in [:registrations n]
           (fnil conj #{})
           registration)
    registration))

(defn deregister!
  [service-id registration]
  (let [n (->name service-id)]
    (swap! *registry* update-in [:registrations n]
           (fn [s] (disj (or s #{}) registration)))))

(defn registrations-for [service-id]
  (get-in @*registry* [:registrations (->name service-id)] #{}))

(defn fresh-registry
  ([] (fresh-registry {}))
  ([instances]
   {:instances     instances
    :registrations {}}))

(defn snapshot []
  @*registry*)
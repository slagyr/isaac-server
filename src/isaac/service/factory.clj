(ns isaac.service.factory
  "The :isaac.server/service berth's factory. Service modules contribute
   inert data only ({:namespace …}); instantiation happens at server boot
   via the `create` multimethod, keyed by service id."
  (:require
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.schema.registered-in :as registered-in]))

(defmulti create
  "Instantiate the Service for a manifest contribution id. Service
   modules implement this for each id they contribute."
  (fn [service-id _ctx] service-id))

(defn- contribution [module-index service-key]
  (some (fn [[module-id entry]]
          (when-let [contribution (get-in entry [:manifest :isaac.server/service service-key])]
            [module-id contribution]))
        module-index))

(defn- ensure-impl!
  [module-index service-key]
  (when-let [[module-id entry] (contribution module-index service-key)]
    (let [activated (try
                      (module-loader/activate! module-id module-index)
                      (catch clojure.lang.ExceptionInfo _ :failed))
          required  (when-not (get-method create service-key)
                      (when-let [ns-sym (:namespace entry)]
                        (try
                          (require ns-sym)
                          nil
                          (catch Throwable t
                            (log/error :module/activation-failed
                                       :error  (.getMessage t)
                                       :service (name service-key)
                                       :module (name module-id))
                            :failed))))]
      (when (or (= :failed activated) (= :failed required))
        :failed))))

(defn create!
  "Resolve and invoke `create` for `service-id`, loading the contributing
   module on first use. Returns nil when no implementation can be found."
  [service-id ctx]
  (let [failed? (= :failed (ensure-impl! registered-in/*module-index* service-id))]
    (if-let [instance (when (get-method create service-id)
                        (create service-id ctx))]
      instance
      (do (when-not failed?
            (log/error :module/activation-failed
                       :error (str "no implementation creates service " (pr-str service-id))
                       :service (name service-id)))
          nil))))
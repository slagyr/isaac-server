(ns isaac.service.runtime
  "Server-only service lifecycle. Only isaac.server.app invokes
   start-all! / stop-all! — CLI and other entry points gather service
   contributions but never start them."
  (:require
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.schema.registered-in :as registered-in]
    [isaac.service.factory :as factory]
    [isaac.service.protocol :as protocol]
    [isaac.service.registry :as registry]))

(defonce ^:private started* (atom []))

(declare stop-all!)

(defn started-services
  "Started entries in boot order — each `{:id :module-id :instance}`."
  []
  @started*)

(defn- service-entries [module-index]
  (mapcat (fn [[module-id entry]]
            (when-let [services (get-in entry [:manifest :isaac.server/service])]
              (map (fn [[service-id contribution]]
                     {:module-id    module-id
                      :service-id   service-id
                      :contribution contribution})
                   services)))
          module-index))

(defn- ranked-entries [module-index]
  (let [order (zipmap (module-loader/topological-order module-index) (range))]
    (sort-by (fn [{:keys [module-id]}] (get order module-id)) (service-entries module-index))))

(defn start-all!
  "Instantiate and start every :isaac.server/service contribution in
   module topological order. Returns :started."
  [module-index]
  (binding [registered-in/*module-index* module-index]
    (let [entries  (ranked-entries module-index)
          started  (atom [])]
      (reset! started* [])
      (try
        (doseq [{:keys [module-id service-id contribution]} entries]
          (when-let [instance (factory/create! service-id {:module-id    module-id
                                                          :service-id   service-id
                                                          :contribution contribution})]
            (protocol/run-start! instance)
            (registry/register-instance! service-id instance)
            (log/info :service/started :service (name service-id) :module (name module-id))
            (swap! started conj {:id service-id :module-id module-id :instance instance})))
        (reset! started* @started)
        :started
        (catch Throwable t
          ;; started* is only committed on the happy path; sync the partial
          ;; progress so stop-all! can unwind services already started.
          (reset! started* @started)
          (stop-all!)
          (throw t))))))

(defn reset-state!
  "Clear started-service bookkeeping. For tests only."
  []
  (reset! started* []))

(defn stop-all!
  "Stop started services in reverse topological order."
  []
  (doseq [{:keys [id instance module-id]} (reverse @started*)]
    (try
      (protocol/run-stop! instance)
      (registry/deregister-instance! id)
      (log/info :service/stopped :service (name id) :module (name module-id))
      (catch Throwable t
        (log/error :service/stop-failed
                   :service (name id)
                   :module  (name module-id)
                   :error   (.getMessage t)))))
  (reset! started* []))
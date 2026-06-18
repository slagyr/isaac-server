(ns isaac.comm.factory
  "The :isaac.server/comm config berth's factory. Comm modules
   contribute data only ({:namespace … :extra-schema …}); instantiation
   attaches in code by implementing the `create` multimethod, keyed by
   impl id. The berth machinery calls `create!` per configured slot and
   the returned instance lives in the nexus."
  (:require
    [isaac.comm.registry :as comm-registry]
    [isaac.config.schema-base :as schema-base]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.schema.registered-in :as registered-in]))

(defn impl-id
  "The impl a slot instantiates: its :type when present, else the slot's
   own name — normalized to a keyword id."
  [node-path slice]
  (keyword (schema-base/->id (or (when (map? slice) (or (get slice :type) (get slice "type")))
                                 (last node-path)))))

(defmulti create
  "Instantiate the comm instance for a configured slot. Comm modules
   implement this for each impl id they contribute; the instance goes
   in the nexus (and receives on-load/on-config-change!/on-unload when it
   satisfies Reconfigurable)."
  (fn [node-path slice] (impl-id node-path slice)))

(defn- manifest-comm-contribution [entry impl-key]
  (or (get-in entry [:manifest :isaac.server/comm impl-key])
      (get-in entry [:manifest :isaac.agent/comm impl-key])))

(defn- contribution [module-index impl-key]
  (some (fn [[module-id entry]]
          (when-let [contribution (manifest-comm-contribution entry impl-key)]
            [module-id contribution]))
        module-index))

(defn- ensure-impl!
  "Make `create` dispatchable for impl-key: activate the contributing
   module (idempotent per activation lifecycle — activate! tracks and
   logs its own failures) and require the entry's :namespace so its
   defmethod installs. Returns :failed when something would not load
   (already logged)."
  [module-index impl-key]
  (when-let [[module-id entry] (contribution module-index impl-key)]
    (let [activated (try
                      (module-loader/activate! module-id module-index)
                      (catch clojure.lang.ExceptionInfo _ :failed))
          required  (when-not (get-method create impl-key)
                      (when-let [ns-sym (:namespace entry)]
                        (try
                          ;; :reload, not a plain require. We only reach here
                          ;; when create's defmethod is missing, so the entry
                          ;; ns must re-run to install it. A load-once require
                          ;; can't guarantee that: once a ns is in
                          ;; *loaded-libs* require no-ops even though the
                          ;; defmethod isn't there — e.g. an earlier load
                          ;; threw partway (a module failing on first
                          ;; activation), or the ns was removed without
                          ;; clearing *loaded-libs*. Reloading re-evals the ns
                          ;; and reinstalls the method, so activation is
                          ;; idempotent and recoverable. Costs nothing once a
                          ;; comm impl is live (the method is then present).
                          (require ns-sym :reload)
                          nil
                          (catch Throwable t
                            (log/error :module/activation-failed
                                       :error  (.getMessage t)
                                       :impl   (name impl-key)
                                       :module (name module-id))
                            :failed))))]
      (when (or (= :failed activated) (= :failed required))
        :failed))))

(defn create!
  "Per-slot factory for the :isaac.server/comm config berth. Resolves
   the impl's `create` method (loading the contributing module on first
   use), preferring a programmatically registered constructor
   (isaac.api/register-comm-factory!). Returns nil — leaving the slot
   inert — when no implementation can be found."
  [node-path slice]
  (let [impl-key (impl-id node-path slice)
        slot     (name (last node-path))
        failed?  (= :failed (ensure-impl! registered-in/*module-index* impl-key))]
    (if-let [instance (if-let [legacy (comm-registry/factory-for impl-key)]
                        (legacy {:name (last node-path)})
                        (when (get-method create impl-key)
                          (create node-path slice)))]
      (do (log/info :comm/activated :comm slot :type (name impl-key))
          instance)
      (do (when-not failed?
            (log/error :module/activation-failed
                       :error (str "no implementation creates comm impl " (pr-str impl-key))
                       :impl  (name impl-key)))
          nil))))

(ns isaac.config.runtime
  "The server-side companion to isaac.config.loader. Where the loader is the read
   API (load, snapshot, env), config.runtime is the write /
   lifecycle side: installing a committed config into the live nexus,
   reconciling config-driven components (the Reconfigurable protocol), and
   the config change source that drives hot reload.

   This surface exists so server callers requiring lifecycle behavior don't
   drag isaac.comm.registry / isaac.session.store.spi (pulled in transitively by
   install / configurator) into read-only foundation code. Everything outside
   the isaac.config.* namespaces requires *only* config.loader, config.api (test
   write helpers), and/or config.runtime — never install /
   configurator /
   change-source directly — so those internals stay free to reorganize behind
   these surfaces.

   Each fn delegates to its source at call time, so `with-redefs` on the
   underlying fn still takes effect for callers through this API."
  (:require
    [isaac.config.change-source :as change-source]
    [isaac.config.configurator :as configurator]
    [isaac.config.install :as install]
    [isaac.reconfigurable :as reconfigurable]))

;; ----- install (config -> nexus) -----

(defn install!
  "Reconciles an already-committed config into the nexus: ensures the session
   store, then reconciles the given registries' config slices into the nexus as
   live component instances (the snapshot must already be committed). opts keys:
   :config (required), :old-config (nil on boot), :registries, :host. Returns
   {:config config}."
  [opts]
  (install/install! opts))

(defn install-config-berths!
  "Builds and registers live nodes for any berth-backed config slices after
   module startup. opts keys: :config and :module-index. Returns {:config config}."
  [opts]
  (install/install-config-berths! opts))

(defn reload!
  "Hot-reload coordinator (server only): re-load config from :root/:fs,
   validate it; on error log and keep the running config (returns nil); on
   success commit the new snapshot and reconcile :registries against :old-config.
   opts keys: :root :fs :old-config :comm-registry :registries :host :path.
   Returns the new config on success, nil if rejected."
  [opts]
  (install/reload! opts))

(defn validate-config!
  "Logs comm-impl validation errors for `cfg` against `comm-registry` and returns
   the seq of errors (empty if valid). Used at boot."
  [cfg comm-registry]
  (install/validate-config! cfg comm-registry))

;; ----- reconciliation (config -> live components) -----

(def Reconfigurable
  "Protocol implemented by config-driven components (comms, hail bands, hooks,
   cron) so the reconciler can start/stop/update them: on-startup! /
   on-config-change!. Canonical definition: isaac.reconfigurable."
  reconfigurable/Reconfigurable)

(defn on-startup!
  "Reconfigurable method: called when an instance is first started with its
   config slice."
  [instance slice]
  (reconfigurable/on-startup! instance slice))

(defn on-config-change!
  "Reconfigurable method: called on reload with the old and new config slices."
  [instance old-slice new-slice]
  (reconfigurable/on-config-change! instance old-slice new-slice))

(defn reconcile!
  "Walks the registries' config slices and reconciles the live component
   instances in the nexus against them. One fn for boot (old nil), reload (old
   vs new), and shutdown (new nil): [host old-cfg new-cfg registry-or-registries]."
  [host old-cfg new-cfg registry-or-registries]
  (configurator/reconcile! host old-cfg new-cfg registry-or-registries))

(defn slot-impl
  "Resolves the component impl/type for a slot from its config slice."
  [slot slice]
  (configurator/slot-impl slot slice))

(defn ->name
  "Coerces a keyword or value to its string name (reconciler helper)."
  [x]
  (configurator/->name x))

;; ----- config change source (file watcher for hot reload) -----

(defn watch-service-source
  "Creates a filesystem-watching config change source rooted at `root`."
  [root]
  (change-source/watch-service-source root))

(defn memory-source
  "Creates an in-memory config change source rooted at `root` (test/dev)."
  [root]
  (change-source/memory-source root))

(defn start!
  "Starts a config change source. Returns the source."
  [source]
  (change-source/start! source))

(defn stop!
  "Stops a config change source."
  [source]
  (change-source/stop! source))

(defn poll!
  "Polls a config change source for the next changed config-relative path,
   waiting up to `timeout-ms` (default 0)."
  ([source]            (change-source/poll! source))
  ([source timeout-ms] (change-source/poll! source timeout-ms)))

(defn notify-path!
  "Notifies a config change source that `path` changed (test/dev)."
  [source path]
  (change-source/notify-path! source path))

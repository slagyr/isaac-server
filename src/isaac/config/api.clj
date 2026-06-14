(ns isaac.config.api
  "Host and test write helpers for Isaac configuration. Server lifecycle (install,
   reconcile, reload) lives in `isaac.config.runtime`. This namespace is for
   committing synthetic snapshots and
   env-var overrides in tests and boot paths that bypass the loader.

   Each fn delegates to `isaac.config.loader` at call time, so `with-redefs` on the
   underlying fn still takes effect for callers through this API."
  (:require
    [isaac.config.loader :as loader]))

(defn dangerously-install-config!
  "Commit an already-built config value as the process-wide snapshot, bypassing
   the loader. Reserved for boot (committing a runtime-built config), reload
   (after validation), and tests committing a synthetic config. Prefer
   `load-config!`. `reason` documents the call site."
  [cfg reason]
  (loader/set-snapshot! cfg reason))

(defn set-env-override!
  "Sets an env-var override (test support). Clears the load cache."
  [name value]
  (loader/set-env-override! name value))

(defn clear-env-overrides!
  "Clears all env-var overrides and the .env snapshot (test support)."
  []
  (loader/clear-env-overrides!))
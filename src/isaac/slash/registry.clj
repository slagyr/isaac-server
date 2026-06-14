(ns isaac.slash.registry
  (:require
    [isaac.config.loader :as loader]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [isaac.prompt.catalog :as prompt-catalog]
    [isaac.slash.builtin :as builtin]))

(defonce ^:private commands* (atom {}))

(defn- ensure-builtins! []
  (builtin/ensure-registered!))

(defn registered-command [name]
  (get @commands* (str name)))

(defn register! [{:keys [name] :as command}]
  (let [name     (str name)
        previous (get @commands* name)]
    (swap! commands* assoc name (assoc command :name name))
    (if previous
      (log/warn :slash/override :command name)
      (log/info :slash/registered :command name :module name))
    name))

(defn unregister! [name]
  (swap! commands* dissoc (str name)))

(defn clear! []
  (reset! commands* {})
  (module-loader/deactivate-foundation!))

(declare register-slash-entry!)

(defn- activate-all! [module-index]
  ;; Phase 7 (isaac-ho18): slash-command registration moved from
  ;; activate!'s register-extensions! pass into the berth's per-entry
  ;; factory. Activate each module for its bootstrap + non-slash
  ;; extensions, then install slash entries directly.
  (doseq [[module-id entry] module-index
          :let [contribs (get-in entry [:manifest :isaac.server/slash-commands])]
          :when (seq contribs)]
    (module-loader/activate! module-id module-index)
    (doseq [pair contribs]
      (register-slash-entry! pair))))

(defn register-slash-entry!
  "Per-entry factory for the :isaac.server/slash-commands berth (phase 7
   of the berth epic). Receives `[command-id entry]`; resolves the
   entry's symbol-valued :factory and registers the resulting spec under
   the berth key — the command's name."
  [[command-id entry]]
  (let [command-id (clojure.core/name command-id)
        factory    (some-> (:factory entry) requiring-resolve var-get)
        spec       (factory)]
    (register! {:name        command-id
                :description (:description spec)
                :handler     (:handler spec)})))

(defn- prompt-catalog-opts [opts]
  (let [root (or (:root opts)
                      (nexus/get :root)
                      (loader/root))
        fs*       (or (:fs opts) (nexus/get :fs))]
    (when (and root fs*)
      {:config    (or (:config opts)
                      (loader/snapshot "slash command advertisement resolves prompt-template commands"))
       :cwd       (:cwd opts)
       :fs        fs*
       :root root})))

(defn- prompt-template-commands [opts]
  (if-let [catalog-opts (prompt-catalog-opts opts)]
    (->> (prompt-catalog/resolve-catalog catalog-opts)
         :commands
         vals
         (map #(select-keys % [:description :name :params])))
    []))

(defn- advertised-commands [opts]
  (let [registered   (->> (vals @commands*)
                          (map #(dissoc % :handler)))
        claimed      (into #{} (map :name) registered)
        templated    (remove #(contains? claimed (:name %)) (prompt-template-commands opts))]
    (->> (concat registered templated)
         (sort-by :name)
         vec)))

(defn lookup
  ([name]
   (ensure-builtins!)
   (registered-command name))
  ([name module-index]
   (ensure-builtins!)
   (activate-all! module-index)
   (registered-command name)))

(defn all-commands
  ([]
   (ensure-builtins!)
   (advertised-commands nil))
  ([module-index]
   (all-commands module-index nil))
  ([module-index opts]
   (ensure-builtins!)
   (activate-all! module-index)
   (advertised-commands opts)))

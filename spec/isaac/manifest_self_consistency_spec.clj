(ns isaac.manifest-self-consistency-spec
  (:require
    [clojure.edn :as edn]
    [isaac.config.berths :as berths]
    [isaac.module.loader :as module-loader]
    [isaac.config.validation]
    [isaac.schema.meta]
    [isaac.schema.registered-in]
    [isaac.session.compaction-schema :as compaction-schema]
    [clojure.java.io :as io]
    [speclj.core :refer :all]))

(defn- read-manifest [path]
  (-> path io/file slurp edn/read-string))

(defn- builtin-manifests []
  (->> (module-loader/builtin-index)
       (map (fn [[_ {:keys [manifest]}]] manifest))
       (remove nil?)))

(defn- manifest-symbols
  "Every symbol referenced anywhere in the manifest data — berth and
   contribution :factory, route :handler, :isaac.config/check :fn, :cli
   :namespace, :bootstrap, the top-level :factory. Manifests are pure
   data, so each symbol is a reference that must resolve."
  [x]
  (cond
    (symbol? x)     [x]
    (map? x)        (mapcat manifest-symbols (vals x))
    (sequential? x) (mapcat manifest-symbols x)
    :else           []))

(defn- schema-contributions []
  (->> (builtin-manifests)
       (map :isaac.config/schema)
       (remove nil?)
       (apply merge {})))

(describe "manifest self-consistency"

  (it "the server manifest is pure data — clojure.edn parses it with no readers"
    (let [manifest (edn/read-string (slurp "resources/isaac-manifest.edn"))]
      (should= :isaac.server (:id manifest))
      (should= manifest (edn/read-string (pr-str manifest)))))

  (it "the server manifest only declares server-owned berths and CLI commands"
    (let [manifest (read-manifest "resources/isaac-manifest.edn")]
      (should= #{:isaac.server/route} (set (keys (:berths manifest))))
      (should= #{:server :service} (set (keys (:isaac/cli manifest))))
      (should= #{:server} (set (keys (:isaac.config/schema manifest))))))

  (it "every inline :isaac.config/schema contribution meta-validates"
    (doseq [[config-key {:keys [schema]}] (schema-contributions)]
      (should (map? schema))
      (should-not-throw (isaac.schema.meta/conform-spec! schema))))

  (it "the embedded compaction schemas stay aligned with isaac.session.compaction-schema"
    (let [contributions (schema-contributions)]
      (doseq [path [[:crew :schema :value-spec :schema :compaction :schema]
                    [:models :schema :value-spec :schema :compaction :schema]
                    [:defaults :schema :schema :compaction :schema]]]
        (should= compaction-schema/config-schema (get-in contributions path)))))

  (it "no config path is claimed twice — one schema owner per path (berth :config XOR :isaac.config/schema factory)"
    (let [paths (berths/config-paths (module-loader/builtin-index))]
      (should= [] (->> paths frequencies (keep (fn [[p n]] (when (> n 1) p))) vec))))

  (it "resolves every symbol the manifest references (factories, handlers, check :fns, cli namespaces, bootstrap)"
    (doseq [manifest (builtin-manifests)]
      (doseq [sym (distinct (manifest-symbols manifest))]
        (if (namespace sym)
          (should-not-be-nil (requiring-resolve sym))   ; ns/var reference
          (should-not-throw (require sym)))))))          ; bare namespace (e.g. :cli :namespace)
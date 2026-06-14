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
    [clojure.string :as str]
    [speclj.core :refer :all]))

(defn- read-manifest [path]
  (-> path io/file slurp edn/read-string))

(defn- ensure-local-deps! [path]
  ;; Under bb, dynamically classpath the module so requiring-resolve can
  ;; find its symbols. Under JVM, the test alias in deps.edn already
  ;; pre-declares the modules (clojure.repl.deps/add-libs is REPL-only
  ;; and can't add deps from a spec-runner thread), so this is a no-op.
  (when-let [add-deps (try (requiring-resolve 'babashka.deps/add-deps)
                           (catch Throwable _ nil))]
    (cond
      (str/starts-with? path "modules/")
      (when-let [module-root (second (re-find #"^(modules/[^/]+)" path))]
        (add-deps {:deps {(symbol module-root) {:local/root module-root}}}))

      (str/starts-with? path "../isaac-cron/")
      (add-deps {:deps {'io.github.slagyr/isaac-cron {:local/root "../isaac-cron"}}})

      (str/starts-with? path "../isaac-hail/")
      (add-deps {:deps {'io.github.slagyr/isaac-hail {:local/root "../isaac-hail"}}})

      (str/starts-with? path "../isaac-hooks/")
      (add-deps {:deps {'io.github.slagyr/isaac-hooks {:local/root "../isaac-hooks"}}}))))

(defn- manifest-paths []
  ["resources/isaac-manifest.edn"
   "../isaac-agent/resources/isaac-manifest.edn"
   "../isaac-hail/resources/isaac-manifest.edn"
   "../isaac-hooks/resources/isaac-manifest.edn"
   "../isaac-cron/resources/isaac-manifest.edn"
   "modules/isaac.host/resources/isaac-manifest.edn"])

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
  (->> (manifest-paths)
       (map read-manifest)
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
    (doseq [path (manifest-paths)
            :let [manifest (read-manifest path)]]
      (ensure-local-deps! path)
      (doseq [sym (distinct (manifest-symbols manifest))]
        (if (namespace sym)
          (should-not-be-nil (requiring-resolve sym))   ; ns/var reference
          (should-not-throw (require sym)))))))          ; bare namespace (e.g. :cli :namespace)
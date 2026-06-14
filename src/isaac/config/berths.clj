(ns isaac.config.berths
  (:require
    [clojure.string :as str]
    [isaac.config.schema-base :as schema-base]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.reconfigurable :as reconfigurable]
    [isaac.schema.dynamic :as dynamic]
    [isaac.schema.lexicon :as lexicon]
    [isaac.schema.meta :as meta-schema]
    [isaac.schema.registered-in :as registered-in]))

(def ^:private config-schema-key :isaac.config/schema)

(def Reconfigurable reconfigurable/Reconfigurable)

(defn- ordered-berth-decls [module-index]
  (reduce
    (fn [acc [_module-id entry]]
      (reduce-kv
        (fn [acc berth-id decl]
          (if (contains? acc berth-id)
            acc
            (assoc acc berth-id decl)))
        acc
        (or (get-in entry [:manifest :berths]) {})))
    (sorted-map-by #(compare (str %1) (str %2)))
    (sort-by (comp str key) module-index)))

(defn config-berths [module-index]
  (->> (ordered-berth-decls module-index)
       (keep (fn [[berth-id decl]]
               (when-let [config-decl (:config decl)]
                 [berth-id config-decl])))
       vec))

(declare composed-schema)

(defn- spec-declares-factory?
  "Structural check (no value): would `walk-factory-nodes` ever produce a
   factory node for `spec`? True when the spec, its open-map :value-spec,
   any closed-map :schema field, or a :seq :spec declares a :factory key."
  [spec]
  (boolean
    (when (map? spec)
      (or (:factory spec)
          (and (= :map (:type spec)) (:value-spec spec)
               (spec-declares-factory? (:value-spec spec)))
          (and (= :map (:type spec)) (:schema spec)
               (some spec-declares-factory? (vals (:schema spec))))
          (and (= :seq (:type spec)) (:spec spec)
               (spec-declares-factory? (:spec spec)))))))

(defn compose-config-table-schema
  "The composed inline schema of a :isaac.config/schema contribution:
   meta-conformed, then `:dynamic-schema` gathered (map-form directives
   name their own berth, so the placeholder berth-key is ignored). The
   single composition both schema-compose (effective root) and the
   reconcile engine (node schema) use, so they agree. Computed here —
   not in schema-compose — to keep the reconcile engine a leaf
   dependency."
  [descriptor module-index]
  (let [schema (:schema descriptor)]
    (when (map? schema)
      (dynamic/compose (meta-schema/conform-spec! schema)
                       config-schema-key module-index))))

(defn config-schema-factory-sources
  "[[config-key composed-schema] ...] for every :isaac.config/schema
   table whose composed value-spec carries a :factory. These reconcile
   into the nexus exactly like a berth :config-claimed path; pure-data
   tables (no factory) are skipped. Berth :config is the other source —
   see config-berths — and both are active simultaneously."
  [module-index]
  (->> module-index
       (mapcat (fn [[_module-id entry]]
                 (get-in entry [:manifest config-schema-key])))
       (sort-by (comp str key))
       (keep (fn [[config-key descriptor]]
               (let [schema (compose-config-table-schema descriptor module-index)]
                 (when (spec-declares-factory? schema)
                   [config-key schema]))))
       vec))

(defn reconcile-sources
  "The union of factory'd reconcilable paths: berth :config declarations
   and :isaac.config/schema tables whose value-spec carries a :factory.
   Each source is [path schema] where schema is the composed map/seq spec
   the engine walks for factory nodes. The engine, config-paths, and
   claims-path? all draw from this union."
  [module-index]
  (-> (mapv (fn [[berth-id config-decl]]
              [(:path config-decl) (composed-schema berth-id config-decl module-index)])
            (config-berths module-index))
      (into (mapv (fn [[config-key schema]] [[config-key] schema])
                  (config-schema-factory-sources module-index)))))

(defn config-paths [module-index]
  (mapv first (reconcile-sources module-index)))

(defn claims-path? [module-index path]
  (some #(= path %) (config-paths module-index)))

(defn- open-map-sources
  "[[path schema] ...] from reconcile-sources (berth :config AND
   :isaac.config/schema factory tables) whose composed schema is an
   open map (key-spec + value-spec) — the ones whose error keys want
   slot-bracketing (path[:slot].field)."
  [module-index]
  (->> (reconcile-sources module-index)
       (filter (fn [[_ schema]]
                 (and (= :map (:type schema)) (:value-spec schema))))
       vec))

(defn- open-map-paths [module-index]
  (mapv first (open-map-sources module-index)))

(defn- rewrite-open-map-key [path key]
  (let [segments        (str/split key #"\.")
        prefix-segments (mapv name path)
        prefix-count    (count prefix-segments)]
    (when (and (> (count segments) prefix-count)
               (= prefix-segments (subvec (vec segments) 0 prefix-count)))
      (let [slot      (nth segments prefix-count)
            remainder (drop (inc prefix-count) segments)
            prefix    (str/join "." prefix-segments)]
        (str prefix
             "[:"
             slot
             "]"
             (apply str (map (fn [segment]
                               (if (str/includes? segment "/")
                                 (str "[:"
                                      segment
                                      "]")
                                 (str "." segment)))
                             remainder)))))))

(defn normalize-error-keys [module-index errors]
  (let [paths (open-map-paths module-index)]
    (mapv (fn [entry]
            (if-let [rewritten (some #(rewrite-open-map-key % (:key entry)) paths)]
              (assoc entry :key rewritten)
              entry))
          errors)))

(defn- open-map-field-message [module-index path key]
  (let [prefix (str/join "." (map name path))]
    (some (fn [[src-path schema]]
            (when (= path src-path)
              (let [pattern   (re-pattern (str "^"
                                               (java.util.regex.Pattern/quote prefix)
                                               "\\[:[^\\]]+\\](?:\\.([^\\.\\[]+)|\\[:([^\\]]+)\\])$"))
                    [_ plain bracketed] (re-matches pattern key)
                    field-key (or plain bracketed)]
                (when field-key
                  (get-in schema [:value-spec :schema (keyword field-key) :message])))))
          (open-map-sources module-index))))

(defn normalize-errors [module-index errors]
  (let [rewritten (normalize-error-keys module-index errors)
        paths     (open-map-paths module-index)]
    (mapv (fn [entry]
            (if-let [message (some #(open-map-field-message module-index % (:key entry)) paths)]
              (assoc entry :value message)
              entry))
          rewritten)))

(defn- schema-location [path]
  (vec (mapcat (fn [segment] [:schema segment]) path)))

(defn- composed-schema [berth-id config-decl module-index]
  (dynamic/compose (:schema config-decl) berth-id module-index))

(defn effective-root-schema [root-schema module-index]
  (reduce
    (fn [schema [berth-id config-decl]]
      (assoc-in schema
                (schema-location (:path config-decl))
                (composed-schema berth-id config-decl module-index)))
    root-schema
    (config-berths module-index)))

(defn- resolve-factory! [factory]
  (cond
    (fn? factory) factory
    (symbol? factory) (requiring-resolve factory)
    :else (throw (ex-info (str "invalid config berth factory: " (pr-str factory))
                          {:factory factory
                           :type    :config-berth/invalid-factory}))))

(defn- walk-factory-nodes [path spec value]
  (cond
    (:factory spec)
    [[path spec value]]

    (and (= :map (:type spec)) (:value-spec spec) (map? value))
    (mapcat (fn [[entry-id entry-value]]
              (walk-factory-nodes (conj path entry-id) (:value-spec spec) entry-value))
            value)

    (and (= :map (:type spec)) (:schema spec) (map? value))
    (mapcat (fn [[field child-spec]]
              (when (contains? value field)
                (walk-factory-nodes (conj path field) child-spec (get value field))))
            (:schema spec))

    (and (= :seq (:type spec)) (:spec spec) (sequential? value))
    (mapcat (fn [[idx entry-value]]
              (walk-factory-nodes (conj path idx) (:spec spec) entry-value))
            (map-indexed vector value))

    :else
    []))

(defn- validate-node! [module-index spec value]
  ;; annotation refs (:crew-exists?, [:registered-in? ...]) already ran
  ;; in the load-time semantic pass with full validation context; node
  ;; conform here is shape + coercion only.
  (binding [registered-in/*module-index* module-index]
    (lexicon/conform! (schema-base/strip-validation-annotations (dissoc spec :factory)) value)))

(defn- dotted [path]
  (str/join "." (map #(schema-base/->id %) path)))

(defn- node-impl
  "The impl/variant label for lifecycle logs: a slot's :type when it
   has one, else the slot's own name."
  [node-path slice]
  (schema-base/->id (or (when (map? slice) (or (get slice :type) (get slice "type")))
                        (last node-path))))

(defn- normalize-slot-key
  "Open-map slot keys arrive keyword-keyed from injected configs and
   string-keyed from conformed loads; node paths key slots by keyword."
  [k]
  (keyword (schema-base/->id k)))

(defn- nodes-by-path
  "All factory nodes under `path` for `slice`, keyed by node path."
  [path schema slice]
  (let [slice (if (and (= :map (:type schema)) (:value-spec schema) (map? slice))
                (update-keys slice normalize-slot-key)
                slice)]
    (into {}
          (map (fn [[node-path spec value]] [node-path [spec value]]))
          (walk-factory-nodes path schema slice))))

(defn- create-node! [module-index node-path node-spec slice]
  (let [factory   (resolve-factory! (:factory node-spec))
        conformed (validate-node! module-index node-spec slice)
        node      (binding [registered-in/*module-index* module-index]
                    (factory node-path conformed))]
    (when (some? node)
      (when (satisfies? Reconfigurable node)
        (reconfigurable/on-startup! node conformed))
      (nexus/register! node-path node)
      (log/info :lifecycle/started :path (dotted node-path) :impl (node-impl node-path conformed)))
    node))

(defn- remove-node! [node-path old-slice]
  (when-let [existing (nexus/get-in node-path)]
    (when (satisfies? Reconfigurable existing)
      (reconfigurable/on-config-change! existing old-slice nil))
    (nexus/deregister! node-path)
    (log/info :lifecycle/stopped :path (dotted node-path) :impl (node-impl node-path old-slice))))

(defn- change-node! [module-index node-path node-spec old-slice new-slice]
  (let [existing (nexus/get-in node-path)]
    (cond
      (nil? existing)
      (create-node! module-index node-path node-spec new-slice)

      (not= (node-impl node-path old-slice) (node-impl node-path new-slice))
      (do (remove-node! node-path old-slice)
          (create-node! module-index node-path node-spec new-slice))

      (satisfies? Reconfigurable existing)
      (do (reconfigurable/on-config-change! existing old-slice new-slice)
          (log/info :lifecycle/changed :path (dotted node-path) :impl (node-impl node-path new-slice)))

      :else
      (do (nexus/deregister! node-path)
          (create-node! module-index node-path node-spec new-slice)))))

(defn reconcile!
  "Reconcile factory'd paths against the nexus: factory on appearance,
   on-config-change! when the live node satisfies Reconfigurable
   (recreate when it doesn't), deregister on removal. Paths come from the
   union of berth :config declarations and :isaac.config/schema tables
   whose value-spec carries a :factory (see reconcile-sources). Boot is
   old-config nil; shutdown is config nil — one engine for all three."
  [{:keys [config old-config module-index]}]
  (doseq [[path schema] (reconcile-sources module-index)
          :let [old-nodes (nodes-by-path path schema (get-in old-config path))
                new-nodes (nodes-by-path path schema (get-in config path))]
          node-path (sort-by pr-str (into #{} (concat (keys old-nodes) (keys new-nodes))))]
    (let [[_ old-slice]         (get old-nodes node-path)
          [node-spec new-slice] (get new-nodes node-path)]
      (cond
        (and (nil? old-slice) (some? new-slice))
        (when (nil? (nexus/get-in node-path))
          (create-node! module-index node-path node-spec new-slice))

        (and (some? old-slice) (nil? new-slice))
        (remove-node! node-path old-slice)

        (and (some? new-slice) (not= old-slice new-slice))
        (change-node! module-index node-path node-spec old-slice new-slice))))
  :installed)

(defn install!
  "Boot-shaped reconcile (no previous config)."
  [opts]
  (reconcile! (assoc opts :old-config nil)))

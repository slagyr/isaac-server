(ns isaac.config.validation
  (:require
    [c3kit.apron.schema :as cs]
    [clojure.string :as str]
    [isaac.config.schema-base :as schema-base]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.fs :as fs]
    [isaac.module.loader :as module-loader]
    [isaac.schema.registered-in :as registered-in]))

(def ^:dynamic *config* nil)

(defn- ->id [value]
  (schema-base/->id value))

(defn- runtime-schema [spec]
  (schema-base/strip-validation-annotations spec))

(defn- known-crew-ids [config]
  (->> (keys (:crew config)) (map ->id) distinct sort vec))

(defn- known-model-ids [config]
  (->> (keys (:models config)) (map ->id) distinct sort vec))

(defn- exists-ref [ref-key known-fn message]
  {:validate (fn [value]
               (contains? (or (get-in *config* [:known-sets ref-key])
                              (set (known-fn (or (:raw *config*) *config*))))
                          (->id value)))
   :message  message
   :known    (fn []
               (or (get-in *config* [:known-values ref-key])
                   (known-fn (or (:raw *config*) *config*))))})

(def ^:private existence-refs
  {:model-exists? (exists-ref :model-exists? known-model-ids "references undefined model")
   :crew-exists?  (exists-ref :crew-exists? known-crew-ids "references undefined crew")})

(def ^:private value-refs
  ;; nil-tolerant: apron's conform also resolves these refs and (unlike the
  ;; annotation layer) runs them on absent values.
  {:positive?          {:validate #(or (nil? %) (pos-int? %))
                        :message  "must be a positive integer"}
   :non-negative?      {:validate #(or (nil? %) (and (int? %) (<= 0 %)))
                        :message  "must be a non-negative integer"}
   :absolute-path?     {:validate #(or (nil? %) (and (string? %) (str/starts-with? % "/")))
                        :message  "must be an absolute path"}
   :keyword-set?       {:validate #(or (nil? %) (and (set? %) (every? keyword? %)))
                        :message  "must be a set of keywords"}
   :keyword-or-string? {:validate #(or (nil? %) (keyword? %) (string? %))
                        :message  "must be a keyword or string"}
   :cwd-or-path?       {:validate #(or (nil? %) (= :cwd %) (string? %))
                        :message  "must be :cwd or an absolute path string"}})

(defn- one-of-ref [& allowed]
  {:validate #(or (nil? %) (contains? (set allowed) %))
   :message  (str "must be one of " (str/join ", " allowed))})

(defn- retired-ref [hint]
  {:validate nil?
   :message  (str "retired; " hint)})

(defn- requires-any-ref [& field-keys]
  ;; entity-scope; benign when apron's conform hands it a bare (nil)
  ;; pseudo-field value instead of the entity.
  {:scope    :entity
   :validate (fn [entity & _]
               (or (nil? entity)
                   (boolean (some #(seq (get entity %)) field-keys))))
   :message  (str "must include at least one of "
                  (str/join ", " (map str field-keys)))})

(defn- percentage-ref [hint]
  {:validate #(or (nil? %) (and (number? %) (<= 0.0 %) (< % 1.0)))
   :message  (str "must be a percentage in [0.0, 1.0); " hint)})

(defn- less-than-ref [smaller-key larger-key]
  {:scope    :entity
   :validate (fn [entity & _]
               (let [a (get entity smaller-key)
                     b (get entity larger-key)]
                 (or (nil? a) (nil? b) (< a b))))
   :message  (str (name smaller-key) " must be smaller than " (name larger-key))})

(defn- present-when-ref [other-key expected]
  ;; the discriminator compares by id — conformed configs carry
  ;; string-coerced values where manifests write keywords.
  {:scope    :entity
   :validate (fn [entity field-key]
               (or (not= (->id expected) (->id (get entity other-key)))
                   (cs/present? (get entity field-key))))
   :message  (str "is required when " (name other-key) " is " (->id expected))})

(defonce ^:private _refs-registered
         (do
           (cs/update-lexicon! :validations assoc :one-of? one-of-ref)
           (doseq [[k v] (merge existence-refs value-refs)]
             (cs/update-lexicon! :validations assoc k v))
           (cs/update-lexicon! :validations assoc :present-when? present-when-ref)
           (cs/update-lexicon! :validations assoc :retired? retired-ref)
           (cs/update-lexicon! :validations assoc :requires-any? requires-any-ref)
           (cs/update-lexicon! :validations assoc :percentage? percentage-ref)
           (cs/update-lexicon! :validations assoc :less-than? less-than-ref)
           true))

(defn validation-context [config]
  (let [known-values {:model-exists? (known-model-ids config)
                      :crew-exists?  (known-crew-ids config)}]
    {:raw          config
     :known-values known-values
     :known-sets   (into {} (map (fn [[predicate values]] [predicate (set values)])) known-values)}))

(defn- dotted-path [segments]
  (str/join "." segments))

(defn- path-segment [segment]
  (cond
    (qualified-keyword? segment) (str (namespace segment) "/" (name segment))
    (keyword? segment)           (name segment)
    :else                        (->id segment)))

(defn- exists-at-path? [path]
  (fs/exists? (or (fs/instance) (throw (ex-info "validation requires :fs in system" {}))) path))

(defn- validation-source-file [root key]
  (let [[head id] (str/split key #"\." 3)
        entity-file (when (and root id)
                      (str root "/" head "/" id ".edn"))]
    (cond
      (and entity-file (exists-at-path? entity-file)) (str "config/" head "/" id ".edn")
      :else "config/isaac.edn")))

(defn- validation-error-entry
  ([root key ref-def value]
   (validation-error-entry root key ref-def value nil))
  ([root key ref-def value override-message]
   (let [known-fn (:known ref-def)]
     {:key          key
      :value        (or override-message (:message ref-def))
      :file         (validation-source-file root key)
      :bad-value    (->id value)
      :valid-values (when known-fn (known-fn))})))

(defn- resolve-ref-def [validation]
  (let [[ref-key & args] (if (vector? validation) validation [validation])
        ref-def (try (cs/lex! :validations ref-key) (catch Throwable _ nil))]
    (cond
      (and (fn? ref-def) (seq args)) (apply ref-def args)
      :else ref-def)))

(defn schema-error-entries [prefix result]
  (letfn [(segment-name [segment]
            (cond
              (keyword? segment) (name segment)
              (string? segment) (str/replace-first segment #"^:" "")
              :else (str segment)))
          (join-path [path segment]
            (if path (str path "." segment) segment))
          (entries [path value]
            (if (map? value)
              (mapcat (fn [[field message]]
                        (entries (join-path path (segment-name field)) message))
                      value)
              [{:key path :value value}]))]
    (vec (mapcat (fn [[field message]]
                   (entries (join-path prefix (segment-name field)) message))
                 (cs/message-map result)))))

(defn annotation-errors* [root path spec value & [entity field-key]]
  (let [path-str   (dotted-path path)
        own-errors (->> (:validations spec)
                        (keep (fn [validation]
                                (when-let [ref-def (resolve-ref-def validation)]
                                  (let [present-validation? (or (= :present? validation)
                                                                (and (vector? validation)
                                                                     (= :present? (first validation))))
                                        run-on-nil?         present-validation?]
                                    (try
                                      (let [invalid? (case (:scope ref-def)
                                                       :entity (not ((:validate ref-def) entity field-key))
                                                       (and (or (some? value) run-on-nil?)
                                                            (not ((:validate ref-def) value))))]
                                        (when invalid?
                                          (validation-error-entry root path-str ref-def value)))
                                      (catch clojure.lang.ExceptionInfo e
                                        (validation-error-entry root path-str ref-def value
                                                                (or (:message (ex-data e))
                                                                    (ex-message e))))))))))
        map-errors (when (and (= :map (:type spec)) (map? value))
                     (concat
                       (mapcat (fn [[field-key field-spec]]
                                 (annotation-errors* root (conj path (path-segment field-key)) field-spec (get value field-key) value field-key))
                               (:schema spec))
                       (when-let [value-spec (:value-spec spec)]
                         (mapcat (fn [[entity-id entity-value]]
                                   (when-not (contains? (:schema spec) entity-id)
                                     (annotation-errors* root (conj path (->id entity-id)) value-spec entity-value entity-value nil)))
                                 value))))
        seq-errors (when (and (= :seq (:type spec)) (sequential? value) (:spec spec))
                     (mapcat #(annotation-errors* root path (:spec spec) % % nil) value))]
    (vec (concat own-errors map-errors seq-errors))))

(defn semantic-errors
  ([config] (semantic-errors config nil (schema-compose/cached-root-schema)))
  ([config root] (semantic-errors config root (schema-compose/cached-root-schema)))
  ([config root schema-spec]
   (binding [*config*                    (validation-context config)
             registered-in/*module-index* (merge (module-loader/builtin-index)
                                                 (:module-index config))
             registered-in/*config*       (or (:raw config) config)]
     (annotation-errors* root [] schema-spec config))))

(defn- type-message [field-spec]
  (or (:message field-spec)
      (when-let [field-type (:type field-spec)]
        (case field-type
          :boolean "must be a boolean"
          :int "must be an integer"
          :keyword "must be a keyword"
          :map "must be a map"
          :seq "must be a seq"
          :string "must be a string"
          (str "must be a " (name field-type))))))

(defn- apply-type-messages [field-spec]
  (cond-> (assoc field-spec :message (type-message field-spec))
          (:schema field-spec)
          (update :schema (fn [schema-map]
                            (into {}
                                  (map (fn [[field-key nested-spec]] [field-key (apply-type-messages nested-spec)]))
                                  schema-map)))

          (:spec field-spec)
          (update :spec apply-type-messages)

          (:value-spec field-spec)
          (update :value-spec apply-type-messages)))

(defn- manifest-schema-spec [field-schema]
  {:type   :map
   :schema (into {}
                 (map (fn [[field-key field-spec]] [field-key (apply-type-messages field-spec)]))
                 field-schema)})

(defn- prefix-entry-key [prefix entry]
  (update entry :key #(str prefix "." %)))

(defn- unknown-field-warnings [prefix config field-schema ignored-keys]
  (reduce-kv (fn [warnings field-key _]
               (if (or (contains? field-schema field-key)
                       (contains? ignored-keys field-key))
                 warnings
                 (conj warnings {:key (str prefix "." (name field-key)) :value "unknown key"})))
             []
             config))

(defn- manifest-schema-errors [prefix config field-schema]
  (let [schema-spec       (manifest-schema-spec field-schema)
        type-result       (cs/validate (runtime-schema schema-spec) config)
        type-errors       (schema-error-entries prefix type-result)
        type-error-keys   (into #{} (map :key) type-errors)
        validation-errors (->> (annotation-errors* nil [] schema-spec config)
                               (map #(prefix-entry-key prefix %))
                               (remove #(contains? type-error-keys (:key %)))
                               vec)]
    (into type-errors validation-errors)))

(defn validate-manifest-config [prefix config field-schema & {:keys [ignore-keys warn-unknown?] :or {ignore-keys #{} warn-unknown? true}}]
  {:errors   (manifest-schema-errors prefix config field-schema)
   :warnings (if warn-unknown?
               (unknown-field-warnings prefix config field-schema ignore-keys)
               [])})
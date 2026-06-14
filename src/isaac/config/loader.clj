;; mutation-tested: 2026-05-06
(ns isaac.config.loader
  (:require
    [c3kit.apron.env :as c3env]
    [c3kit.apron.schema :as cs]
    [isaac.schema.lexicon :as lexicon]
    [clj-yaml.core :as yaml]
    [clojure.edn :as edn]
    [clojure.set :as set]
    [clojure.string :as str]
    [isaac.config.berths :as berths]
    [isaac.config.check-compose :as check-compose]
    [isaac.config.companion :as companion]
    [isaac.config.paths :as paths]
    [isaac.config.schema-base :as schema-base]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.config.validation :as validation]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]))

;; region ----- Helpers -----

(def env-overrides* (atom {}))
;; Snapshot of the <root>/.env file, locked at load time (see
;; lock-dotenv!). Avoids re-reading the file on every ${VAR} lookup and removes
;; the need to thread/bind root through the substitution pipeline.
(defonce ^:private dotenv* (atom {}))

(defn- runtime-fs
  ([] (or (fs/instance) (throw (ex-info "config.loader requires :fs in system" {}))))
  ([opts] (or (fs/instance opts) (throw (ex-info "config.loader requires :fs in system" {})))))

(defn- exists?* [path]
  (fs/exists? (runtime-fs) path))

(defn- slurp* [path]
  (fs/slurp (runtime-fs) path))

(defn- children* [path]
  (fs/children (runtime-fs) path))

(defn- read-dotenv [root]
  (let [path (when root (str root "/.env"))]
    (if (and path (exists?* path))
      (let [props (doto (java.util.Properties.)
                    (.load (java.io.StringReader. (or (slurp* path) ""))))]
        (into {} (map (fn [k] [k (.getProperty props k)])) (.stringPropertyNames props)))
      {})))

(defn- lock-dotenv!
  "Snapshots <root>/.env into dotenv*. Called once per load so ${VAR}
   substitution reads a locked map rather than re-reading the file."
  [root]
  (reset! dotenv* (read-dotenv root)))

(defn clear-env-overrides! []
  (reset! env-overrides* {})
  (reset! dotenv* {}))

(defn set-env-override! [name value]
  (swap! env-overrides* assoc name value))

(defn env [name]
  (or (get @env-overrides* name)                            ;; TODO - MDM: c3env allows overrides.  Why reimplement?
      (c3env/env name)
      (get @dotenv* name)))

(def ^:private ->id schema-base/->id)

(defn- runtime-schema [spec]
  (schema-base/strip-validation-annotations spec))

(defn- cached-root-schema []
  (schema-compose/cached-root-schema))

(defn- source-path [relative]
  (str "config/" relative))

(defn- missing-config-message [root]
  (str "no config found; run `isaac init` or create " root "/config/isaac.edn"))

(defn- warning [key value]
  {:key key :value value})

(defn- has-ext? [path ext]
  (str/ends-with? path ext))

(declare overlay-relative)

(defn- split-frontmatter [content]
  (when-let [[_ frontmatter body] (re-matches #"(?s)\A---\r?\n(.*?)\r?\n---\r?\n?(.*)\z" content)]
    {:frontmatter frontmatter
     :body        (str/replace body #"^\r?\n" "")}))

(defn- substitute-env [s]
  (str/replace s #"\$\{([^}]+)\}" (fn [[match var-name]] (or (env var-name) match))))

(defn- substitute-env-recursive [value]
  (cond
    (string? value) (substitute-env value)
    (map? value) (into {} (map (fn [[k v]] [k (substitute-env-recursive v)]) value))
    (sequential? value) (mapv substitute-env-recursive value)
    :else value))

(defn- read-edn-string [content substitute-env?]
  (-> content
      edn/read-string
      ((fn [value]
         (if substitute-env?
           (substitute-env-recursive value)
           value)))))

(defn- read-yaml-string [content substitute-env?]
  (-> (yaml/parse-string content :keywords true)
      ((fn [value]
         (if substitute-env?
           (substitute-env-recursive value)
           value)))))

(defn- read-edn-file [path substitute-env? raw-parse-errors?]
  (try
    {:data (read-edn-string (slurp* path) substitute-env?)}
    (catch Exception e
      {:error (if raw-parse-errors?
                (.getMessage e)
                "EDN syntax error")})))

(def ^:private present? companion/present?)

(defn- assoc-error [result key value]
  (update result :errors conj {:key key :value value}))

(declare schema-for)

(defn- normalize-defaults
  ([defaults] (normalize-defaults (cached-root-schema) defaults))
  ([root-schema defaults]
   (let [result (lexicon/conform (runtime-schema (schema-for root-schema :defaults)) defaults)]
     (if (cs/error? result) {} result))))

(defn- normalize-crew
  ([crew] (normalize-crew (cached-root-schema) crew))
  ([root-schema crew]
   (let [result (lexicon/conform (runtime-schema (schema-for root-schema :crew)) crew)]
     (if (cs/error? result) {} result))))

(defn- normalize-model
  ([model] (normalize-model (cached-root-schema) model))
  ([root-schema model]
   (let [result (lexicon/conform (runtime-schema (schema-for root-schema :models)) model)]
     (if (cs/error? result) {} result))))

(defn- collect-unknown-key-warnings [warnings kind id entity entity-schema]
  (let [entity-fields (schema-base/schema-fields entity-schema)]
    (reduce (fn [acc key]
              (if (contains? entity-fields key)
                acc
                (conj acc (warning (str kind "." id "." (name key)) "unknown key"))))
            warnings
            (keys entity))))

(defn- read-dir-files [root dir-name ext]
  (let [dir (str root "/" dir-name)]
    (->> (or (children* dir) [])
         (filter #(has-ext? % ext))
         sort
         (mapv (fn [name]
                 {:id       (subs name 0 (- (count name) (count ext)))
                  :path     (str dir "/" name)
                  :relative (str dir-name "/" name)})))))

(defn- read-entity-files [root dir-name] (read-dir-files root dir-name ".edn"))
(defn- read-md-files [root dir-name] (read-dir-files root dir-name ".md"))

(defn- overlay-entry [dir-name ext {:keys [overlay-content] :as opts}]
  (when-let [relative (overlay-relative opts)]
    (when (and (str/starts-with? relative (str dir-name "/"))
               (has-ext? relative ext))
      (let [name (last (str/split relative #"/"))]
        {:id       (subs name 0 (- (count name) (count ext)))
         :relative relative
         :content  overlay-content
         :overlay? true}))))

(defn- with-overlay [files overlay]
  (if overlay
    (conj (vec (remove #(= (:relative overlay) (:relative %)) files)) overlay)
    files))

(defn- entry-content [{:keys [content overlay? path]}]
  (if overlay?
    content
    (slurp* path)))

(defn- frontmatter-md-entry? [entry]
  (boolean (split-frontmatter (entry-content entry))))


(defn- overlay-relative [{:keys [overlay-path]}]
  (when (present? overlay-path)
    overlay-path))

(defn- overlay-for [opts relative]
  (when (= relative (overlay-relative opts))
    {:path     (str "<overlay>/" relative)
     :relative relative
     :content  (:overlay-content opts)
     :overlay? true}))

(defn- config-files-present? [root opts]
  (or (overlay-relative opts)
      (exists?* (str root "/" paths/root-filename))
      (some (fn [dir-name]
              (or (seq (read-entity-files root dir-name))
                  (seq (read-md-files root dir-name))))
            (schema-compose/entity-dir-names))))

(defn- schema-for
  ([kind] (schema-for (cached-root-schema) kind))
  ([root-schema kind]
   (schema-compose/schema-for-kind root-schema kind)))

(defn- load-companion-text [path]
  (when path
    {:exists? (exists?* path)
     :text    (when (exists?* path)
                (slurp* path))}))

(defn- resolve-crew-soul [id data load-fn]
  (let [result (companion/resolve-text {:inline  (:soul data)
                                        :load-fn load-fn})]
    {:data  (cond-> data
                    (:value result) (assoc :soul (:value result)))
     :error (when (and (:inline? result) (:companion-exists? result))
              {:key   (str "crew." id ".soul")
               :value "must be set in .edn OR .md"})}))

(defn- resolve-companion-field [ns-prefix field-key id entity load-fn relative]
  (let [result (companion/resolve-text {:inline  (get entity field-key)
                                        :load-fn load-fn})
        errors (cond-> []
                       (and (not (:inline? result)) (not (:companion-exists? result)))
                       (conj {:key   (str ns-prefix id "." (name field-key))
                              :value (str "required (inline or " relative ")")})
                       (and (not (:inline? result)) (:companion-empty? result))
                       (conj {:key   (str ns-prefix id "." (name field-key))
                              :value "must not be empty"}))]
    (when (and (:inline? result) (:companion-exists? result))
      (log/warn :config/companion-inline-wins :field field-key :key (str ns-prefix id) :path relative))
    [(cond-> entity (:value result) (assoc field-key (:value result))) errors]))

(defn- resolve-cron-prompt [id job load-fn relative]
  (let [[resolved errors] (resolve-companion-field "cron." :prompt id job load-fn relative)]
    {:job resolved :errors errors}))

(defn- resolve-cron-prompts [root data]
  (reduce-kv (fn [{:keys [cron errors]} id job]
               (let [id       (->id id)
                     relative (paths/cron-relative id)
                     path     (str root "/" relative)
                     resolved (resolve-cron-prompt id job #(load-companion-text path) relative)]
                 {:cron   (assoc cron id (:job resolved))
                  :errors (into errors (:errors resolved))}))
             {:cron {} :errors []}
             (or (:cron data) {})))

(defn- resolve-hook-template [id hook load-fn relative]
  (let [[resolved errors] (resolve-companion-field "hooks." :template id hook load-fn relative)]
    {:hook resolved :errors errors}))

(defn- resolve-hail-prompt [id band load-fn]
  (let [result (companion/resolve-text {:inline  (:prompt band)
                                        :load-fn load-fn})]
    {:band   (cond-> band
                     (:value result) (assoc :prompt (:value result)))
     :errors []}))

(defn- top-level-warnings
  ([data] (top-level-warnings (cached-root-schema) data))
  ([root-schema data]
   (reduce (fn [acc key]
             (if (contains? (schema-base/schema-fields root-schema) key)
               acc
               (conj acc (warning (name key) "unknown key"))))
           []
           (keys data))))

(defn- root-entity-warning-kinds []
  (remove #{:cron} (schema-compose/merge-root-entity-kinds)))

(defn- root-entity-warnings
  ([raw-data] (root-entity-warnings (cached-root-schema) raw-data))
  ([root-schema raw-data]
   (reduce (fn [warnings kind]
             (reduce-kv (fn [acc id entity]
                          (if (map? entity)
                            (collect-unknown-key-warnings acc (name kind) (->id id) entity (schema-for root-schema kind))
                            acc))
                        warnings
                        (get raw-data kind {})))
           []
           (root-entity-warning-kinds))))

(defn- root-config-warnings
  ([raw-data] (root-config-warnings (cached-root-schema) raw-data))
  ([root-schema raw-data]
   (concat (top-level-warnings root-schema raw-data)
           (root-entity-warnings root-schema raw-data))))

(defn- read-root-config [root {:keys [raw-parse-errors? substitute-env?] :as opts}]
  (let [overlay (overlay-for opts paths/root-filename)
        path    (str root "/" paths/root-filename)]
    (cond
      overlay
      (let [{:keys [content relative]} overlay]
        (try
          (let [raw-data             (read-edn-string content substitute-env?)
                {:keys [cron errors]} (resolve-cron-prompts root raw-data)
                data                 (cond-> raw-data
                                             (:cron raw-data) (assoc :cron cron))]
            {:data     data
             :errors   (vec errors)
             :warnings []
             :sources  [(source-path relative)]})
          (catch Exception _
            {:data nil :errors [{:key paths/root-filename :value "EDN syntax error"}] :warnings [] :sources []})))

      (exists?* path)
      (let [{raw-data :data error :error} (read-edn-file path substitute-env? raw-parse-errors?)]
        (if error
          {:data nil :errors [{:key paths/root-filename :value error}] :warnings [] :sources []}
          (let [{:keys [cron errors]} (resolve-cron-prompts root raw-data)
                data                  (cond-> raw-data
                                              (:cron raw-data) (assoc :cron cron))]
            {:data     data
             :errors   (vec errors)
             :warnings []
             :sources  [(source-path paths/root-filename)]})))

      :else
      {:data nil :errors [] :warnings [] :sources []})))

(defn- validate-root-config
  ([result] (validate-root-config (cached-root-schema) result))
  ([root-schema {:keys [data] :as result}]
   (if-not data
     result
     (let [root-result     (lexicon/conform (runtime-schema root-schema) data)
           defaults-result (when-let [defaults (:defaults data)]
                             (lexicon/conform (runtime-schema (schema-for root-schema :defaults)) defaults))]
       (-> result
           (update :errors into (concat
                                  (when (cs/error? root-result) (validation/schema-error-entries nil root-result))
                                  (when (and defaults-result (cs/error? defaults-result))
                                    (validation/schema-error-entries "defaults" defaults-result))))
           (assoc :warnings (root-config-warnings root-schema data)))))))

(defn- load-root-config [root {:keys [raw-parse-errors? substitute-env?] :as opts}]
  (let [overlay (overlay-for opts paths/root-filename)
        path    (str root "/" paths/root-filename)]
    (cond
      overlay
      (let [{:keys [content relative]} overlay]
        (try
          (let [raw-data        (read-edn-string content substitute-env?)
                {:keys [cron errors]} (resolve-cron-prompts root raw-data)
                data            (cond-> raw-data
                                        (:cron raw-data) (assoc :cron cron))
                root-schema     (cached-root-schema)
                root-result     (lexicon/conform (runtime-schema root-schema) data)
                defaults-result (when-let [defaults (:defaults data)]
                                  (lexicon/conform (runtime-schema (schema-for root-schema :defaults)) defaults))]
            {:data     data
             :errors   (vec (concat errors
                                    (when (cs/error? root-result) (validation/schema-error-entries nil root-result))
                                    (when (and defaults-result (cs/error? defaults-result))
                                      (validation/schema-error-entries "defaults" defaults-result))))
             :warnings (concat (top-level-warnings raw-data)
                               (root-entity-warnings raw-data))
             :sources  [(source-path relative)]})
          (catch Exception _
            {:data nil :errors [{:key paths/root-filename :value "EDN syntax error"}] :warnings [] :sources []})))

      (exists?* path)
      (let [{raw-data :data error :error} (read-edn-file path substitute-env? raw-parse-errors?)]
        (if error
          {:data nil :errors [{:key paths/root-filename :value error}] :warnings [] :sources []}
          (let [{:keys [cron errors]} (resolve-cron-prompts root raw-data)
                data            (cond-> raw-data
                                        (:cron raw-data) (assoc :cron cron))
                root-schema     (cached-root-schema)
                root-result     (lexicon/conform (runtime-schema root-schema) data)
                defaults-result (when-let [defaults (:defaults data)]
                                  (lexicon/conform (runtime-schema (schema-for root-schema :defaults)) defaults))]
            {:data     data
             :errors   (vec (concat errors
                                    (when (cs/error? root-result) (validation/schema-error-entries nil root-result))
                                    (when (and defaults-result (cs/error? defaults-result))
                                      (validation/schema-error-entries "defaults" defaults-result))))
             :warnings (concat (top-level-warnings raw-data)
                               (root-entity-warnings raw-data))
             :sources  [(source-path paths/root-filename)]})))

      :else
      {:data nil :errors [] :warnings [] :sources []})))

(defn- merge-root-entity-with-schema [entity-schema result kind]
  (reduce (fn [acc [id entity]]
            (let [id       (->id id)
                  warnings (collect-unknown-key-warnings [] (name kind) id entity entity-schema)
                  entity   (lexicon/conform (runtime-schema entity-schema) entity)
                  explicit (:id entity)]
              (-> acc
                  (update :warnings into warnings)
                  (cond-> (cs/error? entity)
                          (update :errors into (validation/schema-error-entries (str (name kind) "." id) entity)))
                  (cond-> (and explicit (not= explicit id))
                          (assoc-error (str (name kind) "." id ".id") (str "must match filename (got \"" explicit "\")")))
                  (cond-> (not (cs/error? entity))
                          (assoc-in [:config kind id] (dissoc entity :id))))))
          result
          (get-in result [:root kind])))

(defn- merge-root-entity
  ([result kind]
   (merge-root-entity-with-schema (schema-for kind) result kind))
  ([root-schema result kind]
   (merge-root-entity-with-schema (schema-for root-schema kind) result kind)))

(defn- read-frontmatter-file [{:keys [relative] :as entry} substitute-env? raw-parse-errors?]
  (try
    (if-let [{:keys [body frontmatter]} (split-frontmatter (entry-content entry))]
      {:body body
       :data (read-yaml-string frontmatter substitute-env?)}
      {:error (str relative " is missing YAML frontmatter")})
    (catch Exception e
      {:error (if raw-parse-errors?
                (.getMessage e)
                "YAML syntax error")})))

(defn- entity-files [root dir-name opts]
  (let [edn-files (-> (read-entity-files root dir-name)
                      (with-overlay (overlay-entry dir-name ".edn" opts)))
        md-files  (-> (read-md-files root dir-name)
                      (with-overlay (overlay-entry dir-name ".md" opts)))]
    (if (contains? (schema-compose/frontmatter-entity-dirs) dir-name)
      (let [md-files  (->> md-files
                           (filter frontmatter-md-entry?)
                           (mapv #(assoc % :format :md-frontmatter)))
            edn-files (mapv #(assoc % :format :edn) edn-files)
            md-by-id  (set (map :id md-files))]
        {:files    (vec (sort-by :relative (concat md-files (remove #(contains? md-by-id (:id %)) edn-files))))
         :warnings (mapv (fn [{:keys [id relative]}]
                           (warning relative (str "single-file config overrides legacy " dir-name "/" id ".edn")))
                         (filter #(contains? (set (map :id edn-files)) (:id %)) md-files))})
      {:files    (vec (sort-by :relative (map #(assoc % :format :edn) edn-files)))
       :warnings []})))

(defn- read-entity-entry [entry substitute-env? raw-parse-errors?]
  (let [{:keys [content format overlay? path]} entry]
    (case format
      :md-frontmatter
      (read-frontmatter-file entry substitute-env? raw-parse-errors?)

      (if overlay?
        (try
          {:data (read-edn-string content substitute-env?)}
          (catch Exception e
            {:error (if raw-parse-errors? (.getMessage e) "EDN syntax error")}))
        (read-edn-file path substitute-env? raw-parse-errors?)))))

(defn- resolve-entity-data [root kind id format raw-data body]
  (if-not (map? raw-data)
    {:data raw-data :error nil :extra-errors []}
    (let [{:keys [companion entity-dir]} (schema-compose/descriptor-for kind)
          load-md? (= format :md-frontmatter)
          load-fn  (fn [] {:exists? true :text body})]
      (case (:field companion)
        :soul
        (let [{resolved-data :data companion-error :error}
              (resolve-crew-soul id raw-data (if load-md?
                                               load-fn
                                               #(load-companion-text (str root "/" (paths/soul-relative id)))))]
          {:data resolved-data :error companion-error :extra-errors []})

        :prompt
        (if (= kind :hail)
          (let [{resolved-band :band prompt-errors :errors}
                (resolve-hail-prompt id raw-data #(load-companion-text (str root "/" entity-dir "/" id ".md")))]
            {:data resolved-band :error nil :extra-errors prompt-errors})
          (let [relative (paths/cron-relative id)
                {resolved-job :job prompt-errors :errors}
                (resolve-cron-prompt id raw-data (if load-md?
                                                   load-fn
                                                   #(load-companion-text (str root "/" relative)))
                                     relative)]
            {:data resolved-job :error nil :extra-errors prompt-errors}))

        :template
        (let [relative (paths/hook-relative id)
              {resolved-hook :hook template-errors :errors}
              (resolve-hook-template id raw-data (if load-md?
                                                   load-fn
                                                   #(load-companion-text (str root "/" relative)))
                                     relative)]
          {:data resolved-hook :error nil :extra-errors template-errors})

        {:data raw-data :error nil :extra-errors []}))))

(defn- finalize-entity-load-with-schema [entity-schema result kind id relative data extra-errors]
  (let [warnings    (collect-unknown-key-warnings [] (name kind) id data entity-schema)
        entity      (lexicon/conform (runtime-schema entity-schema) data)
        explicit-id (:id entity)
        result      (-> result
                        (update :warnings into warnings)
                        (update :errors into extra-errors))
        result      (if (cs/error? entity)
                      (update result :errors into (validation/schema-error-entries (str (name kind) "." id) entity))
                      result)
        result      (if (and explicit-id (not= explicit-id id))
                      (assoc-error result (str (name kind) "." id ".id") (str "must match filename (got \"" explicit-id "\")"))
                      result)
        result      (if (and (get-in (:config result) [kind id])
                             (get-in (:root result) [kind id]))
                      (assoc-error result (str (name kind) "." id) (str "defined in both isaac.edn and " relative))
                      result)]
    (if (or (some? (get-in (:root result) [kind id]))
            (cs/error? entity))
      (update result :sources conj (source-path relative))
      (-> result
          (assoc-in [:config kind id] (dissoc entity :id))
          (assoc-in [:raw kind id] (dissoc data :id))
          (update :sources conj (source-path relative))))))

(defn- finalize-entity-load
  ([result kind id relative data extra-errors]
   (finalize-entity-load-with-schema (schema-for kind) result kind id relative data extra-errors))
  ([root-schema result kind id relative data extra-errors]
   (finalize-entity-load-with-schema (schema-for root-schema kind) result kind id relative data extra-errors)))

(defn- load-entity-file
  ([result root kind entry substitute-env? raw-parse-errors?]
   (let [{:keys [format id relative]} entry
         {raw-data :data error :error body :body} (read-entity-entry entry substitute-env? raw-parse-errors?)
         {data :data error :error extra-errors :extra-errors}
         (if error
           {:data raw-data :error error :extra-errors []}
           (resolve-entity-data root kind id format raw-data body))]
     (cond
       error
       (if (map? error)
         (update result :errors conj error)
         (assoc-error result relative error))

       (not (map? data))
       (assoc-error result relative "must contain a map")

       :else
       (finalize-entity-load result kind id relative data extra-errors))))
  ([root-schema result root kind {:keys [format id relative] :as entry} substitute-env? raw-parse-errors?]
   (let [{raw-data :data error :error body :body} (read-entity-entry entry substitute-env? raw-parse-errors?)
         {data :data error :error extra-errors :extra-errors}
         (if error
           {:data raw-data :error error :extra-errors []}
           (resolve-entity-data root kind id format raw-data body))]
     (cond
       error
       (if (map? error)
         (update result :errors conj error)
         (assoc-error result relative error))

       (not (map? data))
       (assoc-error result relative "must contain a map")

       :else
       (finalize-entity-load root-schema result kind id relative data extra-errors)))))

(defn- dangling-entry-kind [kind]
  (case kind
    :hooks "hook"
    :models "model"
    :providers "provider"
    (name kind)))

(defn- dangling-md-warnings [root root-data opts]
  (let [root-data (or root-data {})
        inline-ids (fn [kind]
                     (let [ids (->> (keys (get root-data kind {})) (map ->id) set)]
                       (if (= kind :hooks)
                         (into ids (->> (keys (get root-data :hooks {}))
                                        (filter string?)
                                        (map ->id)))
                         ids)))
        file-ids   (fn [dir-name] (->> (entity-files root dir-name opts) :files (map :id) set))
        warn-for   (fn [kind dir-name]
                     (->> (read-md-files root dir-name)
                          (remove #(contains? (into (inline-ids kind) (file-ids dir-name)) (:id %)))
                          (mapv #(warning (:relative %)
                                          (str "dangling: no matching " (dangling-entry-kind kind) " entry")))))]
    (vec (mapcat (fn [[kind {:keys [entity-dir]}]]
                   (when entity-dir
                     (warn-for kind entity-dir)))
                 (schema-compose/descriptors)))))

(defn- normalize-cron-config [cfg]
  (if (map? (:cron cfg))
    (into {} (map (fn [[id entity]]
                    [(->id id) (cond-> entity
                                       (:crew entity) (update :crew ->id))]))
          (:cron cfg))
    {}))

(defn- modern-crew-map? [crew-block]
  (and (map? crew-block)
       (empty? (set/intersection #{:defaults :list :models} (set (keys crew-block))))))

(defn- normalize-crew-config
  ([crew-block] (normalize-crew-config (cached-root-schema) crew-block))
  ([root-schema crew-block]
   (let [old-crew-list (or (:list crew-block) [])]
    (cond
      (modern-crew-map? crew-block)
      (into {} (map (fn [[id entity]] [(->id id) (normalize-crew root-schema entity)])) crew-block)

      (seq old-crew-list)
      (into {} (map (fn [entity] [(->id (:id entity)) (normalize-crew root-schema entity)])) old-crew-list)

      :else
      {}))))

(defn- normalize-model-config
  ([cfg crew-block] (normalize-model-config (cached-root-schema) cfg crew-block))
  ([root-schema cfg crew-block]
   (let [old-models (or (:models crew-block) {})]
    (cond
      (and (map? (:models cfg))
           (not (vector? (:models cfg)))
           (not (:providers (:models cfg))))
      (into {} (map (fn [[id entity]] [(->id id) (normalize-model root-schema entity)])) (:models cfg))

      (seq old-models)
      (into {} (map (fn [[id entity]] [(->id id) (normalize-model root-schema entity)])) old-models)

      :else
      {}))))

(defn- normalize-provider-config
  ([cfg] (normalize-provider-config (cached-root-schema) cfg))
  ([_root-schema cfg]
   (let [old-providers (or (get-in cfg [:models :providers]) [])]
    (cond
      (map? (:providers cfg))
      (into {} (map (fn [[id entity]] [(->id id) entity])) (:providers cfg))

      (seq old-providers)
      (into {} (map (fn [entity] [(->id (or (:id entity) (:name entity))) (dissoc entity :name)])) old-providers)

      :else
      {}))))

(defn- assoc-present-keys [result source keys]
  (reduce (fn [acc k]
            (if (contains? source k)
              (assoc acc k (get source k))
              acc))
          result
          keys))

(def ^:private extra-present-config-keys [:dev :module-index :root])

(defn- present-config-keys [root-schema]
  (concat extra-present-config-keys
          (remove (schema-compose/normalized-config-keys)
                  (keys (schema-base/schema-fields root-schema)))))

(defn normalize-config
  ([cfg] (normalize-config (cached-root-schema) cfg))
  ([root-schema cfg]
   (let [crew-block    (or (:crew cfg) {})
        defaults      (or (:defaults cfg) (:defaults crew-block) {})
        new-cron      (normalize-cron-config cfg)
        new-crew      (normalize-crew-config root-schema crew-block)
        new-models    (normalize-model-config root-schema cfg crew-block)
        new-providers (normalize-provider-config root-schema cfg)]
    (assoc-present-keys {:defaults  (normalize-defaults root-schema defaults)
                         :crew      new-crew
                         :models    new-models
                         :providers new-providers
                         :cron      new-cron}
                        (cond-> cfg
                                (contains? cfg :cron) (assoc :cron new-cron))
                        (present-config-keys root-schema)))))

;; endregion ^^^^^ Helpers ^^^^^

;; region ----- Loading -----

(defn- slice-unknown-key-warnings
  "Unknown-key warnings for an open-map berth slice — conform strips
   unknown keys silently, so they are collected first. Shallow: one
   warning per unknown field in each slot map."
  [path spec slice]
  (let [known (set (keys (get-in spec [:value-spec :schema])))]
    (when (and (= :map (:type spec)) (seq known) (map? slice))
      (for [[slot-id slot] slice
            :when (map? slot)
            [field _] slot
            :when (not (contains? known field))]
        {:key   (str/join "." (concat (map name path) [(->id slot-id) (name field)]))
         :value "unknown key"}))))

(defn- nested-unknown-key-warnings
  "Recursively collect unknown-key warnings for a config value against
   its schema. A closed map (a :schema, no :value-spec) rejects keys
   absent from the schema and descends into the known ones; an open map
   (:value-spec) accepts any key and descends into each value against the
   shared value-spec. The root conform pass strips these silently, so a
   statically-declared config table (e.g. :tools) needs them gathered."
  [path spec data]
  (when (and (= :map (:type spec)) (map? data))
    (if-let [value-spec (:value-spec spec)]
      (mapcat (fn [[k v]] (nested-unknown-key-warnings (conj path k) value-spec v)) data)
      (when-let [known (:schema spec)]
        (concat
          (for [[k _] data :when (not (contains? known k))]
            {:key (str/join "." (map ->id (conj path k))) :value "unknown key"})
          (mapcat (fn [[k v]]
                    (when-let [child (get known k)]
                      (nested-unknown-key-warnings (conj path k) child v)))
                  data))))))

(defn- config-table-warnings
  "Unknown-key warnings for the statically-declared top-level config
   tables — every table except those whose warnings are produced by the
   berth-slice pass (berth-claimed paths) or the entity-collection pass
   (entity-dir kinds), which would otherwise double-report."
  [root-schema raw-data handled]
  (mapcat (fn [[key spec]]
            (when-not (contains? handled key)
              (nested-unknown-key-warnings [key] spec (get raw-data key))))
          (:schema root-schema)))

(defn- conform-berth-slices
  "Conform each config-berth-claimed slice of `config` against its
   composed schema from the effective root (validations stripped — the
   annotation layer owns those), storing the coerced values back.
   Uncoercible values become error rows, unknown fields warning rows;
   berths/normalize-errors rewrites their keys downstream."
  [module-index root-schema config]
  (reduce
    (fn [acc path]
      (let [slice (get-in (:config acc) path)]
        (if (nil? slice)
          acc
          (let [spec      (get-in root-schema (vec (mapcat (fn [segment] [:schema segment]) path)))
                warnings  (slice-unknown-key-warnings path spec slice)
                conformed (lexicon/conform (runtime-schema spec) slice)
                acc       (update acc :warnings into warnings)]
            (if (cs/error? conformed)
              (update acc :errors into (validation/schema-error-entries
                                         (str/join "." (map name path)) conformed))
              (assoc-in acc (into [:config] path) conformed))))))
    {:config config :errors [] :warnings []}
    (berths/config-paths module-index)))

;; Config-berth compose/check collisions across modules (two modules
;; disagreeing on a table's shell, a duplicate check id, an unresolvable
;; check :fn) throw deep in schema-compose/check-compose. Catch them at
;; the load boundary so `config validate` reports a located error row
;; instead of a stack trace. (isaac-un18 already turned the common
;; per-entry config-schema collision into a warning; these are the
;; residual structural/check throws.)
(def ^:private compose-error-types #{:config-schema/collision :config-schema/invalid-schema})
(def ^:private check-error-types   #{:config-check/collision :config-check/missing-fn :config-check/invalid-fn})

(defn- collision-error-row [prefix id-key e]
  {:key   (if-let [id (id-key (ex-data e))] (str prefix "." (name id)) prefix)
   :value (ex-message e)})

(defn- compose-or-fallback
  "Compose the effective root schema; on a config-schema collision /
   invalid-schema, fall back to the builtin composition (which cannot
   collide — only user modules do) and return the error so the load
   reports it located and keeps going."
  [module-index]
  (try [(schema-compose/cache-composed! module-index) nil]
       (catch clojure.lang.ExceptionInfo e
         (if (compose-error-types (:type (ex-data e)))
           [(schema-compose/cache-composed! (module-loader/builtin-index))
            (collision-error-row "config-schema" :config-key e)]
           (throw e)))))

(defn load-config-result
  [& [{:keys [root raw-parse-errors? substitute-env? skip-entity-files? data-path-overlay]
       :or   {substitute-env? true}
       :as   opts}]]
  (let [fs*  (runtime-fs opts)
        opts (assoc opts :fs fs* :substitute-env? substitute-env?)]
    (nexus/-with-nested-nexus {:fs fs*}
                              (lock-dotenv! root)
                              (let [config-root (paths/config-root root)]
                                (if-not (config-files-present? config-root opts)
                                  {:config          {:root root}
                                   :errors          [{:key "config" :value (missing-config-message root)}]
                                   :missing-config? true
                                   :warnings        []
                                   :sources         []}
                                  (let [root-read       (read-root-config config-root opts)
                                        root-data       (:data root-read)
                                        discovery-input (cond-> {}
                                                          (contains? root-data :modules) (assoc :modules (:modules root-data)))
                                        discovery       (module-loader/discover! discovery-input {:root root
                                                                                                  :cwd  (System/getProperty "user.dir")})
                                        [effective-schema compose-error] (compose-or-fallback (:index discovery))
                                        {root-errors :errors root-warnings :warnings root-sources :sources}
                                        (validate-root-config effective-schema root-read)
                                        entity-kinds     (->> (schema-compose/descriptors)
                                                              (keep (fn [[kind {:keys [entity-dir]}]]
                                                                      (when entity-dir [kind entity-dir])))
                                                              vec)
                                        entity-files-by-kind
                                        (into {} (map (fn [[kind dir]]
                                                        [kind (entity-files config-root dir opts)])
                                                      entity-kinds))
                                        md-warnings      (dangling-md-warnings config-root root-data opts)
                                        base-config      (normalize-config effective-schema (or root-data {}))
                                        result           {:config          base-config
                                                          :errors          root-errors
                                                          :missing-config? false
                                                          :warnings        (vec (concat root-warnings
                                                                                        (config-table-warnings
                                                                                          effective-schema root-data
                                                                                          (into (set (map first entity-kinds))
                                                                                                (map first (berths/config-paths (:index discovery)))))
                                                                                        (mapcat :warnings (vals entity-files-by-kind))
                                                                                        md-warnings))
                                                          :sources         root-sources
                                                          :root            (normalize-config effective-schema (or root-data {}))}
                                        result           (reduce (fn [acc kind]
                                                                   (merge-root-entity effective-schema acc kind))
                                                                 result
                                                                 (schema-compose/merge-root-entity-kinds))
                                        result           (if skip-entity-files?
                                                             result
                                                             (reduce (fn [acc [kind _dir]]
                                                                       (reduce (fn [a entity-file]
                                                                                 (load-entity-file effective-schema a config-root kind
                                                                                                     entity-file substitute-env? raw-parse-errors?))
                                                                               acc
                                                                               (:files (get entity-files-by-kind kind))))
                                                                     result
                                                                     entity-kinds))
                                        config           (update (:config result) :defaults #(normalize-defaults effective-schema %))
                                        config           (if data-path-overlay
                                                           (assoc-in config (:path data-path-overlay) (:value data-path-overlay))
                                                           config)
                                        slices           (conform-berth-slices (:index discovery) effective-schema config)
                                        config           (assoc (:config slices)
                                                           :module-index (:index discovery)
                                                           :root root)
                                        raw-providers    (merge (get-in result [:root :providers])
                                                                (get-in result [:raw :providers]))
                                        check-ctx        {:config           config
                                                            :raw-providers    raw-providers
                                                            :module-index     (:index discovery)
                                                            :root             config-root
                                                            :result           result
                                                            :effective-schema effective-schema}
                                        contributed      (try (check-compose/run-checks (:index discovery) check-ctx)
                                                              (catch clojure.lang.ExceptionInfo e
                                                                (if (check-error-types (:type (ex-data e)))
                                                                  {:errors [(collision-error-row "config-check" :check-id e)] :warnings []}
                                                                  (throw e))))
                                        errors           (->> (concat (validation/semantic-errors config config-root effective-schema)
                                                                      (:errors discovery)
                                                                      (:errors contributed)
                                                                      (:errors slices)
                                                                      (when compose-error [compose-error]))
                                                            (into (:errors result))
                                                            (berths/normalize-errors (:index discovery)))]
                                    {:config   config
                                     :errors   (vec (distinct (sort-by :key errors)))
                                     :warnings (->> (concat (:warnings result) (:warnings contributed) (:warnings slices))
                                                    (berths/normalize-errors (:index discovery))
                                                    (sort-by :key)
                                                    vec)
                                     :sources  (vec (sort (:sources result)))}))))))

;; endregion ^^^^^ Loading ^^^^^

;; region ----- Ambient Config Snapshot -----

(defn- config-atom []
  (or (nexus/get :config)
      (let [cfg* (atom nil)]
        (nexus/register! [:config] cfg*)
        cfg*)))

(defn snapshot
  "Returns the current process-wide config, or nil if not yet initialized.
   Reads ambient config; call ONLY at entry points and wake boundaries (process
   start, request/turn entry, a worker waking from sleep) — in-flight code must
   receive config as a value, not pull a fresh snapshot. `reason` is a short
   string documenting why this site reads ambient config; it keeps such reads
   greppable and reviewable. See set-snapshot!."
  [reason]
  @(config-atom))

(defn set-snapshot!
  "Low-level primitive: reset the process-wide config snapshot to `cfg`. Internal
   to config — callers use load-config! (load + commit) or, for an already-built
   value, dangerously-install-config!. `reason` documents the call site."
  [cfg reason]
  (log/debug :config/set-snapshot :reason reason)
  (reset! (config-atom) cfg)
  cfg)

(defn load-config!
  "THE loader: load config from `root` (read via `fs`), validate it, commit
   it as the process-wide snapshot, and return the value. Call once at an entry
   point, then thread the returned value onward (or read the snapshot). Throws
   ex-info {:errors [...]} carrying ALL validation/coercion errors when the
   config is invalid (a missing config is not an error — it commits the empty
   default). `reason` documents the call site."
  [root fs reason]
  (let [{:keys [config errors missing-config?]}
        (load-config-result {:root root :fs fs})]
    (when (and (seq errors) (not missing-config?))
      (throw (ex-info (str "invalid configuration in " root)
                      {:errors errors :root root})))
    (set-snapshot! config reason)
    config))

(defn load-config
  "Compatibility wrapper for older module repos. Loads config and returns only
   the config value without committing it as the process snapshot."
  ([] (:config (load-config-result)))
  ([opts] (:config (load-config-result opts))))

(defn root
  "Returns the resolved root. Test fixtures install an explicit
   :root on the nexus via -with-nested-nexus and that wins; otherwise the
   loaded config carries :root (derived from home). Production never
   installs the nexus slot, so the config snapshot is authoritative there."
  []
  (or (nexus/get :root)
      (:root (snapshot "root resolution — ambient config fallback"))))

;; endregion ^^^^^ Ambient Config Snapshot ^^^^^

;; region ----- Workspace -----

(defn resolve-workspace
  [crew-id & [{:keys [root] :as opts}]]
  (let [fs*       (runtime-fs opts)
        crew-dir  (str root "/crew/" crew-id)
        isaac-dir (str root "/workspace-" crew-id)
        ;; Legacy ~/.openclaw lives beside ~/.isaac, so it only applies when the
        ;; root is a .isaac directory under a user home.
        oc-dir    (when (str/ends-with? (str root) "/.isaac")
                    (str (subs root 0 (- (count root) (count "/.isaac")))
                         "/.openclaw/workspace-" crew-id))]
    (nexus/-with-nested-nexus {:fs fs*}
                              (cond
                                (some? (children* crew-dir)) crew-dir
                                (and oc-dir (some? (children* oc-dir))) oc-dir
                                (some? (children* isaac-dir)) isaac-dir
                                :else nil))))

(defn read-workspace-file
  [crew-id filename & [{:as opts}]]
  (let [fs* (runtime-fs opts)]
    (nexus/-with-nested-nexus {:fs fs*}
                              (when-let [ws-dir (resolve-workspace crew-id opts)]
                                (let [path (str ws-dir "/" filename)]
                                  (when (exists?* path)
                                    (slurp* path)))))))

;; endregion ^^^^^ Workspace ^^^^^

;; Module-loader registration: dispatched by module.loader when reading
;; user-supplied config for a module's :tools or :slash-commands entry.
(module-loader/register-handler! :user-config
                                 (fn [root-key entry-id]
                                   (let [snap (snapshot "module :user-config handler — ambient config lookup")]
                                     (or (get-in snap [root-key entry-id])
                                         (get-in snap [root-key (keyword entry-id)])))))

;; endregion ^^^^^ Resolution ^^^^^

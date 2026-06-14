(ns isaac.llm.provider
  "Leaf namespace: resolves a (name, config) pair to a concrete Api
   instance via the open registry. Lives below charge/config/drive in
   the dep graph so it can be a plain :require from any of them
   without forming a cycle."
  (:require
    [clojure.string :as str]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.providers :as providers]
    [isaac.module.loader :as module-loader]))

(defn- levenshtein [^String s ^String t]
  (let [m (.length s) n (.length t)]
    (if (zero? m)
      n
      (loop [prev (vec (range (inc n))) i 0]
        (if (= i m)
          (peek prev)
          (recur (reduce (fn [row j]
                           (conj row (min (inc (peek row))
                                          (inc (nth prev (inc j)))
                                          (+ (nth prev j)
                                             (if (= (.charAt s i) (.charAt t j)) 0 1)))))
                         [(inc i)]
                         (range n))
                 (inc i)))))))

(defn- did-you-mean [name pool]
  (->> pool
       (remove #(= name %))
       (filter #(<= (levenshtein name %) 2))
       (sort-by #(levenshtein name %))
       first))

(defn- simulated-provider-target [provider]
  (when (and (string? provider) (str/starts-with? provider "grover:"))
    (subs provider (count "grover:"))))

(defn normalize-pair
  "Merges the provider's catalog defaults into `provider-config` and
   resolves any grover:<target> alias to the underlying target name.
   Returns [resolved-name merged-config]."
  [provider provider-config]
  (if-let [target (simulated-provider-target provider)]
    [target    (merge (providers/grover-defaults target) (or provider-config {}))]
    [provider  (merge (providers/defaults provider)      (or provider-config {}))]))

(defn- unknown-provider-message [provider-name configured templates]
  (let [template-set            (set templates)
        template-match?         (contains? template-set provider-name)
        suggestion              (or (did-you-mean provider-name configured)
                                    (did-you-mean provider-name templates))
        suggestion-is-template? (and suggestion (contains? template-set suggestion))
        configured-str          (if (seq configured) (str/join ", " configured) "(none)")
        templates-str           (str/join ", " templates)
        hint                    (cond
                                  template-match?
                                  (str "\"" provider-name "\" is a built-in template; instantiate it by writing config/providers/" provider-name ".edn")
                                  suggestion-is-template?
                                  (str "did you mean \"" suggestion "\"? (built-in template; instantiate by writing config/providers/" suggestion ".edn)")
                                  suggestion
                                  (str "did you mean \"" suggestion "\"?"))]
    (str "unknown provider \"" provider-name "\""
         (when hint (str "; " hint))
         " — configured: " configured-str
         " — known templates: " templates-str)))

(deftype UnknownApiProvider [provider-name configured-providers templates]
  api/Api
  (chat [_ _] {:error :unknown-provider :message (unknown-provider-message provider-name configured-providers templates)})
  (chat-stream [_ _ _] {:error :unknown-provider :message (unknown-provider-message provider-name configured-providers templates)})
  (followup-messages [_ request _ _ _] (:messages request))
  (config [_] {})
  (display-name [_] provider-name)
  (format-tools [_ _] nil)
  (build-prompt [_ opts] {:model (:model opts) :messages []}))

(defn make-provider
  "Resolve (name, config) to an Api instance via the open registry.
   Each provider impl namespace registers a factory at load time
   (see e.g. isaac.llm.api.messages). Returns an UnknownApiProvider
   (whose chat/chat-stream emit an error response) when the api cannot
   be found."
  [name provider-config]
  (let [[name cfg]   (normalize-pair name provider-config)
        module-index (merge (module-loader/builtin-index) (:module-index cfg))
        user-keys    (->> (or (:providers cfg) {})
                          keys
                          (map (fn [k] (if (keyword? k) (clojure.core/name k) (str k)))))
        configured   (sort (distinct
                             (concat user-keys
                                     (keys (providers/module-providers module-index)))))
        templates    (sort (providers/known-providers))
        api-id       (api/resolve-api name cfg)
        factory      (or (api/factory-for api-id)
                         ;; Phase 7 of brth (isaac-ho18): api registrations
                         ;; flow through the :isaac.server/llm-api berth
                         ;; factory rather than activate!'s register-extensions!
                         ;; pass. Activate the providing module (for its
                         ;; bootstrap), then invoke the berth's per-entry
                         ;; factory directly so the api lands in api/-registry.
                         (when-let [module-id (module-loader/supporting-module-id module-index :isaac.server/llm-api api-id)]
                           (module-loader/activate! module-id module-index)
                           (let [entry (get-in module-index [module-id :manifest :isaac.server/llm-api (keyword api-id)])]
                             (when entry
                               (api/register-api-entry! [(keyword api-id) entry])))
                           (api/factory-for api-id)))]
    (if factory
      (factory name cfg)
      (UnknownApiProvider. name configured templates))))

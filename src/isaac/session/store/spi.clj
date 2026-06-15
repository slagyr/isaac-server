(ns isaac.session.store.spi
  (:require
    [clojure.string :as str]
    [isaac.naming :as naming]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]))

;; session.store is a dependency of config.install, so it can't require the
;; config namespace (config.api -> install -> store would cycle). It reads the
;; config snapshot straight from the nexus :config slot instead.
(defn- config-snapshot []
  (some-> (nexus/get :config) deref))

(defprotocol SessionStore
  (open-session! [this name opts])
  (delete-session! [this name])
  (list-sessions [this])
  (list-sessions-by-agent [this agent])
  (most-recent-session [this])
  (get-session [this name])
  (get-transcript [this name])
  (active-transcript [this name])
  (update-session! [this name updates])
  (append-message! [this name message])
  (append-error! [this name error])
  (append-compaction! [this name compaction])
  (splice-compaction! [this name compaction])
  (truncate-after-compaction! [this name]))

(defonce ^:private in-flight* (atom {}))

(defn- crew-max-in-flight [crew-name]
  (or (get-in (config-snapshot) [:crew crew-name :max-in-flight]) 1))

(defn mark-in-flight! [store session-id]
  (let [crew-name (:crew (get-session store session-id))
        [old new] (swap-vals! in-flight*
                              update store
                              (fn [state]
                                (let [state    (or state {})
                                      sessions (or (:sessions state) {})]
                                  (if (contains? sessions session-id)
                                    state
                                    (assoc state :sessions (assoc sessions session-id crew-name))))))]
    (and (not (contains? (get-in old [store :sessions] {}) session-id))
         (contains? (get-in new [store :sessions] {}) session-id))))

(defn clear-in-flight! [store session-id]
  (swap! in-flight*
         update store
         (fn [state]
           (let [sessions (dissoc (or (:sessions state) {}) session-id)]
             (when (seq sessions)
               (assoc (or state {}) :sessions sessions))))))

(defn in-flight? [store session-id]
  (contains? (get-in @in-flight* [store :sessions] {}) session-id))

(defn in-flight-count [store crew-name]
  (->> (vals (get-in @in-flight* [store :sessions] {}))
       (filter #(= crew-name %))
       count))

(defn can-dispatch? [store crew-name]
  (< (in-flight-count store crew-name) (crew-max-in-flight crew-name)))

(defn tags-of [session]
  (or (:tags session) #{}))

(defn has-tag? [session tag]
  (contains? (tags-of session) tag))

(defn by-tags [store tag-set]
  (->> (list-sessions store)
       (filter (fn [session]
                 (every? #(has-tag? session %) tag-set)))))

;; ----- Impl factory registry -----
;; Each impl namespace (memory/file/index) implements SessionStore (so requires
;; this ns) and registers its create-store fn at load time. We can't require
;; them from here without forming a cycle.
(defonce ^:private factories* (atom {}))

(defn register-factory!
  "Each session store impl namespace calls this at load time."
  [impl-kw factory]
  (swap! factories* assoc impl-kw factory))

(def ^:private default-impl :jsonl-edn-sidecar)
(def ^:private impl->ns
  {:memory          'isaac.session.store.memory
   :jsonl-edn-index 'isaac.session.store.index
   default-impl     'isaac.session.store.sidecar})

(defn create
  "Create a SessionStore for the given state directory and impl keyword.
   :memory            — in-memory store (ephemeral, fast)
   :jsonl-edn-sidecar — file store with per-session EDN sidecar files (default)
   :jsonl-edn-index   — file store with single combined index"
  ([root] (create root default-impl))
  ([root impl]
    (let [factory (or (get @factories* impl)
                      (when-let [ns-sym (get impl->ns impl)]
                        ;; Lazy-load the impl ns only when no test/runtime
                        ;; factory was registered first.
                        (require ns-sym)
                        (get @factories* impl)))]
      (if factory
        (factory root)
        (throw (ex-info (str "no session store factory for impl " impl)
                        {:impl impl :registered (vec (sort (keys @factories*)))}))))))

(defn- name->id
  "Convert a display name to a session ID slug, matching the store's key format."
  [s]
  (let [slug (-> (or s "")
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))]
    (if (str/blank? slug) "session" slug)))

(defrecord SessionDomain [session-store]
  naming/NamedDomain
  (name-taken? [_ name]
    (some? (get-session session-store (name->id name)))))

(defn- naming-strategy-kw [cfg]
  (let [value (get-in cfg [:sessions :naming-strategy])]
    (cond (keyword? value) value
          (string? value)  (keyword value)
          :else            :adjective-noun)))

(defn make-naming-strategy
  "Build a long-lived NameStrategy record from config, root, a live session store, and fs."
  [cfg root session-store fs*]
  (case (naming-strategy-kw cfg)
    :sequential (naming/->SequentialStrategy root "sessions" "session-" fs*)
    (naming/->AdjectiveNounStrategy (->SessionDomain session-store) naming/adjectives naming/nouns)))

(defn registered-store
  "Returns the session store registered in the system, or nil."
  []
  (nexus/get-in [:sessions :store]))

(defn ensure-naming-strategy!
  "Returns the registered naming strategy, building and caching it from config if not yet present."
  [root fs*]
  (or (nexus/get-in [:sessions :naming-strategy])
      (let [cfg   (or (config-snapshot) {})
            strat (make-naming-strategy cfg root (registered-store) fs*)]
        (nexus/register! [:sessions :naming-strategy] strat)
        strat)))

(defn register-store!
  "Registers store in the system under [:sessions :store], preserving other :sessions values."
  [store]
  (nexus/register! [:sessions :store] store))


(defn register!
  "Create a store and naming strategy from config and register them in the system under :sessions.
   Reads :sessions :store and :sessions :naming-strategy from cfg."
  [cfg root]
  (let [impl     (get-in cfg [:sessions :store] :jsonl-edn-sidecar)
        fs*      (fs/instance)
        store    (create root impl)
        strategy (make-naming-strategy cfg root store fs*)]
    (nexus/register! [:sessions] {:store store :naming-strategy strategy})
    store))

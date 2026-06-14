(ns isaac.hail.router
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [clojure.set :as set]
    [isaac.config.loader :as loader]
    [isaac.crew.store :as crew-store]
    [isaac.fs :as fs]
    [isaac.naming :as naming]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.session.store.spi :as session-store]))

(def default-tick-ms 1000)

(defn- write-edn [value]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint value))))

(defn- runtime-root []
  (or (loader/root) (throw (ex-info "hail router requires :root" {}))))

(defn- filesystem []
  (or (fs/instance) (throw (ex-info "hail.router requires :fs in system" {}))))

(defn- pending-dir []
  (str (runtime-root) "/hail/pending"))

(defn- deliveries-dir []
  (str (runtime-root) "/hail/deliveries"))

(defn- undeliverable-dir []
  (str (runtime-root) "/hail/undeliverable"))

(defn- pending-path [id]
  (str (pending-dir) "/" id ".edn"))

(defn- delivery-path [id]
  (str (deliveries-dir) "/" id ".edn"))

(defn- undeliverable-path [id]
  (str (undeliverable-dir) "/" id ".edn"))

(defn- temp-path [path]
  (str path ".tmp"))

(defn- read-record [path]
  (let [fs* (filesystem)]
    (when (fs/exists? fs* path)
      (edn/read-string (fs/slurp fs* path)))))

(defn- write-record! [path record]
  (let [fs*  (filesystem)
        temp (temp-path path)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* temp (write-edn record))
    (fs/move fs* temp path)))

(defn- delete-pending! [id]
  (fs/delete (filesystem) (pending-path id)))

(defn- list-pending []
  (let [fs* (filesystem)
        dir (pending-dir)]
    (if-let [children (fs/children fs* dir)]
      (->> children
           (map #(read-record (str dir "/" %)))
           (remove nil?)
           (sort-by :id)
           vec)
      [])))

(defn- delivery-id [root fs*]
  (naming/generate (naming/->SequentialStrategy root "hail/deliveries" "delivery-" fs*)))

(defn normalize-id [value]
  (cond
    (keyword? value) (name value)
    (string? value)  value
    (nil? value)     nil
    :else            (str value)))

(defn id-keyword [value]
  (some-> value normalize-id keyword))

(defn state-id-value [value]
  (let [id (normalize-id value)]
    (if (and (string? id) (re-matches #"[a-z][a-z-]*" id))
      (keyword id)
      id)))

(defn normalize-tags [tags]
  (set (map keyword (or tags #{}))))

(defn- selector-ids [selector]
  (set (keep id-keyword selector)))

(defn- selector-tags [selector]
  (normalize-tags selector))

(defn- intersect-or [left right]
  (cond
    (and (seq left) (seq right)) (set/intersection left right)
    (seq left)                   left
    (seq right)                  right
    :else                        nil))

(defn effective-reach [band hail]
  (or (:reach hail)
      (get-in hail [:frequency :reach])
      (:reach band)
      :one))

(defn effective-spawn [band hail]
  (or (:spawn hail)
      (get-in hail [:frequency :spawn])
      (:spawn band)
      false))

(defn- effective-filter [band hail key selector-fn]
  (let [band-value (selector-fn (get band key))
        hail-value (selector-fn (get-in hail [:frequency key]))]
    (intersect-or band-value hail-value)))

(defn- crew-config [crews session]
  (let [crew-id (normalize-id (:crew session))]
    (or (get crews crew-id)
        (get crews (some-> crew-id keyword)))))

(defn matching-crews [band crews hail]
  (let [band-name (get-in hail [:frequency :band])
        crew-ids  (effective-filter band hail :crew selector-ids)
        crew-tags (effective-filter band hail :crew-tags selector-tags)]
    (cond
      (and band-name (nil? band))
      {:reason :unknown-band}

      (and (nil? crew-ids) (nil? crew-tags))
      {:crews []}

      :else
      {:crews
       (->> crews
            (map (fn [[crew-id crew-cfg]]
                   {:id   (normalize-id crew-id)
                    :crew crew-cfg}))
            (filter (fn [{:keys [id crew]}]
                      (and (or (nil? crew-ids) (contains? crew-ids (keyword id)))
                           (or (nil? crew-tags)
                               (every? #(contains? (crew-store/tags-of crew) %) crew-tags)))))
            (sort-by :id)
            vec)})))

(defn matching-sessions [band crews sessions hail]
  (let [band-name    (get-in hail [:frequency :band])
        crew-ids     (effective-filter band hail :crew selector-ids)
        session-ids  (effective-filter band hail :session selector-ids)
        crew-tags    (effective-filter band hail :crew-tags selector-tags)
        session-tags (effective-filter band hail :session-tags selector-tags)]
    (cond
      (and band-name (nil? band))
      {:reason :unknown-band}

      :else
      {:sessions
       (->> sessions
            (filter (fn [session]
                      (let [session-id (id-keyword (:id session))
                            crew-id    (id-keyword (:crew session))
                            crew-cfg   (crew-config crews session)]
                        (and
                          (or (nil? crew-ids) (contains? crew-ids crew-id))
                          (or (nil? session-ids) (contains? session-ids session-id))
                          (or (nil? crew-tags)
                              (every? #(contains? (crew-store/tags-of crew-cfg) %) crew-tags))
                          (or (nil? session-tags)
                              (every? #(contains? (session-store/tags-of session) %) session-tags))))))
            (sort-by (juxt :id :crew))
            vec)})))

(defn- bound-delivery [hail session]
  {:hail     hail
   :crew     (id-keyword (:crew session))
   :session  (state-id-value (:id session))
   :attempts 0})

(defn- candidate-entry [session]
  {:crew    (id-keyword (:crew session))
   :session (state-id-value (:id session))})

(defn resolve-obligations [bands crews sessions hail]
  (let [band-name     (get-in hail [:frequency :band])
        band          (when band-name (get bands band-name))
        reach         (effective-reach band hail)
        spawn?        (effective-spawn band hail)
        crew-result   (matching-crews band crews hail)
        match-result  (matching-sessions band crews sessions hail)
        matches       (:sessions match-result)
        host-crews    (:crews crew-result)]
    (cond
      (:reason match-result)
      {:undeliverable {:hail hail :reason (:reason match-result)}}

      (empty? matches)
      (if (and spawn? (= :one reach))
        (if (seq host-crews)
          {:deliveries [{:hail     hail
                         :crew     nil
                         :session  nil
                         :attempts 0}]}
          {:undeliverable {:hail hail :reason :no-host}})
        {:undeliverable {:hail hail :reason :no-recipients}})

      (= :all reach)
      {:deliveries (mapv #(bound-delivery hail %) matches)}

      (= 1 (count matches))
      {:deliveries [(bound-delivery hail (first matches))]}

      :else
      {:deliveries [{:hail       hail
                     :crew       nil
                     :session    nil
                     :candidates (mapv candidate-entry matches)
                     :attempts   0}]})))

(defn- write-deliveries! [root fs* hail deliveries]
  (doseq [delivery deliveries]
    (let [id       (delivery-id root fs*)
          delivery (assoc delivery :id id)]
      (write-record! (delivery-path id) delivery)))
  (delete-pending! (:id hail)))

(defn- write-undeliverable! [hail record]
  (write-record! (undeliverable-path (:id hail)) record)
  (delete-pending! (:id hail)))

(defn tick!
  [{:keys [cfg root] :as opts}]
  (let [cfg            (or cfg (loader/snapshot "hail router tick wake boundary — config may have changed") {})
        root      (or root (runtime-root))
        fs*            (filesystem)
        session-store* (or (:session-store opts)
                           (session-store/registered-store)
                           (session-store/create root))
        crews          (:crew cfg)
        bands          (:hail cfg)
        sessions       (session-store/list-sessions session-store*)]
    (doseq [hail (list-pending)]
      (let [{:keys [deliveries undeliverable]}
            (resolve-obligations bands crews sessions hail)]
        (cond
          (seq deliveries)       (write-deliveries! root fs* hail deliveries)
          undeliverable          (write-undeliverable! hail undeliverable)
          :else                  (write-undeliverable! hail {:hail hail :reason :no-recipients}))))))

(defn start!
  [{:keys [tick-ms]
    :or   {tick-ms default-tick-ms}}]
  (let [shared-scheduler (or (nexus/get :scheduler)
                             (throw (ex-info "hail router requires :scheduler in isaac.nexus" {})))]
    (scheduler/schedule! shared-scheduler
                         {:id      :hail/route
                          :trigger {:kind :interval :ms tick-ms}
                          :handler (fn [_] (tick! {}))})
    {:scheduler shared-scheduler
     :task-id   :hail/route}))

(defn stop! [{:keys [scheduler task-id]}]
  (when scheduler
    (scheduler/cancel! scheduler task-id)))

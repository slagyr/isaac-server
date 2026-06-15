(ns isaac.config.configurator-spec
  (:require
    [isaac.config.berths :as berths]
    [isaac.config.schema.root :as schema]
    [isaac.config.configurator :as sut]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.server.app :as app]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "configurator"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {}
      (example)))

  (describe "component reconciliation"

    (defn- recording-component []
      (let [events (atom [])]
        [events (reify sut/Reconfigurable
                  (on-startup! [_ slice] (swap! events conj [:started slice]))
                  (on-config-change! [_ old new] (swap! events conj [:changed old new])))]))

    (it "starts a component when its slice appears"
      (let [[events instance] (recording-component)
            registry {:kind :component :path [:cron] :impl "cron" :factory (fn [_] instance)}]
        (log/capture-logs
          (sut/reconcile! {} nil {:cron {:nightly {:expr "0 0 * * *"}}} registry)
          (should= instance (nexus/get-in [:cron]))
          (should= [[:started {:nightly {:expr "0 0 * * *"}}]] @events)
          (should (some #(= :lifecycle/started (:event %)) @log/captured-logs)))))

    (it "delivers on-config-change! when the slice changes"
      (let [[events instance] (recording-component)
            registry {:kind :component :path [:cron] :impl "cron" :factory (fn [_] instance)}]
        (sut/reconcile! {} nil {:cron {:a {}}} registry)
        (sut/reconcile! {} {:cron {:a {}}} {:cron {:a {} :b {}}} registry)
        (should= [:changed {:a {}} {:a {} :b {}}] (last @events))))

    (it "stops a component when its slice is removed"
      (let [[events instance] (recording-component)
            registry {:kind :component :path [:cron] :impl "cron" :factory (fn [_] instance)}]
        (sut/reconcile! {} nil {:cron {:a {}}} registry)
        (sut/reconcile! {} {:cron {:a {}}} {} registry)
        (should-be-nil (nexus/get-in [:cron]))
        (should= [:changed {:a {}} nil] (last @events))))

    (it "ignores non-component registries — slot trees belong to the berth engine"
      (let [registry {:kind :slot-tree :path [:comms]}]
        (sut/reconcile! {} nil {:comms {:bert {:type :telly}}} registry)
        (should-be-nil (nexus/get-in [:comms :bert])))))

  (describe "schema ownership"

    (defn- owned-paths []
      (into (->> (app/registries) (map :path) set)
            (berths/config-paths (module-loader/builtin-index))))

    (defn- entity-collection-entry? [[_ entry]]
      (and (= :map (:type entry))
           (:key-spec entry)
           (:value-spec entry)
           (let [value-spec (:value-spec entry)]
             (and (= :map (:type value-spec))
                  (or (:name value-spec)
                      (seq (:schema value-spec)))))))

    (it "every config-driven entity collection has a lifecycle owner or is marked snapshot-only"
      (let [owned-paths (owned-paths)
            unowned     (for [[key entry] (filter entity-collection-entry? (:schema schema/root))
                              :when (not (or (contains? owned-paths [key])
                                             (:snapshot-only? entry)))]
                          (str "key `" key "` has no owner — register a Reconfigurable for `["
                               key "]` or add `:snapshot-only? true` to its schema entry"))]
        (should= [] (vec unowned))))

    (it "marks crew models and providers as snapshot-only"
      (should= true (get-in schema/root [:schema :crew :snapshot-only?]))
      (should= true (get-in schema/root [:schema :models :snapshot-only?]))
      (should= true (get-in schema/root [:schema :providers :snapshot-only?])))))
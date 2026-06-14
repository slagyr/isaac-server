(ns isaac.server.server-steps
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.config.resolve :as resolve]
    [isaac.config.runtime :as runtime]
    [isaac.foundation.fs-steps :as ffs]
    [isaac.foundation.root-steps :as froot]
    [isaac.session.store.memory :as memory-store]
    [isaac.server.cli :as server]
    [isaac.hail.delivery-worker :as hail-delivery-worker]
    [isaac.hail.router :as hail-router]
    [isaac.scheduler.cron :as cron]
    [isaac.module.loader :as module-loader]
    [isaac.cron.service :as cron-service]
    [isaac.session.store.spi :as store]
    [isaac.comm.protocol :as comm]
    [isaac.comm.delivery.worker :as worker]
    [isaac.comm.registry :as comm-registry]
    [isaac.bridge.status :as bridge-status]
    [isaac.config.root :as root]
    [isaac.scheduler.runtime :as scheduler-core]
    [isaac.slash.registry :as slash-registry]
    [isaac.nexus :as nexus]
    [isaac.step-tables :as match]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.main :as main]
    [isaac.spec-helper :as helper]
    [isaac.server.app :as app]
    [isaac.server.http :as server-http]
    [isaac.server.routes :as routes]
    [org.httpkit.client :as http]
    [org.httpkit.server :as httpkit]
    [taoensso.timbre :as timbre])
  (:import
    (java.time ZonedDateTime)
    (java.time.format DateTimeFormatter)))

(helper! isaac.server.server-steps)

;; c3kit.apron.refresh logs via timbre and forces :info level, bypassing
;; isaac.logger. Disable timbre's default println appender at step-namespace
;; load time so c3kit's internal logs (">>>>> Stopping App", etc.) don't
;; pollute feature test output. Gherclj loads isaac.features.steps.* for
;; every run, so this silences timbre for the whole feature suite.
(timbre/merge-config! {:appenders {:println {:enabled? false}}})

;; The foundation isaac-file write steps (moved to isaac.foundation.fs-steps)
;; fire post-write hooks; register the server-side config-change notification
;; so hot-reload scenarios still get notified when a config file is written.
(ffs/register-post-write-hook!
  (fn [path]
    (when-let [source (g/get :config-change-source)]
      (runtime/notify-path! source path))))

(froot/register-root-setup-hook!
  (fn [abs-dir]
    (reset! comm-registry/*registry* (comm-registry/fresh-registry))
    (store/register-store! (memory-store/create-store abs-dir))))

(defn- grover-root-dir []
  (or (g/get :runtime-root-dir) (g/get :root)))

(defn- grover-mem-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- with-grover-fs [f]
  (nexus/-with-nested-nexus {:fs (grover-mem-fs)}
    (f)))

(defn- write-grover-defaults! []
  (let [root (str (grover-root-dir) "/config")
        fs*  (grover-mem-fs)]
    (fs/mkdirs fs* root)
    (fs/spit fs* (str root "/isaac.edn")
             (pr-str {:defaults {:crew "main" :model "grover"}}))
    (fs/mkdirs fs* (str root "/models"))
    (fs/mkdirs fs* (str root "/providers"))
    (fs/mkdirs fs* (str root "/crew"))
    (fs/spit fs* (str root "/models/grover.edn")
             (pr-str {:model "echo" :provider :grover :context-window 32768}))
    (fs/spit fs* (str root "/providers/grover.edn") (pr-str {}))
    (fs/spit fs* (str root "/crew/main.edn")
             (pr-str {:model :grover :soul "You are Atticus."}))
    (g/dissoc! :feature-config)))

(defn default-grover-setup []
  (froot/initialize-root! "target/test-state" true)
  (with-grover-fs write-grover-defaults!))

(defn- parse-config-value [value]
  (cond
    (re-matches #"-?\d+" value) (parse-long value)
    (= "true" (str/lower-case value)) true
    (= "false" (str/lower-case value)) false
    (or (str/starts-with? value "[")
        (str/starts-with? value "{")
        (str/starts-with? value ":")
        (str/starts-with? value "\"")
        (str/starts-with? value "#"))
    (try
      (edn/read-string value)
      (catch RuntimeException _
        value))
    (= "bind-server-port" value) false
    :else value))

(defn- resolved-config-value [value]
  (if-let [[_ env-name] (re-matches #"\$\{([^}]+)\}" (str value))]
    (or (loader/env env-name) value)
    value))

(defn- config-path [path]
  (mapv keyword (str/split path #"\.")))

(defn- delete-sentinel? [value]
  (= "#delete" (str/trim (str value))))

(defn- skip-row? [value]
  (str/blank? (str value)))

(defn- dissoc-in [m path]
  (cond
    (empty? path)      m
    (= 1 (count path)) (dissoc m (first path))
    :else              (let [parent-path (vec (butlast path))
                             leaf        (last path)
                             parent      (get-in m parent-path)]
                         (if (map? parent)
                           (assoc-in m parent-path (dissoc parent leaf))
                           m))))

(def ^:private offset-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssZ"))

(defn- config-rows [table]
  (cond-> (:rows table)
    (seq (:headers table)) (conj (:headers table))))

(defn- server-fs []
  (or (g/get :mem-fs)
      (fs/real-fs)))

(defn- with-server-fs [f]
  (let [fs* (server-fs)]
    (nexus/-with-nested-nexus {:fs fs*}
      (f))))

(defn- notify-config-change! [path]
  (g/dissoc! :feature-config)
  (when-let [source (g/get :config-change-source)]
    (runtime/notify-path! source path)))

(defn- isaac-root-path []
  (g/get :root))

(defn- runtime-root-dir []
  (or (g/get :runtime-root-dir)
      (g/get :root)))

(defn- config-path? [path]
  (str/starts-with? path "config/"))

(defn- isaac-file-path [path]
  (cond
    (str/starts-with? path "/") path
    (= path "isaac.edn")         (str (isaac-root-path) "/config/isaac.edn")
    (config-path? path)          (str (isaac-root-path) "/" path)
    :else                        (str (runtime-root-dir) "/" path)))

(defn- parse-isaac-value [file-path path value]
  (cond
    (re-matches #"-?\d+" value) (parse-long value)
    (= "true" (str/lower-case value)) true
    (= "false" (str/lower-case value)) false
    (= path "tools.allow")
    (->> (str/split value #",")
         (map str/trim)
         (remove str/blank?)
         (mapv keyword))

    (or (str/starts-with? value "[")
        (str/starts-with? value "{")
        (str/starts-with? value ":")
        (str/starts-with? value "\"")
        (str/starts-with? value "#"))
    (edn/read-string value)

    (or (contains? #{"defaults.crew" "defaults.model"} path)
        (and (= path "model") (re-find #"/config/crew/" file-path))
        (and (= path "crew") (re-find #"/config/cron/" file-path))
    (and (= path "api") (re-find #"/config/providers/" file-path)))
    (keyword value)

    :else value))

(defn- isaac-file-data [path]
  (let [path (isaac-file-path path)
        fs*  (server-fs)]
    (when (fs/exists? fs* path)
      (edn/read-string (fs/slurp fs* path)))))

(defn- copy-state-tree! [source-fs source-path target-fs target-path]
  (when (fs/exists? source-fs source-path)
    (if (fs/file? source-fs source-path)
      (do
        (fs/mkdirs target-fs (fs/parent target-path))
        (fs/spit target-fs target-path (fs/slurp source-fs source-path)))
      (do
        (fs/mkdirs target-fs target-path)
        (doseq [child (or (fs/children source-fs source-path) [])]
          (copy-state-tree! source-fs
                            (str source-path "/" child)
                            target-fs
                            (str target-path "/" child)))))))



(defn- get-path [data path]
  (reduce (fn [current segment]
            (cond
              (nil? current) nil
              (map? current) (or (get current (keyword segment))
                                 (get current segment))
              :else nil))
          data
          (str/split path #"\.")))

(defn- config-file-path []
  (str (g/get :root) "/config/isaac.edn"))

(defn- load-server-config [root fs*]
  (let [load!       #(:config (loader/load-config-result {:root root :fs fs*}))
        entity-dir? #(with-server-fs
                       (fn []
                         (seq (fs/children fs* (str root "/config/" %)))))
        cfg         (load!)]
    (if (and (or (entity-dir? "crew") (entity-dir? "models") (entity-dir? "providers"))
             (empty? (or (:crew cfg) {}))
             (empty? (or (:models cfg) {}))
             (empty? (or (:providers cfg) {})))
      (load!)
      cfg)))

(defn- persist-config-entry! [k v]
  (when-let [_ (g/get :root)]
    (with-server-fs
      (fn []
        (let [path    (config-file-path)
              fs*     (server-fs)
              current (if (fs/exists? fs* path) (edn/read-string (fs/slurp fs* path)) {})
              updated (if (delete-sentinel? v)
                        (dissoc-in current (config-path k))
                        (assoc-in current (config-path k) (parse-config-value v)))]
          (fs/mkdirs fs* (fs/parent path))
          (fs/spit   fs* path (pr-str updated)))))))

;; region ----- Setup -----

(defn- deep-merge [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    b))

(defn stop-server! []
  (app/stop!))

(g/after-scenario stop-server!)

(defn configure [table]
  (doseq [[k v] (config-rows table)]
    (if (= "log.output" k)
      (case v
         "memory" (do (log/set-output! :memory)
                      (log/clear-entries!))
         (do (log/set-log-file! v)
             (log/set-output! :file)))
      (if (= "bind-server-port" k)
        (g/assoc! :bind-server-port? (parse-config-value v))
        (do
          (g/update! :server-config #(if (delete-sentinel? v)
                                       (dissoc-in (or % {}) (config-path k))
                                       (assoc-in (or % {}) (config-path k) (parse-config-value (resolved-config-value v)))))
          (persist-config-entry! k v))))))

;; isaac-edn-file-exists ("the EDN isaac file X exists with:") and
;; isaac-file-exists-with-content ("the isaac file X exists with:") moved to
;; isaac.foundation.fs-steps (write closure duplicated there).

(defn isaac-edn-file-contains-content [path content]
  (with-server-fs
    (fn []
      (let [file-path (isaac-file-path path)
            fs*       (server-fs)]
        (fs/mkdirs fs* (fs/parent file-path))
        (fs/spit   fs* file-path (str/trim content))
        (notify-config-change! file-path)))))

(defn isaac-config-path-is [path value]
  (with-server-fs
    (fn []
      (when-not (skip-row? value)
        (let [file-path (isaac-file-path "isaac.edn")
              data      (or (isaac-file-data "isaac.edn") {})
              fs*       (server-fs)]
          (fs/mkdirs fs* (fs/parent file-path))
          (fs/spit   fs* file-path
                          (pr-str (assoc-in data
                                            (mapv keyword (str/split path #"\."))
                                            (parse-isaac-value file-path path value))))
          (notify-config-change! file-path))))))

(defn isaac-file-with-log-entries [path n]
  (let [n     (parse-long n)
        lines (->> (range 1 (inc n))
                   (map #(format "{:ts \"2026-05-12T00:%02d:%02dZ\" :level :info :event :e%02d}"
                                 (quot % 60) (mod % 60) %))
                   (str/join "\n"))]
    (with-server-fs
      (fn []
        (let [file-path (isaac-file-path path)
              fs*       (server-fs)]
          (fs/mkdirs fs* (fs/parent file-path))
          (fs/spit   fs* file-path lines))))))

(defn- clean-real-dir! [path]
  (let [dir (java.io.File. path)]
    (when (.exists dir)
      (doseq [f (-> dir file-seq reverse)]
        (.delete f)))))

(defn- default-server-home []
  (str (System/getProperty "user.dir") "/target/test-state/server-default-home"))

(defn server-running []
  (app/stop!)
  (let [explicit-home? (or (g/get :root) (g/get :root))
        virtual-home   (or explicit-home?
                           (default-server-home))
        mem            (g/get :mem-fs)
        ;; All server reads/writes flow through (nexus/get :fs). When the test
        ;; uses a mem-fs, app/start! installs it in the global nexus runtime so
        ;; HTTP handler threads see the same fs.
        home           (if mem
                         (do (g/assoc! :root virtual-home) virtual-home)
                         (do
                           (when-not explicit-home?
                             (clean-real-dir! virtual-home)
                             (g/assoc! :root virtual-home))
                           virtual-home))
        runtime-state  home
        server-config  (let [fs*     (server-fs)
                             base    (with-server-fs #(load-server-config home fs*))
                             merged  (deep-merge base
                                                 (merge (or (g/get :server-config) {})
                                                        (when-let [providers (g/get :provider-configs)]
                                                          {:providers providers})))
                             disc    (nexus/-with-nested-nexus {:fs fs*}
                                       (module-loader/discover! merged {:root runtime-state
                                                                        :cwd       (System/getProperty "user.dir")}))]
                               (assoc merged :module-index (:index disc)))
        cfg            (resolve/server-config server-config)
        ;; For synthetic default homes, feature steps notify config changes
        ;; explicitly, so a memory-backed source is deterministic and cheap.
        ;; Real root scenarios keep the real watcher path when hot reload
        ;; is enabled; no watcher is needed for pure startup-only scenarios.
        config-source  (when (:hot-reload cfg)
                         (if (or mem (not explicit-home?))
                           (runtime/memory-source home)
                           (runtime/watch-service-source home)))
        _              (g/assoc! :config-change-source config-source)
        run-server?    (not (false? (g/get :bind-server-port?)))
        start-opts     {:cfg                  server-config
                         :config-change-source config-source
                         :dev                  (= "true" (loader/env "ISAAC_DEV"))
                         :fs                   (server-fs)
                         :host                 (:host cfg)
                         ;; Explicit :server :port in the scenario's config is honored;
                         ;; otherwise bind ephemerally so suites never collide with a
                         ;; live isaac on the default port.
                         :port                 (if run-server?
                                                 (or (get-in server-config [:server :port]) 0)
                                                 0)
                         :root            runtime-state
                        :start-http-server?   run-server?}]
    (g/assoc! :runtime-root-dir runtime-state)
    (g/assoc! :server-handler-opts {:cfg-fn    (fn [] (or (some-> app/state deref :cfg deref) server-config))
                                    :root runtime-state
                                    :home      home})
    (when-let [{:keys [port]} (app/start! start-opts)]
      (g/assoc! :server-port port))))

;; endregion ^^^^^ Setup ^^^^^

;; region ----- Server Commands -----

(defn- run-cli-with-stubbed-config!
  "Runs `argv` through isaac.main with loader/load-config-result stubbed to
   the current :server-config bean and block! no-op'd, then stops the
   server. Stops any prior server first so consecutive scenarios don't
   collide on the same port."
  [argv]
  (let [cfg (or (g/get :server-config) {})]
    (with-redefs [server/block!             (fn [] nil)
                  loader/load-config-result (fn [& _] {:config cfg})]
      (with-out-str
        (app/stop!)
        (main/run argv))))
  (app/stop!))

(defn server-command-run [port]
  (run-cli-with-stubbed-config! ["server" "--port" (str port)]))

(defn server-command-run-no-port []
  (let [cfg (or (g/get :server-config) {})]
    (with-redefs [server/block!             (fn [] nil)
                  loader/load-config-result (fn [& _] {:config cfg})
                  httpkit/run-server        (fn [_handler opts] (atom (:port opts)))
                  httpkit/server-port       (fn [s] (or @s 0))
                  httpkit/server-stop!      (fn [_s] nil)]
      (with-out-str
        (app/stop!)
        (main/run ["server"]))
      (app/stop!))))

(defn server-command-run-with-args [args]
  (let [arg-parts (remove str/blank? (str/split args #"\s+" 2))]
    (run-cli-with-stubbed-config! (into ["server"] arg-parts))))

(defn gateway-command-run [port]
  (run-cli-with-stubbed-config! ["gateway" "--port" (str port)]))

;; endregion ^^^^^ Server Commands ^^^^^

;; region ----- Request / Response -----

(defn- extract-headers [rows]
  (into {} (keep (fn [[k v]]
                   (when (str/starts-with? k "header.")
                     [(subs k 7) v]))
                 rows)))

(defn- direct-headers [headers]
  (into {} (map (fn [[k v]] [(str/lower-case k) v])) headers))

(defn- extract-body [rows]
  (some (fn [[k v]] (when (= "body" k) v)) rows))

(defn- request-base-url []
  (let [port (g/get :server-port)
        host (or (get-in (g/get :server-config) [:server :host]) "localhost")
        host (if (= "::1" host) "[::1]" "localhost")]
    (str "http://" host ":" port)))

(defn- table->kv-rows [table]
  (let [rows (cond-> (:rows table)
               (seq (:headers table)) (conj (:headers table)))]
    (mapv (fn [row] (mapv identity row)) rows)))

(defn- current-server-config []
  (let [home     (or (g/get :root) (g/get :root))
        fs*      (server-fs)
        base     (with-server-fs #(load-server-config home fs*))
        merged   (deep-merge base
                             (merge (or (g/get :server-config) {})
                                    (when-let [providers (g/get :provider-configs)]
                                      {:providers providers})))
        runtime  (runtime-root-dir)
        disc     (nexus/-with-nested-nexus {:fs fs*}
                   (module-loader/discover! merged {:root runtime
                                                    :cwd       (System/getProperty "user.dir")}))]
    (assoc merged
           :module-index (:index disc)
           :root runtime)))

(defn- current-handler-opts []
  (or (g/get :server-handler-opts)
      (let [home (or (g/get :root) (g/get :root))]
        {:cfg-fn    current-server-config
         :root (runtime-root-dir)
         :home      home})))

(defn- register-direct-routes! [cfg]
  (reset! routes/*registry* (routes/fresh-registry))
  (let [module-index (merge (module-loader/foundation-index) (:module-index cfg))]
    (module-loader/process-manifest-berths! module-index)))

(defn- direct-response [request]
  (let [handler-opts (current-handler-opts)
        cfg          ((:cfg-fn handler-opts))
        fs*          (server-fs)]
    (register-direct-routes! cfg)
    (nexus/-with-nested-nexus {:fs fs* :root (:root handler-opts)}
      ((server-http/create-handler handler-opts) request))))

(defn get-request [path]
  (let [port (g/get :server-port)
        resp (if (pos? (long (or port 0)))
               @(http/get (str (request-base-url) path))
               (direct-response {:request-method :get
                                 :uri            path
                                 :headers        {}}))]
    (g/assoc! :http-response resp)))

(defn get-request-with-headers [path table]
  (let [port    (g/get :server-port)
        rows    (table->kv-rows table)
        headers (extract-headers rows)
        resp    (if (pos? (long (or port 0)))
                  @(http/get (str (request-base-url) path) {:headers headers})
                  (direct-response {:request-method :get
                                    :uri            path
                                    :headers        (direct-headers headers)}))]
    (g/assoc! :http-response resp)))

(defn get-request-with-header [path header]
  (let [port            (g/get :server-port)
        [name value]    (str/split header #":\s*" 2)
        headers         {name value}
        resp            (if (pos? (long (or port 0)))
                          @(http/get (str (request-base-url) path) {:headers headers})
                          (direct-response {:request-method :get
                                            :uri            path
                                            :headers        (direct-headers headers)}))]
    (g/assoc! :http-response resp)))

(defn post-request [path table]
  (let [port     (g/get :server-port)
        rows     (table->kv-rows table)
        headers  (extract-headers rows)
        body     (extract-body rows)
        headers  (if (and body (not (contains? headers "Content-Type")))
                   (assoc headers "Content-Type" "application/json")
                   headers)
        resp     (if (pos? (long (or port 0)))
                   @(http/post (str (request-base-url) path)
                               (cond-> {:headers headers :as :text}
                                 body (assoc :body body)))
                   (direct-response {:request-method :post
                                     :uri            path
                                     :headers        (direct-headers headers)
                                     :body           body}))]
    (g/assoc! :http-response resp)
    ;; Store hook turn future so session-transcript-matching can await it
    (when-let [hook-ns (find-ns 'isaac.hooks)]
      (when-let [fut-fn (ns-resolve hook-ns 'last-turn-future)]
        (when-let [fut (fut-fn)]
          (g/assoc! :turn-future fut))))))

(defn- scheduler-idle? [instance]
  (every? (fn [task]
            (and (nil? (:active-run task))
                 (empty? (:pending-fire-ats task))))
          (scheduler-core/list-tasks instance)))

(defn- invoke-scheduled-cron-tasks! [scheduler now]
  (doseq [{:keys [handler trigger]} (scheduler-core/list-tasks scheduler)]
    (let [zone         (java.time.ZoneId/of (or (:zone trigger) (str (.getZone now))))
          scheduled-at (cron/previous-fire-at (:expr trigger) now zone)]
      (when scheduled-at
        (handler {:scheduled-at (.toInstant scheduled-at)
                  :now          (.toInstant now)})))))

(defn scheduler-ticks-at [iso]
  (g/assoc! :isaac-file-phase :assert)
  (g/assoc! :runtime-root-dir (g/get :root))
  (with-server-fs
    (fn []
      (let [fs*        (server-fs)
            cfg        (merge (load-server-config (g/get :root) fs*)
                              (when-let [providers (g/get :provider-configs)]
                                {:providers providers}))
            now        (ZonedDateTime/parse iso offset-formatter)
            scheduler  (scheduler-core/create {:clock (fn [] (.toInstant now))})]
        (try
          (nexus/-with-nexus {:config    (atom cfg)
                               :scheduler scheduler
                               :root (runtime-root-dir)
                               :fs        fs*
                               :sessions  {:store (store/create (runtime-root-dir))}}
            (let [runner (cron-service/start! {:cfg cfg :root (runtime-root-dir)})]
              (try
                (invoke-scheduled-cron-tasks! scheduler now)
                (helper/await-condition #(scheduler-idle? scheduler) 3000)
                (finally
                  (cron-service/stop! runner)))))
          (finally
            (scheduler-core/shutdown! scheduler)))))))

(deftype StubComm []
  comm/Comm
  (on-turn-start [_ _ _] nil)
  (on-text-chunk [_ _ _] nil)
  (on-tool-call [_ _ _] nil)
  (on-tool-cancel [_ _ _] nil)
  (on-tool-result [_ _ _ _] nil)
  (on-compaction-start [_ _ _] nil)
  (on-compaction-success [_ _ _] nil)
  (on-compaction-failure [_ _ _] nil)
  (on-compaction-disabled [_ _ _] nil)
  (on-turn-end [_ _ _] nil)
  (send! [_ record]
    (g/update! :stub-comm-calls #(conj (or % []) record))
    (or (g/get :stub-comm-result) {:ok true})))

(defn- with-stub-comm [root f]
  (let [reg (assoc (comm-registry/fresh-registry) :instances {"stub" (->StubComm)})]
    (binding [comm-registry/*registry* (atom reg)
              root/*root*         root]
      (f))))

(defn delivery-worker-ticks []
  (g/assoc! :isaac-file-phase :assert)
  (g/assoc! :runtime-root-dir (g/get :root))
  (with-server-fs
    (fn []
      (with-stub-comm (runtime-root-dir)
        (fn []
          (nexus/-with-nexus {:root (runtime-root-dir) :fs (server-fs)}
            (worker/tick! {})))))))

(defn delivery-worker-ticks-at [iso]
  (g/assoc! :isaac-file-phase :assert)
  (g/assoc! :runtime-root-dir (g/get :root))
  (with-server-fs
    (fn []
      (with-stub-comm (runtime-root-dir)
        (fn []
          (nexus/-with-nexus {:root (runtime-root-dir) :fs (server-fs)}
            (worker/tick! {:now (java.time.Instant/parse iso)})))))))

(defn hail-router-ticks []
  (g/assoc! :isaac-file-phase :assert)
  (g/assoc! :runtime-root-dir (g/get :root))
  (with-server-fs
    (fn []
      (let [fs*           (server-fs)
            cfg           (merge (load-server-config (g/get :root) fs*)
                                 (when-let [providers (g/get :provider-configs)]
                                   {:providers providers}))
            root     (runtime-root-dir)
            session-store (store/create root)]
        (nexus/-with-nexus {:config    (atom cfg)
                            :root root
                            :fs        fs*
                            :sessions  {:store session-store}}
          (hail-router/tick! {:cfg cfg :session-store session-store}))))))

(defn- record-turn-future! [futures]
  (if-let [future* (first futures)]
    (g/assoc! :turn-future future*)
    (g/dissoc! :turn-future)))

(defn hail-delivery-worker-ticks []
  (g/assoc! :isaac-file-phase :assert)
  (g/assoc! :runtime-root-dir (g/get :root))
  (with-server-fs
    (fn []
      (let [fs*           (server-fs)
            cfg           (current-server-config)
            root     (runtime-root-dir)
            session-store (store/create root)]
        (nexus/-with-nexus {:config    (atom cfg)
                            :root root
                            :fs        fs*
                            :sessions  {:store session-store}}
          (config/dangerously-install-config! cfg "spec")
          (record-turn-future! (hail-delivery-worker/tick! {:cfg cfg :session-store session-store})))))))

(defn hail-delivery-worker-ticks-at [iso]
  (g/assoc! :isaac-file-phase :assert)
  (g/assoc! :runtime-root-dir (g/get :root))
  (with-server-fs
    (fn []
      (let [fs*           (server-fs)
            cfg           (current-server-config)
            root     (runtime-root-dir)
            session-store (store/create root)]
        (nexus/-with-nexus {:config    (atom cfg)
                            :root root
                            :fs        fs*
                            :sessions  {:store session-store}}
          (config/dangerously-install-config! cfg "spec")
          (record-turn-future! (hail-delivery-worker/tick! {:cfg           cfg
                                                            :now           (java.time.Instant/parse iso)
                                                            :session-store session-store})))))))

(defn comm-stub-returns [_comm-name table]
  (let [headers (:headers table)
        row     (first (:rows table))
        result  (into {} (map #(vector (keyword %1) (parse-config-value %2)) headers row))]
    (g/assoc! :stub-comm-result result)))

(defn comm-stub-was-called-with [_comm-name table]
  (let [calls  (or (g/get :stub-comm-calls) [])
        result (match/match-entries table calls)]
    (g/should= [] (:failures result))))

(defn response-status [code]
  (let [resp   (g/get :http-response)
        status (:status resp)]
    (g/should= code status)))

(defn response-header-matches [header pattern]
  (let [resp   (g/get :http-response)
        actual (some (fn [[k v]]
                       (when (= (str/lower-case header) (str/lower-case (name k)))
                         v))
                     (:headers resp))]
    (g/should (some? actual))
    (g/should (re-find (re-pattern pattern) (str actual)))))

(defn server-failed-to-start []
  (g/should-not (app/running?))
  (g/should-not (g/get :server-port)))

(defn response-body-key-equals [key value]
  (let [resp (g/get :http-response)
        body (json/parse-string (:body resp) true)
        k    (keyword key)]
    (g/should= value (get body k))))

(defn response-body-has-key [key]
  (let [resp (g/get :http-response)
        body (json/parse-string (:body resp) true)
        k    (keyword key)]
    (g/should-not-be-nil (get body k))))

;; edn-isaac-file-contains ("the EDN isaac file X contains:") and
;; edn-isaac-file-does-not-exist ("the isaac file X does not exist") moved to
;; isaac.foundation.fs-steps.

(defn isaac-edn-file-removed [path]
  (with-server-fs
    (fn []
      (let [file-path (isaac-file-path path)
            fs*       (server-fs)]
        (when (fs/exists? fs* file-path)
          (fs/delete fs* file-path))
        (notify-config-change! file-path)))))

(defn isaac-file-removed [path]
  (with-server-fs
    (fn []
      (let [file-path (isaac-file-path path)
            fs*       (server-fs)]
        (when (fs/exists? fs* file-path)
          (fs/delete fs* file-path))
        (notify-config-change! file-path)))))

;; endregion ^^^^^ Request / Response ^^^^^

;; region ----- Log Assertions -----
;; "the log has entries matching:" / "no entries matching:" moved to
;; isaac.foundation.log-steps (foundation-grade; logger/step-tables only).

(defn config-reloaded []
  (helper/await-condition
    #(some (fn [entry] (= :config/reloaded (:event entry))) (log/get-entries))
    2000)
  (g/should (some (fn [entry] (= :config/reloaded (:event entry))) (log/get-entries))))

(defn available-slash-commands-include [table]
  (let [commands (slash-registry/all-commands (:module-index (or (loader/snapshot "spec") {})))
        headers  (:headers table)]
    (doseq [row (:rows table)]
      (let [expected (zipmap headers row)
            matched? (some (fn [entry]
                             (every? (fn [[k v]] (= v (get entry (keyword k)))) expected))
                           commands)]
        (g/should matched?)))))

;; endregion ^^^^^ Log Assertions ^^^^^

;; region ----- Routing -----

;; default Grover setup + config: — isaac.session.session-steps (agent spec).
;; Server feature classpath includes ../isaac-agent/spec.

(defwhen "the isaac EDN file {path:string} is removed" isaac.server.server-steps/isaac-edn-file-removed
  "Deletes the EDN file at <root>/.isaac/<path> and fires a config-change
   notification so a running server's hot-reload processes the removal.")

(defwhen "the isaac file {path:string} is removed" isaac.server.server-steps/isaac-file-removed
  "Deletes any file at <root>/.isaac/<path> and fires a config-change
   notification so a running server's hot-reload processes the removal.")

(defgiven #"the isaac config path \"([^\"]+)\" is \"([^\"]*)\"" isaac.server.server-steps/isaac-config-path-is)

(defgiven #"the isaac EDN file \"([^\"]+)\" contains:" isaac.server.server-steps/isaac-edn-file-contains-content
  "Writes heredoc EDN content to <root>/.isaac/<path> and notifies the
   running config change source when present. Useful for replacing a whole
   config file instead of patching it with a table.")

(defwhen #"the isaac EDN file \"([^\"]+)\" changes to:" isaac.server.server-steps/isaac-edn-file-contains-content
  "Alias for the heredoc EDN writer used after startup to trigger hot reload
   with a full-file replacement.")

(defgiven #"the isaac file \"([^\"]+)\" exists with (\d+) log entries" isaac.server.server-steps/isaac-file-with-log-entries
  "Writes N EDN log lines to <root>/.isaac/<path>. Each line has a
   distinct two-digit-padded :event keyword (:e01..:eNN) so substring
   assertions don't collide across IDs.")

(defgiven "the Isaac server is started" isaac.server.server-steps/server-running
  "Stops any prior server, then starts one against :root / :root.
   Merges in-memory :server-config and :provider-configs over whatever
   loader/load-config-result returns from disk. When mem-fs is active,
   wires a synchronous memory change-source so hot-reload scenarios fire
   deterministically from test writes.")

(defwhen "the Isaac process is started" isaac.server.server-steps/server-running
  "Alias for 'the Isaac server is started' as a When step. Starts the full
   Isaac process (including comm activation) against the configured state dir.")

(defwhen "the server command is run on port {port:int}" isaac.server.server-steps/server-command-run
  "Runs 'isaac server --port N' with server/block! stubbed to no-op and
   loader/load-config-result stubbed to {:config <feature server-config>}.
   Immediately stops the server after the run returns — use for testing
   startup flags/logging only.")

(defwhen "the server command is run without a port flag" isaac.server.server-steps/server-command-run-no-port)

(defwhen "the server command is run with args {args:string}" isaac.server.server-steps/server-command-run-with-args)

(defwhen "the isaac config is reloaded" isaac.server.server-steps/config-reloaded)

(defwhen "the gateway command is run on port {port:int}" isaac.server.server-steps/gateway-command-run)

(defwhen #"a GET request is made to \"([^\"]+)\"$" isaac.server.server-steps/get-request)

(defwhen #"the client sends GET \"([^\"]+)\"$" isaac.server.server-steps/get-request)

(defwhen #"a GET request is made to \"([^\"]+)\":" isaac.server.server-steps/get-request-with-headers)

(defwhen #"the client sends GET \"([^\"]+)\" with header \"([^\"]+)\"" isaac.server.server-steps/get-request-with-header)

(defwhen #"a POST request is made to \"([^\"]+)\":" isaac.server.server-steps/post-request)

(defwhen #"the scheduler ticks at \"([^\"]+)\"" isaac.server.server-steps/scheduler-ticks-at
  "Schedules configured cron jobs on the shared scheduler, then invokes
   their registered handlers at the given ISO timestamp. Flips
   :isaac-file-phase to :assert so subsequent 'the EDN isaac file X
   contains:' steps read/assert instead of write.")

(defwhen "the delivery worker ticks" isaac.server.server-steps/delivery-worker-ticks
  "Invokes worker/tick! once with the comm stub. Flips
   :isaac-file-phase to :assert so subsequent file-contains steps
   read/assert. For time-sensitive scheduling, use 'ticks at' variant.")

(defwhen #"the delivery worker ticks at \"([^\"]+)\"" isaac.server.server-steps/delivery-worker-ticks-at)

(defwhen "the hail router ticks" isaac.server.server-steps/hail-router-ticks
  "Invokes hail-router/tick! once against the current on-disk hail and
   session state. Flips :isaac-file-phase to :assert so follow-up file
   assertions read/assert instead of write.")

(defwhen "the hail delivery worker ticks" isaac.server.server-steps/hail-delivery-worker-ticks
  "Invokes hail-delivery-worker/tick! once against the current hail and
   session state, capturing the launched background future for later
   'the turn ends on session ...' coordination.")

(defwhen #"the hail delivery worker ticks at \"([^\"]+)\"" isaac.server.server-steps/hail-delivery-worker-ticks-at)

(defgiven #"the comm \"([^\"]+)\" returns:" isaac.server.server-steps/comm-stub-returns
  "Configures the StubComm return value for all subsequent send! calls.
   Horizontal single-row table: columns are result map keys (ok, transient?, etc.).")

(defthen #"the comm \"([^\"]+)\" was called with:" isaac.server.server-steps/comm-stub-was-called-with
  "Asserts the StubComm received at least one send! call whose record
   matches all fields in the horizontal table (target, content, etc.).")

(defthen "the response status is {code:int}" isaac.server.server-steps/response-status)

(defthen #"the response header \"([^\"]+)\" matches \"([^\"]+)\"" isaac.server.server-steps/response-header-matches)

(defthen "the server failed to start" isaac.server.server-steps/server-failed-to-start)

(defthen "the response body has {key:string} equal to {value:string}" isaac.server.server-steps/response-body-key-equals)

(defthen "the response body has a {key:string} key" isaac.server.server-steps/response-body-has-key)

(defthen "the available slash commands include:" isaac.server.server-steps/available-slash-commands-include)

;; endregion ^^^^^ Routing ^^^^^

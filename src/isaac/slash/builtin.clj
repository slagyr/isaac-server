(ns isaac.slash.builtin
  (:require
    [clojure.string :as str]
    [isaac.bridge.status :as status]
    [isaac.config.loader :as loader]
    [isaac.effort :as effort]
     [isaac.fs :as fs]
     [isaac.config.root :as root]
     [isaac.logger :as log]
     [isaac.module.loader :as module-loader]
     [isaac.session.store.spi :as store]
     [isaac.nexus :as nexus]))

(defn parse-command [input]
  (let [parts (str/split (str/trim input) #"\s+" 2)
        cmd   (subs (first parts) 1)]
    {:name cmd :args (second parts)}))

(defn- find-alias [models model provider]
  (some (fn [[alias cfg]]
          (when (and (= model (:model cfg)) (= provider (:provider cfg)))
            (if (keyword? alias) (name alias) (str alias))))
        models))

(defn handle-status [session-key _input ctx]
  {:type    :command
   :command :status
   :data    (status/status-data session-key ctx)})

(defn handle-model [session-key input ctx]
  (let [{:keys [args]} (parse-command input)
        models (:models ctx)]
    (if (str/blank? args)
      (let [model    (:model ctx)
            provider (status/ctx-provider-name ctx)
            alias    (or (find-alias models model provider) model)]
        {:type    :command
         :command :model
         :message (str alias " (" provider "/" model ") is the current model")})
      (if-let [model-cfg (or (get models args)
                             (get models (keyword args)))]
        (do
          (store/update-session! (or (:session-store ctx) (nexus/get-in [:sessions :store])) session-key {:model    args
                                                                                                            :provider nil})
          {:type    :command
           :command :model
           :message (str "switched model to " args " (" (:provider model-cfg) "/" (:model model-cfg) ")")})
        {:type    :command
         :command :unknown
         :message (str "unknown model: " args)}))))

(defn handle-crew [session-key input ctx]
  (let [{:keys [args]} (parse-command input)
        current-crew (or (:crew ctx) "main")
        crew-members (or (:crew-members ctx) {})]
    (if (str/blank? args)
      {:type    :command
       :command :crew
       :message (str current-crew " is the current crew member")}
      (if (contains? crew-members args)
        (do
          (store/update-session! (or (:session-store ctx) (nexus/get-in [:sessions :store])) session-key {:crew     args
                                                                                                            :model    nil
                                                                                                            :provider nil})
          (log/info :session/crew-changed {:session session-key
                                           :from    current-crew
                                           :to      args})
          {:type    :command
           :command :crew
           :message (str "switched crew to " args)})
        {:type    :command
         :command :unknown
         :message (str "unknown crew: " args)}))))

(defn- resolve-cwd-path [ctx path]
  (let [root (or (get-in ctx [:config :root]) (:root ctx) (loader/root))]
    (cond
      (str/starts-with? path "/") path
      (str/starts-with? path "~/") (str (root/user-home) (subs path 1))
      (nil? root) (throw (ex-info "cwd command requires :root for relative paths" {:path path}))
      :else (str root "/" path))))

(defn handle-cwd [session-key input ctx]
  (let [{:keys [args]} (parse-command input)]
    (if (str/blank? args)
      (let [cwd (:cwd (store/get-session (or (:session-store ctx) (nexus/get-in [:sessions :store])) session-key))]
        {:type    :command
         :command :cwd
         :message (str "current directory: " (or cwd "(not set)"))})
      (let [resolved (resolve-cwd-path ctx args)]
        (if (fs/dir? (fs/instance) resolved)
          (do
            (store/update-session! (or (:session-store ctx) (nexus/get-in [:sessions :store])) session-key {:cwd resolved})
            {:type    :command
             :command :cwd
             :message (str "working directory set to " resolved)})
          {:type    :command
           :command :unknown
           :message (str "no such directory: " args)})))))

(defn- handle-effort [session-key input ctx]
  (let [{:keys [args]} (parse-command input)]
    (cond
      (str/blank? args)
      (let [session        (store/get-session (or (:session-store ctx) (nexus/get-in [:sessions :store])) session-key)
            session-effort (:effort session)
            effective      (or session-effort effort/default-effort)]
        {:type    :command
         :command :effort
         :message (str "current effort: " effective)})

      (= "clear" (str/trim args))
      (do
        (store/update-session! (or (:session-store ctx) (nexus/get-in [:sessions :store])) session-key {:effort nil})
        {:type    :command
         :command :effort
         :message "effort cleared"})

      :else
      (let [n (try (parse-long (str/trim args)) (catch Exception _ nil))]
        (if (and n (<= 0 n 10))
          (do
            (store/update-session! (or (:session-store ctx) (nexus/get-in [:sessions :store])) session-key {:effort n})
            {:type    :command
             :command :effort
             :message (str "effort set to " n)})
          {:type    :command
           :command :unknown
           :message "effort must be between 0 and 10"})))))

(defn status-command []
  {:description "Show session status"
   :handler     handle-status})

(defn model-command []
  {:description "Show or switch model"
   :handler     handle-model})

(defn crew-command []
  {:description "Show or switch crew"
   :handler     handle-crew})

(defn cwd-command []
  {:description "Show or set working directory"
   :handler     handle-cwd})

(defn effort-command []
  {:description "Show or set session effort (0-10)"
   :handler     handle-effort})

(defn ensure-registered!
  "Make sure isaac.foundation's slash commands are installed in the slash
   registry. Phase 7 of the berth epic (isaac-ho18) moved slash-command
   registration into the :isaac.server/slash-commands berth, so the
   built-ins now flow through the berth's per-entry factory the same
   way third-party contributions do."
  []
  (module-loader/activate-server!)
  (let [server-entry (get (module-loader/builtin-index) :isaac.server)
        contribs     (get-in server-entry [:manifest :isaac.server/slash-commands])
        register   (some-> 'isaac.slash.registry/register-slash-entry!
                           requiring-resolve var-get)]
    (doseq [entry contribs]
      (register entry))))

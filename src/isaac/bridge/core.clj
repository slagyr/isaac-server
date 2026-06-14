(ns isaac.bridge.core
  (:require
    [clojure.string :as str]
    [isaac.bridge.status :as status]
    [isaac.charge :as charge]
    [isaac.comm.protocol :as comm]
    [isaac.config.loader :as loader]
    [isaac.drive.turn :as turn]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.prompt.catalog :as prompt-catalog]
    [isaac.session.context :as session-ctx]
    [isaac.session.store.spi :as store]
    [isaac.slash.builtin :as slash-builtin]
    [isaac.slash.registry :as slash-registry]))

;; region ----- Helpers -----

(defn resolve-session-cwd
  "Resolves session cwd from the cascade: explicit override > crew > channel default.
   explicit-cwd: user-specified override (highest priority).
   crew-cfg: crew config map; may contain :cwd.
   channel-default: the channel's automatic fallback (lowest priority)."
  [explicit-cwd crew-cfg channel-default]
  (or explicit-cwd (:cwd crew-cfg) channel-default))

(defn- unknown-session-crew-message [session-key crew-id origin]
  (let [kind (:kind origin)]
    (str "unknown crew on session " session-key ": " crew-id
         (cond
           (= :cli kind)                      "\npass --crew to override"
           (contains? #{:webhook :cron} kind) nil
           :else                              "\nsend /crew <name> to change crew"))))

(defn- no-model-message [crew-id]
  (str "no model configured for crew: " crew-id))

(defn- reject-turn [session-key crew-id reason message]
  (log/warn :drive/turn-rejected :session session-key :crew crew-id :reason reason)
  {:error reason :message message})

(defn- refuse-dispatch [session-key]
  (log/warn :dispatch/refused :reason :session-in-flight :session session-key)
  {:dispatched? false :reason :session-in-flight})

(defn- reply-result [session-key ch result]
  (let [output (if (contains? result :data)
                 (status/format-status (:data result))
                 (:message result))]
    (when ch
      (comm/on-text-chunk ch session-key output)
      (comm/on-turn-end ch session-key (assoc result :content output)))
    result))

(defn- autonomous-origin? [origin]
  (contains? #{:hail :cron} (:kind origin)))

(defn- prompt-catalog-opts [ctx]
  {:config    (:config ctx)
   :cwd       (:cwd ctx)
   :fs        (or (nexus/get :fs) (fs/instance))
   :root (or (get-in ctx [:config :root])
                  (:root ctx)
                  (nexus/get :root))})

(defn- unknown-command-result [name args]
  {:type    :command
   :command :unknown
   :message (str "unknown command: "
                 (if (str/blank? args)
                   (str "/" name)
                   name))})

(defn- ensure-session! [request]
  (let [session-key    (:session-key request)
        session-store* (or (:session-store request) (nexus/get-in [:sessions :store]))
        cfg            (or (when (map? (:config request)) (:config request)) (loader/snapshot "turn dispatch entry — falls back to ambient config when charge carries none") {})
        crew-id        (or (:crew request) (get-in cfg [:defaults :crew]) "main")
        crew-cfg       (get (:crew cfg) crew-id)
        resolved-cwd   (resolve-session-cwd (:cwd request) crew-cfg nil)]
    (when (and session-key
               (nil? (store/get-session session-store* session-key))
               (or (:origin request) resolved-cwd))
      (session-ctx/create-with-resolved-behavior!
        session-key {:crew          crew-id
                     :cwd           resolved-cwd
                     :origin        (:origin request)
                     :config        cfg
                     :session-store session-store*}))))

;; endregion ^^^^^ Helpers ^^^^^

;; region ----- Slash Command Handlers -----

(defn- handle-slash [session-key input ctx]
  (let [{:keys [args name]} (slash-builtin/parse-command input)]
    (if-let [command (slash-registry/lookup name (:module-index ctx))]
      {:action :reply
       :result ((:handler command) session-key input ctx)}
      (if-let [{:keys [input]} (prompt-catalog/resolve-command-prompt (prompt-catalog-opts ctx) name args)]
        {:action :turn
         :charge (assoc ctx :input input)}
        (if (autonomous-origin? (:origin ctx))
          {:action :turn
           :charge ctx}
          {:action :reply
           :result (unknown-command-result name args)})))))

;; endregion ^^^^^ Slash Command Handlers ^^^^^

;; region ----- Triage -----

(defn slash-command?
  "Returns true if input begins with a slash."
  [input]
  (and (string? input) (str/starts-with? input "/")))

(defn- route-charge! [c]
  (let [ch          (:comm c)
        session-key (:session-key c)]
    (cond
      (charge/slash? c)
      (let [{:keys [action charge result]} (handle-slash session-key (:input c) c)]
        (case action
          :reply {:result (reply-result session-key ch result)}
          :turn  {:charge charge}
          {:error :invalid-slash-action}))

      (charge/unresolved? c)
      {:result (reject-turn session-key (:crew c) (:charge/reason c)
                            (case (:charge/reason c)
                              :unknown-crew (unknown-session-crew-message session-key (:crew c) (:origin c))
                              :no-model     (no-model-message (:crew c))
                              "resolution failed"))}

      :else
      {:charge c})))

(defn- dispatch-charge! [c]
  (let [{:keys [charge result]} (route-charge! c)]
    (if charge
      (if-let [session-key (:session-key charge)]
        (let [session-store* (or (:session-store charge) (nexus/get-in [:sessions :store]))]
          (if (store/mark-in-flight! session-store* session-key)
            (try
              (turn/run-turn! charge)
              (finally
                (store/clear-in-flight! session-store* session-key)))
            (refuse-dispatch session-key)))
        (turn/run-turn! charge))
      result)))

(defn dispatch!
  "Comm-facing entry point. Accepts a charge (built via charge/build) or a
   request map (which gets passed through charge/build). Slash commands are
   handled here; normal turns delegate to run-turn!. Bridge -> drive only."
  ([input]
    (if (charge/charge? input)
      (dispatch-charge! input)
      (let [request (merge (nexus/necho) input)]
        (ensure-session! request)
        (dispatch-charge! (charge/build request)))))
  ([_root request]
    ;; Two-arg form is a back-compat shim — root now lives on the
    ;; config snapshot, which downstream readers consult directly.
    (dispatch! request)))

;; endregion ^^^^^ Triage ^^^^^

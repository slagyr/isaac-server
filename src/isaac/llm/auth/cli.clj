;; mutation-tested: 2026-05-06
(ns isaac.llm.auth.cli
  (:require
    [isaac.cli.api :as cli-api]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.llm.auth.device-code :as device-code]
    [isaac.llm.auth.store :as auth-store]
    [isaac.config.root :as root]))

;; region ----- Login -----

(defn- known-providers [] #{"anthropic" "ollama" "openai" "chatgpt"})

(defn- login-api-key [provider-name]
  (print (str "Enter API key for " provider-name ": "))
  (flush)
  (if-let [key (read-line)]
    (if (str/blank? key)
      (do (println "Error: API key is required")
          1)
      (let [sdir (or (:root (loader/load-config! (root/current-root) (fs/instance) "auth cli: key login"))
                     (root/current-root))]
        (auth-store/save-api-key! sdir provider-name key (fs/instance))
        (println (str "Authenticated with " provider-name " via API key"))
        0))
    (do (println "Error: No input")
        1)))

(defn- auth-dir []
  (root/current-root))

(defn- login-device-code [provider-name]
  (println "Requesting device code...")
  (let [user-code-resp (device-code/request-user-code!)]
    (if (:error user-code-resp)
      (do
        (println (str "Error: Failed to request device code: " (:error user-code-resp)))
        1)
      (let [user-code    (:user_code user-code-resp)
            device-id    (:device_auth_id user-code-resp)
            raw-interval (:interval user-code-resp)
            interval     (if (string? raw-interval) (parse-long raw-interval) (or raw-interval 5))]
        (println)
        (println "Follow these steps to sign in:")
        (println)
        (println "  1. Open this link in your browser:")
        (println (str "     " device-code/verification-url))
        (println)
        (println "  2. Enter this one-time code (expires in 15 minutes)")
        (println (str "     " user-code))
        (println)
        (println "Waiting for authorization...")
        (let [auth-resp (device-code/poll-for-auth! device-id user-code (* interval 1000))]
          (cond
            (:error auth-resp)
            (do
              (println (str "Error: Authorization failed: " (:error auth-resp)))
              1)

            :else
            (let [tokens (device-code/exchange-tokens! (:authorization_code auth-resp)
                                                       (:code_verifier auth-resp))]
              (cond
                (:error tokens)
                (do
                  (println (str "Error: Token exchange failed: " (:error tokens)
                                (when (:body tokens) (str " - " (:body tokens)))))
                  1)

                :else
                (do
                  (auth-store/save-tokens! (auth-dir) provider-name tokens (fs/instance))
                  (println)
                  (println "Authentication successful!")
                  (println (str "Tokens saved for " provider-name))
                  0)))))))))

(defn- login [{:keys [provider api-key]}]
  (cond
    (nil? provider)
    (do (println "Error: --provider is required")
        (println)
        (println "Usage: isaac auth login --provider <name> [--api-key]")
        (println)
        (println "Options:")
        (println "  --provider <name>  Provider to authenticate with")
        (println "  --api-key          Use API key authentication (prompts for key)")
        1)

    (not (contains? (known-providers) provider))
    (do (println (str "Unknown provider: " provider))
        1)

    (= "chatgpt" provider)
    (login-device-code provider)

    api-key
    (login-api-key provider)

    :else
    (do (println "Error: --api-key is required")
        (println)
        (println "Usage: isaac auth login --provider <name> --api-key")
        1)))

;; endregion ^^^^^ Login ^^^^^

;; region ----- Status -----

(defn- status [_opts]
  (let [cfg (loader/load-config! (root/current-root) (fs/instance) "auth cli: status")]
    (println "Provider status:")
    (doseq [[name p] (or (seq (:providers cfg)) [["ollama" {}]])]
      (case name
        "anthropic" (if (:api-key p)
                       (println (str "  " name ": authenticated (API key)"))
                       (println (str "  " name ": not authenticated")))
        (println (str "  " name ": no auth required"))))
    0))

;; endregion ^^^^^ Status ^^^^^

;; region ----- Logout -----

(defn- logout [{:keys [provider]}]
  (if (nil? provider)
    (do (println "Error: --provider is required")
        1)
    (do ;; TODO: remove stored credentials
        (println (str "Logged out from " provider))
        0)))

;; endregion ^^^^^ Logout ^^^^^

;; region ----- Entry Point -----

(def option-spec
  [["-h" "--help" "Show help"]])

(def ^:private login-option-spec
  [["-p" "--provider NAME" "Provider to authenticate with"]
   ["-k" "--api-key"       "Use API key authentication (prompts for key)"]
   ["-h" "--help"          "Show help"]])

(def ^:private status-option-spec
  [["-h" "--help" "Show help"]])

(def ^:private logout-option-spec
  [["-p" "--provider NAME" "Provider to logout from"]
   ["-h" "--help"          "Show help"]])

(defn- parse-option-map [args option-spec & parse-args]
  (let [{:keys [options arguments errors]} (apply tools-cli/parse-opts args option-spec parse-args)]
    {:options   (->> options
                     (remove (comp nil? val))
                     (into {}))
     :arguments arguments
     :errors    errors}))

(defn help-text []
  (str "Usage: isaac auth <subcommand> [options]\n\n"
       "Subcommands:\n"
       "  login   Authenticate with a provider\n"
       "  status  Show authentication status\n"
       "  logout  Remove stored credentials"))

(defn- print-auth-help []
  (println (help-text)))

(defn run [args]
  (let [subcmd   (first args)
        sub-args (rest args)]
    (cond
      (or (nil? subcmd) (= "--help" subcmd) (= "-h" subcmd))
      (do (print-auth-help)
          0)

      (= "login" subcmd)
      (let [{:keys [options errors]} (parse-option-map sub-args login-option-spec)]
        (cond
          (:help options)
          (do
            (println "Usage: isaac auth login --provider <name> [--api-key]")
            0)

          (seq errors)
          (do
            (doseq [error errors]
              (println error))
            1)

          :else
          (login options)))

      (= "status" subcmd)
      (let [{:keys [options errors]} (parse-option-map sub-args status-option-spec)]
        (cond
          (:help options)
          (do
            (println "Usage: isaac auth status")
            0)

          (seq errors)
          (do
            (doseq [error errors]
              (println error))
            1)

          :else
          (status options)))

      (= "logout" subcmd)
      (let [{:keys [options errors]} (parse-option-map sub-args logout-option-spec)]
        (cond
          (:help options)
          (do
            (println "Usage: isaac auth logout --provider <name>")
            0)

          (seq errors)
          (do
            (doseq [error errors]
              (println error))
            1)

          :else
          (logout options)))

      :else
      (do (println (str "Unknown auth subcommand: " subcmd))
          1))))

(defn run-fn [{:keys [_raw-args]}]
  (let [{:keys [options arguments errors]} (parse-option-map (or _raw-args []) option-spec :in-order true)]
    (cond
      (seq errors)
      (do
        (doseq [error errors]
          (println error))
        1)

      (:help options)
      (run ["--help"])

      :else
      (run arguments))))

;; endregion ^^^^^ Entry Point ^^^^^

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :auth [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :auth [_id]
  option-spec)

(defmethod cli-api/help :auth [_id]
  (help-text))

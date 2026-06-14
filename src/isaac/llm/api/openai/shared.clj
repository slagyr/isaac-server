(ns isaac.llm.api.openai.shared
  "Shared auth and wire utilities for OpenAI-family providers.
   Used by isaac.llm.api.chat-completions and isaac.llm.api.responses."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.llm.auth.store :as auth-store]
    [isaac.llm.followup :as followup]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]))

;; region ----- Auth -----

(defn decode-jwt-payload [token]
  (when (and token (str/includes? token "."))
    (let [[_ payload _] (str/split token #"\." 3)]
      (when payload
        (try
          (let [decoder (java.util.Base64/getUrlDecoder)
                bytes   (.decode decoder payload)]
            (json/parse-string (String. bytes "UTF-8") true))
          (catch Exception _ nil))))))

(defn- extract-account-id [tokens]
  (let [payload (or (decode-jwt-payload (:access tokens))
                    (decode-jwt-payload (:id-token tokens)))
        auth    (or (get payload "https://api.openai.com/auth")
                    (some (fn [[k v]]
                            (when (= "https://api.openai.com/auth" (str/replace (str k) #"^:" ""))
                              v))
                          payload))]
    (or (:chatgpt_account_id auth)
        (get auth "chatgpt_account_id")
        (:chatgpt_account_id payload)
        (get payload "chatgpt_account_id"))))

(defn resolve-oauth-tokens [provider-name {:keys [auth] :as config}]
  (when (= "oauth-device" auth)
    (when-let [root (or (:auth-dir config) (:root config))]
      (let [tokens (auth-store/load-tokens root provider-name (fs/instance))]
        (when (and tokens (not (auth-store/token-expired? tokens)))
          tokens)))))

(defn provider-env-var
  "Conventional environment-variable name for a provider's API key
   (e.g. \"xai\" -> \"XAI_API_KEY\"). Hyphens become underscores."
  [provider-name]
  (when-not (str/blank? provider-name)
    (str (-> provider-name str/upper-case (str/replace "-" "_")) "_API_KEY")))

(defn resolve-api-key
  "Returns the API key for `provider-name`: the explicit :api-key from config
   when present, falling back to the <PROVIDER>_API_KEY env var when blank.
   Returns nil when neither is set."
  [provider-name config]
  (let [explicit (:api-key config)]
    (if-not (str/blank? explicit)
      explicit
      (when-let [env-var (provider-env-var provider-name)]
        (loader/env env-var)))))

(defn missing-auth-error [provider-name {:keys [auth] :as config}]
  (cond
    (:simulate-provider config)
    nil

    (= "oauth-device" auth)
    (when-not (resolve-oauth-tokens provider-name config)
      {:error   :auth-missing
       :message "Missing OpenAI ChatGPT login. Run `isaac auth login --provider chatgpt` first."})

    (str/blank? (resolve-api-key provider-name config))
    (let [env-var (provider-env-var provider-name)
          label   (or provider-name "provider")]
      {:error   :auth-missing
       :message (str "No API key for " label "."
                     (when env-var (str " Set " env-var " in the environment"))
                     (when provider-name (str " or :api-key in providers/" provider-name ".edn"))
                     ".")})))

(defn provider-base-url [config]
  (or (:base-url config) "http://localhost:11434/v1"))

(defn llm-http-opts [config]
  (cond-> {}
    (:session-key config)       (assoc :session-key (:session-key config))
    (:simulate-provider config) (assoc :simulate-provider (:simulate-provider config))))

(defn auth-headers [provider-name config]
  (let [oauth-tokens (resolve-oauth-tokens provider-name config)
        oauth-token  (:access oauth-tokens)
        account-id   (or (extract-account-id oauth-tokens)
                         (when (= "chatgpt" (:simulate-provider config)) "grover-account"))
        api-key      (resolve-api-key provider-name config)
        token        (or oauth-token api-key)]
    (cond-> {"content-type" "application/json"}
      token                             (assoc "Authorization" (str "Bearer " token))
      account-id                        (assoc "ChatGPT-Account-Id" account-id)
      (= "oauth-device" (:auth config)) (assoc "originator" "isaac"))))

;; endregion ^^^^^ Auth ^^^^^

(defn parse-usage [usage]
  {:input-tokens  (or (:prompt_tokens usage) (:input_tokens usage) 0)
   :output-tokens (or (:completion_tokens usage) (:output_tokens usage) 0)})

(defn followup-messages
  "Build the next iteration's :messages vector. Assistant message carries
   tool_calls in OpenAI function-call wire format; tool replies are role=tool."
  [request response tool-calls tool-results]
  (let [assistant-msg {:role       "assistant"
                       :content    (get-in response [:message :content])
                       :tool_calls (mapv (fn [tc]
                                           {:id       (:id tc)
                                            :type     "function"
                                            :function {:name      (:name tc)
                                                       :arguments (json/generate-string (:arguments tc))}})
                                         tool-calls)}
        result-msgs   (followup/map-tool-results tool-calls tool-results
                                                 (fn [tc result]
                                                   {:role         "tool"
                                                    :tool_call_id (:id tc)
                                                    :content      result}))]
    (followup/append-followup-messages request assistant-msg result-msgs)))

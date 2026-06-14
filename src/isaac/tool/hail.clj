(ns isaac.tool.hail
  (:require
    [clojure.walk :as walk]
    [isaac.hail.queue :as queue]
    [isaac.session.store.spi :as store]
    [isaac.tool.fs-bounds :as bounds]))

(defn- session-crew [args]
  (let [session-key   (get args "session_key")
        session-store (bounds/session-store args)]
    (when session-key
      (some-> (store/get-session session-store session-key)
              :crew))))

(defn- normalize-frequency [frequency]
  (when frequency
    (walk/keywordize-keys frequency)))

(defn hail-send-tool
  "Send a hail from the calling crew session.
   Args: frequency, payload, prompt, session_key (runtime-injected)."
  [arguments]
  (let [args       (bounds/string-key-map arguments)
        session-key (get args "session_key")
        crew-id    (session-crew args)]
    (if-not crew-id
      {:isError true :error (str "session not found: " session-key)}
      (let [record (cond-> {:frequency (normalize-frequency (get args "frequency"))
                            :from      (keyword (str "crew/" crew-id))}
                     (contains? args "payload") (assoc :payload (get args "payload"))
                     (contains? args "prompt")  (assoc :prompt (get args "prompt")))]
        {:result (:id (queue/send! record))}))))

(defn hail-send-tool-factory [_]
  {:description "Send a hail to a frequency."
   :parameters  {:type       "object"
                 :properties {"frequency" {:type "object" :description "Hail address map"}
                              "payload"   {:description "Optional hail payload"}
                              "prompt"    {:type "string" :description "Optional prompt for recipients"}}
                 :required   ["frequency"]}
   :handler     #'hail-send-tool})

(ns isaac.hail.cli
  (:require
    [isaac.cli.api :as cli-api]
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.common :as cli-common]
    [isaac.hail.queue :as queue]))

(def hail-option-spec
  [["-h" "--help" "Show help"]])

(def ^:private send-option-spec
  [["-h" "--help" "Show help"]
   [nil "--band NAME" "Band name"]
   [nil "--crew ID" "Crew id (repeatable)"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--session ID" "Session id (repeatable)"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--crew-tag TAG" "Crew tag (repeatable)"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--session-tag TAG" "Session tag (repeatable)"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--reach MODE" "Reach mode (:one or :all) for direct/tag addressing"]
   [nil "--prompt TEXT" "Prompt for direct/tag-addressed hails"]
   [nil "--payload EDN" "Payload EDN, or '-' to read payload from stdin"]
   [nil "--from-json" "Read whole-hail stdin input as JSON"]
   [nil "--json" "Print the full hail record as JSON"]
   [nil "--edn" "Print the full hail record as EDN"]])

(defn hail-help []
  (str "Usage: isaac hail <subcommand> [options]\n\n"
       "Send and inspect hail records.\n\n"
       "Subcommands:\n"
       "  send    Persist a hail record to hail/pending\n"))

(defn- send-help []
  (str "Usage: isaac hail send [addressing flags] [--prompt <text>] [--payload <edn>|-] [--json|--edn]\n"
       "       isaac hail send - [--from-json]\n\n"
       "Persist a hail record to hail/pending.\n\n"
       "Options:\n"
       "  -h, --help                 Show help\n"
       "      --band NAME            Band name\n"
       "      --crew ID              Crew id (repeatable)\n"
       "      --session ID           Session id (repeatable)\n"
       "      --crew-tag TAG         Crew tag (repeatable)\n"
       "      --session-tag TAG      Session tag (repeatable)\n"
       "      --reach MODE           Reach mode (:one or :all) for direct/tag addressing\n"
       "      --prompt TEXT          Prompt for direct/tag-addressed hails\n"
       "      --payload EDN          Payload EDN, or '-' to read payload from stdin\n"
       "      --from-json            Read whole-hail stdin input as JSON\n"
       "      --json                 Print the full hail record as JSON\n"
       "      --edn                  Print the full hail record as EDN\n"))

(defn- slurp-stdin []
  (let [content (slurp *in*)]
    (when-not (str/blank? content)
      content)))

(defn- read-edn [text]
  (edn/read-string text))

(defn- read-json [text]
  (json/parse-string text true))

(defn- keywordize* [values]
  (mapv keyword values))

(defn- keyword-set* [values]
  (into #{} (map keyword) values))

(defn- direct-addressing? [frequency]
  (boolean (some #(contains? frequency %)
                 [:crew :session :crew-tags :session-tags])))

(defn- has-addressing? [frequency]
  (boolean (some #(contains? frequency %)
                 [:band :crew :session :crew-tags :session-tags])))

(defn- frequency-from-options [options]
  (cond-> {}
    (:band options)         (assoc :band (:band options))
    (:crew options)         (assoc :crew (keywordize* (:crew options)))
    (:session options)      (assoc :session (keywordize* (:session options)))
    (:crew-tag options)     (assoc :crew-tags (keyword-set* (:crew-tag options)))
    (:session-tag options)  (assoc :session-tags (keyword-set* (:session-tag options)))
    (:reach options)        (assoc :reach (keyword (:reach options)))))

(defn- parse-whole-hail [options]
  (let [text (or (slurp-stdin) "{}")]
    (if (:from-json options)
      (read-json text)
      (read-edn text))))

(defn- build-errors [whole-hail? options]
  (let [frequency         (when-not whole-hail? (frequency-from-options options))
        direct?           (direct-addressing? frequency)
        band?             (contains? frequency :band)
        has-addressing?   (has-addressing? frequency)]
    (cond-> []
      (and (not whole-hail?) (not has-addressing?))
      (conj "At least one addressing option is required")

      (and (not whole-hail?) direct? (not band?) (str/blank? (:prompt options)))
      (conj "Missing required option --prompt for direct/tag addressing")

      (and (not whole-hail?) (:reach options) (not direct?))
      (conj "Option --reach requires direct/tag addressing")

      (and (:json options) (:edn options))
      (conj "Choose either --json or --edn, not both"))))

(defn- validate-hail [record]
  (let [frequency (or (:frequency record) {})
        direct?   (direct-addressing? frequency)
        band?     (contains? frequency :band)]
    (cond-> []
      (not (has-addressing? frequency))
      (conj "Hail must include at least one addressing field")

      (and direct? (not band?) (str/blank? (:prompt record)))
      (conj "Direct/tag-addressed hails require :prompt")

      (and (:reach frequency) (not direct?))
      (conj "Hails with :reach require direct/tag addressing"))))

(defn- parse-send-opts [args]
  (let [whole-hail?                        (= "-" (first args))
        parse-args                         (if whole-hail? (rest args) args)
        {:keys [arguments errors options]} (tools-cli/parse-opts parse-args send-option-spec :in-order true)
        errors                             (into (vec errors) (build-errors whole-hail? options))]
    {:arguments (if whole-hail? ["-"] arguments)
     :errors    errors
     :options   (->> options
                     (remove (comp nil? val))
                     (into {}))}))

(defn- whole-hail-stdin? [arguments]
  (= ["-"] arguments))

(defn- build-hail [{:keys [arguments options]}]
  (if (whole-hail-stdin? arguments)
    (assoc (parse-whole-hail options) :from :cli)
    (cond-> {:frequency (frequency-from-options options)
             :from      :cli}
      (:prompt options)  (assoc :prompt (:prompt options))
      (:payload options) (assoc :payload (if (= "-" (:payload options))
                                           (read-edn (or (slurp-stdin) "nil"))
                                           (read-edn (:payload options)))))))

(defn- print-record! [record options]
  (cond
    (:json options) (cli-common/print-json! record)
    (:edn options)  (cli-common/print-edn! record)
    :else           (println (:id record))))

(defn- run-send [args]
  (let [{:keys [errors options] :as parsed} (parse-send-opts args)]
    (cond
      (:help options)
      (do (println (send-help)) 0)

      (seq errors)
      (do
        (doseq [error errors]
          (binding [*out* *err*]
            (println error)))
        1)

      :else
      (let [record (build-hail parsed)
            errors (validate-hail record)]
        (if (seq errors)
          (do
            (doseq [error errors]
              (binding [*out* *err*]
                (println error)))
            1)
          (let [record (queue/send! record)]
            (print-record! record options)
            0))))))

(defn run [args]
  (let [{:keys [arguments errors options]} (tools-cli/parse-opts args hail-option-spec :in-order true)]
    (cond
      (:help options)
      (do (println (hail-help)) 0)

      (seq errors)
      (do
        (doseq [error errors]
          (binding [*out* *err*]
            (println error)))
        1)

      (= "send" (first arguments))
      (run-send (rest arguments))

      :else
      (do
        (binding [*out* *err*]
          (println (str "Unknown hail subcommand: " (or (first arguments) ""))))
        1))))

(defn run-fn [{:keys [_raw-args]}]
  (run (or _raw-args [])))

(defn read-pending [id]
  (queue/read-pending id))

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :hail [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :hail [_id]
  hail-option-spec)

(defmethod cli-api/help :hail [_id]
  (hail-help))

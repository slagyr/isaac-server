(ns isaac.bridge.status
  (:require
    [clojure.string :as str]
    [isaac.llm.api.protocol :as api]
    [isaac.nexus :as nexus]
    [isaac.session.store.spi :as store]
    [isaac.tool.registry :as tool-registry]))

;; region ----- Helpers -----

(defn- turn-count [transcript]
  (count (filter #(= "message" (:type %)) transcript)))

(defn ctx-provider-name [ctx]
  (let [p (:provider ctx)]
    (cond
      (string? p) p
      (some? p)   (api/display-name p)
      :else       nil)))

(defn- summarize-soul [ctx]
  (let [soul    (or (:soul ctx) "")
        source  (if (> (count (remove str/blank? (str/split (str/trim soul) #"\s+"))) 4)
                  soul
                  (or (:boot-files ctx) soul ""))
        text   (-> source
                   (str/replace #"(?m)^#+\s.*$" "")
                   (str/replace #"\[([^\]]+)\]\([^)]+\)" "$1")
                   (str/replace #"`" "")
                   (str/replace #"\s+" " ")
                   str/trim)
        words  (->> (str/split text #"\s+")
                    (remove str/blank?)
                    vec)]
    (cond
      (empty? words)
      ""

      (<= (count words) 8)
      (str/join " " words)

      :else
      (str (str/join " " (take 8 words)) " ..."))))

;; endregion ^^^^^ Helpers ^^^^^

;; region ----- Public API -----

(defn- session-store
  ([ctx-or-root]
    (cond
      (map? ctx-or-root)
      (or (:session-store ctx-or-root) (nexus/get-in [:sessions :store]))

      :else
      (store/create ctx-or-root))))

(defn- status-data* [session-store session-key ctx]
  (let [entry          (store/get-session session-store session-key)
        transcript     (or (store/get-transcript session-store session-key) [])
        turns          (turn-count transcript)
        tokens         (or (:last-input-tokens entry) 0)
        context-window (or (:context-window ctx) 32768)
        context-pct    (if (pos? context-window)
                         (int (Math/round (* 100.0 (/ tokens context-window))))
                         0)]
    {:crew           (:crew ctx)
     :boot-files     (:boot-files ctx)
     :soul           (:soul ctx)
     :model          (or (get-in ctx [:model-cfg :model])
                         (:model ctx))
     :provider       (ctx-provider-name ctx)
     :tags           (or (:tags entry) #{})
     :session-key    session-key
     :session-file   (:session-file entry)
     :turns          turns
     :compactions    (or (:compaction-count entry) 0)
     :tokens         tokens
     :context-window context-window
     :context-pct    context-pct
     :tool-count     (count (tool-registry/all-tools))
     :cwd            (or (:cwd entry) (System/getProperty "user.dir"))}))

(defn status-data
  "Gather session and model info for the /status command."
  ([session-key ctx]
   (let [ctx (merge (nexus/necho) ctx)]
     (status-data* (session-store ctx) session-key ctx)))
  ([root session-key ctx]
   (status-data* (session-store root) session-key ctx)))

(defn format-status
  "Format status data as human-readable markdown-style status lines."
  [data]
  (let [label-width 12
        line        (fn [label value]
                      (format (str "%-" label-width "s %s") label value))]
    (str "```text\n"
         (str/join "\n"
                   ["Session Status"
                    (apply str (repeat 22 "─"))
                    (line "Crew"        (:crew data))
                     (line "Model"       (str (:model data) " (" (:provider data) ")"))
                     (line "Session"     (:session-key data))
                     (line "Tags"        (pr-str (:tags data)))
                     (line "File"        (:session-file data))
                    (line "Turns"       (:turns data))
                    (line "Compactions" (:compactions data))
                    (line "Context"     (str (format "%,d" (:tokens data)) " / "
                                              (format "%,d" (:context-window data)) " ("
                                              (:context-pct data) "%)"))
                    (line "Soul"        (str "\"" (summarize-soul data) "\""))
                    (line "Tools"       (:tool-count data))
                    (line "CWD"         (:cwd data))])
         "\n```")))

;; endregion ^^^^^ Public API ^^^^^

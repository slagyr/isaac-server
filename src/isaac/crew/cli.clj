(ns isaac.crew.cli
  (:require
    [isaac.cli.api :as cli-api]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.registry :as cli]
    [isaac.cli.common :as cli-common]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.crew.store :as store]
    [isaac.fs :as fs]))

(def option-spec
  [[nil  "--json" "Output result as JSON"]
   [nil  "--edn"  "Output result as EDN"]
   [nil  "--tag TAG" "Filter to crews carrying this tag (repeatable)"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil  "--without-tag TAG" "Exclude crews carrying this tag (repeatable)"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil  "--untagged" "Show only crews with no tags"]
   ["-h" "--help" "Show help"]])

(defn- text-tags [tags]
  (if (seq tags)
    (->> tags (sort-by str) (map pr-str) (str/join " "))
    ""))

(defn- parse-option-map [raw-args]
  (let [{:keys [options errors]} (tools-cli/parse-opts raw-args option-spec)]
    {:options (->> options (remove (comp nil? val)) (into {}))
     :errors  errors}))

(defn- soul-source [crew-cfg]
  (when-let [s (:soul crew-cfg)]
    (if (> (count s) 40)
      (str (subs s 0 37) "...")
      s)))

(defn- derive-root [opts]
  (root/default-root opts))

(defn resolve-crew
  "Returns a seq of {:name :model :provider :soul-source :tags} for display."
  [opts]
  (let [{:keys [crew models]} opts
        root (derive-root opts)
        cfg       (if crew
                    (cli-common/build-cfg crew models)
                    (loader/load-config! root (fs/instance) "crew cli"))
        cfg       (loader/normalize-config cfg)
        crew-map  (cond-> (:crew cfg)
                    (not (contains? (:crew cfg) "main")) (assoc "main" {}))]
    (map (fn [[crew-id crew-member]]
           (let [model-id    (or (:model crew-member) (get-in cfg [:defaults :model]))
                 model-cfg   (get-in cfg [:models model-id])
                 model-name  (or (:model model-cfg) model-id "-")
                 provider    (or (:provider model-cfg) "-")]
              {:name        crew-id
               :model       model-name
               :provider    provider
               :tags        (or (:tags crew-member) #{})
               :soul-source (soul-source crew-member)}))
          crew-map)))

(defn- resolve-crew-by-name [opts crew-id]
  (some #(when (= crew-id (:name %)) %) (resolve-crew opts)))

(defn format-crew [rows]
  (let [cols    [[:name "Name"] [:model "Model"] [:provider "Provider"] [:soul-source "Soul"] [:tags-text "Tags"]]
        widths  (map (fn [[k header]]
                       (apply max (count header) (map #(count (str (get % k ""))) rows)))
                     cols)
        pad     (fn [s w] (str s (apply str (repeat (- w (count s)) " "))))
        header  (str/join "  " (map (fn [[_ h] w] (pad h w)) cols widths))
        rule    (str/join "  " (map (fn [_ w] (apply str (repeat w "─"))) cols widths))
        lines   (map (fn [row]
                       (str/join "  " (map (fn [[k _] w] (pad (str (get row k "")) w)) cols widths)))
                      rows)]
    (str/join "\n" (concat [header rule] lines))))

(defn- render-list! [rows opts]
  (let [rows (vec (sort-by :name rows))]
    (cond
      (:json opts) (cli-common/print-json! rows)
      (:edn opts)  (cli-common/print-edn! rows)
      :else        (println (format-crew rows)))))

(defn- render-show! [row opts]
  (cond
    (:json opts) (cli-common/print-json! row)
    (:edn opts)  (cli-common/print-edn! row)
    :else        (println (format-crew [row]))))

(defn run [opts]
  (let [required-tags (set (map keyword (:tag opts)))
        excluded-tags (set (map keyword (:without-tag opts)))
        rows          (->> (resolve-crew opts)
                           (filter (fn [row]
                                     (let [tags (:tags row)]
                                       (and (every? #(store/has-tag? row %) required-tags)
                                            (not-any? #(store/has-tag? row %) excluded-tags)
                                            (if (:untagged opts) (empty? tags) true)))))
                           (map #(assoc % :tags-text (text-tags (:tags %)))))]
    (render-list! rows opts))
  0)

(defn- run-show [opts crew-id]
  (if-let [row (resolve-crew-by-name opts crew-id)]
    (do
      (render-show! (assoc row :tags-text (text-tags (:tags row))) opts)
      0)
    (do
      (binding [*out* *err*]
        (println (str "crew not found: " crew-id)))
      1)))

(defn run-fn [opts]
  (let [raw-args (or (:_raw-args opts) [])]
    (if (= "show" (first raw-args))
      (let [{:keys [options errors]} (parse-option-map (drop 2 raw-args))]
        (cond
          (:help options)
          (do (println (cli/command-help (cli/get-command "crew"))) 0)

          (seq errors)
          (do (doseq [error errors] (println error)) 1)

          :else
          (run-show (merge (dissoc opts :_raw-args) options) (second raw-args))))
      (cli-common/standard-run-fn "crew" parse-option-map run opts))))

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :crew [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :crew [_id]
  option-spec)

(ns isaac.config.companion
  (:require
    [clojure.string :as str]))

(defn present? [value]
  (and (some? value)
       (not (and (string? value) (str/blank? value)))))

(defn resolve-text
  [{:keys [inline load-fn]}]
  (let [{:keys [exists? text] :or {exists? false text nil}} (or (when load-fn (load-fn)) {})
        inline?  (present? inline)
        value    (if inline?
                   inline
                   (when (present? text) text))]
    {:value             value
     :inline?           inline?
     :companion-exists? exists?
     :companion-empty?  (and exists? (not (present? text)))}))

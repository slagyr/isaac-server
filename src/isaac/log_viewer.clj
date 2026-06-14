(ns isaac.log-viewer
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]))

;; region ----- ANSI helpers -----

(defn- ansi [& codes]
  (str "\033[" (str/join ";" codes) "m"))

(def ^:private reset      (ansi 0))
(def ^:private dim        (ansi 2))

(def ^:private palette
  [(ansi "38;5;39")    ;; bright blue
   (ansi "38;5;208")   ;; orange
   (ansi "38;5;76")    ;; green
   (ansi "38;5;213")   ;; pink
   (ansi "38;5;220")   ;; gold
   (ansi "38;5;51")    ;; aqua
   (ansi "38;5;141")   ;; lavender
   (ansi "38;5;167")   ;; salmon
   (ansi "38;5;108")   ;; sage
   (ansi "38;5;215")   ;; peach
   (ansi "38;5;111")   ;; sky
   (ansi "38;5;179")]) ;; mustard

(defn- palette-color [s]
  (nth palette (mod (Math/abs (hash (str s))) (count palette))))

(defn color-for-ns [s] (palette-color s))
(defn color-for-session [s] (palette-color s))

(defn color-for-level [level]
  (case level
    :error (ansi 1 31)
    :warn  (ansi 1 33)
    :info  (ansi 1 36)
    :debug dim
    :trace dim
    (ansi 0)))

(defn color-for-value [v]
  (cond
    (nil? v)     (ansi 31)
    (boolean? v) (ansi 33)
    (number? v)  (ansi 32)
    (keyword? v) (ansi 35)
    :else        (ansi "38;5;222")))

;; endregion ^^^^^ ANSI helpers ^^^^^

;; region ----- Formatting -----

(defn format-time [ts]
  (try
    (let [inst (java.time.Instant/parse (str ts))
          ldt  (-> inst
                   (.atZone (java.time.ZoneId/systemDefault))
                   .toLocalDateTime)
          fmt  (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss.SSS")]
      (.format fmt ldt))
    (catch Exception _
      (let [s (str ts)]
        (if (>= (count s) 12) (subs s 0 12) s)))))

(defn- format-kv [k v color?]
  (let [k-str (pr-str k)
        v-str (pr-str v)]
    (if color?
      (let [val-color (if (and (= k :sessionId) (string? v))
                        (color-for-session v)
                        (color-for-value v))]
        (str dim k-str reset " " val-color v-str reset))
      (str k-str " " v-str))))

(defn- format-map [m color?]
  (let [pairs (str/join " " (map (fn [[k v]] (format-kv k v color?)) m))]
    (if color?
      (str dim "{" reset pairs dim "}" reset)
      (str "{" pairs "}"))))

(defn format-entry [entry color?]
  (let [ts         (get entry :ts "")
        level      (get entry :level :info)
        event      (get entry :event "")
        kvs        (dissoc entry :ts :level :event :file :line)
        time-str   (format-time ts)
        level-str  (format "%-5s" (str/upper-case (name (or level "INFO"))))
        event-str  (str event)
        event-ns   (when (keyword? event) (namespace event))
        time-part  (if color? (str dim time-str reset "  ") (str time-str "  "))
        level-part (if color?
                     (str (color-for-level level) level-str reset "  ")
                     (str level-str "  "))
        event-part (if (and color? event-ns)
                     (str (color-for-ns event-ns) event-str reset)
                     event-str)
        map-part   (when (seq kvs)
                     (str "  " (format-map kvs color?)))]
    (str time-part level-part event-part map-part)))

(defn format-line [line color?]
  (let [line (str/trim (or line ""))]
    (when-not (str/blank? line)
      (try
        (let [entry (edn/read-string line)]
          (if (map? entry)
            (format-entry entry color?)
            line))
        (catch Exception _
          line)))))

(defn- zebra-wrap [s]
  ;; Re-apply dim after every internal reset so the entire row stays muted.
  (str dim (str/replace s reset (str reset dim)) reset))

;; endregion ^^^^^ Formatting ^^^^^

;; region ----- Tailing -----

(def ^:dynamic *follow-sleep-ms* 100)

(defn tty? []
  (some? (System/console)))

(defn- print-line! [line row {:keys [color? zebra? plain?]}]
  (when (and line (not (str/blank? line)))
    (let [out (if plain? line (format-line line color?))]
      (when out
        (println (if (and zebra? color? (odd? row))
                   (zebra-wrap out)
                   out))
        true))))

(defn- read-initial-lines [raf limit]
  (if (and limit (pos? limit))
    (loop [lines clojure.lang.PersistentQueue/EMPTY]
      (if-let [line (.readLine raf)]
        (let [next-lines (conj lines line)
              next-lines (if (> (count next-lines) limit)
                           (pop next-lines)
                           next-lines)]
          (recur next-lines))
        lines))
    (loop [lines []]
      (if-let [line (.readLine raf)]
        (recur (conj lines line))
        lines))))

(defn tail!
  "Print formatted log entries from `path`.
   opts:
     :color?  (bool, default false)
     :follow? (bool, default false) — watch file for new lines; never returns
     :zebra?  (bool, default false)
     :plain?  (bool, default false) — raw passthrough, no parsing/coloring/zebra
     :limit   (int, default nil)    — show only the last N lines (nil/0/neg = all)"
  [path {:keys [color? follow? zebra? plain? limit]
         :or   {color? false follow? false zebra? false plain? false}}]
  (let [file (java.io.File. path)
        opts {:color? (and color? (not plain?))
              :zebra? (and zebra? (not plain?))
              :plain? plain?}
        row  (atom 0)
        emit (fn [line]
               (when (print-line! line @row opts)
                 (swap! row inc)))]
    (with-open [raf (java.io.RandomAccessFile. file "r")]
      (doseq [line (read-initial-lines raf limit)]
        (emit line))
      (when follow?
        (loop []
          (if-let [line (.readLine raf)]
            (do (emit line) (recur))
            (do (Thread/sleep *follow-sleep-ms*) (recur))))))))

;; endregion ^^^^^ Tailing ^^^^^

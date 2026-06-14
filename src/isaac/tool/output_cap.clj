(ns isaac.tool.output-cap
  (:require [clojure.string :as str]))

(def default-max-output-lines 2000)
(def default-max-output-bytes 262144)

(defn- byte-count [s]
  (alength (.getBytes ^String s "UTF-8")))

(defn cap-result
  "Applies line and byte caps to a tool result string using head-tail truncation.
   Checks line cap first, then byte cap. Returns capped string with a marker
   naming the amount cut and which cap fired."
  [s max-lines max-bytes]
  (let [lines   (str/split-lines s)
        n-lines (count lines)]
    (cond
      (> n-lines max-lines)
      (let [head-count (quot max-lines 2)
            tail-count (- max-lines head-count)
            head       (str/join "\n" (take head-count lines))
            tail       (str/join "\n" (take-last tail-count lines))
            truncated  (- n-lines max-lines)]
        (str head "\n... [ " truncated " lines truncated; line cap hit ] ...\n" tail))

      (> (byte-count s) max-bytes)
      (let [half      (quot max-bytes 2)
            bytes     (.getBytes ^String s "UTF-8")
            total     (alength bytes)
            head      (String. (java.util.Arrays/copyOfRange bytes 0 (min half total)) "UTF-8")
            tail      (String. (java.util.Arrays/copyOfRange bytes (max 0 (- total half)) total) "UTF-8")
            truncated (- total max-bytes)]
        (str head "\n... [ " truncated " bytes truncated; byte cap hit ] ...\n" tail))

      :else s)))

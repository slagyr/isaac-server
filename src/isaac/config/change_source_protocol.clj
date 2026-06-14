(ns isaac.config.change-source-protocol
  (:require [clojure.string :as str]))

(defprotocol ConfigChangeSource
  (-start! [this])
  (-stop! [this])
  (-poll! [this timeout-ms])
  (-notify-path! [this path]))

(defn editor-artifact?
  "Returns true when the path looks like an editor temp/swap/backup artifact
   that should not trigger a config reload."
  [path]
  (let [filename (last (str/split (str/replace path "\\" "/") #"/"))]
    (or (str/ends-with? filename "~")
        (str/ends-with? filename ".swp")
        (str/ends-with? filename ".swo")
        (str/ends-with? filename ".swx")
        (str/starts-with? filename ".#")
        (and (str/starts-with? filename "#") (str/ends-with? filename "#"))
        (boolean (re-matches #"\d+" filename)))))

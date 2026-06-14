(ns isaac.util.shell
  (:require [babashka.process :as process]
            [clojure.java.shell :as sh]))

(def ^:dynamic *sh* nil)
(def ^:dynamic *os-name* nil)

(defn sh! [& args]
  (if *sh* (apply *sh* args) (apply sh/sh args)))

(defn exec!
  "Like sh!, but streams stdout/stderr directly instead of capturing. In test
  context (*sh* bound) behaves identically to sh!."
  [& args]
  (if *sh*
    (apply *sh* args)
    @(process/process args {:out :inherit :err :inherit})))

(defn os-name []
  (or *os-name* (System/getProperty "os.name")))

(defn cmd-available? [cmd]
  (= 0 (:exit (sh/sh "which" cmd))))

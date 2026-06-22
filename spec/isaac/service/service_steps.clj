(ns isaac.service.service-steps
  (:require
    [clojure.string :as str]
    [clojure.data.xml :as xml]
    [gherclj.core :as g :refer [defgiven defthen helper!]]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.fs :as fs]
    [isaac.config.root :as root]
    [isaac.nexus :as nexus]
    [isaac.service.cli :as service-cli]))

(helper! isaac.service.service-steps)

(defn- expand-path [path]
  (cond
    (= "~" path)                    (root/user-home)
    (str/starts-with? path "~/")    (str (root/user-home) (subs path 1))
    (str/starts-with? path "/")     path
    :else                           (str (or (g/get :root) (System/getProperty "user.dir")) "/" path)))

(defn- elem-nodes [content]
  (filter map? content))

(declare plist-dict)

(defn- plist-value [node]
  (case (:tag node)
    :string  (first (:content node))
    :integer (parse-long (first (:content node)))
    :true    true
    :false   false
    :array   (mapv plist-value (elem-nodes (:content node)))
    :dict    (plist-dict (elem-nodes (:content node)))
    nil))

(defn plist-dict [content]
  (loop [remaining (elem-nodes content) m {}]
    (if (< (count remaining) 2)
      m
      (let [k (first (:content (first remaining)))
            v (plist-value (second remaining))]
        (recur (drop 2 remaining) (assoc m k v))))))

(defn- parse-plist [xml-str]
  (let [clean (str/replace xml-str #"<!DOCTYPE[^>]*>" "")
        root  (xml/parse (java.io.ByteArrayInputStream. (.getBytes clean)))
        dict  (first (elem-nodes (:content root)))]
    (plist-dict (:content dict))))

(defn- plist-get [pmap path]
  (cond
    (str/includes? path ".")
    (let [[parent child] (str/split path #"\." 2)]
      (get (get pmap parent) child))

    :else
    (if-let [[_ key idx-str] (re-matches #"(.+)\[(\d+)\]" path)]
      (nth (get pmap key) (parse-long idx-str) nil)
      (get pmap path))))

(defn- sh-fn []
  (fn [& args]
    (let [argv (vec args)]
      (g/update! :sh-calls #(conj (or % []) argv))
      (when (= "launchctl" (first argv))
        (g/update! :launchctl-calls #(conj (or % []) argv)))
      (cond
        (and (= "launchctl" (first argv)) (= "print" (second argv)))
        {:exit 0 :out (or (g/get :launchctl-print-output) "") :err ""}

        (= "which" (first argv))
        (if-let [bin (g/get :which-results)]
          (let [cmd (second argv)]
            (if-let [path (get bin cmd)]
              {:exit 0 :out (str path "\n") :err ""}
              {:exit 1 :out "" :err ""}))
          {:exit 1 :out "" :err ""})

        (= "id" (first argv))
        {:exit 0 :out "501\n" :err ""}

        :else
        {:exit 0 :out "" :err ""}))))

(defn launchctl-stubbed []
  (g/assoc! :launchctl-calls [])
  (g/assoc! :sh-calls [])
  (g/assoc! :sh-fn (sh-fn)))

(defn launchctl-print-returns [doc-string]
  (g/assoc! :launchctl-print-output (str/trim doc-string))
  (when-not (g/get :sh-fn)
    (g/assoc! :sh-fn (sh-fn))))

(defn launchctl-was-called-with [expected]
  (let [calls   (or (g/get :launchctl-calls) [])
        norm    (fn [s] (-> s
                            (str/replace "<uid>" "501")
                            (str/replace "~" (root/user-home))
                            str/trim))
        pattern (norm expected)]
    (g/should (some (fn [call]
                      (let [call-str (str/join " " call)]
                        (str/includes? call-str pattern)))
                    calls))))

(defn operating-system-is [os]
  (g/assoc! :os-name os))

(defn bb-resolves-to [cmd path]
  (g/update! :which-results #(assoc (or % {}) cmd path))
  (when-not (g/get :sh-fn)
    (g/assoc! :sh-fn (sh-fn))))

(defn bb-not-on-path [_cmd]
  (g/assoc! :which-results {})
  (when-not (g/get :sh-fn)
    (g/assoc! :sh-fn (sh-fn))))

(defn current-process-path-is [path]
  (g/assoc! :process-path path))

(cli-steps/register-isaac-run-wrapper!
  (fn [thunk]
    (if-let [path (g/get :process-path)]
      (binding [service-cli/*caller-path* path]
        (thunk))
      (thunk))))

(defn sh-was-called-with [expected]
  (let [calls   (or (g/get :sh-calls) [])
        pattern (str/trim expected)]
    (g/should (some (fn [call]
                      (str/includes? (str/join " " call) pattern))
                    calls))))

(defn plist-contains [table]
  (let [plist-path (expand-path "~/Library/LaunchAgents/com.slagyr.isaac.plist")
        content    (if-let [mem-fs (g/get :mem-fs)]
                     (fs/slurp mem-fs plist-path)
                     (slurp plist-path))
        pmap       (parse-plist content)]
    (doseq [row (:rows table)]
      (let [[path expected] row
            actual          (plist-get pmap path)]
        (g/should= expected (str actual))))))

(defn plist-program-arguments-end-with [expected]
  (let [plist-path (expand-path "~/Library/LaunchAgents/com.slagyr.isaac.plist")
        content    (if-let [mem-fs (g/get :mem-fs)]
                     (fs/slurp mem-fs plist-path)
                     (slurp plist-path))
        args       (get (parse-plist content) "ProgramArguments")]
    (g/should= expected (last args))))

(defgiven "launchctl is stubbed" isaac.service.service-steps/launchctl-stubbed)

(defgiven "launchctl print returns:" isaac.service.service-steps/launchctl-print-returns)

(defthen "launchctl was called with {expected:string}" isaac.service.service-steps/launchctl-was-called-with)

(defgiven "the operating system is {os:string}" isaac.service.service-steps/operating-system-is)

(defgiven "{cmd:string} resolves to {path:string}" isaac.service.service-steps/bb-resolves-to)

(defgiven "{cmd:string} is not on PATH" isaac.service.service-steps/bb-not-on-path)

(defgiven "the current process PATH is {path:string}" isaac.service.service-steps/current-process-path-is)

(defthen "the plist contains:" isaac.service.service-steps/plist-contains)

(defthen "the plist program arguments end with {expected:string}"
  isaac.service.service-steps/plist-program-arguments-end-with)

(defthen "sh was called with {expected:string}" isaac.service.service-steps/sh-was-called-with)
(ns isaac.service.launch
  "Platform-neutral pieces of `isaac service install`: how the server is
   invoked (program arguments per mode/runtime), which PATH the service gets,
   and how to read the runtime back out of an installed invocation. Both the
   macOS (launchd) and Linux (systemd) managers build on this."
  (:require
    [clojure.string :as str]))

(defn- server-tail-args
  "Trailing argv after root flags for bb (and dev jvm trampoline). Default bb
   omits --runtime so existing installs stay unchanged."
  [runtime]
  (if (= "jvm" runtime)
    ["server" "--runtime" "jvm"]
    ["server"]))

(defn jvm-packaged-exec-cmd
  "Single sh -c string: exec clojure with a fresh -Sdeps from isaac modules deps."
  [{:keys [isaac-bin root]}]
  (str "exec clojure -Sdeps \"$(" isaac-bin
       (when root (str " --root " root))
       " modules deps --edn)\" -M -m isaac.main"
       (when root (str " --root " root))
       " server"))

(defn- jvm-packaged-program-arguments
  "Neither launchd nor systemd expands $() in program arguments; sh -c runs
   modules deps each boot."
  [{:keys [isaac-bin root]}]
  ["/bin/sh" "-c" (jvm-packaged-exec-cmd {:isaac-bin isaac-bin :root root})])

(defn program-arguments
  "Packaged bb runs the launcher (`isaac server`); packaged jvm execs clojure via
   sh wrapper; dev checkouts use `bb --config <repo>/bb.edn -m isaac.main`."
  [{:keys [mode isaac-bin bb-bin bb-edn root runtime]}]
  (let [runtime (or runtime "bb")]
    (case mode
      :packaged
      (if (= "jvm" runtime)
        (jvm-packaged-program-arguments {:isaac-bin isaac-bin :root root})
        (into (cond-> [isaac-bin]
                (some? root) (into ["--root" root]))
              (server-tail-args runtime)))

      :dev
      (into [bb-bin "--config" (str bb-edn "/bb.edn") "-m" "isaac.main"]
            (server-tail-args runtime)))))

(defn- parent-dir [path]
  (when (and (string? path) (seq path))
    (.getParent (java.io.File. path))))

(defn default-path
  "Minimal PATH for a service manager: bb/isaac dirs plus /usr/bin and /bin (git)."
  [{:keys [bb-bin isaac-bin]}]
  (->> [(parent-dir bb-bin)
        (parent-dir isaac-bin)
        "/usr/bin"
        "/bin"]
       (remove str/blank?)
       distinct
       (str/join ":")))

(defn service-path
  "PATH baked into the service definition: explicit override, else caller
   shell PATH, else synthesized default-path."
  [{:keys [path caller-path bb-bin isaac-bin]}]
  (or path
      (when-let [p (when (string? caller-path) (str/trim caller-path))]
        (when (seq p) p))
      (default-path {:bb-bin bb-bin :isaac-bin isaac-bin})))

(defn runtime-from-program-arguments
  "Infer the installed server runtime (bb or jvm) from its program arguments."
  [args]
  (cond
    (and (= "/bin/sh" (first args))
         (some #(and (string? %)
                     (str/includes? % "clojure")
                     (str/includes? % "-m isaac.main"))
               args))
    "jvm"

    :else
    (let [idx (.indexOf ^java.util.List (or args []) "--runtime")]
      (if (neg? idx) "bb" (nth args (inc idx) "bb")))))

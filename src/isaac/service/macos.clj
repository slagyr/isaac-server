(ns isaac.service.macos
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.config.root :as root]
    [isaac.nexus :as nexus]
    [isaac.shell :as shell]))

(defn- runtime-fs [opts] (fs/instance opts))

(def ^:private label "com.slagyr.isaac")

(def ^:private plist-template
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">
<plist version=\"1.0\">
<dict>
    <key>Label</key>
    <string>{LABEL}</string>
    <key>ProgramArguments</key>
    <array>
        {PROGRAM_ARGS}
    </array>
    <key>EnvironmentVariables</key>
    <dict>
        <key>PATH</key>
        <string>{PATH}</string>
    </dict>
    <key>KeepAlive</key>
    <true/>
    <key>RunAtLoad</key>
    <true/>
    <key>StandardOutPath</key>
    <string>{LOG_DIR}/server.log</string>
    <key>StandardErrorPath</key>
    <string>{LOG_DIR}/server.log</string>
</dict>
</plist>")

(defn- program-arguments
  "Packaged installs run the launcher (`isaac server`); dev checkouts use
   `bb --config <repo>/bb.edn -m isaac.main server`."
  [{:keys [mode isaac-bin bb-bin bb-edn root]}]
  (case mode
    :packaged
    (cond-> [isaac-bin]
      (some? root) (into ["--root" root])
      true         (conj "server"))

    :dev
    [bb-bin "--config" (str bb-edn "/bb.edn") "-m" "isaac.main" "server"]))

(defn- program-args-xml [args]
  (str/join "\n        " (map #(str "<string>" % "</string>") args)))

(defn- parent-dir [path]
  (when (and (string? path) (seq path))
    (.getParent (java.io.File. path))))

(defn launchd-path
  "Minimal PATH for launchd: bb/isaac dirs plus /usr/bin and /bin (git)."
  [{:keys [bb-bin isaac-bin]}]
  (->> [(parent-dir bb-bin)
        (parent-dir isaac-bin)
        "/usr/bin"
        "/bin"]
       (remove str/blank?)
       distinct
       (str/join ":")))

(defn plist-content [{:keys [mode isaac-bin bb-bin bb-edn root log-dir path]}]
  (let [args (program-arguments {:mode      mode
                                 :isaac-bin isaac-bin
                                 :bb-bin    bb-bin
                                 :bb-edn    bb-edn
                                 :root      root})
        path (or path (launchd-path {:bb-bin bb-bin :isaac-bin isaac-bin}))]
    (-> plist-template
        (str/replace "{LABEL}" label)
        (str/replace "{PROGRAM_ARGS}" (program-args-xml args))
        (str/replace "{PATH}" path)
        (str/replace "{LOG_DIR}" log-dir))))

(defn- user-home [] (root/user-home))
(defn- plist-path [] (str (user-home) "/Library/LaunchAgents/" label ".plist"))
(defn- log-dir []   (str (user-home) "/Library/Logs/isaac"))

(defn- uid []
  (str/trim (:out (shell/sh! "id" "-u"))))

(defn- service-target []
  (str "gui/" (uid) "/" label))

(defn- bootstrap-target []
  (str "gui/" (uid)))

(defn install! [{:keys [mode isaac-bin bb-bin bb-edn root] :as opts}]
  (let [log-d   (log-dir)
        plist-p (plist-path)
        fs*     (runtime-fs opts)
        content (plist-content {:mode      mode
                                :isaac-bin isaac-bin
                                :bb-bin    bb-bin
                                :bb-edn    bb-edn
                                :root      root
                                :log-dir   log-d
                                :path      (:path opts)})]
    (fs/mkdirs fs* (fs/parent plist-p))
    (fs/mkdirs fs* log-d)
    (fs/spit fs* plist-p content)
    (shell/sh! "launchctl" "bootstrap" (bootstrap-target) plist-p)))

(defn uninstall! [opts]
  (let [plist-p (plist-path)
        fs*     (runtime-fs opts)]
    (when (fs/exists? fs* plist-p)
      (shell/sh! "launchctl" "bootout" (service-target))
      (fs/delete fs* plist-p))))

(defn start! [_opts]
  (shell/sh! "launchctl" "bootstrap" (bootstrap-target) (plist-path)))

(defn stop! [_opts]
  (shell/sh! "launchctl" "bootout" (service-target)))

(defn restart! [_opts]
  (shell/sh! "launchctl" "kickstart" "-k" (service-target)))

(defn parse-status [output]
  {:state      (second (re-find #"state\s*=\s*(\S+)" output))
   :pid        (second (re-find #"pid\s*=\s*(\d+)" output))
   :last-exit  (second (re-find #"last exit code\s*=\s*(-?\d+)" output))})

(defn status! [opts]
  (let [plist-p (plist-path)
        fs*     (runtime-fs opts)]
    (if-not (fs/exists? fs* plist-p)
      {:installed? false}
      (let [result (shell/sh! "launchctl" "print" (service-target))]
        (if (zero? (:exit result))
          (assoc (parse-status (:out result)) :installed? true)
          {:installed? true :state "stopped"})))))

(defn logs! [{:keys [follow?] :as opts}]
  (let [log-file (str (log-dir) "/server.log")
        fs*      (runtime-fs opts)]
    (if (fs/exists? fs* log-file)
      (if follow?
        (do (shell/exec! "tail" "-f" log-file)
            {:log-path log-file :content nil})
        {:log-path log-file :content (fs/slurp fs* log-file)})
      {:log-path log-file :content nil})))
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
        <string>{BB_BIN}</string>
        <string>--config</string>
        <string>{BB_EDN}/bb.edn</string>
        <string>-m</string>
        <string>isaac.main</string>
        <string>server</string>
    </array>
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

(defn plist-content [{:keys [bb-bin bb-edn home log-dir]}]
  (-> plist-template
      (str/replace "{LABEL}"   label)
      (str/replace "{BB_BIN}"  bb-bin)
      (str/replace "{BB_EDN}"  bb-edn)
      (str/replace "{HOME}"    (or home ""))
      (str/replace "{LOG_DIR}" log-dir)))

(defn- user-home [] (root/user-home))
(defn- plist-path [] (str (user-home) "/Library/LaunchAgents/" label ".plist"))
(defn- log-dir []   (str (user-home) "/Library/Logs/isaac"))

(defn- uid []
  (str/trim (:out (shell/sh! "id" "-u"))))

(defn- service-target []
  (str "gui/" (uid) "/" label))

(defn- bootstrap-target []
  (str "gui/" (uid)))

(defn install! [{:keys [bb-bin bb-edn] :as opts}]
  (let [log-d   (log-dir)
        plist-p (plist-path)
        h       (user-home)
        fs*     (runtime-fs opts)
        content (plist-content {:bb-bin  bb-bin
                                :bb-edn  bb-edn
                                :home    h
                                :log-dir log-d})]
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

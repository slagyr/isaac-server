(ns isaac.service.linux
  "systemd user-unit manager for `isaac service` on Linux. Writes
   ~/.config/systemd/user/isaac.service, drives `systemctl --user`, and warns
   when the user session does not linger (a user unit stops at logout
   otherwise)."
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.config.root :as root]
    [isaac.service.launch :as launch]
    [isaac.service.manager :as manager]
    [isaac.shell :as shell]))

(defn- runtime-fs [opts] (fs/instance opts))

(def unit-name "isaac")

(def ^:private unit-template
  "[Unit]
Description=Isaac server
After=network-online.target

[Service]
ExecStart={EXEC_START}
Environment=PATH={PATH}
Restart=always
RestartSec=2
StandardOutput=append:{LOG_DIR}/server.log
StandardError=append:{LOG_DIR}/server.log

[Install]
WantedBy=default.target
")

(defn- needs-quotes? [arg]
  (or (str/blank? arg) (re-find #"[\s\"]" arg)))

(defn quote-arg
  "One ExecStart word under systemd's quoting rules: a lone $ is variable
   substitution, so every $ becomes $$; words with whitespace or quotes are
   double-quoted with C-style escapes."
  [arg]
  (let [escaped (-> arg
                    (str/replace "\\" "\\\\")
                    (str/replace "\"" "\\\"")
                    (str/replace "$" "$$"))]
    (if (needs-quotes? arg)
      (str "\"" escaped "\"")
      escaped)))

(defn exec-start
  "Space-joined ExecStart line for a program-argument vector."
  [args]
  (str/join " " (map quote-arg args)))

(defn- unquote-word [word]
  (-> word
      (str/replace "$$" "$")
      (str/replace "\\\"" "\"")
      (str/replace "\\\\" "\\")))

(defn exec-start-args
  "Inverse of `exec-start` for the shapes we write: a leading double-quoted
   word (the sh -c wrapper) or plain whitespace-separated words."
  [line]
  (loop [rest-line (str/trim line) args []]
    (cond
      (str/blank? rest-line)
      args

      (str/starts-with? rest-line "\"")
      (let [m (re-find #"^\"((?:[^\"\\]|\\.)*)\"\s*(.*)$" rest-line)]
        (if m
          (recur (nth m 2) (conj args (unquote-word (nth m 1))))
          (conj args (unquote-word (subs rest-line 1)))))

      :else
      (let [[word remaining] (str/split rest-line #"\s+" 2)]
        (recur (or remaining "") (conj args (unquote-word word)))))))

(defn parse-exec-start
  "ExecStart= value from a unit file, or nil."
  [content]
  (second (re-find #"(?m)^ExecStart=(.*)$" content)))

(defn runtime-from-unit
  "Infer installed server runtime from the unit's ExecStart (default bb)."
  [content]
  (launch/runtime-from-program-arguments (exec-start-args (or (parse-exec-start content) ""))))

(defn unit-content [{:keys [mode isaac-bin bb-bin bb-edn root runtime log-dir path caller-path]}]
  (let [args     (launch/program-arguments {:mode      mode
                                            :isaac-bin isaac-bin
                                            :bb-bin    bb-bin
                                            :bb-edn    bb-edn
                                            :root      root
                                            :runtime   runtime})
        env-path (launch/service-path {:path        path
                                       :caller-path caller-path
                                       :bb-bin      bb-bin
                                       :isaac-bin   isaac-bin})]
    (-> unit-template
        (str/replace "{EXEC_START}" (exec-start args))
        (str/replace "{PATH}" env-path)
        (str/replace "{LOG_DIR}" log-dir))))

(defn- user-home [] (root/user-home))
(defn unit-file-path [] (str (user-home) "/.config/systemd/user/" unit-name ".service"))
(defn log-dir [] (str (user-home) "/.local/state/isaac"))

(defn- user-name [] (System/getProperty "user.name"))

(defn- systemctl! [& args]
  (apply shell/sh! "systemctl" "--user" args))

(defn parse-linger
  "True only when loginctl reports Linger=yes; anything else (no, empty, error)
   is treated as not lingering so the warning errs toward showing."
  [output]
  (= "yes" (second (re-find #"Linger=(\S+)" (or output "")))))

(defn linger?
  "Does the user's session linger (user units survive logout)?"
  []
  (let [result (shell/sh! "loginctl" "show-user" (user-name) "-p" "Linger")]
    (and (zero? (:exit result)) (parse-linger (:out result)))))

(defn install! [{:keys [mode isaac-bin bb-bin bb-edn root runtime] :as opts}]
  (let [log-d   (log-dir)
        unit-p  (unit-file-path)
        fs*     (runtime-fs opts)
        content (unit-content {:mode      mode
                               :isaac-bin isaac-bin
                               :bb-bin    bb-bin
                               :bb-edn    bb-edn
                               :root      root
                               :runtime   runtime
                               :log-dir   log-d
                               :path      (:path opts)})]
    (fs/mkdirs fs* (fs/parent unit-p))
    (fs/mkdirs fs* log-d)
    (fs/spit fs* unit-p content)
    (systemctl! "daemon-reload")
    (systemctl! "enable" "--now" unit-name)
    {:linger? (linger?)}))

(defn uninstall! [opts]
  (let [unit-p (unit-file-path)
        fs*    (runtime-fs opts)]
    (when (fs/exists? fs* unit-p)
      (systemctl! "disable" "--now" unit-name)
      (fs/delete fs* unit-p)
      (systemctl! "daemon-reload"))))

(defn start! [_opts] (systemctl! "start" unit-name))
(defn stop! [_opts] (systemctl! "stop" unit-name))
(defn restart! [_opts] (systemctl! "restart" unit-name))

(defn- property [output name]
  (second (re-find (re-pattern (str "(?m)^" name "=(\\S*)$")) output)))

(defn parse-status
  "systemctl show output → {:state :pid :last-exit}. active ⇒ \"running\";
   any other ActiveState is reported raw."
  [output]
  (let [active (property output "ActiveState")
        pid    (property output "MainPID")]
    {:state     (when active (if (= "active" active) "running" active))
     :pid       (when (and pid (not= "0" pid)) pid)
     :last-exit (property output "ExecMainStatus")}))

(defn status! [opts]
  (let [unit-p (unit-file-path)
        fs*    (runtime-fs opts)]
    (if-not (fs/exists? fs* unit-p)
      {:installed? false}
      (let [runtime (runtime-from-unit (fs/slurp fs* unit-p))
            result  (systemctl! "show" unit-name "-p" "ActiveState" "-p" "MainPID" "-p" "ExecMainStatus")]
        (if (zero? (:exit result))
          (assoc (parse-status (:out result)) :installed? true :runtime runtime)
          {:installed? true :state "stopped" :runtime runtime})))))

(defn logs! [{:keys [follow?] :as opts}]
  (let [log-file (str (log-dir) "/server.log")
        fs*      (runtime-fs opts)]
    (if (fs/exists? fs* log-file)
      (if follow?
        (do (shell/exec! "tail" "-f" log-file)
            {:log-path log-file :content nil})
        {:log-path log-file :content (fs/slurp fs* log-file)})
      {:log-path log-file :content nil})))

(defrecord SystemdManager []
  manager/Manager
  (service-name [_] unit-name)
  (install! [_ opts] (install! opts))
  (uninstall! [_ opts] (uninstall! opts))
  (start! [_ opts] (start! opts))
  (stop! [_ opts] (stop! opts))
  (restart! [_ opts] (restart! opts))
  (status! [_ opts] (status! opts))
  (logs! [_ opts] (logs! opts)))

(def manager (->SystemdManager))

(ns isaac.service.linux-spec
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.config.root :as root]
    [isaac.nexus :as nexus]
    [isaac.service.linux :as sut]
    [isaac.shell :as shell]
    [speclj.core :refer :all]))

(defn- stub-sh
  ([calls-atom] (stub-sh calls-atom {}))
  ([calls-atom stdout-by-cmd]
   (fn [& args]
     (swap! calls-atom conj (vec args))
     {:exit 0 :out (get stdout-by-cmd (first args) "") :err ""})))

(defn- called? [calls & prefix]
  (some #(= (vec prefix) (vec (take (count prefix) %))) calls))

(def ^:private unit-path "/test/home/.config/systemd/user/isaac.service")
(def ^:private log-dir "/test/home/.local/state/isaac")

(describe "service.linux"

  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (binding [root/*user-home* "/test/home"]
        (example))))

  (describe "quote-arg"

    (it "leaves plain words alone"
      (should= "/usr/local/bin/isaac" (sut/quote-arg "/usr/local/bin/isaac")))

    (it "doubles every $ so systemd passes it through"
      (should= "$$HOME" (sut/quote-arg "$HOME")))

    (it "double-quotes words with whitespace and escapes inner quotes"
      (should= "\"say \\\"hi\\\"\"" (sut/quote-arg "say \"hi\"")))

    (it "escapes backslashes inside quotes"
      (should= "\"a\\\\ b\"" (sut/quote-arg "a\\ b"))))

  (describe "exec-start / exec-start-args"

    (it "round-trips the packaged bb invocation"
      (let [args ["/usr/local/bin/isaac" "--root" "/var/isaac" "server"]]
        (should= "/usr/local/bin/isaac --root /var/isaac server" (sut/exec-start args))
        (should= args (sut/exec-start-args (sut/exec-start args)))))

    (it "round-trips the jvm sh wrapper"
      (let [args ["/bin/sh" "-c" "exec clojure -Sdeps \"$(/usr/local/bin/isaac modules deps --edn)\" -M -m isaac.main server"]
            line (sut/exec-start args)]
        (should (str/starts-with? line "/bin/sh -c \"exec clojure -Sdeps \\\"$$(/usr/local/bin/isaac"))
        (should= args (sut/exec-start-args line)))))

  (describe "unit-content"

    (it "bakes ExecStart, PATH, restart policy, log redirects and install target"
      (let [unit (sut/unit-content {:mode      :packaged
                                    :isaac-bin "/usr/local/bin/isaac"
                                    :bb-bin    "/usr/local/bin/bb"
                                    :log-dir   log-dir})]
        (should-contain "[Unit]\nDescription=Isaac server" unit)
        (should-contain "ExecStart=/usr/local/bin/isaac server\n" unit)
        (should-contain "Environment=PATH=/usr/local/bin:/usr/bin:/bin\n" unit)
        (should-contain "Restart=always\n" unit)
        (should-contain (str "StandardOutput=append:" log-dir "/server.log\n") unit)
        (should-contain (str "StandardError=append:" log-dir "/server.log\n") unit)
        (should-contain "[Install]\nWantedBy=default.target" unit)))

    (it "prefers caller PATH and honors --path"
      (should-contain "Environment=PATH=/opt/quartz/bin:/usr/bin:/bin\n"
                      (sut/unit-content {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :bb-bin "/usr/local/bin/bb"
                                         :path "/opt/quartz/bin:/usr/bin:/bin" :caller-path "/opt/marigold/bin" :log-dir log-dir}))
      (should-contain "Environment=PATH=/opt/marigold/bin\n"
                      (sut/unit-content {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :bb-bin "/usr/local/bin/bb"
                                         :caller-path "/opt/marigold/bin" :log-dir log-dir})))

    (it "writes the dev-checkout invocation"
      (should-contain "ExecStart=/opt/homebrew/bin/bb --config /projects/isaac/bb.edn -m isaac.main server\n"
                      (sut/unit-content {:mode :dev :bb-bin "/opt/homebrew/bin/bb" :bb-edn "/projects/isaac" :log-dir log-dir}))))

  (describe "runtime-from-unit"

    (it "reads jvm from the sh wrapper"
      (should= "jvm" (sut/runtime-from-unit (sut/unit-content {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :bb-bin "/usr/local/bin/bb" :runtime "jvm" :log-dir log-dir}))))

    (it "defaults to bb"
      (should= "bb" (sut/runtime-from-unit (sut/unit-content {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :bb-bin "/usr/local/bin/bb" :log-dir log-dir}))))

    (it "defaults to bb when ExecStart is missing"
      (should= "bb" (sut/runtime-from-unit ""))))

  (describe "install!"

    (it "writes the unit and the log dir, reloads, enables --now"
      (let [calls (atom [])]
        (binding [shell/*sh* (stub-sh calls)]
          (sut/install! {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :bb-bin "/usr/local/bin/bb"})
          (should (fs/exists? (fs/instance) unit-path))
          (should (fs/exists? (fs/instance) log-dir))
          (should (called? @calls "systemctl" "--user" "daemon-reload"))
          (should (called? @calls "systemctl" "--user" "enable" "--now" "isaac")))))

    (it "reports linger? true when loginctl says yes"
      (let [calls (atom [])]
        (binding [shell/*sh* (stub-sh calls {"loginctl" "Linger=yes\n"})]
          (should= {:linger? true} (sut/install! {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :bb-bin "/usr/local/bin/bb"}))
          (should (called? @calls "loginctl" "show-user")))))

    (it "reports linger? false when loginctl says no or nothing"
      (binding [shell/*sh* (stub-sh (atom []) {"loginctl" "Linger=no\n"})]
        (should= {:linger? false} (sut/install! {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :bb-bin "/usr/local/bin/bb"})))
      (binding [shell/*sh* (stub-sh (atom []))]
        (should= {:linger? false} (sut/install! {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :bb-bin "/usr/local/bin/bb"}))))

    (it "reports linger? false when loginctl fails"
      (binding [shell/*sh* (fn [& args] (if (= "loginctl" (first args)) {:exit 1 :out "" :err "no such user"} {:exit 0 :out "" :err ""}))]
        (should= {:linger? false} (sut/install! {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :bb-bin "/usr/local/bin/bb"}))))

    (it "accepts an explicit fs via opts"
      (let [mem (fs/mem-fs)]
        (binding [shell/*sh* (stub-sh (atom []))]
          (sut/install! {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :fs mem})
          (should (fs/exists? mem unit-path))))))

  (describe "uninstall!"

    (it "disables --now, removes the unit, reloads"
      (let [calls (atom [])]
        (binding [shell/*sh* (stub-sh calls)]
          (fs/mkdirs (fs/instance) (fs/parent unit-path))
          (fs/spit (fs/instance) unit-path "test")
          (sut/uninstall! {})
          (should-not (fs/exists? (fs/instance) unit-path))
          (should (called? @calls "systemctl" "--user" "disable" "--now" "isaac"))
          (should (called? @calls "systemctl" "--user" "daemon-reload")))))

    (it "is a no-op when the unit is absent"
      (let [calls (atom [])]
        (binding [shell/*sh* (stub-sh calls)]
          (sut/uninstall! {})
          (should= [] @calls)))))

  (describe "start! stop! restart!"

    (it "call the matching systemctl --user verbs"
      (let [calls (atom [])]
        (binding [shell/*sh* (stub-sh calls)]
          (sut/start! {})
          (sut/stop! {})
          (sut/restart! {})
          (should= [["systemctl" "--user" "start" "isaac"]
                    ["systemctl" "--user" "stop" "isaac"]
                    ["systemctl" "--user" "restart" "isaac"]]
                   @calls)))))

  (describe "parse-status"

    (it "maps active to running with pid and last exit"
      (should= {:state "running" :pid "51234" :last-exit "0"}
               (sut/parse-status "ActiveState=active\nMainPID=51234\nExecMainStatus=0\n")))

    (it "reports other states raw and drops pid 0"
      (should= {:state "failed" :pid nil :last-exit "1"}
               (sut/parse-status "ActiveState=failed\nMainPID=0\nExecMainStatus=1\n")))

    (it "returns nils for empty output"
      (should= {:state nil :pid nil :last-exit nil} (sut/parse-status ""))))

  (describe "status!"

    (it "reports not installed without a unit file"
      (binding [shell/*sh* (stub-sh (atom []))]
        (should= {:installed? false} (sut/status! {}))))

    (it "reads state from systemctl show and runtime from the unit"
      (let [calls (atom [])]
        (binding [shell/*sh* (stub-sh calls {"systemctl" "ActiveState=active\nMainPID=7\nExecMainStatus=0\n"})]
          (sut/install! {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :bb-bin "/usr/local/bin/bb" :runtime "jvm"})
          (let [result (sut/status! {})]
            (should= "running" (:state result))
            (should= "7" (:pid result))
            (should= "jvm" (:runtime result))
            (should (called? @calls "systemctl" "--user" "show" "isaac"))))))

    (it "reports stopped when systemctl show fails"
      (binding [shell/*sh* (fn [& args] (if (= "show" (nth args 2 nil)) {:exit 4 :out "" :err ""} {:exit 0 :out "" :err ""}))]
        (sut/install! {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :bb-bin "/usr/local/bin/bb"})
        (should= {:installed? true :state "stopped" :runtime "bb"} (sut/status! {})))))

  (describe "logs!"

    (it "returns file content when follow? is false"
      (binding [shell/*sh* (stub-sh (atom []))]
        (fs/mkdirs (fs/instance) log-dir)
        (fs/spit (fs/instance) (str log-dir "/server.log") "log line")
        (should= "log line" (:content (sut/logs! {:follow? false})))))

    (it "calls tail -f when follow? is true"
      (let [calls (atom [])]
        (binding [shell/*sh* (stub-sh calls)]
          (fs/mkdirs (fs/instance) log-dir)
          (fs/spit (fs/instance) (str log-dir "/server.log") "log line")
          (sut/logs! {:follow? true})
          (should (called? @calls "tail" "-f" (str log-dir "/server.log"))))))

    (it "returns nil content when the log is missing"
      (binding [shell/*sh* (stub-sh (atom []))]
        (should-be-nil (:content (sut/logs! {:follow? false})))))))

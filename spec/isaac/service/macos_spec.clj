(ns isaac.service.macos-spec
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.config.root :as root]
    [isaac.service.macos :as sut]
    [isaac.nexus :as nexus]
    [isaac.shell :as shell]
    [speclj.core :refer :all]))

(def ^:dynamic *fs* nil)

(defn- stub-sh [calls-atom]
  (fn [& args]
    (swap! calls-atom conj (vec args))
    {:exit 0 :out "" :err ""}))

(describe "service.macos"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (let [mem (fs/mem-fs)]
      (nexus/-with-nested-nexus {:fs mem}
        (binding [*fs*             mem
                  root/*user-home* "/test/home"]
          (example)))))

  (describe "plist-content"

    (it "substitutes packaged launcher program arguments"
      (let [plist (sut/plist-content {:mode      :packaged
                                      :isaac-bin "/usr/local/bin/isaac"
                                      :bb-bin    "/usr/local/bin/bb"
                                      :log-dir   "/test/home/Library/Logs/isaac"})]
        (should (str/includes? plist "/usr/local/bin/isaac"))
        (should (str/includes? plist "<string>server</string>"))
        (should-not (str/includes? plist "bb.edn"))
        (should (str/includes? plist "com.slagyr.isaac"))
        (should (str/includes? plist "/test/home/Library/Logs/isaac/server.log"))))

    (it "sets launchd PATH with bb dir plus /usr/bin and /bin"
      (let [plist (sut/plist-content {:mode      :packaged
                                      :isaac-bin "/usr/local/bin/isaac"
                                      :bb-bin    "/usr/local/bin/bb"
                                      :log-dir   "/test/home/Library/Logs/isaac"})]
        (should (str/includes? plist "<key>EnvironmentVariables</key>"))
        (should (str/includes? plist "<string>/usr/local/bin:/usr/bin:/bin</string>"))))

    (it "builds launchd-path from bb and isaac parent dirs"
      (should= "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"
               (sut/launchd-path {:bb-bin    "/opt/homebrew/bin/bb"
                                  :isaac-bin "/usr/local/bin/isaac"})))

    (it "passes --root before server for packaged installs"
      (let [plist (sut/plist-content {:mode      :packaged
                                      :isaac-bin "/usr/local/bin/isaac"
                                      :root      "/var/isaac"
                                      :log-dir   "/test/home/Library/Logs/isaac"})]
        (should (str/includes? plist "<string>--root</string>\n        <string>/var/isaac</string>\n        <string>server</string>"))))

    (it "bakes --runtime jvm after server for packaged installs"
      (let [plist (sut/plist-content {:mode      :packaged
                                      :isaac-bin "/usr/local/bin/isaac"
                                      :bb-bin    "/usr/local/bin/bb"
                                      :runtime   "jvm"
                                      :log-dir   "/test/home/Library/Logs/isaac"})]
        (should (str/includes? plist "<string>server</string>\n        <string>--runtime</string>\n        <string>jvm</string>"))
        (should-not (str/includes? plist "<string>--runtime</string>\n        <string>bb</string>"))))

    (it "omits --runtime for default bb packaged installs"
      (let [plist (sut/plist-content {:mode      :packaged
                                      :isaac-bin "/usr/local/bin/isaac"
                                      :bb-bin    "/usr/local/bin/bb"
                                      :log-dir   "/test/home/Library/Logs/isaac"})]
        (should (str/includes? plist "<string>server</string>"))
        (should-not (str/includes? plist "--runtime"))))

    (it "substitutes dev-checkout program arguments"
      (let [plist (sut/plist-content {:mode    :dev
                                      :bb-bin  "/opt/homebrew/bin/bb"
                                      :bb-edn  "/projects/isaac"
                                      :log-dir "/test/home/Library/Logs/isaac"})]
        (should (str/includes? plist "/opt/homebrew/bin/bb"))
        (should (str/includes? plist "/projects/isaac/bb.edn"))
        (should (str/includes? plist "com.slagyr.isaac"))))

    (it "generates valid XML plist structure"
      (let [plist (sut/plist-content {:mode    :dev
                                      :bb-bin  "/usr/local/bin/bb"
                                      :bb-edn  "/repo"
                                      :log-dir "/home/user/Library/Logs/isaac"})]
        (should (str/starts-with? plist "<?xml"))
        (should (str/includes? plist "<plist"))))

    (it "invokes bb with -m isaac.main for dev checkout, not the run subcommand"
      (let [plist (sut/plist-content {:mode    :dev
                                      :bb-bin  "/opt/homebrew/bin/bb"
                                      :bb-edn  "/projects/isaac"
                                      :log-dir "/test/home/Library/Logs/isaac"})]
        (should-not (str/includes? plist "<string>run</string>"))
        (should (str/includes? plist "<string>-m</string>\n        <string>isaac.main</string>\n        <string>server</string>")))))

  (describe "install!"

    (it "writes the plist file for packaged installs"
      (let [calls (atom [])]
        (binding [shell/*sh* (stub-sh calls)]
          (sut/install! {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :bb-bin "/usr/local/bin/bb"})
          (should (fs/exists? *fs* "/test/home/Library/LaunchAgents/com.slagyr.isaac.plist")))))

    (it "creates the log directory"
      (let [calls (atom [])]
        (binding [shell/*sh* (stub-sh calls)]
          (sut/install! {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :bb-bin "/usr/local/bin/bb"})
          (should (fs/exists? *fs* "/test/home/Library/Logs/isaac")))))

    (it "calls launchctl bootstrap with the plist path"
      (let [calls (atom [])]
        (binding [shell/*sh* (stub-sh calls)]
          (sut/install! {:mode :dev :bb-bin "/opt/homebrew/bin/bb" :bb-edn "/projects/isaac"})
          (should (some #(and (= "launchctl" (first %))
                              (= "bootstrap" (second %))
                              (str/includes? (last %) "com.slagyr.isaac.plist"))
                        @calls))))))

    (it "accepts an explicit fs via opts"
      (let [calls (atom [])
            mem   (fs/mem-fs)]
        (binding [shell/*sh* (stub-sh calls)
                  root/*user-home* "/test/home"]
          (sut/install! {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :fs mem})
          (should (fs/exists? mem "/test/home/Library/LaunchAgents/com.slagyr.isaac.plist")))))

  (describe "start!"

    (it "calls launchctl bootstrap with the plist path"
      (let [calls (atom [])]
        (binding [shell/*sh* (stub-sh calls)]
          (sut/start! {})
          (should (some #(and (= "launchctl" (first %))
                              (= "bootstrap" (second %))
                              (str/includes? (last %) "com.slagyr.isaac.plist"))
                        @calls))))))

  (describe "logs!"

    (it "returns file content when follow? is false"
      (let [calls (atom [])]
        (binding [shell/*sh* (stub-sh calls)]
          (fs/mkdirs *fs* "/test/home/Library/Logs/isaac")
          (fs/spit   *fs* "/test/home/Library/Logs/isaac/server.log" "log line")
          (let [result (sut/logs! {:follow? false})]
            (should= "log line" (:content result))))))

    (it "calls tail -f when follow? is true"
      (let [calls (atom [])]
        (binding [shell/*sh* (stub-sh calls)]
          (fs/mkdirs *fs* "/test/home/Library/Logs/isaac")
          (fs/spit   *fs* "/test/home/Library/Logs/isaac/server.log" "log line")
          (sut/logs! {:follow? true})
          (should (some #(and (= "tail" (first %)) (= "-f" (second %))) @calls))))))

  (describe "uninstall!"

    (it "removes the plist file"
      (let [calls (atom [])]
        (binding [shell/*sh* (stub-sh calls)]
          (fs/mkdirs *fs* "/test/home/Library/LaunchAgents")
          (fs/spit   *fs* "/test/home/Library/LaunchAgents/com.slagyr.isaac.plist" "test")
          (sut/uninstall! {})
          (should-not (fs/exists? *fs* "/test/home/Library/LaunchAgents/com.slagyr.isaac.plist")))))

    (it "calls launchctl bootout"
      (let [calls (atom [])]
        (binding [shell/*sh* (stub-sh calls)]
          (fs/mkdirs *fs* "/test/home/Library/LaunchAgents")
          (fs/spit   *fs* "/test/home/Library/LaunchAgents/com.slagyr.isaac.plist" "test")
          (sut/uninstall! {})
          (should (some #(and (= "launchctl" (first %)) (= "bootout" (second %))) @calls))))))

  (describe "runtime-from-plist"

    (it "reads jvm from ProgramArguments"
      (let [plist (sut/plist-content {:mode      :packaged
                                      :isaac-bin "/usr/local/bin/isaac"
                                      :bb-bin    "/usr/local/bin/bb"
                                      :runtime   "jvm"
                                      :log-dir   "/test/home/Library/Logs/isaac"})]
        (should= "jvm" (sut/runtime-from-plist plist))))

    (it "defaults to bb when --runtime is absent"
      (let [plist (sut/plist-content {:mode      :packaged
                                      :isaac-bin "/usr/local/bin/isaac"
                                      :bb-bin    "/usr/local/bin/bb"
                                      :log-dir   "/test/home/Library/Logs/isaac"})]
        (should= "bb" (sut/runtime-from-plist plist)))))

  (describe "parse-status"

    (it "extracts state, pid, and last exit code from launchctl print output"
      (let [output "{\n\tstate = running\n\tpid = 51234\n\tlast exit code = 0\n}"
            result (sut/parse-status output)]
        (should= "running" (:state result))
        (should= "51234" (:pid result))
        (should= "0" (:last-exit result))))

    (it "returns nil for missing fields"
      (let [result (sut/parse-status "{ state = waiting }")]
        (should= "waiting" (:state result))
        (should-be-nil (:pid result))
        (should-be-nil (:last-exit result)))))

  (describe "status! runtime"

    (it "includes installed runtime from the plist"
      (let [calls (atom [])]
        (binding [shell/*sh* (stub-sh calls)]
          (sut/install! {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :bb-bin "/usr/local/bin/bb" :runtime "jvm"})
          (binding [shell/*sh* (fn [& args]
                                 (if (= "print" (second (vec args)))
                                   {:exit 0 :out "{ state = stopped }" :err ""}
                                   {:exit 0 :out "" :err ""}))]
            (should= "jvm" (:runtime (sut/status! {})))))))))
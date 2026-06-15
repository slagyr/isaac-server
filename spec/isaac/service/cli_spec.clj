(ns isaac.service.cli-spec
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.config.root :as root]
    [isaac.main :as main]
    [isaac.nexus :as nexus]
    [isaac.shell :as shell]
    [speclj.core :refer :all]))

(defn- run [args]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        code (binding [*out* out
                       *err* err]
               (main/run (str/split args #"\s+" -1)))]
    {:exit code :out (str out) :err (str err)}))

(describe "service.cli"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (binding [root/*user-home* "/test/home"
                shell/*sh*       (fn [& _]
                                    {:exit 0 :out "" :err ""})]
        (example))))

  (describe "on macOS"

    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (binding [shell/*os-name* "Mac OS X"]
        (example)))

    (it "install succeeds when bb is found"
      (binding [shell/*sh* (fn [& args]
                             (if (= ["which" "bb"] (take 2 (vec args)))
                               {:exit 0 :out "/opt/homebrew/bin/bb\n" :err ""}
                               {:exit 0 :out "" :err ""}))]
        (let [result (run "service install")]
          (should= 0 (:exit result))
          (should (str/includes? (:out result) "Resolved bb:")))))

    (it "install fails when bb is not found"
      (binding [shell/*sh* (fn [& _] {:exit 1 :out "" :err ""})]
        (let [result (run "service install")]
          (should= 1 (:exit result))
          (should (str/includes? (:err result) "could not locate bb")))))

    (it "install succeeds with --bb-bin override"
      (binding [shell/*sh* (fn [& _] {:exit 0 :out "" :err ""})]
        (let [result (run "service install --bb-bin /usr/local/bin/bb")]
          (should= 0 (:exit result))
          (should (str/includes? (:out result) "/usr/local/bin/bb")))))

    (it "uninstall prints confirmation"
      (let [result (run "service uninstall")]
        (should= 0 (:exit result))
        (should (str/includes? (:out result) "uninstalled"))))

    (it "status reports not installed when plist absent"
      (let [result (run "service status")]
        (should= 1 (:exit result))
        (should (str/includes? (:out result) "not installed"))))

    (it "status reports running when launchctl print shows running"
      (fs/mkdirs (nexus/get :fs) "/test/home/Library/LaunchAgents")
      (fs/spit   (nexus/get :fs) "/test/home/Library/LaunchAgents/com.slagyr.isaac.plist" "test")
      (binding [shell/*sh* (fn [& args]
                             (if (= "print" (second (vec args)))
                               {:exit 0 :out "{ state = running\n\tpid = 99\n\tlast exit code = 0 }" :err ""}
                               {:exit 0 :out "" :err ""}))]
        (let [result (run "service status")]
          (should= 0 (:exit result))
          (should (str/includes? (:out result) "running")))))

    (it "restart calls launchctl kickstart -k"
      (let [calls (atom [])]
        (binding [shell/*sh* (fn [& args]
                               (swap! calls conj (vec args))
                               {:exit 0 :out "" :err ""})]
          (run "service restart")
          (should (some #(and (= "launchctl" (first %)) (= "kickstart" (second %)) (some #{"-k"} %)) @calls)))))

    (it "start calls launchctl bootstrap to re-load after stop"
      (let [calls (atom [])]
        (binding [shell/*sh* (fn [& args]
                               (swap! calls conj (vec args))
                               {:exit 0 :out "" :err ""})]
          (run "service stop")
          (reset! calls [])
          (run "service start")
          (should (some #(and (= "launchctl" (first %)) (= "bootstrap" (second %))) @calls)))))

    (it "logs --follow calls tail -f on the log file"
      (fs/mkdirs (nexus/get :fs) "/test/home/Library/Logs/isaac")
      (fs/spit   (nexus/get :fs) "/test/home/Library/Logs/isaac/server.log" "log line")
      (let [calls (atom [])]
        (binding [shell/*sh* (fn [& args]
                               (swap! calls conj (vec args))
                               {:exit 0 :out "" :err ""})]
          (let [result (run "service logs --follow")]
            (should= 0 (:exit result))
            (should (some #(and (= "tail" (first %)) (= "-f" (second %))) @calls))))))

    (it "service --help prints subcommand help"
      (let [result (run "service --help")]
        (should= 0 (:exit result))
        (should-contain "Usage: isaac service [options] <subcommand>" (:out result))
        (should-contain "Subcommands:" (:out result))
        (should-contain "install" (:out result))
        (should-contain "logs" (:out result))))

    (it "bare service prints the same subcommand help"
      (let [result (run "service")]
        (should= 0 (:exit result))
        (should-contain "Usage: isaac service [options] <subcommand>" (:out result))
        (should-contain "Subcommands:" (:out result))
        (should-contain "install" (:out result))))

    (it "help service prints the same subcommand help"
      (let [result (run "help service")]
        (should= 0 (:exit result))
        (should-contain "Usage: isaac service [options] <subcommand>" (:out result))
        (should-contain "Subcommands:" (:out result))
        (should-contain "install" (:out result)))))

  (describe "on unsupported OS"

    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (binding [shell/*os-name* "Linux"]
        (example)))

    (it "install prints not supported message"
      (let [result (run "service install")]
        (should= 1 (:exit result))
        (should (str/includes? (:err result) "not yet supported on Linux"))))

    (it "status prints not supported message"
      (let [result (run "service status")]
        (should= 1 (:exit result))
        (should (str/includes? (:err result) "not yet supported on Linux"))))))

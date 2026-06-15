(ns isaac.service.cli
  (:require
    [isaac.cli.api :as cli-api]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.registry :as cli]
    [isaac.service.macos :as macos]
    [isaac.shell :as shell]))

(def ^:private install-options
  [[nil "--bb-bin PATH" "Path to bb binary (default: resolved via which)"]
   [nil "--isaac-dir PATH" "Path to Isaac repo root (default: current directory)"]
   ["-h" "--help" "Show help"]])

(def ^:private logs-options
  [["-f" "--follow" "Follow log output (tail -f)"]
   ["-h" "--help" "Show help"]])

(defn- find-bb [bb-bin-override]
  (if bb-bin-override
    bb-bin-override
    (let [result (shell/sh! "which" "bb")]
      (when (zero? (:exit result))
        (str/trim (:out result))))))

(defn- bb-edn-dir [isaac-dir-override]
  (or isaac-dir-override (System/getProperty "user.dir")))

(defn- unsupported-os [os]
  (binding [*out* *err*]
    (println (str "isaac service is not yet supported on " os)))
  1)

(defn- run-install [opts]
  (let [{:keys [options errors]} (tools-cli/parse-opts (or (:_raw-args opts) []) install-options)]
    (cond
      (:help options) (do (println "Usage: isaac service install [options]") 0)
      (seq errors)    (do (binding [*out* *err*] (doseq [e errors] (println e))) 1)
      :else
      (let [bb-bin (find-bb (:bb-bin options))]
        (if-not bb-bin
          (do
            (binding [*out* *err*]
              (println "could not locate bb on PATH")
              (println "pass --bb-bin <path> to specify it explicitly"))
            1)
          (let [bb-edn (bb-edn-dir (:isaac-dir options))]
            (macos/install! {:bb-bin bb-bin :bb-edn bb-edn})
            (println (str "Resolved bb: " bb-bin))
            (println (str "Service installed: com.slagyr.isaac"))
            0))))))

(defn- run-uninstall [opts]
  (macos/uninstall! opts)
  (println "Service uninstalled (or already uninstalled)")
  0)

(defn- run-start [opts]
  (macos/start! opts)
  (println "Service started")
  0)

(defn- run-stop [opts]
  (macos/stop! opts)
  (println "Service stopped")
  0)

(defn- run-restart [opts]
  (macos/restart! opts)
  (println "Service restarted")
  0)

(defn- run-status [_opts]
  (let [result (macos/status! {})]
    (if-not (:installed? result)
      (do (println "not installed") 1)
      (do
        (println (str "state: " (or (:state result) "unknown")))
        (when-let [pid (:pid result)]
          (println (str "pid:   " pid)))
        (when-let [exit (:last-exit result)]
          (println (str "last exit: " exit)))
        (if (= "running" (:state result)) 0 1)))))

(defn- run-logs [opts]
  (let [{:keys [options]} (tools-cli/parse-opts (or (:_raw-args opts) []) logs-options)
        follow?           (:follow options)
        result            (macos/logs! {:follow? follow?})]
    (cond
      follow?           0
      (:content result) (do (print (:content result)) 0)
      :else             (do (binding [*out* *err*] (println "log file not found")) 1))))

(def subcommands
  [{:name "install" :summary "Install Isaac as a launchd service" :run run-install}
   {:name "uninstall" :summary "Remove the Isaac launchd service" :run run-uninstall}
   {:name "start" :summary "Start the Isaac service" :run run-start}
   {:name "stop" :summary "Stop the Isaac service" :run run-stop}
   {:name "restart" :summary "Restart the Isaac service" :run run-restart}
   {:name "status" :summary "Show the Isaac service status" :run run-status}
   {:name "logs" :summary "Tail Isaac service logs" :run run-logs}])

(def ^:private subcommands-by-name
  (into {} (map (juxt :name identity) subcommands)))

(defn- dispatch [subcmd opts]
  (let [os (shell/os-name)]
    (if (= "Mac OS X" os)
      (if-let [run (get-in subcommands-by-name [subcmd :run])]
        (run opts)
        (do (binding [*out* *err*] (println (str "Unknown service subcommand: " subcmd))) 1))
      (unsupported-os os))))

(defn run-fn [{:keys [_raw-args] :as opts}]
  (let [args (or _raw-args [])]
    (if (or (empty? args) (#{"--help" "-h"} (first args)))
      (do (println (cli/command-help (cli/get-command "service"))) 0)
      (dispatch (first args) (assoc opts :_raw-args (vec (rest args)))))))

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :service [_id opts]
  (run-fn opts))

(defmethod cli-api/subcommands :service [_id]
  subcommands)

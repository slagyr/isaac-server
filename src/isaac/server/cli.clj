;; mutation-tested: 2026-05-06
(ns isaac.server.cli
  (:require
    [isaac.cli.api :as cli-api]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.common :as cli-common]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.log-viewer :as viewer]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.server.app :as app]
    [isaac.server.runtime :as runtime]
    ))

(defn block!
  "Block the current thread until interrupted."
  []
  @(promise))

(def ^:private server-log-prelude-limit 10)

(defn- start-log-tail! [log-path root {:keys [no-color zebra]}]
  (let [color? (not no-color)
        zebra? (boolean zebra)
        path   (cond
                 (nil? log-path)                         nil
                 (str/starts-with? log-path "/")         log-path
                 (and root (seq root))         (str root "/" log-path)
                 :else                                   log-path)]
    (when path
      (let [f (java.io.File. path)]
        (.mkdirs (or (.getParentFile f) (java.io.File. ".")))
        (when-not (.exists f) (.createNewFile f)))
      (future (viewer/tail! path {:color?  color?
                                  :zebra?  zebra?
                                  :follow? true
                                  :limit   server-log-prelude-limit}))
      path)))

(defn run [{:keys [port host logs] :as opts}]
  (let [root-dir      (root/default-root opts)
        fs*           (or (fs/instance opts) (fs/real-fs))
        ;; CLIs load config at their entry point (never reload — that's a
        ;; server-only concern); app/start! resolves port/host and commits it.
        loaded-config (:config (loader/load-config-result {:root root-dir :fs fs*}))
        ;; dev mode is an environment/launch concern, not config: --dev overrides,
        ;; otherwise the ISAAC_DEV env var.
        dev?          (if (contains? opts :dev)
                        (boolean (:dev opts))
                        (= "true" (loader/env "ISAAC_DEV")))]
    (when logs
      (when-let [abs-path (start-log-tail! (log/log-file) root-dir opts)]
        (log/set-log-file! abs-path)
        (log/set-output! :file)))
    (nexus/-with-nested-nexus {:fs fs*}
      (nexus/init! {:fs fs*})
      (log/info :server/boot-starting)
      (if-let [{started-port :port started-host :host}
               (app/start! {:cfg       loaded-config
                            :root      root-dir
                            :dev       dev?
                            :port      (when port (parse-long (str port)))
                            :host      host})]
        (do
          (log/info :server/started :host started-host :port started-port)
          (println (str "Isaac server running on " started-host ":" started-port))
          (block!))
        (do
          (println "Failed to start: invalid configuration (see logs)")
          1)))))

(def option-spec
  [["-p" "--port N" "Port to listen on (default: 6674)"]
   ["-H" "--host H" "Host to bind to (default: 127.0.0.1)"]
   ["-d" "--dev" "Enable development reload mode"]
   [nil  "--runtime RUNTIME" "Server runtime: bb (default) or jvm"
    :default "bb"]
   [nil  "--logs" "Tail and print the log file while the server runs"]
   [nil  "--no-color" "Disable color output for --logs"]
   [nil  "--zebra" "Enable zebra striping for --logs"]
   ["-h" "--help" "Show help"]])

(defn- parse-option-map [raw-args]
  (let [{:keys [options errors]} (tools-cli/parse-opts raw-args option-spec)]
    {:options (->> options
                   (remove (comp nil? val))
                   (into {}))
     :errors  errors}))

(defn- dispatch-run [opts raw-args]
  (if-let [exit (runtime/maybe-trampoline! opts raw-args)]
    exit
    (run opts)))

(defn run-fn [opts]
  (let [raw-args (or (:_raw-args opts) [])]
    (cli-common/standard-run-fn "server" parse-option-map
      (fn [merged] (dispatch-run merged raw-args))
      opts)))

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :server [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :server [_id]
  option-spec)

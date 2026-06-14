(ns isaac.server.cli-spec
  (:require
    [isaac.cli.registry :as registry]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.log-viewer :as viewer]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.server.app :as app]
    [isaac.server.cli :as sut]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "isaac-server-cli-spec" (make-array java.nio.file.attribute.FileAttribute 0))))

(def config-stub (atom {}))
(def started (atom {}))

(describe "Server command"

  (helper/with-captured-logs)

  (describe "command registration"

    (it "registers the server command"
      (module-loader/process-manifest-berths! (module-loader/builtin-index))
      (should-not-be-nil (registry/get-command "server"))))

  (describe "run"

    (before (reset! config-stub {})
            (reset! started false))
    (redefs-around [sut/block! (fn [] nil)
                    app/start! (fn [opts] (reset! started opts) {:port (:port opts) :host (:host opts)})
                    loader/load-config-result (fn [& _] @config-stub)])

    (describe "start-log-tail!"

      (it "returns nil when log-path is nil"
        (should-be-nil (#'sut/start-log-tail! nil "/tmp/state" {})))

      (it "resolves a relative log path under root creates the file and forwards tail options"
        (let [base     (temp-dir)
              events   (promise)
              log-path "logs/server.log"
              resolved (str (.getAbsolutePath base) "/" log-path)]
          (with-redefs [viewer/tail! (fn [path opts]
                                       (deliver events [path opts])
                                       nil)]
            (should= resolved
                     (#'sut/start-log-tail! log-path (.getAbsolutePath base) {:zebra true}))
            (should= [resolved {:color?  true
                                :zebra?  true
                                :follow? true
                                :limit   10}]
                     (deref events 1000 ::timeout))
            (should (.exists (java.io.File. resolved))))))

      (it "preserves an absolute path and disables color when requested"
        (let [base     (temp-dir)
              abs-path (str (.getAbsolutePath base) "/server.log")
              events   (promise)]
          (with-redefs [viewer/tail! (fn [path opts]
                                       (deliver events [path opts])
                                       nil)]
            (should= abs-path
                     (#'sut/start-log-tail! abs-path "/ignored" {:no-color true :zebra true}))
            (should= [abs-path {:color?  false
                                :zebra?  true
                                :follow? true
                                :limit   10}]
                     (deref events 1000 ::timeout)))))

      )

    (it "starts the server on the given port"
      (with-out-str (sut/run {:port "4000"}))
      (should= 4000 (:port @started)))

    (it "loads config and passes it to app start as :cfg"
      (swap! config-stub assoc-in [:config :server :auth :token] "s3cr3t")
      (with-out-str (sut/run {}))
      (should= "s3cr3t" (get-in @started [:cfg :server :auth :token])))

    (it "prints the host and port on startup"
      (let [output (with-out-str (sut/run {:port "5000"}))]
        (should (re-find #"5000" output))))

    (it "logs server/started with host and port"
      (with-out-str (sut/run {:port "7000" :host "0.0.0.0"}))
      (let [started (first (filter #(= :server/started (:event %)) @log/captured-logs))]
        (should-not-be-nil started)
        (should= 7000 (:port started))
        (should= "0.0.0.0" (:host started))))

    (it "enables dev mode from the ISAAC_DEV env var"
      (config/set-env-override! "ISAAC_DEV" "true")
      (try
        (with-out-str (sut/run {}))
        (finally (config/clear-env-overrides!)))
      (should= true (:dev @started)))

    (it "CLI --dev enables dev mode"
      (with-out-str (sut/run {:dev true}))
      (should= true (:dev @started)))

    (it "CLI --dev false overrides the ISAAC_DEV env var"
      (config/set-env-override! "ISAAC_DEV" "true")
      (try
        (with-out-str (sut/run {:dev false}))
        (finally (config/clear-env-overrides!)))
      (should= false (:dev @started)))

    (it "derives root from home before starting the app"
      (with-out-str (sut/run {:home "/tmp/server-home"}))
      (should= "/tmp/server-home/.isaac" (:root @started))
      (should-not (contains? @started :home)))

    (it "enables file logging when --logs is requested"
      (let [log-file    (temp-dir)
            tailed-path (atom nil)
            output-kind (atom nil)]
        (with-redefs [log/log-file              (fn [] "server.log")
                      sut/start-log-tail!       (fn [path root opts]
                                                  (reset! tailed-path [path root opts])
                                                  (.getAbsolutePath log-file))
                      log/set-log-file!         (fn [path] (reset! tailed-path (conj @tailed-path path)))
                      log/set-output!           (fn [kind] (reset! output-kind kind))]
          (with-out-str (sut/run {:home "/tmp/server-home" :logs true :zebra true})))
        (should= ["server.log"
                  "/tmp/server-home/.isaac"
                  {:home "/tmp/server-home" :logs true :zebra true}
                  (.getAbsolutePath log-file)]
                 @tailed-path)
        (should= :file @output-kind)
        (should= "/tmp/server-home/.isaac" (:root @started))))

    )

  (describe "run-fn"

    (it "prints command help and returns 0 when --help is requested"
      (with-redefs [sut/parse-option-map  (fn [_] {:options {:help true} :errors []})
                    registry/get-command  (fn [_] {:name "server"})
                    registry/command-help (fn [_] "server help")]
        (let [output (with-out-str (should= 0 (sut/run-fn {:_raw-args ["--help"]})))]
          (should (re-find #"server help" output)))))

    (it "prints parse errors and returns 1"
      (with-redefs [sut/parse-option-map (fn [_] {:options {} :errors ["bad arg"]})]
        (let [output (with-out-str (should= 1 (sut/run-fn {:_raw-args ["--bogus"]})))]
          (should (re-find #"bad arg" output)))))

    (it "delegates to run with parsed options merged into opts"
      (let [captured (atom nil)]
        (with-redefs [sut/parse-option-map (fn [_] {:options {:port "4000" :host "127.0.0.1"} :errors []})
                      sut/run              (fn [opts]
                                             (reset! captured opts)
                                             0)]
          (should= 0 (sut/run-fn {:_raw-args ["--port" "4000"] :home "/tmp/server-home"}))
          (should= {:home "/tmp/server-home" :port "4000" :host "127.0.0.1"} @captured)))))

  )

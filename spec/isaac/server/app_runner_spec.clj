(ns isaac.server.app-runner-spec
  (:require
    [isaac.runner :as runner]
    [isaac.server.app :as sut]
    [isaac.server.component.runtime :as runtime]
    [speclj.core :refer :all]))

(describe "server app runner"

  (it "delegates startup to Foundation without lifecycle hook bindings"
    (let [opts {:config {} :root "/isaac"}
          seen (atom nil)]
      (with-redefs [runtime/valid-start? (constantly true)
                    runner/start!        #(do (reset! seen %) ::started)]
        (should= ::started (sut/start! opts)))
      (should= opts (select-keys @seen (keys opts)))))

  (it "delegates shutdown to Foundation"
    (with-redefs [runner/stop! (constantly ::stopped)]
      (should= ::stopped (sut/stop!))))

  )

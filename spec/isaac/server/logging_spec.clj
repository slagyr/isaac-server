(ns isaac.server.logging-spec
  (:require
    [isaac.fs :as fs]
    [isaac.log.file :as lfile]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.server.logging :as sut]
    [speclj.core :refer :all]))

(describe "Server logging"

  (around [it]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (it)))

  (before (lfile/clear-sink-config!)
          (log/set-output! :stderr)
          (log/clear-entries!))

  (after (lfile/clear-sink-config!)
         (log/set-output! :stderr))

  (it "configure! routes server logs to <root>/logs/server.log while output stays stderr"
    (let [root "/isaac-root"]
      (sut/configure! root {:tz "UTC"})
      (log/info :server/test-boot)
      (should (fs/exists? (fs/instance) (lfile/server-log-path root))))))
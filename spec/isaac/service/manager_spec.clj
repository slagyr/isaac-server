(ns isaac.service.manager-spec
  (:require
    [isaac.service.linux :as linux]
    [isaac.service.macos :as macos]
    [isaac.service.manager :as sut]
    [speclj.core :refer :all]))

(describe "service.manager"

  (it "launchd and systemd both implement Manager"
    (should (satisfies? sut/Manager macos/manager))
    (should (satisfies? sut/Manager linux/manager)))

  (it "each manager names its service"
    (should= "com.slagyr.isaac" (sut/service-name macos/manager))
    (should= "isaac" (sut/service-name linux/manager))))

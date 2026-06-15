(ns isaac.server.module-spec
  (:require
    [isaac.module.protocol]
    [isaac.module.loader :as module-loader]
    [isaac.server.module :as sut]
    [speclj.core :refer :all]))

(describe "isaac.server.module"

  (describe "comm-kinds"

    (it "returns empty when module index has no comm entries"
      (should= [] (sut/comm-kinds {})))

    (it "returns sorted comm kind name from a module"
      (let [index {:my.mod {:manifest {:isaac.server/comm {:telly {:factory 'foo/make}}}}}]
        (should= ["telly"] (sut/comm-kinds index))))

    (it "filters out entries with :configurable? false"
      (let [index {:my.mod {:manifest {:isaac.server/comm {:internal {:factory 'foo/make :configurable? false}
                                              :external {:factory 'bar/make}}}}}]
        (should= ["external"] (sut/comm-kinds index))))

    (it "aggregates and sorts kinds from multiple modules"
      (let [index {:mod-a {:manifest {:isaac.server/comm {:bravo {:factory 'a/make}}}}
                   :mod-b {:manifest {:isaac.server/comm {:alpha {:factory 'b/make}}}}}]
        (should= ["alpha" "bravo"] (sut/comm-kinds index))))

    (it "with no args falls back to builtin-index"
      (let [index {:isaac.server {:coord {} :manifest {:id :isaac.server :version "1"
                                                      :isaac.server/comm {:widget {:factory 'foo/make}}}}}]
        (binding [module-loader/*foundation-index-override* index]
          (should= ["widget"] (sut/comm-kinds))))))

  (describe "create-module"
    (it "returns a module record"
      (should (satisfies? isaac.module.protocol/Module (sut/create-module))))))
(ns isaac.service.launch-spec
  (:require
    [isaac.service.launch :as sut]
    [speclj.core :refer :all]))

(describe "service.launch"

  (describe "program-arguments"

    (it "packaged bb runs the launcher with server"
      (should= ["/usr/local/bin/isaac" "server"]
               (sut/program-arguments {:mode :packaged :isaac-bin "/usr/local/bin/isaac"})))

    (it "packaged bb passes --root before server"
      (should= ["/usr/local/bin/isaac" "--root" "/var/isaac" "server"]
               (sut/program-arguments {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :root "/var/isaac"})))

    (it "omits --runtime for default bb packaged installs"
      (should-not (some #{"--runtime"} (sut/program-arguments {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :runtime "bb"}))))

    (it "packaged jvm execs clojure through an sh -c wrapper"
      (let [args (sut/program-arguments {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :runtime "jvm"})]
        (should= ["/bin/sh" "-c"] (take 2 args))
        (should-contain "exec clojure -Sdeps" (last args))
        (should-contain "/usr/local/bin/isaac modules deps --edn" (last args))
        (should-contain "-M -m isaac.main server" (last args))
        (should-not-contain "--runtime" (last args))))

    (it "embeds --root in the jvm sh wrapper"
      (let [cmd (last (sut/program-arguments {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :root "/var/isaac" :runtime "jvm"}))]
        (should-contain "/usr/local/bin/isaac --root /var/isaac modules deps --edn" cmd)
        (should-contain "-m isaac.main --root /var/isaac server" cmd)))

    (it "dev checkout runs bb -m isaac.main against the repo bb.edn"
      (should= ["/opt/homebrew/bin/bb" "--config" "/projects/isaac/bb.edn" "-m" "isaac.main" "server"]
               (sut/program-arguments {:mode :dev :bb-bin "/opt/homebrew/bin/bb" :bb-edn "/projects/isaac"})))

    (it "dev checkout jvm appends --runtime jvm"
      (should= ["server" "--runtime" "jvm"]
               (drop 5 (sut/program-arguments {:mode :dev :bb-bin "/opt/homebrew/bin/bb" :bb-edn "/projects/isaac" :runtime "jvm"})))))

  (describe "PATH"

    (it "default-path is bb and isaac parent dirs plus /usr/bin and /bin"
      (should= "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"
               (sut/default-path {:bb-bin "/opt/homebrew/bin/bb" :isaac-bin "/usr/local/bin/isaac"})))

    (it "default-path dedupes a shared parent dir"
      (should= "/usr/local/bin:/usr/bin:/bin"
               (sut/default-path {:bb-bin "/usr/local/bin/bb" :isaac-bin "/usr/local/bin/isaac"})))

    (it "service-path prefers caller PATH over default-path"
      (should= "/opt/marigold/bin:/opt/starboard/bin:/usr/bin:/bin"
               (sut/service-path {:caller-path "/opt/marigold/bin:/opt/starboard/bin:/usr/bin:/bin"
                                  :bb-bin      "/opt/marigold/bin/bb"
                                  :isaac-bin   "/opt/marigold/bin/isaac"})))

    (it "service-path uses the explicit override over caller PATH"
      (should= "/opt/quartz/bin:/usr/bin:/bin"
               (sut/service-path {:path        "/opt/quartz/bin:/usr/bin:/bin"
                                  :caller-path "/opt/marigold/bin:/usr/bin:/bin"
                                  :bb-bin      "/opt/marigold/bin/bb"
                                  :isaac-bin   "/opt/marigold/bin/isaac"})))

    (it "service-path falls back to default-path when caller PATH is blank"
      (should= "/usr/local/bin:/usr/bin:/bin"
               (sut/service-path {:caller-path "   "
                                  :bb-bin      "/usr/local/bin/bb"
                                  :isaac-bin   "/usr/local/bin/isaac"}))))

  (describe "runtime-from-program-arguments"

    (it "reads jvm from the sh wrapper"
      (should= "jvm" (sut/runtime-from-program-arguments
                       (sut/program-arguments {:mode :packaged :isaac-bin "/usr/local/bin/isaac" :runtime "jvm"}))))

    (it "reads the --runtime flag"
      (should= "jvm" (sut/runtime-from-program-arguments ["/opt/homebrew/bin/bb" "-m" "isaac.main" "server" "--runtime" "jvm"])))

    (it "defaults to bb"
      (should= "bb" (sut/runtime-from-program-arguments ["/usr/local/bin/isaac" "server"])))

    (it "defaults to bb for nil args"
      (should= "bb" (sut/runtime-from-program-arguments nil)))))

Feature: Module activation
  Modules in the discovered index are loaded and activated during server
  boot in dependency order. Comms and services still instantiate on first
  configured slot use after boot.

  Scenario: Comm slot starts when configured at boot
    Given an empty Isaac root at "/tmp/isaac"
    And config:
      | key              | value  |
      | bind-server-port | false  |
      | log.output       | memory |
    And the isaac file "isaac.edn" exists with:
      """
      {:log     {:output :memory}
       :server  {:hot-reload false}
       :modules {:isaac.comm.telly {:git/url "https://github.com/slagyr/isaac-agent.git", :git/sha "632d7fead97bf9c35f9a892a7ace5857c7e68972", :deps/root "modules/isaac.comm.telly"}}
       :comms   {:bert {:type :telly :loft "rooftop"}}}
      """
    When the Isaac server is started
    Then the log has entries matching:
      | level | event             | module           |
      | :info | :module/activated | isaac.comm.telly |
    And the log has entries matching:
      | level | event              | path       | impl  |
      | :info | :lifecycle/started | comms.bert | telly |

  Scenario: Declared module is activated during server boot even without a slot
    Given an empty Isaac root at "/tmp/isaac"
    And config:
      | key              | value  |
      | bind-server-port | false  |
      | log.output       | memory |
    And the isaac file "isaac.edn" exists with:
      """
      {:log     {:output :memory}
       :server  {:hot-reload false}
       :modules {:isaac.comm.telly {:git/url "https://github.com/slagyr/isaac-agent.git", :git/sha "632d7fead97bf9c35f9a892a7ace5857c7e68972", :deps/root "modules/isaac.comm.telly"}}}
      """
    When the Isaac server is started
    Then the log has entries matching:
      | level | event             | module           |
      | :info | :module/activated | isaac.comm.telly |

  Scenario: Module activation failure surfaces a structured error
    Given an empty Isaac root at "/tmp/isaac"
    And config:
      | key              | value  |
      | bind-server-port | false  |
      | log.output       | memory |
    And environment variable "ISAAC_TELLY_FAIL_ON_LOAD" is "true"
    And the isaac file "isaac.edn" exists with:
      """
      {:log     {:output :memory}
       :server  {:hot-reload false}
       :modules {:isaac.comm.telly {:git/url "https://github.com/slagyr/isaac-agent.git", :git/sha "632d7fead97bf9c35f9a892a7ace5857c7e68972", :deps/root "modules/isaac.comm.telly"}}
       :comms   {:bert {:type :telly :loft "rooftop"}}}
      """
    When the Isaac server is started
    Then the log has entries matching:
      | level  | event                     | module           |
      | :error | :module/activation-failed | isaac.comm.telly |
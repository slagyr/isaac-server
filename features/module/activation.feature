Feature: Module activation
  Modules declared in :modules are activated on first use of a capability
  they extend. Activation requires the module's :entry namespace, which
  in turn registers the module's contributions (comms, providers, tools).

  Scenario: Activating the telly module on first comm slot use
    Given an empty Isaac root at "/tmp/isaac"
    And config:
      | key              | value  |
      | bind-server-port | false  |
      | log.output       | memory |
    And the isaac file "isaac.edn" exists with:
      """
      {:log     {:output :memory}
       :server  {:hot-reload false}
       :modules {:isaac.comm.telly {:local/root "../isaac-agent/modules/isaac.comm.telly"}}
       :comms   {:bert {:type :telly :loft "rooftop"}}}
      """
    When the Isaac server is started
    Then the log has entries matching:
      | level | event             | module           |
      | :info | :module/activated | isaac.comm.telly |
      | :info | :telly/started    | bert             |

  Scenario: Declared module is not activated when no slot uses it
    Given an empty Isaac root at "/tmp/isaac"
    And config:
      | key              | value  |
      | bind-server-port | false  |
      | log.output       | memory |
    And the isaac file "isaac.edn" exists with:
      """
      {:log     {:output :memory}
       :server  {:hot-reload false}
       :modules {:isaac.comm.telly {:local/root "../isaac-agent/modules/isaac.comm.telly"}}}
      """
    When the Isaac server is started
    Then the log has no entries matching:
      | event             | module           |
      | :module/activated | isaac.comm.telly |

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
       :modules {:isaac.comm.telly {:local/root "../isaac-agent/modules/isaac.comm.telly"}}
       :comms   {:bert {:type :telly :loft "rooftop"}}}
      """
    When the Isaac server is started
    Then the log has entries matching:
      | level  | event                     | module           |
      | :error | :module/activation-failed | isaac.comm.telly |
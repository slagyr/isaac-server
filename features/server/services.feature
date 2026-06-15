@wip
Feature: Server service lifecycle
  Modules contribute runtime services via :isaac.server/service. Contributions
  are inert factory data gathered at config load (CLI-safe). Only server boot
  instantiates and starts them in module topological order; shutdown stops
  them in reverse.

  Background:
    Given default Grover setup

  Scenario: a module's service starts on server boot
    Given the widget test service module is registered
    And config:
      | key                 | value |
      | server.auth.token   | test  |
    When the Isaac server is started
    Then the log has entries matching:
      | level | event             | service |
      | :info | :service/started  | widget  |
    When the Isaac server is stopped
    Then the log has entries matching:
      | level | event             | service |
      | :info | :service/stopped  | widget  |

  Scenario: a service does NOT start on CLI
    Given the widget test service module is registered
    And config:
      | key                 | value |
      | server.auth.token   | test  |
    When the config is loaded
    Then the log has no entries matching:
      | event            |
      | :service/started |

  Scenario: services start in topological order
    Given the alpha and bravo test service modules are registered
    And config:
      | key                 | value |
      | server.auth.token   | test  |
    When the Isaac server is started
    Then the service start order is "bravo, alpha"
    When the Isaac server is stopped
    Then the service stop order is "alpha, bravo"
Feature: Server lifecycle bookends
  The Marigold's console announces itself when it powers on and bids the
  crew farewell when it powers down.

  Background:
    Given default Grover setup

  Scenario: the console greets the crew on boot
    Given config:
      | key               | value |
      | server.auth.token | shh   |
    When the Isaac server is started
    Then the log has entries matching:
      | level | event         | runtime | version | root | dev   | pid |
      | :info | :server/hello | #*      | #*      | #*   | false | #*  |

  Scenario: the console bids the crew farewell on shutdown
    Given the widget test service module is registered
    And config:
      | key               | value |
      | server.auth.token | shh   |
    When the Isaac server is started
    And the Isaac server is stopped
    Then the log has entries matching:
      | level | event                     | service |
      | :info | :server/shutdown-starting |         |
      | :info | :service/stopped          | widget  |
      | :info | :server/stopped           |         |
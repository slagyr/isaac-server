Feature: HTTP component lifecycle
  The HTTP listener is an isaac-server contribution to foundation's :isaac/component berth.

  Background:
    Given default Grover setup

  Scenario: the HTTP listener is a component of isaac-server (isaac-vs6f)
    Given config:
      | key               | value |
      | server.auth.token | test  |
      | server.port       | 0     |
    When the Isaac server is started
    Then the log has entries matching:
      | level | event              | component | module       |
      | :info | :component/started | http      | isaac.server |
    When a GET request is made to "/status"
    Then the response status is 401
    When the Isaac server is stopped
    Then the log has entries matching:
      | level | event              | component |
      | :info | :component/stopped | http      |

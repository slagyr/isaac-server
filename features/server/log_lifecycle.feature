Feature: Server log file lifecycle
  The server owns a durable rotated log at <root>/logs/server.log. Short-lived
  CLI commands do not write that file unless --log-file opts in.

  Background:
    Given default Grover setup

  Scenario: S1 — the server's active log is logs/server.log
    Given config:
      | key               | value |
      | server.port       | 0     |
      | server.hot-reload | false |
    When the Isaac server is started
    Then the isaac file "logs/server.log" exists

  Scenario: S1b — isaac server CLI dispatch writes logs/server.log
    Given config:
      | key               | value |
      | server.port       | 0     |
      | server.hot-reload | false |
      | server.auth.token | test  |
    When the server command is run on port 0
    Then the isaac file "logs/server.log" exists

  Scenario: S2a — daily rollover archives the previous day
    Given the clock is fixed at "2026-06-28T12:00:00Z"
    And the isaac file "logs/server.log" exists with 3 log entries
    And the clock is fixed at "2026-06-29T12:00:00Z"
    And config:
      | key               | value |
      | server.port       | 0     |
      | server.hot-reload | false |
    When the Isaac server is started
    Then the isaac file "logs/server-20260628.log" exists
    And the isaac file "logs/server.log" exists with log entries

  Scenario: S2b — size cap rolls within a day
    Given config:
      | key                 | value |
      | logging.max-bytes   | 2000  |
      | server.port         | 0     |
      | server.hot-reload   | false |
    And the clock is fixed at "2026-06-29T12:00:00Z"
    And the isaac file "logs/server.log" exists with 100 log entries
    When the Isaac server is started
    Then the isaac file "logs/server-20260629.log" exists
    And the isaac file "logs/server.log" exists with log entries

  Scenario: S2c — retention drops archives older than max-days
    Given config:
      | key               | value |
      | logging.max-days  | 30    |
      | server.port       | 0     |
      | server.hot-reload | false |
    And a file "logs/server-20260401.log" exists with content "old"
    And a file "logs/server-20260601.log" exists with content "keep"
    And the clock is fixed at "2026-06-29T12:00:00Z"
    When the Isaac server is started
    Then the isaac file "logs/server-20260401.log" does not exist
    And the file "logs/server-20260601.log" exists

  Scenario: S3a — a CLI command creates no server log file by default
    When isaac is run with "version"
    Then the isaac file "logs/server.log" does not exist

  Scenario: S3b — --log-file writes a CLI-owned log
    When isaac is run with "version --log-file logs/cmd.log"
    Then the isaac file "logs/cmd.log" exists with log entries
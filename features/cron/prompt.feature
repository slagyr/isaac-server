@wip
Feature: Cron prompts from config or markdown companion
  Cron jobs specify their prompt either inline via :prompt, or via a
  sibling markdown file at ~/.isaac/config/cron/<name>.md. Mirrors the
  soul pattern on crew. Exactly one source required; missing both or
  empty markdown errors at config load.

  The md-companion pattern is shared with crew soul — the
  implementation extracts a reusable helper rather than duplicating
  the resolve-or-load logic per field.

  Background:
    Given default Grover setup

  Scenario: cron prompt defined inline in config
    Given config:
      | sessions.naming-strategy | sequential              |
      | tz                       | America/Chicago         |
      | cron.health-check.expr   | 0 9 * * *               |
      | cron.health-check.crew   | main                    |
      | cron.health-check.prompt | Run the health checkin. |
    And the following model responses are queued:
      | type | content         | model |
      | text | Health is good. | echo  |
    When the scheduler ticks at "2026-04-21T09:00:00-0500"
    Then session "session-1" has transcript matching:
      | type    | message.role | message.content         |
      | message | user         | Run the health checkin. |

  Scenario: cron prompt loaded from companion markdown file
    Given config:
      | sessions.naming-strategy | sequential |
      | tz                       | America/Chicago |
      | cron.health-check.expr   | 0 9 * * *  |
      | cron.health-check.crew   | main       |
    And config file "cron/health-check.md" containing:
      """
      Run the daily health checkin.
      """
    And the following model responses are queued:
      | type | content         | model |
      | text | Health is good. | echo  |
    When the scheduler ticks at "2026-04-21T09:00:00-0500"
    Then session "session-1" has transcript matching:
      | type    | message.role | message.content               |
      | message | user         | Run the daily health checkin. |

  Scenario: cron job loaded from a single markdown file with YAML frontmatter
    Given config:
      | sessions.naming-strategy | sequential      |
      | tz                       | America/Chicago |
    And config file "cron/health-check.md" containing:
      """
      ---
      expr: "0 9 * * *"
      crew: main
      ---

      Run the daily health checkin.
      """
    And the following model responses are queued:
      | type | content         | model |
      | text | Health is good. | echo  |
    When the scheduler ticks at "2026-04-21T09:00:00-0500"
    Then session "session-1" has transcript matching:
      | type    | message.role | message.content               |
      | message | user         | Run the daily health checkin. |

  Scenario: cron with neither :prompt nor companion md errors at config load
    Given config:
      | cron.health-check.expr | 0 9 * * * |
      | cron.health-check.crew | main      |
    Then the config has validation errors matching:
      | key                      | value                                     |
      | cron.health-check.prompt | required \(inline or cron/health-check.md\) |

  Scenario: cron with an empty companion md errors at config load
    Given config:
      | cron.health-check.expr | 0 9 * * * |
      | cron.health-check.crew | main      |
    And config file "cron/health-check.md" containing:
      """
      """
    Then the config has validation errors matching:
      | key                      | value             |
      | cron.health-check.prompt | must not be empty |

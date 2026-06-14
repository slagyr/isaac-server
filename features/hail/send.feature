@wip
Feature: Hail send
  `isaac hail send [addressing flags] [--payload <edn>]` produces a
  hail by atomically writing an EDN record to <root>/hail/pending/.
  The record carries an auto-generated id (counted via isaac-pctr's
  SequentialStrategy), the address, the payload (if any), sender
  identity, and a sent-at timestamp. This bean covers the substrate
  (`hail.queue/send!` library function) and the `isaac hail send`
  CLI surface. v1 supports `--band` addressing only; other addressing
  flags (`--crew`, `--session`, `--crew-tag`, `--session-tag`) are
  follow-up.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: isaac hail send writes a hail record to pending/
    When isaac is run with "hail send --band bean-pickup --payload '{:n 1}'"
    Then the exit code is 0
    And the EDN isaac file "hail/pending/hail-1.edn" contains:
      | path      | value                 |
      | id        | hail-1                |
      | frequency | {:band "bean-pickup"} |
      | payload   | {:n 1}                |
      | from      | :cli                  |

  Scenario: each isaac hail send mints a unique sequential id
    When isaac is run with "hail send --band bean-pickup --payload '{:n 1}'"
    Then the exit code is 0
    When isaac is run with "hail send --band bean-pickup --payload '{:n 2}'"
    Then the exit code is 0
    And the EDN isaac file "hail/pending/hail-1.edn" contains:
      | path    | value  |
      | payload | {:n 1} |
    And the EDN isaac file "hail/pending/hail-2.edn" contains:
      | path    | value  |
      | payload | {:n 2} |

  Scenario: hail records carry a sent-at timestamp
    Given the clock is fixed at "2026-05-23T12:00:00Z"
    When isaac is run with "hail send --band bean-pickup --payload '{:n 1}'"
    Then the exit code is 0
    And the EDN isaac file "hail/pending/hail-1.edn" contains:
      | path    | value                |
      | sent-at | 2026-05-23T12:00:00Z |

  Scenario: isaac hail send prints the hail id to stdout
    When isaac is run with "hail send --band bean-pickup --payload '{:n 1}'"
    Then the stdout contains "hail-1"
    And the exit code is 0

  Scenario: isaac hail send reads payload from stdin when "-" is given
    Given stdin is:
      """
      {:n 1}
      """
    When isaac is run with "hail send --band bean-pickup --payload -"
    Then the exit code is 0
    And the EDN isaac file "hail/pending/hail-1.edn" contains:
      | path    | value  |
      | payload | {:n 1} |

  Scenario: isaac hail send --json prints the full hail record
    Given the clock is fixed at "2026-05-23T12:00:00Z"
    When isaac is run with "hail send --band bean-pickup --payload '{:n 1}' --json"
    Then the exit code is 0
    And the stdout JSON contains:
      | path           | value                  |
      | id             | "hail-1"               |
      | frequency.band | "bean-pickup"          |
      | payload        | {"n": 1}               |
      | from           | "cli"                  |
      | sent-at        | "2026-05-23T12:00:00Z" |

  Scenario: isaac hail send --edn prints the full hail record
    Given the clock is fixed at "2026-05-23T12:00:00Z"
    When isaac is run with "hail send --band bean-pickup --payload '{:n 1}' --edn"
    Then the exit code is 0
    And the stdout EDN contains:
      | path           | value                |
      | id             | "hail-1"             |
      | frequency.band | "bean-pickup"        |
      | payload        | {:n 1}               |
      | sent-at        | 2026-05-23T12:00:00Z |

  Scenario: isaac hail send works without a payload
    When isaac is run with "hail send --band bean-pickup"
    Then the exit code is 0
    And the EDN isaac file "hail/pending/hail-1.edn" contains:
      | path      | value                |
      | id        | hail-1               |
      | frequency | {:band "bean-pickup"} |
      | from      | :cli                 |

  Scenario: isaac hail send accepts a whole hail record from stdin
    Given stdin is:
      """
      {:frequency {:band "bean-pickup"} :payload {:n 1}}
      """
    When isaac is run with "hail send -"
    Then the exit code is 0
    And the EDN isaac file "hail/pending/hail-1.edn" contains:
      | path      | value                 |
      | id        | hail-1                |
      | frequency | {:band "bean-pickup"} |
      | payload   | {:n 1}                |
      | from      | :cli                  |

@wip
Feature: Hail send — direct addressing flags
  Beyond `--band`, `isaac hail send` accepts `--crew`, `--session`,
  `--crew-tag`, `--session-tag`, and `--from-json`. Each contributes
  a key to the persisted hail's `:frequency` map; combinations form
  an intersection at fan-out time.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: --crew populates :crew in the address map
    When isaac is run with "hail send --crew marvin --prompt 'Heads up' --payload '{:n 1}'"
    Then the exit code is 0
    And the EDN isaac file "hail/pending/hail-1.edn" contains:
      | path      | value             |
      | id        | hail-1            |
      | frequency | {:crew [:marvin]} |
      | prompt    | Heads up          |
      | payload   | {:n 1}            |
      | from      | :cli              |

  Scenario: --session populates :session in the address map
    When isaac is run with "hail send --session tidy-cavern --prompt 'wake up' --payload '{:n 1}'"
    Then the exit code is 0
    And the EDN isaac file "hail/pending/hail-1.edn" contains:
      | path      | value                     |
      | id        | hail-1                    |
      | frequency | {:session [:tidy-cavern]} |
      | prompt    | wake up                   |
      | payload   | {:n 1}                    |
      | from      | :cli                      |

  Scenario: --crew-tag populates :crew-tags (repeatable AND-set)
    When isaac is run with "hail send --crew-tag role/worker --crew-tag wip --prompt 'go' --payload '{:n 1}'"
    Then the exit code is 0
    And the EDN isaac file "hail/pending/hail-1.edn" contains:
      | path      | value                            |
      | id        | hail-1                           |
      | frequency | {:crew-tags #{:role/worker :wip}} |
      | prompt    | go                               |
      | from      | :cli                             |

  Scenario: --session-tag populates :session-tags (repeatable AND-set)
    When isaac is run with "hail send --session-tag project/chess --session-tag wip --prompt 'do it' --payload '{:n 1}'"
    Then the exit code is 0
    And the EDN isaac file "hail/pending/hail-1.edn" contains:
      | path      | value                                  |
      | id        | hail-1                                 |
      | frequency | {:session-tags #{:project/chess :wip}} |
      | prompt    | do it                                  |
      | from      | :cli                                   |

  Scenario: combining --crew with --session-tag forms an intersection
    When isaac is run with "hail send --crew marvin --session-tag project/chess --prompt 'go' --payload '{:n 1}'"
    Then the exit code is 0
    And the EDN isaac file "hail/pending/hail-1.edn" contains:
      | path      | value                                             |
      | id        | hail-1                                            |
      | frequency | {:crew [:marvin] :session-tags #{:project/chess}} |
      | prompt    | go                                                |
      | from      | :cli                                              |

  Scenario: --from-json reads the whole hail from stdin as JSON
    Given stdin is:
      """
      {"frequency": {"band": "bean-pickup"}, "payload": {"n": 1}}
      """
    When isaac is run with "hail send - --from-json"
    Then the exit code is 0
    And the EDN isaac file "hail/pending/hail-1.edn" contains:
      | path      | value                 |
      | id        | hail-1                |
      | frequency | {:band "bean-pickup"} |
      | payload   | {:n 1}                |
      | from      | :cli                  |

  Scenario: bare - reads the whole hail from stdin as EDN
    Given stdin is:
      """
      {:frequency {:crew [:marvin] :session-tags #{:project/chess}}
       :prompt    "go"
       :payload   {:n 1}}
      """
    When isaac is run with "hail send -"
    Then the exit code is 0
    And the EDN isaac file "hail/pending/hail-1.edn" contains:
      | path      | value                                             |
      | id        | hail-1                                            |
      | frequency | {:crew [:marvin] :session-tags #{:project/chess}} |
      | prompt    | go                                                |
      | payload   | {:n 1}                                            |
      | from      | :cli                                              |

  Scenario: direct addressing without --prompt errors clearly
    When isaac is run with "hail send --crew marvin --payload '{:n 1}'"
    Then the stderr contains "prompt"
    And the exit code is 1

Feature: Lifecycle reconciler keeps comm object tree synced with config
  The reconciler walks user-chosen slots under :comms in config, dispatches
  factory + on-load when a slot appears, on-config-change! when its slice
  changes, and on-config-change! with new=nil when the slot is removed.
  Multiple slots with the same :type run as independent instances.

  Background:
    Given default Grover setup
    And the "test-comm" comm is registered

  Scenario: Two comms run independently when both slots are present at boot
    Given config:
      | key                 | value |
      | comms.bert.type     | test-comm |
      | comms.bert.loft     | roof |
      | comms.bert.color    | yellow |
      | comms.ernie.type    | test-comm |
      | comms.ernie.loft    | attic |
      | comms.ernie.color   | orange |
    And the Isaac server is started
    Then the comm "bert" exists with state:
      | path         | value  |
      | started?     | true   |
      | slice.type   | test-comm  |
      | slice.color  | yellow |
    And the comm "ernie" exists with state:
      | path         | value  |
      | started?     | true   |
      | slice.type   | test-comm  |
      | slice.color  | orange |
    And the log has entries matching:
      | level | event                | path        | impl  |
      | :info | :lifecycle/started   | comms.bert  | test-comm |
      | :info | :lifecycle/started   | comms.ernie | test-comm |

  @wip
  Scenario: Comm receives on-config-change! when its slice changes
    Given config:
      | key             | value  |
      | comms.elmo.type | test-comm  |
      | comms.elmo.loft | tower  |
      | comms.elmo.mood | happy  |
    And the Isaac server is started
    When config is updated:
      | path            | value |
      | comms.elmo.mood | sad   |
    Then the comm "elmo" exists with state:
      | path        | value    |
      | slice.mood  | sad      |
      | last-event  | :changed |
    And the log has entries matching:
      | level | event                | path       | impl  |
      | :info | :lifecycle/changed   | comms.elmo | test-comm |

  @wip
  Scenario: Comm is stopped and evicted when its slot is removed from config
    Given config:
      | key              | value |
      | comms.abby.type  | test-comm |
      | comms.abby.loft  | dorm  |
      | comms.abby.color | pink  |
    And the Isaac server is started
    When config is updated:
      | path            | value   |
      | comms.abby      | #delete |
    Then the comm "abby" does not exist
    And the log has entries matching:
      | level | event                | path       | impl  |
      | :info | :lifecycle/stopped   | comms.abby | test-comm |

  Scenario: Boot fails with a validation error when a slot's :type is unregistered
    Given config:
      | key                 | value        |
      | comms.bigbird.type  | unknown-type |
    When the Isaac server is started
    Then the Isaac server is not running
    And the log has entries matching:
      | level  | event                      | path             | message                                  |
      | :error | :config/validation-error   | comms.bigbird    | unknown :type "unknown-type"             |

  @wip
  Scenario: Comm is hot-added when its slot appears in config at runtime
    Given the Isaac server is started
    And the comm "grover" does not exist
    When config is updated:
      | path                | value  |
      | comms.grover.type   | test-comm  |
      | comms.grover.loft   | nest   |
      | comms.grover.color  | blue   |
    Then the comm "grover" exists with state:
      | path         | value |
      | started?     | true  |
      | slice.type   | test-comm |
      | slice.color  | blue  |
    And the log has entries matching:
      | level | event                | path         | impl  |
      | :info | :lifecycle/started   | comms.grover | test-comm |
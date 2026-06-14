@wip
Feature: Hail-driven session spawning (get-or-create)
  A spawn-enabled reach-one hail uses get-or-create for its target session.
  The :spawn flag is the descriptive/prescriptive toggle for the frequency's
  tags:

    :spawn false (default) - tags are a read-only FILTER over existing
                             sessions. No match -> undeliverable. Nothing is
                             ever created.
    :spawn true            - MATCH-OR-CREATE. An existing matching session ->
                             deliver to it; none -> create one under a
                             matching crew, apply the hail's :session-tags,
                             deliver. (One-line: ":spawn = create the
                             addressed session if it doesn't exist.")

  Two phases:
  - Router (features/hail/router.feature): a spawn-enabled reach-one with a
    matching crew but no session emits an unbound spawn delivery instead of
    undeliverable. With no resolvable host crew -> undeliverable :no-host.
  - Delivery worker (features/hail/delivery.feature): treats a spawn-enabled
    delivery as live get-or-create each tick. An existing matching session
    that is idle -> bind; busy -> wait (never a sibling — preserves the
    crew's context); none -> spawn under a matching crew (first by id, only
    if it has :max-in-flight capacity), tagging the session with the hail's
    :session-tags and marking :origin {:kind :hail ...}.

  Spawn is reach-one only; :reach :all never spawns. Default :spawn is false.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  Scenario: spawn-enabled reach-one with a matching crew but no session yields a spawn delivery
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/engineer} |
    And the EDN isaac file "hail/pending/hail-1.edn" exists with:
      | path                   | value                 |
      | id                     | hail-1                |
      | frequency.crew-tags    | #{:role/engineer}     |
      | frequency.session-tags | #{:project/warp-coil} |
      | frequency.reach        | :one                  |
      | frequency.spawn        | true                  |
      | prompt                 | Resonance climbing.   |
      | from                   | :cli                  |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/undeliverable/hail-1.edn" does not exist
    And the EDN isaac file "hail/deliveries/delivery-1.edn" contains:
      | path                 | value  | #comment                        |
      | hail.id              | hail-1 |                                 |
      | hail.frequency.spawn | true   | spawn-eligible, unbound         |
      | crew                 |        | nil — worker will get-or-create |
      | session              |        | nil                             |

  Scenario: without spawn, a matching crew with no session is undeliverable
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/engineer} |
    And the EDN isaac file "hail/pending/hail-1.edn" exists with:
      | path                | value               |
      | id                  | hail-1              |
      | frequency.crew-tags | #{:role/engineer}   |
      | frequency.reach     | :one                |
      | prompt              | Resonance climbing. |
      | from                | :cli                |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/delivery-1.edn" does not exist
    And the EDN isaac file "hail/undeliverable/hail-1.edn" contains:
      | path    | value          | #comment                        |
      | hail.id | hail-1         |                                 |
      | reason  | :no-recipients | spawn off — no existing session |

  Scenario: spawn with session-tags but no crew to host is undeliverable
    Given the EDN isaac file "hail/pending/hail-1.edn" exists with:
      | path                   | value                 |
      | id                     | hail-1                |
      | frequency.session-tags | #{:project/warp-coil} |
      | frequency.reach        | :one                  |
      | frequency.spawn        | true                  |
      | prompt                 | Resonance climbing.   |
      | from                   | :cli                  |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/delivery-1.edn" does not exist
    And the EDN isaac file "hail/undeliverable/hail-1.edn" contains:
      | path    | value    | #comment                                   |
      | hail.id | hail-1   |                                            |
      | reason  | :no-host | spawn requested, no crew to host a session |

  Scenario: a spawn delivery with no existing session creates a tagged session and dispatches
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/engineer} |
    And the following model responses are queued:
      | type | content      | model  |
      | text | On the coil. | grover |
    And the EDN isaac file "hail/deliveries/delivery-1.edn" exists with:
      | path                        | value                 |
      | id                          | delivery-1            |
      | hail.id                     | hail-1                |
      | hail.frequency.crew-tags    | #{:role/engineer}     |
      | hail.frequency.session-tags | #{:project/warp-coil} |
      | hail.frequency.reach        | :one                  |
      | hail.frequency.spawn        | true                  |
      | hail.prompt                 | Resonance climbing.   |
      | attempts                    | 0                     |
    When the hail delivery worker ticks
    And the turn ends on session "session-1"
    Then the following sessions match:
      | id        | crew        | tags                  | origin.kind |
      | session-1 | bartholomew | #{:project/warp-coil} | hail        |
    And session "session-1" has transcript matching:
      | type    | message.role | message.content     |
      | message | user         | Resonance climbing. |
      | message | assistant    | On the coil.        |
    And the isaac file "hail/deliveries/delivery-1.edn" does not exist
    And the EDN isaac file "hail/delivered/delivery-1.edn" contains:
      | path    | value       |
      | crew    | bartholomew |
      | session | session-1   |

  Scenario: a spawn delivery binds an existing matching session instead of spawning
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/engineer} |
    And the following sessions exist:
      | name      | crew        | tags                  |
      | coil-work | bartholomew | #{:project/warp-coil} |
    And the following model responses are queued:
      | type | content      | model  |
      | text | On the coil. | grover |
    And the EDN isaac file "hail/deliveries/delivery-1.edn" exists with:
      | path                        | value                 |
      | id                          | delivery-1            |
      | hail.id                     | hail-1                |
      | hail.frequency.crew-tags    | #{:role/engineer}     |
      | hail.frequency.session-tags | #{:project/warp-coil} |
      | hail.frequency.reach        | :one                  |
      | hail.frequency.spawn        | true                  |
      | hail.prompt                 | Resonance climbing.   |
      | attempts                    | 0                     |
    When the hail delivery worker ticks
    And the turn ends on session "coil-work"
    Then session "session-1" does not exist
    And session "coil-work" has transcript matching:
      | type    | message.role | message.content     |
      | message | user         | Resonance climbing. |
      | message | assistant    | On the coil.        |
    And the EDN isaac file "hail/delivered/delivery-1.edn" contains:
      | path    | value     |
      | session | coil-work |

  Scenario: a spawn delivery whose only matching session is in flight waits, no sibling
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path          | value             |
      | model         | grover            |
      | tags          | #{:role/engineer} |
      | max-in-flight | 2                 |
    And the following sessions exist:
      | name      | crew        | tags                  |
      | coil-work | bartholomew | #{:project/warp-coil} |
    And session "coil-work" is in flight
    And the EDN isaac file "hail/deliveries/delivery-1.edn" exists with:
      | path                        | value                 |
      | id                          | delivery-1            |
      | hail.id                     | hail-1                |
      | hail.frequency.crew-tags    | #{:role/engineer}     |
      | hail.frequency.session-tags | #{:project/warp-coil} |
      | hail.frequency.reach        | :one                  |
      | hail.frequency.spawn        | true                  |
      | hail.prompt                 | Resonance climbing.   |
      | attempts                    | 0                     |
    When the hail delivery worker ticks
    Then session "session-1" does not exist
    And the EDN isaac file "hail/deliveries/delivery-1.edn" contains:
      | path     | value      | #comment                                            |
      | id       | delivery-1 | matching session busy — wait, don't spawn a sibling |
      | attempts | 0          |                                                     |

  Scenario: a spawn delivery waits when the only matching crew is at capacity
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path          | value             |
      | model         | grover            |
      | tags          | #{:role/engineer} |
      | max-in-flight | 1                 |
    And the following sessions exist:
      | name       | crew        |
      | other-work | bartholomew |
    And session "other-work" is in flight
    And the EDN isaac file "hail/deliveries/delivery-1.edn" exists with:
      | path                        | value                 |
      | id                          | delivery-1            |
      | hail.id                     | hail-1                |
      | hail.frequency.crew-tags    | #{:role/engineer}     |
      | hail.frequency.session-tags | #{:project/warp-coil} |
      | hail.frequency.reach        | :one                  |
      | hail.frequency.spawn        | true                  |
      | hail.prompt                 | Resonance climbing.   |
      | attempts                    | 0                     |
    When the hail delivery worker ticks
    Then session "session-1" does not exist
    And the EDN isaac file "hail/deliveries/delivery-1.edn" contains:
      | path     | value      | #comment                                 |
      | id       | delivery-1 | crew at capacity — can't spawn yet, wait |
      | attempts | 0          |                                          |

@wip
Feature: Hail delivery
  The hail delivery worker ticks on the shared scheduler, reads pending
  deliveries from hail/deliveries/, binds unbound (reach-one) deliveries
  to an idle candidate, and gates on session in-flight + crew capacity.
  For each ready delivery it claims the session (marks it in-flight),
  moves the delivery to hail/inflight/, and schedules the turn as a
  background task WITHOUT waiting — so it never dispatches two turns on
  the same session at once. The turn opens with an origin+autonomy system
  preamble (this turn came from a hail; it runs unattended, the user may
  not see the reply or be available for questions) followed by the
  resolved prompt. On turn completion the delivery moves to
  hail/delivered/; a failed turn increments attempts and backs off
  (inflight/ -> deliveries/), dead-lettering to hail/failed/ after the
  5-attempt max.

  In tests the scheduler interval is mocked away — ticks are invoked
  directly — and turn completion is driven explicitly with
  "the turn ends on session ...". A "wait | true" queued response holds a
  turn open so the in-flight window is observable.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  Scenario: a bound delivery dispatches a turn and moves to delivered
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type | content      | model  |
      | text | Sealing now. | grover |
    And the EDN isaac file "hail/deliveries/delivery-1.edn" exists with:
      | path         | value                  |
      | id           | delivery-1             |
      | hail.id      | hail-1                 |
      | hail.payload | {:dilithium-leak true} |
      | hail.prompt  | Seal the leak.         |
      | crew         | bartholomew            |
      | session      | engine-room            |
      | attempts     | 0                      |
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then session "engine-room" has transcript matching:
      | type    | message.role | message.content | #comment                        |
      | message | user         | Seal the leak.  | resolved prompt                 |
      | message | assistant    | Sealing now.    | grover's reply — turn completed |
    # Origin framing (the autonomy preamble in the system prompt) is asserted
    # in features/hail/spawn.feature's parent refactor (isaac-uysx), not here —
    # this bean only sets :origin {:kind :hail ...} and dispatches.
    And the isaac file "hail/deliveries/delivery-1.edn" does not exist
    And the EDN isaac file "hail/delivered/delivery-1.edn" contains:
      | path    | value      |
      | id      | delivery-1 |
      | hail.id | hail-1     |

  Scenario: an unbound delivery binds the idle candidate over the in-flight one
    Given the isaac EDN file "config/crew/atticus.edn" exists with:
      | path  | value  |
      | model | grover |
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew     |
      | bridge      | atticus  |
      | first-watch | cordelia |
    And session "first-watch" is in flight
    And the following model responses are queued:
      | type | content      | model  |
      | text | Bridge here. | grover |
    And the EDN isaac file "hail/deliveries/delivery-1.edn" exists with:
      | path        | value                                                                       |
      | id          | delivery-1                                                                  |
      | hail.id     | hail-1                                                                      |
      | hail.prompt | Status report?                                                              |
      | candidates  | [{:crew :atticus :session :bridge} {:crew :cordelia :session :first-watch}] |
      | attempts    | 0                                                                           |
    When the hail delivery worker ticks
    And the turn ends on session "bridge"
    Then session "bridge" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Status report?  |
      | message | assistant    | Bridge here.    |
    And the EDN isaac file "hail/delivered/delivery-1.edn" contains:
      | path    | value   | #comment                           |
      | crew    | atticus | bound to the idle candidate        |
      | session | bridge  | first-watch was in flight, skipped |

  Scenario: a delivery to an in-flight session is left pending
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path          | value  | #comment                         |
      | model         | grover |                                  |
      | max-in-flight | 2      | capacity is not the blocker here |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And session "engine-room" is in flight
    And the EDN isaac file "hail/deliveries/delivery-1.edn" exists with:
      | path        | value          |
      | id          | delivery-1     |
      | hail.id     | hail-1         |
      | hail.prompt | Seal the leak. |
      | crew        | bartholomew    |
      | session     | engine-room    |
      | attempts    | 0              |
    When the hail delivery worker ticks
    Then the EDN isaac file "hail/deliveries/delivery-1.edn" contains:
      | path     | value      | #comment                              |
      | id       | delivery-1 | still pending — session was in flight |
      | attempts | 0          | gating is not a failed attempt        |
    And the isaac file "hail/inflight/delivery-1.edn" does not exist
    And the isaac file "hail/delivered/delivery-1.edn" does not exist

  Scenario: a delivery for an at-capacity crew is left pending
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path          | value  | #comment                         |
      | model         | grover |                                  |
      | max-in-flight | 1      | one turn at a time for this crew |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
      | warp-core   | bartholomew |
    And session "warp-core" is in flight
    And the EDN isaac file "hail/deliveries/delivery-1.edn" exists with:
      | path        | value           |
      | id          | delivery-1      |
      | hail.id     | hail-1          |
      | hail.prompt | Check the core. |
      | crew        | bartholomew     |
      | session     | engine-room     |
      | attempts    | 0               |
    When the hail delivery worker ticks
    Then the EDN isaac file "hail/deliveries/delivery-1.edn" contains:
      | path     | value      | #comment                                |
      | id       | delivery-1 | still pending — bartholomew at capacity |
      | attempts | 0          |                                         |
    And the isaac file "hail/inflight/delivery-1.edn" does not exist
    And the isaac file "hail/delivered/delivery-1.edn" does not exist

  Scenario: the worker dispatches at most one turn per session, serializing across ticks
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path          | value  | #comment                      |
      | model         | grover |                               |
      | max-in-flight | 2      | crew capacity is not the gate |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type | content        | model  | wait |
      | text | Leak sealed.   | grover | true |
      | text | Plasma vented. | grover |      |
    And the EDN isaac file "hail/deliveries/delivery-1.edn" exists with:
      | path        | value          |
      | id          | delivery-1     |
      | hail.id     | hail-1         |
      | hail.prompt | Seal the leak. |
      | crew        | bartholomew    |
      | session     | engine-room    |
      | attempts    | 0              |
    And the EDN isaac file "hail/deliveries/delivery-2.edn" exists with:
      | path        | value            |
      | id          | delivery-2       |
      | hail.id     | hail-2           |
      | hail.prompt | Vent the plasma. |
      | crew        | bartholomew      |
      | session     | engine-room      |
      | attempts    | 0                |
    When the hail delivery worker ticks
    Then session "engine-room" in-flight status is true
    And the EDN isaac file "hail/deliveries/delivery-2.edn" contains:
      | path | value      | #comment                                  |
      | id   | delivery-2 | not dispatched — engine-room already busy |
    When the turn ends on session "engine-room"
    Then the EDN isaac file "hail/delivered/delivery-1.edn" contains:
      | path | value      |
      | id   | delivery-1 |
    When the hail delivery worker ticks
    And the turn ends on session "engine-room"
    Then the EDN isaac file "hail/delivered/delivery-2.edn" contains:
      | path | value      |
      | id   | delivery-2 |

  Scenario: a dispatch failure increments attempts and backs off
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type  | content | model  |
      | error | boom    | grover |
    And the EDN isaac file "hail/deliveries/delivery-1.edn" exists with:
      | path        | value          |
      | id          | delivery-1     |
      | hail.id     | hail-1         |
      | hail.prompt | Seal the leak. |
      | crew        | bartholomew    |
      | session     | engine-room    |
      | attempts    | 0              |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And the turn ends on session "engine-room"
    Then the EDN isaac file "hail/deliveries/delivery-1.edn" contains:
      | path            | value                | #comment                              |
      | attempts        | 1                    | incremented after the failed dispatch |
      | next-attempt-at | 2026-04-21T10:00:01Z | tick time + 1s (first backoff step)   |
    And the isaac file "hail/inflight/delivery-1.edn" does not exist
    And the isaac file "hail/delivered/delivery-1.edn" does not exist
    And the isaac file "hail/failed/delivery-1.edn" does not exist

  Scenario: a delivery that exhausts max attempts dead-letters to failed
    Given the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | type  | content | model  |
      | error | boom    | grover |
    And the EDN isaac file "hail/deliveries/delivery-1.edn" exists with:
      | path        | value          | #comment                                          |
      | id          | delivery-1     |                                                   |
      | hail.id     | hail-1         |                                                   |
      | hail.prompt | Seal the leak. |                                                   |
      | crew        | bartholomew    |                                                   |
      | session     | engine-room    |                                                   |
      | attempts    | 4              | one short of the 5-attempt max; this tick is last |
    When the hail delivery worker ticks at "2026-04-21T10:00:00Z"
    And the turn ends on session "engine-room"
    Then the isaac file "hail/deliveries/delivery-1.edn" does not exist
    And the isaac file "hail/inflight/delivery-1.edn" does not exist
    And the EDN isaac file "hail/failed/delivery-1.edn" contains:
      | path     | value      | #comment                                |
      | id       | delivery-1 |                                         |
      | attempts | 5          | hit the max on this tick; dead-lettered |
    And the log has entries matching:
      | level | event               | id         | reason     |
      | error | :hail/dead-lettered | delivery-1 | :exhausted |
    And the isaac file "hail/delivered/delivery-1.edn" does not exist

  Scenario: the hail delivery worker tick is registered with the shared scheduler
    When the Isaac system is started
    Then the scheduled tasks include:
      | id           | trigger.kind | trigger.ms |
      | hail/deliver | interval     | 1000       |

@wip
Feature: Hail router
  The hail router ticks on the shared scheduler, reads raw hails from
  hail/pending/, resolves each :frequency into delivery obligations, and
  writes one file per obligation to hail/deliveries/. Each delivery wraps
  the original hail verbatim under :hail plus resolved addressing. reach
  :all fans out to every match (one bound delivery each); reach :one over
  a pool is left unbound with a frozen :candidates list for the delivery
  worker to bind. Routing is fail-fast: a hail that cannot produce at
  least one delivery moves to hail/undeliverable/ with a :reason. After a
  tick every processed hail has left pending/ — to deliveries/ or
  undeliverable/. The delivery worker (separate bean) consumes
  hail/deliveries/.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  Scenario: a reach-one band matching exactly one session binds immediately
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path      | value             |
      | crew-tags | #{:role/engineer} |
      | reach     | :one              |
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/engineer} |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the EDN isaac file "hail/pending/hail-1.edn" exists with:
      | path      | value                          |
      | id        | hail-1                         |
      | frequency | {:band "engineering-intercom"} |
      | payload   | {:dilithium-leak true}         |
      | from      | :cli                           |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the EDN isaac file "hail/deliveries/delivery-1.edn" contains:
      | path           | value                          | #comment                      |
      | hail.id        | hail-1                         | original hail nested verbatim |
      | hail.frequency | {:band "engineering-intercom"} |                               |
      | hail.payload   | {:dilithium-leak true}         |                               |
      | crew           | bartholomew                    | only one engineer → bound now |
      | session        | engine-room                    |                               |

  Scenario: a reach-one tag pool of many is left unbound with frozen candidates
    Given the isaac EDN file "config/crew/atticus.edn" exists with:
      | path  | value            |
      | model | grover           |
      | tags  | #{:role/command} |
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value            |
      | model | grover           |
      | tags  | #{:role/command} |
    And the following sessions exist:
      | name        | crew     |
      | bridge      | atticus  |
      | first-watch | cordelia |
    And the EDN isaac file "hail/pending/hail-1.edn" exists with:
      | path      | value                         |
      | id        | hail-1                        |
      | frequency | {:crew-tags #{:role/command}} |
      | reach     | :one                          |
      | prompt    | Status report?                |
      | from      | :cli                          |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the EDN isaac file "hail/deliveries/delivery-1.edn" contains:
      | path       | value                                                                       | #comment             |
      | hail.id    | hail-1                                                                      |                      |
      | crew       |                                                                             | unbound — nil        |
      | session    |                                                                             | unbound — nil        |
      | candidates | [{:crew :atticus :session :bridge} {:crew :cordelia :session :first-watch}] | frozen pool snapshot |

  Scenario: a direct crew frequency binds to that crew's session
    Given the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/botanist} |
    And the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |
    And the EDN isaac file "hail/pending/hail-1.edn" exists with:
      | path      | value                   |
      | id        | hail-1                  |
      | frequency | {:crew [:hieronymus]}   |
      | prompt    | The lettuce is wilting. |
      | payload   | {:wilting [:lettuce]}   |
      | from      | :cli                    |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the EDN isaac file "hail/deliveries/delivery-1.edn" contains:
      | path        | value                   | #comment                         |
      | hail.id     | hail-1                  |                                  |
      | hail.prompt | The lettuce is wilting. | carried verbatim under :hail     |
      | crew        | hieronymus              | direct crew, one session → bound |
      | session     | greenhouse              |                                  |

  Scenario: a direct session frequency binds to that exact session only
    Given the isaac EDN file "config/crew/mavis.edn" exists with:
      | path  | value              |
      | model | grover             |
      | tags  | #{:role/navigator} |
    And the following sessions exist:
      | name           | crew  |
      | charted-course | mavis |
      | side-quest     | mavis |
    And the EDN isaac file "hail/pending/hail-1.edn" exists with:
      | path      | value                        |
      | id        | hail-1                       |
      | frequency | {:session [:charted-course]} |
      | prompt    | Adjust bearing 12 degrees.   |
      | from      | :cli                         |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the EDN isaac file "hail/deliveries/delivery-1.edn" contains:
      | path    | value          | #comment             |
      | hail.id | hail-1         |                      |
      | crew    | mavis          |                      |
      | session | charted-course | the targeted session |
    And the isaac file "hail/deliveries/delivery-2.edn" does not exist

  Scenario: reach :all fans out to one bound delivery per matching session
    Given the isaac EDN file "config/crew/atticus.edn" exists with:
      | path  | value            |
      | model | grover           |
      | tags  | #{:role/command} |
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value            |
      | model | grover           |
      | tags  | #{:role/command} |
    And the following sessions exist:
      | name        | crew     |
      | bridge      | atticus  |
      | first-watch | cordelia |
    And the EDN isaac file "hail/pending/hail-1.edn" exists with:
      | path      | value                         |
      | id        | hail-1                        |
      | frequency | {:crew-tags #{:role/command}} |
      | reach     | :all                          |
      | prompt    | Red alert!                    |
      | from      | :cli                          |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the EDN isaac file "hail/deliveries/delivery-1.edn" contains:
      | path    | value   | #comment                             |
      | hail.id | hail-1  |                                      |
      | crew    | atticus |                                      |
      | session | bridge  | deliveries emitted sorted by session |
    And the EDN isaac file "hail/deliveries/delivery-2.edn" contains:
      | path    | value       |
      | hail.id | hail-1      |
      | crew    | cordelia    |
      | session | first-watch |
    And the isaac file "hail/deliveries/delivery-3.edn" does not exist

  Scenario: combined band and session-tag intersect to one bound delivery
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path      | value             |
      | crew-tags | #{:role/engineer} |
      | reach     | :one              |
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/engineer} |
    And the following sessions exist:
      | name           | crew        | tags                  |
      | engine-room    | bartholomew | #{}                   |
      | coil-tinkering | bartholomew | #{:project/warp-coil} |
    And the EDN isaac file "hail/pending/hail-1.edn" exists with:
      | path      | value                                                              |
      | id        | hail-1                                                             |
      | frequency | {:band "engineering-intercom" :session-tags #{:project/warp-coil}} |
      | payload   | {:resonance-drift 0.03}                                            |
      | from      | :cli                                                               |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the EDN isaac file "hail/deliveries/delivery-1.edn" contains:
      | path    | value          | #comment                                   |
      | hail.id | hail-1         |                                            |
      | crew    | bartholomew    |                                            |
      | session | coil-tinkering | warp-coil session matched, engine-room not |
    And the isaac file "hail/deliveries/delivery-2.edn" does not exist

  Scenario: an unknown band moves the hail to undeliverable
    Given the EDN isaac file "hail/pending/hail-1.edn" exists with:
      | path      | value                  | #comment                               |
      | id        | hail-1                 |                                        |
      | frequency | {:band "phantom-band"} | no config/hail/phantom-band.edn exists |
      | payload   | {:n 1}                 |                                        |
      | from      | :cli                   |                                        |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/delivery-1.edn" does not exist
    And the EDN isaac file "hail/undeliverable/hail-1.edn" contains:
      | path           | value                  | #comment                  |
      | hail.id        | hail-1                 | original hail preserved   |
      | hail.frequency | {:band "phantom-band"} |                           |
      | reason         | :unknown-band          | why it couldn't be routed |

  Scenario: a reach-one band with no matching crew moves the hail to undeliverable
    Given the isaac EDN file "config/hail/engineering-intercom.edn" exists with:
      | path      | value             |
      | crew-tags | #{:role/engineer} |
      | reach     | :one              |
    And the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value             | #comment                       |
      | model | grover            |                                |
      | tags  | #{:role/botanist} | no engineer-tagged crew exists |
    And the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |
    And the EDN isaac file "hail/pending/hail-1.edn" exists with:
      | path      | value                          |
      | id        | hail-1                         |
      | frequency | {:band "engineering-intercom"} |
      | payload   | {:n 1}                         |
      | from      | :cli                           |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/delivery-1.edn" does not exist
    And the EDN isaac file "hail/undeliverable/hail-1.edn" contains:
      | path    | value          | #comment                         |
      | hail.id | hail-1         |                                  |
      | reason  | :no-recipients | band exists, no engineer matched |

  Scenario: reach :all matching zero sessions moves the hail to undeliverable
    Given the isaac EDN file "config/crew/hieronymus.edn" exists with:
      | path  | value             |
      | model | grover            |
      | tags  | #{:role/botanist} |
    And the following sessions exist:
      | name       | crew       |
      | greenhouse | hieronymus |
    And the EDN isaac file "hail/pending/hail-1.edn" exists with:
      | path      | value                         | #comment                      |
      | id        | hail-1                        |                               |
      | frequency | {:crew-tags #{:role/command}} | no command-tagged crew exists |
      | reach     | :all                          |                               |
      | prompt    | All hands!                    |                               |
      | from      | :cli                          |                               |
    When the hail router ticks
    Then the isaac file "hail/pending/hail-1.edn" does not exist
    And the isaac file "hail/deliveries/delivery-1.edn" does not exist
    And the EDN isaac file "hail/undeliverable/hail-1.edn" contains:
      | path    | value          | #comment                    |
      | hail.id | hail-1         |                             |
      | reason  | :no-recipients | snapshot matched no session |

  Scenario: the hail router tick is registered with the shared scheduler
    When the Isaac system is started
    Then the scheduled tasks include:
      | id         | trigger.kind | trigger.ms |
      | hail/route | interval     | 1000       |

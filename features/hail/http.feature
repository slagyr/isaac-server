@wip
Feature: Hail HTTP route POST /hail/send
  External producers (CI, webhooks, beans CLI, other Isaac installs)
  publish hails via POST /hail/send. The route reuses the existing
  server-wide auth token (isaac-g69y), accepts JSON or EDN content
  types, and records :from :http on the persisted hail. Auth and
  method handling come from the server's standard middleware; this
  feature covers Hail-specific contracts only.

  Background:
    Given default Grover setup

  Scenario: POST with JSON body and valid auth persists a hail
    When a POST request is made to "/hail/send":
      | key                  | value                                                       |
      | header.Content-Type  | application/json                                            |
      | header.Authorization | Bearer secret123                                            |
      | body                 | {"frequency": {"band": "bean-pickup"}, "payload": {"n": 1}} |
    Then the response status is 201
    And the EDN isaac file "hail/pending/hail-1.edn" contains:
      | path      | value                 |
      | id        | hail-1                |
      | frequency | {:band "bean-pickup"} |
      | payload   | {:n 1}                |
      | from      | :http                 |

  Scenario: POST with EDN body and valid auth persists a hail
    When a POST request is made to "/hail/send":
      | key                  | value                                              |
      | header.Content-Type  | application/edn                                    |
      | header.Authorization | Bearer secret123                                   |
      | body                 | {:frequency {:band "bean-pickup"} :payload {:n 1}} |
    Then the response status is 201
    And the EDN isaac file "hail/pending/hail-1.edn" contains:
      | path      | value                 |
      | id        | hail-1                |
      | frequency | {:band "bean-pickup"} |
      | payload   | {:n 1}                |
      | from      | :http                 |

  Scenario: POST with missing frequency returns 400
    When a POST request is made to "/hail/send":
      | key                  | value                 | #comment                                  |
      | header.Content-Type  | application/json      |                                           |
      | header.Authorization | Bearer secret123      |                                           |
      | body                 | {"payload": {"n": 1}} | no "frequency" key in body — must reject  |
    Then the response status is 400
    And the response body has a "error" key

  Scenario: POST with malformed JSON body returns 400
    When a POST request is made to "/hail/send":
      | key                  | value            |
      | header.Content-Type  | application/json |
      | header.Authorization | Bearer secret123 |
      | body                 | {not valid json  |
    Then the response status is 400
    And the response body has a "error" key

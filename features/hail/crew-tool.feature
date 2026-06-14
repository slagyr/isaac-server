@wip
Feature: Hail crew tool
  The `hail-send` tool lets an LLM in a turn dispatch hails from
  inside its reasoning loop. Crews opt in via :tools.allow. The
  sent hail's :from records the calling crew's identity.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup

  Scenario: crew with hail-send allowed dispatches a hail from a turn
    Given the crew "main" allows tools: "hail-send"
    And the following sessions exist:
      | name      |
      | work-sess |
    And the following model responses are queued:
      | model | tool_call | arguments                                                    |
      | echo  | hail-send | {"frequency": {"band": "bean-pickup"}, "payload": {"n": 1}} |
      | model | type      | content                                                      |
      | echo  | text      | Done.                                                        |
    When the user sends "send a hail" on session "work-sess"
    Then the EDN isaac file "hail/pending/hail-1.edn" contains:
      | path      | value                 |
      | id        | hail-1                |
      | frequency | {:band "bean-pickup"} |
      | payload   | {:n 1}                |
      | from      | :crew/main            |

  Scenario: crew without hail-send in allow list cannot invoke it
    Given the following sessions exist:
      | name      |
      | work-sess |
    When the user sends "anything" on session "work-sess"
    Then the prompt does not have tools:
      | name      |
      | hail-send |

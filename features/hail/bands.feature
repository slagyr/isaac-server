@wip
Feature: Hail bands declared in config
  Bands are declared via files under ~/.isaac/config/hail/<name>.edn
  with optional .md companions for the prompt. The config schema
  validates each band's shape; bad declarations error at validate
  time.

  Background:
    Given an Isaac root at "isaac-state"

  Scenario: config validate accepts a valid band declaration
    Given config file "hail/bean-pickup.edn" containing:
      """
      {:crew-tags    [:role/worker]
       :session-tags [:project/chess]
       :reach        :one}
      """
    When isaac is run with "config validate"
    Then the stdout contains "OK"
    And the exit code is 0

  Scenario: config validate rejects a band with an invalid :reach
    Given config file "hail/bogus.edn" containing:
      """
      {:crew-tags [:role/worker]
       :reach     :many}
      """
    When isaac is run with "config validate"
    Then the stderr contains "reach"
    And the exit code is 1

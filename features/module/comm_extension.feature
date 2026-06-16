Feature: Comm extension
  Comms are declared as :comm extensions in module manifests, each with
  a :factory and optional :schema. User configs under :comms instantiate
  comms by name, with an explicit :type field referencing the manifest
  entry that supplies the implementation. The instance key and the
  :type are independent, so multiple instances of the same comm kind
  can coexist with distinct names.

  Scenario: Multiple comm instances of the same :type coexist
    Given an empty Isaac root at "/tmp/isaac"
    And config:
      | key              | value  |
      | bind-server-port | false  |
      | log.output       | memory |
    And the isaac file "isaac.edn" exists with:
      """
      {:log    {:output :memory}
       :server {:hot-reload false}
       :crew   {:main {}}
       :modules {:isaac.comm.telly {:local/root "../isaac/modules/isaac.comm.telly"}}
       :comms  {:north-bot {:type :telly :crew :main}
                :south-bot {:type :telly :crew :main}}}
      """
    When the Isaac server is started
    Then the log has entries matching:
      | level | event           | comm      |
      | :info | :comm/activated | north-bot |
      | :info | :comm/activated | south-bot |
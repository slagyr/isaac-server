@wip
Feature: isaac service — Linux systemd user unit management
  On Linux `isaac service` manages Isaac as a systemd user unit at
  ~/.config/systemd/user/isaac.service, mirroring the macOS LaunchAgent
  subcommand for subcommand: install writes the unit with Isaac's
  invocation baked in and enables it, status reads systemctl, logs read
  the file the unit appends to. Every subprocess goes through isaac.shell
  so these scenarios run on any box with the shell stubbed.

  Background:
    Given an Isaac root at "target/test-state"
    And the operating system is "Linux"
    And shell commands are stubbed

  Scenario: packaged install writes the unit and enables it
    Given "isaac" resolves to "/opt/marigold/bin/isaac"
    And "bb" resolves to "/opt/marigold/bin/bb"
    And the current process PATH is "/opt/marigold/bin:/usr/bin:/bin"
    When isaac is run with "service install"
    Then the INI file "~/.config/systemd/user/isaac.service" matches:
      | path                | value                                |
      | Unit.Description    | Isaac server                         |
      | Service.ExecStart   | /opt/marigold/bin/isaac server       |
      | Service.Environment | PATH=/opt/marigold/bin:/usr/bin:/bin |
      | Service.Restart     | always                               |
      | Install.WantedBy    | default.target                       |
    And sh was called with "systemctl --user daemon-reload"
    And sh was called with "systemctl --user enable --now isaac"
    And the stdout contains "Resolved launcher: /opt/marigold/bin/isaac"
    And the stdout contains "Service installed: isaac"
    And the exit code is 0

  Scenario: dev-checkout install runs bb against the repo's bb.edn
    Given "bb" resolves to "/opt/marigold/bin/bb"
    And the current process PATH is "/opt/marigold/bin:/usr/bin:/bin"
    When isaac is run with "service install --isaac-dir /projects/marigold-bridge"
    Then the INI file "~/.config/systemd/user/isaac.service" matches:
      | path                | value                                                                               |
      | Service.ExecStart   | /opt/marigold/bin/bb --config /projects/marigold-bridge/bb.edn -m isaac.main server |
      | Service.Environment | PATH=/opt/marigold/bin:/usr/bin:/bin                                                |
    And sh was called with "systemctl --user enable --now isaac"
    And the stdout contains "Resolved bb: /opt/marigold/bin/bb"
    And the stdout contains "Service installed: isaac"
    And the exit code is 0

  Scenario: packaged install passes --root to the launcher
    Given "isaac" resolves to "/opt/marigold/bin/isaac"
    And "bb" resolves to "/opt/marigold/bin/bb"
    When isaac is run with "service install --root /srv/marigold"
    Then the INI file "~/.config/systemd/user/isaac.service" matches:
      | path              | value                                               |
      | Service.ExecStart | /opt/marigold/bin/isaac --root /srv/marigold server |
    And the exit code is 0

  Scenario: --runtime jvm bakes the clojure wrapper into ExecStart and status reads it back
    Given "isaac" resolves to "/opt/marigold/bin/isaac"
    And "bb" resolves to "/opt/marigold/bin/bb"
    When isaac is run with "service install --runtime jvm --root /srv/marigold"
    Then the INI file "~/.config/systemd/user/isaac.service" matches:
      | path              | value                                                                                                                                                  |
      | Service.ExecStart | /bin/sh -c "exec clojure -Sdeps \"$$(/opt/marigold/bin/isaac --root /srv/marigold modules deps --edn)\" -M -m isaac.main --root /srv/marigold server" |
    And the exit code is 0
    When isaac is run with "service status"
    Then the stdout contains "runtime: jvm"

  Scenario: install warns when the user session does not linger
    Given "isaac" resolves to "/opt/marigold/bin/isaac"
    And "bb" resolves to "/opt/marigold/bin/bb"
    When isaac is run with "service install"
    Then the stdout contains "Service installed: isaac"
    And the stderr contains "lingering is off"
    And the stderr contains "loginctl enable-linger"
    And the exit code is 0

  Scenario: install stays quiet when the user session lingers
    Given "isaac" resolves to "/opt/marigold/bin/isaac"
    And "bb" resolves to "/opt/marigold/bin/bb"
    And sh "loginctl" prints to stdout:
      """
      Linger=yes
      """
    When isaac is run with "service install"
    Then the stderr does not contain "linger"
    And the exit code is 0

  Scenario: status shows not installed when the unit is absent
    When isaac is run with "service status"
    Then the stdout contains "not installed"
    And the exit code is 1

  Scenario: status shows running with pid and last exit
    Given "isaac" resolves to "/opt/marigold/bin/isaac"
    And "bb" resolves to "/opt/marigold/bin/bb"
    And isaac is run with "service install"
    And sh "systemctl" prints to stdout:
      """
      ActiveState=active
      MainPID=51234
      ExecMainStatus=0
      """
    When isaac is run with "service status"
    Then sh was called with "systemctl --user show isaac"
    And the stdout contains "state: running"
    And the stdout contains "runtime: bb"
    And the stdout contains "pid:   51234"
    And the stdout contains "last exit: 0"
    And the exit code is 0

  Scenario: uninstall is idempotent when the unit is absent
    When isaac is run with "service uninstall"
    Then the stdout contains "uninstalled"
    And the exit code is 0

  Scenario: uninstall disables the unit and removes the file
    Given "isaac" resolves to "/opt/marigold/bin/isaac"
    And "bb" resolves to "/opt/marigold/bin/bb"
    And isaac is run with "service install"
    When isaac is run with "service uninstall"
    Then sh was called with "systemctl --user disable --now isaac"
    And sh was called with "systemctl --user daemon-reload"
    And the file "~/.config/systemd/user/isaac.service" does not exist
    And the exit code is 0

  Scenario: start, stop, and restart call the matching systemctl verbs
    Given "isaac" resolves to "/opt/marigold/bin/isaac"
    And "bb" resolves to "/opt/marigold/bin/bb"
    And isaac is run with "service install"
    When isaac is run with "service stop"
    Then sh was called with "systemctl --user stop isaac"
    When isaac is run with "service start"
    Then sh was called with "systemctl --user start isaac"
    When isaac is run with "service restart"
    Then sh was called with "systemctl --user restart isaac"
    And the exit code is 0

  Scenario: logs prints the file the unit appends to
    Given "isaac" resolves to "/opt/marigold/bin/isaac"
    And "bb" resolves to "/opt/marigold/bin/bb"
    And isaac is run with "service install"
    And the file "~/.local/state/isaac/server.log" contains:
      """
      11:15:15.692  INFO   :server/started  {:port 6674}
      """
    When isaac is run with "service logs"
    Then the INI file "~/.config/systemd/user/isaac.service" matches:
      | path                   | value                                  |
      | Service.StandardOutput | append:~/.local/state/isaac/server.log |
      | Service.StandardError  | append:~/.local/state/isaac/server.log |
    And the stdout contains ":server/started"
    And the exit code is 0

  Scenario: logs --follow streams via tail -f
    Given "isaac" resolves to "/opt/marigold/bin/isaac"
    And "bb" resolves to "/opt/marigold/bin/bb"
    And isaac is run with "service install"
    And the file "~/.local/state/isaac/server.log" contains:
      """
      11:15:15.692  INFO   :server/started  {:port 6674}
      """
    When isaac is run with "service logs --follow"
    Then sh was called with "tail -f"
    And the exit code is 0

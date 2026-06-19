Feature: isaac service — macOS LaunchAgent management
  `isaac service` manages Isaac as a background service on macOS via
  launchctl. It writes a LaunchAgent plist with Isaac's invocation
  baked in, bootstraps/boots-out the agent, and exposes status and
  log access without requiring the operator to know launchctl
  incantations.

  All scenarios here assume macOS unless otherwise stated. On other
  platforms, every subcommand prints "isaac service is not yet
  supported on <OS>" and exits non-zero.

  Background:
    Given an Isaac root at "target/test-state"
    And the operating system is "Mac OS X"
    And launchctl is stubbed

  Scenario: install uses the packaged launcher when isaac is on PATH
    Given "isaac" resolves to "/usr/local/bin/isaac"
    When isaac is run with "service install"
    Then the file "~/Library/LaunchAgents/com.slagyr.isaac.plist" exists
    And the plist contains:
      | path                | value               |
      | Label               | com.slagyr.isaac    |
      | ProgramArguments[0] | /usr/local/bin/isaac |
    And the plist program arguments end with "server"
    And the stdout does not contain "bb.edn"
    And launchctl was called with "bootstrap"
    And the stdout contains "Resolved launcher: /usr/local/bin/isaac"
    And the exit code is 0

  Scenario: install writes a dev-checkout plist when only bb is on PATH
    Given "bb" resolves to "/opt/homebrew/bin/bb"
    When isaac is run with "service install --isaac-dir /projects/isaac"
    Then the file "~/Library/LaunchAgents/com.slagyr.isaac.plist" exists
    And the plist contains:
      | path                | value            |
      | Label               | com.slagyr.isaac |
      | ProgramArguments[0] | /opt/homebrew/bin/bb |
      | ProgramArguments[4] | isaac.main       |
      | ProgramArguments[5] | server           |
    And launchctl was called with "bootstrap"
    And the stdout contains "Resolved bb: /opt/homebrew/bin/bb"
    And the exit code is 0

  Scenario: install errors clearly when neither launcher nor bb is on PATH
    Given "bb" is not on PATH
    When isaac is run with "service install"
    Then the stderr contains "could not locate isaac or bb"
    And the stderr contains "--isaac-bin"
    And the file "~/Library/LaunchAgents/com.slagyr.isaac.plist" does not exist
    And the exit code is 1

  Scenario: install accepts --bb-bin override for dev checkout
    Given "bb" is not on PATH
    When isaac is run with "service install --bb-bin /usr/local/bin/bb --isaac-dir /projects/isaac"
    Then the plist contains:
      | path                | value             |
      | ProgramArguments[0] | /usr/local/bin/bb |
    And the exit code is 0

  Scenario: packaged install passes --root to the launcher
    Given "isaac" resolves to "/usr/local/bin/isaac"
    When isaac is run with "service install --root /var/isaac"
    Then the plist contains:
      | path                | value               |
      | ProgramArguments[0] | /usr/local/bin/isaac |
      | ProgramArguments[1] | --root              |
      | ProgramArguments[2] | /var/isaac          |
      | ProgramArguments[3] | server              |
    And the exit code is 0

  Scenario: status shows not installed when plist is absent
    When isaac is run with "service status"
    Then the stdout contains "not installed"
    And the exit code is 1

  Scenario: status shows running with pid and last exit
    Given "isaac" resolves to "/usr/local/bin/isaac"
    And isaac is run with "service install"
    And launchctl print returns:
      """
      state = running
      pid = 51234
      last exit code = 0
      """
    When isaac is run with "service status"
    Then the stdout contains "state: running"
    And the stdout contains "pid:   51234"
    And the stdout contains "last exit: 0"
    And the exit code is 0

  Scenario: uninstall is idempotent when service is absent
    When isaac is run with "service uninstall"
    Then the stdout contains "uninstalled"
    And the exit code is 0

  Scenario: restart kicks the agent
    Given "isaac" resolves to "/usr/local/bin/isaac"
    And isaac is run with "service install"
    When isaac is run with "service restart"
    Then launchctl was called with "kickstart -k"
    And the exit code is 0

  Scenario: logs prints recent entries when log file exists
    Given "isaac" resolves to "/usr/local/bin/isaac"
    And isaac is run with "service install"
    And the file "~/Library/Logs/isaac/server.log" contains:
      """
      11:15:15.692  INFO   :server/started  {:port 6674}
      """
    When isaac is run with "service logs"
    Then the stdout contains ":server/started"
    And the exit code is 0

  Scenario: logs --follow streams via tail -f
    Given "isaac" resolves to "/usr/local/bin/isaac"
    And isaac is run with "service install"
    And the file "~/Library/Logs/isaac/server.log" contains:
      """
      11:15:15.692  INFO   :server/started  {:port 6674}
      """
    When isaac is run with "service logs --follow"
    Then sh was called with "tail -f"
    And the exit code is 0

  Scenario: start re-bootstraps the service after stop
    Given "isaac" resolves to "/usr/local/bin/isaac"
    And isaac is run with "service install"
    And isaac is run with "service stop"
    When isaac is run with "service start"
    Then launchctl was called with "bootstrap"
    And the exit code is 0

  Scenario: Linux is not yet supported
    Given the operating system is "Linux"
    When isaac is run with "service install"
    Then the stderr contains "not yet supported on Linux"
    And the exit code is 1

  Scenario: isaac service --help lists subcommands
    When isaac is run with "service --help"
    Then the stdout matches:
      | pattern                                                  |
      | Usage: isaac service \[options\] <subcommand>            |
      | Manage Isaac as a background service                     |
      | Subcommands:                                             |
      | install\s+Install Isaac as a launchd service             |
      | uninstall\s+Remove the Isaac launchd service             |
      | start\s+Start the Isaac service                          |
      | stop\s+Stop the Isaac service                            |
      | restart\s+Restart the Isaac service                      |
      | status\s+Show the Isaac service status                   |
      | logs\s+Tail Isaac service logs                           |
    And the exit code is 0

  Scenario: isaac help service prints the same listing
    When isaac is run with "help service"
    Then the stdout matches:
      | pattern                                                  |
      | Usage: isaac service \[options\] <subcommand>            |
      | install\s+Install Isaac as a launchd service             |
    And the exit code is 0

  Scenario: bare isaac service prints the same listing
    When isaac is run with "service"
    Then the stdout matches:
      | pattern                                                  |
      | Usage: isaac service \[options\] <subcommand>            |
      | Subcommands:                                             |
      | install\s+Install Isaac as a launchd service             |
    And the exit code is 0
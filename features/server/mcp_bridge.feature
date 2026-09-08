Feature: MCP route and the mcp-bridge command
  The per-turn tool registry (isaac-agent) is exposed on the running
  server as one route, POST /mcp/turns/{turn-id}, behind the server-wide
  bearer-token middleware, speaking one MCP JSON-RPC message per request.
  `isaac mcp-bridge --turn <id> --server <url>` is the stdio MCP server
  Claude Code spawns from its --mcp-config: it answers initialize locally,
  drops notifications, and forwards every other line as a POST, writing
  the response line back. Unknown turns come back as JSON-RPC errors so
  the CLI reports a failed tool call rather than hanging (isaac-zocg).

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | server.host       | 127.0.0.1 |
      | server.auth.token | s3cr3t    |
    And the Isaac server is started

  Scenario: the route is behind the server token
    When the client sends POST "/mcp/turns/t-x" with body:
      """
      {"jsonrpc":"2.0","id":1,"method":"tools/list"}
      """
    Then the response status is 401

  Scenario: tools/list for a registered turn answers over the route
    Given a turn "t-route" is registered for session "route-sess" with tools "exec/run"
    When the client sends POST "/mcp/turns/t-route" with header "Authorization: Bearer s3cr3t" and body:
      """
      {"jsonrpc":"2.0","id":1,"method":"tools/list"}
      """
    Then the response status is 200
    And the response body has "result.tools[0].name" equal to "exec__run"

  Scenario: an unknown turn is a JSON-RPC error over the route, not an HTTP failure
    When the client sends POST "/mcp/turns/t-ghost" with header "Authorization: Bearer s3cr3t" and body:
      """
      {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"exec__run","arguments":{"command":"echo never"}}}
      """
    Then the response status is 200
    And the response body has "error.code" equal to "-32001"

  Scenario: the mcp-bridge command round-trips initialize, tools/list and tools/call over stdio
    Given a turn "t-stdio" is registered for session "stdio-sess" with tools "exec/run"
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"claude-code","version":"test"}}}
      {"jsonrpc":"2.0","method":"notifications/initialized"}
      {"jsonrpc":"2.0","id":2,"method":"tools/list"}
      {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"exec__run","arguments":{"command":"echo bridged"}}}
      """
    When isaac is run with "mcp-bridge --turn t-stdio --server http://127.0.0.1:${server.port} --token s3cr3t"
    Then the exit code is 0
    And the stdout matches:
      | pattern |
      | "id":1.*"serverInfo".*"name":"isaac" |
      | "id":2.*"tools".*"exec__run" |
      | "id":3.*"content".*bridged |

  Scenario: mcp-bridge auth failure on tools/list is a JSON-RPC error, never plain text (isaac-o2fh)
    Given stdin is:
      """
      {"jsonrpc":"2.0","id":2,"method":"tools/list"}
      """
    When isaac is run with "mcp-bridge --turn t-ghost --server http://127.0.0.1:${server.port} --token wrong-token"
    Then the exit code is 0
    And the stdout matches:
      | pattern |
      | "id":2.*"error".*"code":-32001.*"unauthorized" |
    And the log has entries matching:
      | event                      |
      | :mcp-bridge/unauthorized   |

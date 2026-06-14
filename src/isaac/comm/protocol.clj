(ns isaac.comm.protocol)

(defprotocol Comm
  "Pluggable interface for surfacing Isaac output and turn events to a
   user-facing channel — CLI terminal, Discord channel, ACP client,
   memory comm for tests, etc.

   Comm callbacks are emitted by the turn pipeline and related command
   paths. Most events come from `isaac.drive.turn`, while command-style
   output such as slash-command responses may be emitted by `isaac.bridge`.

   A Comm impl decides how to render or react to each event on its own
   surface. Implementations may no-op methods they don't need.

   `comm`        — the Comm instance (this).
   `session-key` — string identifying the session the event belongs to."

  (on-turn-start [comm session-key input]
    "Fired before any LLM call, immediately after the user's input is
     accepted. Useful for ack signals (typing indicator, status pings).
     `input` is the raw user-supplied text.")

  (on-text-chunk [comm session-key text]
    "Fired for every streaming text fragment emitted during the turn —
     LLM tokens as they arrive, slash-command output, error text the
     drive wants to surface. Comm impls typically append `text` to
     their current output stream.")

  (on-tool-call [comm session-key tool-call]
    "Fired when the LLM requests a tool invocation. `tool-call` is a
     map with :id (uuid), :name, :arguments, :type. Comm impls may
     surface the call to the user (e.g., 'Running tool foo...').")

  (on-tool-cancel [comm session-key tool-call]
    "Fired when a pending tool call is cancelled before it ran (user
     cancelled the turn, deadline elapsed, etc.). `tool-call` is the
     same map shape as on-tool-call.")

  (on-tool-result [comm session-key tool-call result]
    "Fired after a tool call completes. `tool-call` is the original
     call map; `result` is the tool's return value (shape depends on
     the tool).")

  (on-compaction-start [comm session-key payload]
    "Fired when transcript compaction begins. `payload` carries
     :provider, :model, and trigger metadata (token counts, threshold).
     Compaction may be inline (during the turn) or asynchronous.")

  (on-compaction-success [comm session-key payload]
    "Fired when compaction finishes successfully. `payload` carries
     :summary and any usage/cost data the compactor produced.")

  (on-compaction-failure [comm session-key payload]
    "Fired when compaction fails. `payload` carries :consecutive-failures,
     :error, and other diagnostic context. Compaction may auto-disable
     after repeated failures.")

  (on-compaction-disabled [comm session-key payload]
    "Fired when compaction was triggered but skipped because it has
     been disabled (config or auto-disabled after failures).
     `payload` carries :reason.")

  (on-turn-end [comm session-key result]
    "Fired exactly once per turn, regardless of outcome. `result` is
     the final response map ({:message ..., :usage ..., :tool-calls ...})
     for successful turns, or an error map ({:error keyword, :message ...})
     for failed/cancelled turns. Impls that want to render errors differently
     should branch on (:error result) inside this method.")

  (send! [comm record]
    "Attempt to deliver a queued outbound record. `record` carries :comm,
     :target, :content, and delivery metadata. Return {:ok true} on success
     or {:ok false :transient? bool} on failure (transient failures are
     rescheduled; permanent failures are dead-lettered)."))

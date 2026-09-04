(ns isaac.comm.protocol)

(defprotocol Comm
  "Pluggable interface for surfacing Isaac output and turn events to a
   user-facing channel — CLI terminal, Discord channel, ACP client,
   memory comm for tests, etc.

   Comm callbacks are emitted by the turn pipeline and related command
   paths. Most events come from `isaac.drive.turn`, while command-style
   output such as slash-command responses may be emitted by `isaac.bridge`.

   Implementors are a state-only deftype plus
   (extend TheType Comm (merge defaults overrides)) — never inline.
   New signals add one entry to `defaults`; existing extenders keep working.

   `comm`        — the Comm instance (this).
   `session-key` — string identifying the session the event belongs to.
   `cycle`       — {:n n :model \"...\"} for the current LLM call, or nil."

  (on-turn-start [comm session-key input]
    "Fired before any LLM call, immediately after the user's input is
     accepted. Useful for ack signals (typing indicator, status pings).
     `input` is the raw user-supplied text.")

  (on-turn-end [comm session-key result]
    "Fired exactly once per turn, regardless of outcome. `result` is
     the final response map for successful turns, or an error map for
     failed/cancelled turns.")

  (on-cycle-start [comm session-key cycle]
    "Fired at the start of each LLM call. `cycle` is {:n n :model ...}.")

  (on-cycle-end [comm session-key cycle outcome]
    "Fired after the LLM call returns. `outcome` is
     {:outcome :aside|:reply :text \"...\" :tool-calls [...]}.")

  (on-chatter [comm session-key cycle chunk]
    "Live outward-voice deltas while a cycle is streaming. Classification
     is unknown until cycle-end. `chunk` is usually a string; fixed-width
     slash blocks may be a tagged map. Use isaac.comm.render/chunk-text.")

  (on-reckoning [comm session-key cycle chunk]
    "Inward voice — provider reasoning/thinking. Not addressed to the user.")

  (on-aside [comm session-key cycle text]
    "Cycle-end outward voice when tool calls followed. Theater aside.")

  (on-reply [comm session-key text]
    "Cycle-end outward voice with no tool calls — the answer.")

  (on-tool-call [comm session-key tool-call]
    "Fired when the LLM requests a tool invocation. `tool-call` is a
     map with :id (uuid), :name, :arguments, :type.")

  (on-tool-cancel [comm session-key tool-call]
    "Fired when a pending tool call is cancelled before it ran.")

  (on-tool-result [comm session-key tool-call result]
    "Fired after a tool call completes.")

  (on-tool-progress [comm session-key tool-call chunk]
    "Incremental output from a running tool (any tool; exec is one source).")

  (on-bulletin [comm session-key bulletin]
    "Something the ship did. `bulletin` is {:kind keyword :payload? ...}
     with :kind in #{:compaction/start :compaction/success :compaction/failure
     :compaction/disabled :recall/injected :episodes/opened :turnstile/held ...}.")

  (send! [comm record]
    "Attempt to deliver a queued outbound record. Return {:ok true} on
     success or {:ok false :transient? bool} on failure. No default —
     delivery is mandatory per comm."))

(defn- noop
  "Variadic no-op used as the default for every turn-event method."
  [& _])

(def defaults
  "No-op fn per turn-event method. :send! is deliberately absent — every
   comm must supply delivery."
  {:on-turn-start    noop
   :on-turn-end      noop
   :on-cycle-start   noop
   :on-cycle-end     noop
   :on-chatter       noop
   :on-reckoning     noop
   :on-aside         noop
   :on-reply         noop
   :on-tool-call     noop
   :on-tool-cancel   noop
   :on-tool-result   noop
   :on-tool-progress noop
   :on-bulletin      noop})

(ns isaac.llm.api.protocol
  "Protocol for an Api adapter — the gateway to a thinking-engine
   (Anthropic, OpenAI, Ollama, Grover test stub).

   Implementations live alongside their wire code in isaac.llm.api.<name> namespaces.
   The `make` factory in isaac.llm.provider resolves a (name, config)
   pair to an Api instance — it lives there to avoid a cycle, since
   the impl namespaces all require this one for the protocol."
  (:require
    [c3kit.apron.schema :as schema]
    [clojure.string :as str]
    [isaac.logger :as log]))

(defprotocol Api
  (chat
    [this request]
    "One-shot LLM call. Returns a normalized response map with
     :message, :model, :usage, optional :tool-calls.")

  (chat-stream
    [this request on-chunk]
    "Streaming LLM call. Calls on-chunk per text delta as it arrives.
     Returns the final accumulated response (same shape as chat).")

  (followup-messages
    [this request response tool-calls tool-results]
    "Build the next iteration's :messages vector for the tool loop in
     this api's wire format. Used by isaac.llm.tool-loop/run
     between chat iterations.")

  (config
    [this]
    "Return the bound provider's raw (kebab-case) config map. Used for
     introspection — e.g. `:stream-supports-tool-calls`. The wire
     format used for outbound calls is kept inside the deftype.")

  (display-name
    [this]
    "Name string of the provider that owns this api instance —
     `anthropic`, `chatgpt`, `grover:openai`, etc. Used for
     log lines and observability.")

  (build-prompt
    [this opts]
    "Build a prompt request map for this api from turn opts.
     opts keys: :boot-files :model :soul :transcript :tools :context-window.
     Returns a map with :model :messages and optionally :system :max_tokens :tools.")

  (format-tools
    [this tools]
    "Format tool definitions into this api's wire shape. Returns nil for empty/nil input."))

;; --- Response Schema ---
;;
;; Every Api's chat and chat-stream returns one of two shapes:
;;
;;   Success: a Response map (see below)
;;   Failure: an Error map ({:error keyword :message? string :status? int})
;;
;; Callers (tool-loop, dispatch logging, turn.clj) check `(:error response)`
;; first to disambiguate. A Response carries the parsed assistant message,
;; the model that produced it, normalized usage, and (when present) the
;; tool calls the model wants Isaac to run.

(def tool-call
  {:name        :tool-call
   :type        :map
   :description "Normalized tool call, provider-agnostic. The :raw field
                 preserves provider-specific wire payload when present
                 (e.g., Ollama's :function map) for round-tripping."
   :schema      {:id        {:type :string :description "Stable id; UUID-ish for providers without one"}
                 :name      {:type :string :description "Tool name to invoke"}
                 :arguments {:type :ignore :description "Parsed args (map). Coerced to map by the provider."}
                 :raw       {:type :ignore :description "Optional pass-through of the original wire payload"}}})

(def usage
  {:name        :usage
   :type        :map
   :description "Normalized token accounting"
   :schema      {:input-tokens  {:type :int :description "Prompt-side token count"}
                 :output-tokens {:type :int :description "Completion-side token count"}
                 :cache-read     {:type :int :description "Tokens served from prompt cache (Anthropic)"}
                 :cache-write    {:type :int :description "Tokens written to prompt cache (Anthropic)"}}})

(def assistant-message
  {:name        :assistant-message
   :type        :map
   :description "The assistant's reply, in a wire shape close to OpenAI's.

                 NOTE on snake_case: :tool_calls is intentionally NOT kebab-cased.
                 It carries the provider's native wire format (OpenAI's tool_calls
                 array, Anthropic's tool_use blocks adapted, etc.) so it can be
                 round-tripped into the next request body unchanged. The outer
                 Response :tool-calls (kebab-case) is the normalized form for
                 iteration; this :tool_calls is for wire faithfulness."
   :schema      {:role       {:type :string :description "Always \"assistant\" for chat returns"}
                 :content    {:type :ignore :description "String, or empty when the turn is purely tool-using"}
                 :tool_calls {:type :ignore :description "Optional. Provider-native wire shape (kept for followup-messages)."}}})

(def response
   {:name        :api-response
   :type        :map
   :description "Successful return shape from Api/chat and Api/chat-stream.
                 Errors are returned as a separate {:error _ :message? _} map.

                 NOTE on the leading underscore: :_headers follows the Clojure
                 convention of marking diagnostic / non-canonical fields. It
                 carries the raw HTTP response headers when present, useful
                 for rate-limit debugging and incident triage. Production code
                 should not branch on it; it's there for the human reading logs."
   :schema      {:message    assistant-message
                 :model      {:type :string :description "Model id the provider chose to record on this response"}
                 :tool-calls {:type :seq :spec tool-call
                              :description "Normalized tool calls, empty/absent when none"}
                 :usage      usage
                 :_headers   {:type :ignore :description "Optional raw response headers, for diagnostics only"}}})

(def error-response
   {:name        :api-error
   :type        :map
   :description "Failure return shape. :error is a keyword (:auth-missing,
                 :auth-failed, :connection-refused, :llm-error, :unknown,
                 :timeout, etc.). Callers branch on (:error response)."
   :schema      {:error   {:type :keyword :description "Error category"}
                 :message {:type :string  :description "Human-readable detail"}
                 :status  {:type :int     :description "HTTP status when applicable"}
                 :body    {:type :ignore  :description "Optional raw error body from the provider"}}})

(defn error?
  "True when `response` is an Api error rather than a successful Response."
  [response]
  (some? (:error response)))

(defn validate-response
  "Validate an Api response against the response schema. Returns the
   value unchanged on success, or throws with a structured error. Use in
   debug or test paths — production code branches on `error?` and reads
   fields directly without coercion."
  [value]
  (schema/conform! response value))

(declare ->api)

(defn resolve-api
  "Resolve a (name, config) to its api keyword, or nil if unknown.
   Expects `provider-config` to already include provider defaults — callers
   should run it through `isaac.llm.provider/normalize-pair` first."
  [provider provider-config]
  (some-> (or (:api provider-config)
              (cond
                (= provider "grover")                            "grover"
                (str/starts-with? (or provider "") "anthropic")  "messages"
                (= provider "ollama")                            "ollama"
                :else                                            nil))
          ->api))

;; --- Registry ---
;;
;; Each provider implementation registers itself by api keyword. The factory
;; takes (name, raw-cfg) and returns an Api instance. Built-in providers
;; self-register at namespace load time; third parties do the same in their
;; own namespace.

(defonce ^:private -registry (atom {}))
(defonce ^:private -built-in-keys (atom #{}))

(defn- ->api [provider-key]
  (cond
    (keyword? provider-key) provider-key
    (string? provider-key)  (keyword provider-key)
    :else                   provider-key))

(defn register!
  "Register an Api factory under the given provider-key keyword.
   factory: (fn [name cfg] -> Api)
   Returns the provider-key keyword for chaining."
  [provider-key factory]
  (let [provider-key (->api provider-key)]
    (swap! -registry assoc provider-key factory)
    (log/info :api/registered :api (clojure.core/name provider-key))
    provider-key))

(defn register-api-entry!
  "Per-entry factory for the :isaac.server/llm-api berth (phase 7 of
   the berth epic). Receives `[api-id entry]`; resolves the entry's
   symbol-valued :factory and registers it under api-id."
  [[api-id entry]]
  (register! api-id (some-> (:factory entry) requiring-resolve var-get)))

(defn mark-built-ins!
  "Snapshot the current registry as the built-in set. Called once after all
   built-in providers have registered so module registrations can be cleared
   separately between tests without disturbing the built-ins."
  []
  (reset! -built-in-keys (set (keys @-registry))))

(defn clear-module-registrations!
  "Remove all api factories that were registered after the built-in snapshot.
   Called by module-loader/clear-activations! between feature-test scenarios."
  []
  (swap! -registry #(select-keys % (seq @-built-in-keys))))

((requiring-resolve 'isaac.module.loader/register-handler!)
 :clear-registrations clear-module-registrations!)

(defn unregister!
  "Remove the factory registered for `provider-key`."
  [provider-key]
  (let [provider-key (->api provider-key)]
    (swap! -registry dissoc provider-key)
    provider-key))

(defn factory-for
  "Return the factory registered for `provider-key`, or nil if none."
  [provider-key]
  (get @-registry (->api provider-key)))

(defn registered-apis
  "Return the set of provider keywords that have a factory registered."
  []
  (set (keys @-registry)))

;; --- Tool Shape Helpers ---

(defn wrapped-function-tool
  "Standard OpenAI Chat-Completions / Ollama tool shape: `{:type \"function\"
   :function {:name ..., :description ..., :parameters ...}}`."
  [tool]
  {:type     "function"
   :function {:name        (:name tool)
              :description (:description tool)
              :parameters  (:parameters tool)}})

(defn flat-function-tool
  "OpenAI Responses-API tool shape: `{:type \"function\" :name ...,
   :description ..., :parameters ...}`."
  [tool]
  {:type        "function"
   :name        (:name tool)
   :description (:description tool)
   :parameters  (:parameters tool)})

;; --- Compaction Utilities ---

(defn estimate-tokens
  "Estimate token count using chars/4 heuristic."
  [request]
  (let [text (str request)]
    (max 1 (quot (count text) 4))))

(defn build-summary-request
  "Build a compaction summary request for the given api instance. When `api`
   is nil (test fallback / api-less call sites), tools are emitted in the
   standard wrapped chat-completions shape."
  [api model system-prompt messages tool-defs]
  {:model    model
   :messages [{:role "system" :content system-prompt}
              {:role "user"   :content (pr-str messages)}]
   :tools    (if api
               (format-tools api tool-defs)
               (when (seq tool-defs) (mapv wrapped-function-tool tool-defs)))})

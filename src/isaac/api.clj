(ns isaac.api
  (:require
    [isaac.bridge.core :as bridge-impl]
    [isaac.comm.protocol :as comm-impl]
    [isaac.comm.registry :as comm-registry]
    [isaac.reconfigurable :as reconfigurable]
    [isaac.llm.api.protocol :as api-impl]
    [isaac.nexus :as nexus]
    [isaac.session.store.spi :as session-store]))

(def Comm
  "Protocol implemented by comm integrations (Discord, Telly, etc.).
   Implement to receive lifecycle callbacks for turns, tool calls, compaction,
   and errors. See isaac.comm.protocol/Comm for the full method list."
  comm-impl/Comm)

(def Reconfigurable
  "Protocol implemented by long-running module instances.
   on-startup! is called when the instance is first started;
   on-config-change! is called on every config reload.
   See isaac.reconfigurable/Reconfigurable for method signatures."
  reconfigurable/Reconfigurable)

(defn register-comm!
  "Register a Comm factory under impl-name.
   factory is (fn [host-map] -> Comm-instance) where host-map contains
   :root and :connect-ws!.
   Returns impl-name (normalised to a string). Side-effects the global registry."
  [impl-name factory]
  (comm-registry/register-factory! impl-name factory))

(defn comm-registered?
  "Return true if a Comm factory is registered under impl-name, false otherwise."
  [impl-name]
  (comm-registry/registered? impl-name))

(defn register-provider!
  "Register an Api factory by name (e.g. \"ollama\", \"anthropic\").
   factory is (fn [name cfg] -> Api).
   Returns api-key. Side-effects the global provider registry."
  [api-key factory]
  (api-impl/register! api-key factory))

(defn create-session!
  "Create (or reopen) a session record.
   identifier may be a session name string or an existing session map.
   opts may include :crew, :origin, :chatType, :channel, :cwd.
   Returns the session map."
  ([identifier]
   (session-store/open-session! (session-store/registered-store) identifier {}))
  ([identifier opts]
   (let [store        (or (:session-store opts) (session-store/registered-store))
         session-opts (dissoc opts :root :session-store)]
     (session-store/open-session! store identifier session-opts)))
  ([root identifier opts]
   (session-store/open-session! (session-store/create root) identifier opts)))

(defn get-session
  "Return the session map for identifier, or nil if not found.
   identifier may be a session name string, key string, or session map."
  ([identifier]
   (session-store/get-session (session-store/registered-store) identifier))
  ([root identifier]
   (session-store/get-session (session-store/create root) identifier)))

(defn dispatch!
  "Comm-facing entry point for inbound messages. Triage slash commands,
   then delegate normal turns to the bridge dispatcher.
   request must have :session-key and :input; see bridge/dispatch! for full shape."
  ([request]
   (bridge-impl/dispatch! (merge (nexus/necho) request)))
  ([root request]
   (bridge-impl/dispatch! root request)))

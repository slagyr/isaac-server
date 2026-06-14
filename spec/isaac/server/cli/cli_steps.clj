(ns isaac.server.cli.cli-steps
  "Server/LLM-flavored CLI feature steps layered on the foundation run
   wrapper (isaac.foundation.cli-steps). Owns the comm-neutral `reply`
   assertions (which read bridge state), the background run, and the
   registrations that extend `isaac is run with` with provider/HTTP/drive
   capture and the memory-tool clock — all via the foundation hook
   registries, so the foundation namespace stays server-free."
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defthen defwhen helper!]]
    [isaac.drive.dispatch :as drive-dispatch]
    [isaac.foundation.cli-steps :as fcli]
    [isaac.config.root :as root]
    [isaac.bridge.status :as bridge]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.http :as llm-http]
    [isaac.main :as main]
    [isaac.tool.memory :as memory]
    [isaac.nexus :as nexus]))

(helper! isaac.server.cli.cli-steps)

(defn- current-reply []
  (let [result (g/get :llm-result)]
    (or (when-let [message (:message result)]
          (cond
            (string? message) message
            (map? message)    (:content message)
            :else             nil))
        (when (= :status (:command result))
          (bridge/format-status (:data result)))
        (:content result)
        (get-in result [:response :message :content])
        (fcli/current-output)
        "")))

(defn- with-current-time [f]
  (if-let [current-time (g/get :current-time)]
    (binding [memory/*now* current-time]
      (f))
    (f)))

(defn isaac-run-background [args]
  (let [argv          (fcli/parse-argv args)
         root-dir      (g/get :root)
         extra-opts    (cond-> {}
                         root-dir (assoc :root root-dir))
         mem-fs        (g/get :mem-fs)
         output-writer (java.io.StringWriter.)
         error-writer  (java.io.StringWriter.)]
    (g/assoc! :live-output-writer output-writer)
    (g/assoc! :live-error-writer error-writer)
    (future
       (let [run! (fn [run-opts]
                    (binding [*out* output-writer
                              *err* error-writer
                              root/*user-home* (or (g/get :user-home) root/*user-home*)]
                      (let [code (with-current-time
                                   #(if (seq run-opts)
                                      (binding [main/*extra-opts* run-opts]
                                        (main/run argv))
                                      (main/run argv)))]
                        (g/assoc! :exit-code code))))]
        (if mem-fs
          (nexus/-with-nested-nexus {:fs mem-fs}
            (run! (assoc extra-opts :fs mem-fs)))
          (run! extra-opts))))))

(defn reply-contains [expected]
  (let [expected (fcli/unescape-expected expected)
        output   (fcli/await-text current-reply #(str/includes? % expected))]
    (g/should (str/includes? output expected))))

(defn reply-matches [table]
  (let [output   (or (current-reply) "")
        patterns (fcli/extract-patterns table)]
    (doseq [pattern patterns]
      (g/should (re-find (re-pattern pattern) output)))))

(defn reply-does-not-contain [expected]
  (let [output   (current-reply)
        expected (fcli/unescape-expected expected)]
    (g/should-not (str/includes? (or output "") expected))))

;; Extend the foundation `isaac is run with` wrapper with the server/LLM
;; concerns: clear provider/HTTP/drive capture before the run (preflight),
;; harvest it after (postflight), and bind the memory-tool clock during
;; the run (wrapper). These registrations keep isaac.foundation.cli-steps
;; free of isaac.llm.* / isaac.drive.* / isaac.tool.memory.
(fcli/register-isaac-run-preflight!
  (fn []
    (grover/clear-provider-requests!)
    (llm-http/clear-outbound-requests!)
    (drive-dispatch/clear-last-request!)))

(fcli/register-isaac-run-postflight!
  (fn []
    (let [outbound-requests (or (seq (llm-http/outbound-requests))
                                (seq (grover/provider-requests)))
          outbound-requests (some-> outbound-requests vec)
          grover-request    (some-> (grover/last-request) (hash-map :body))]
      (g/assoc! :provider-request (or (last outbound-requests)
                                      (grover/last-provider-request)
                                      grover-request))
      (g/assoc! :outbound-http-requests outbound-requests)
      (g/assoc! :outbound-http-request (or (first outbound-requests)
                                           (grover/last-provider-request)
                                           grover-request)))
    (g/assoc! :llm-request (or (drive-dispatch/last-request)
                               (grover/last-request)))))

(fcli/register-isaac-run-wrapper!
  (fn [thunk]
    (if-let [ct (g/get :current-time)]
      (binding [memory/*now* ct] (thunk))
      (thunk))))

;; region ----- Routing -----

(defwhen "isaac is run in the background with {args:string}" isaac.server.cli.cli-steps/isaac-run-background
  "Runs 'isaac <args>' in a background thread. Binds a live StringWriter to
   *out* and stores it as :live-output-writer so 'the stdout eventually contains'
   can poll while the command is still running.")

(defthen "the reply contains {expected:string}" isaac.server.cli.cli-steps/reply-contains
  "Comm-neutral: polls up to 1s for the user-visible reply to contain
   the substring. Same underlying source as 'the stdout contains' today;
   the name distinction is semantic — use 'reply' in comm-agnostic
   scenarios (bridge/session/drive) and 'stdout' in CLI scenarios.")

(defthen "the reply matches:" isaac.server.cli.cli-steps/reply-matches
  "Comm-neutral regex match, same semantics as 'the stdout matches:'.")

(defthen "the reply does not contain {expected:string}" isaac.server.cli.cli-steps/reply-does-not-contain)

;; endregion ^^^^^ Routing ^^^^^

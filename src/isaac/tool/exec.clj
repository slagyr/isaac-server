;; mutation-tested: 2026-05-06
(ns isaac.tool.exec
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [isaac.bridge.cancellation :as bridge]
    [isaac.tool.fs-bounds :as bounds])
  (:import
    [java.util.concurrent TimeUnit]))

(def ^:private default-timeout 30000)

(defn start-process [args]
  ;; Spawn the process AND immediately start draining its merged
  ;; stdout+stderr in a background thread. Without the concurrent drain
  ;; any process that emits more than ~64KB of output before exit
  ;; (xcodebuild, large test runs) fills the OS pipe buffer, blocks
  ;; writing, and Process.waitFor never returns — the whole tool call
  ;; hangs forever. Return shape is {:proc java.lang.Process :output future}
  ;; so the rest of the exec-tool pipeline can wait on the process and
  ;; deref the captured output.
  (let [command (get args "command")
        workdir (get args "workdir")
        pb      (doto (ProcessBuilder. ["/bin/sh" "-c" command])
                  (.redirectErrorStream true))]
    (when workdir
      (.directory pb (io/file workdir)))
    (let [proc (.start pb)]
      {:proc   proc
       :output (future (slurp (.getInputStream proc)))})))

(defn process-finished? [proc timeout-ms]
  (.waitFor (:proc proc) timeout-ms TimeUnit/MILLISECONDS))

(defn destroy-process!
  ([proc]
   (destroy-process! proc 100))
  ([proc grace-ms]
   (.destroy (:proc proc))
   (when-not (process-finished? proc grace-ms)
     (.destroyForcibly (:proc proc)))))

(defn read-process-output [proc]
  @(:output proc))

(defn process-exit-value [proc]
  (.exitValue (:proc proc)))

(defn- resolve-exec-args [args]
  (let [resolved (bounds/resolve-path
                    (get args "workdir")
                    (bounds/session-workdir args))]
    (cond-> args
      resolved (assoc "workdir" resolved))))

(defn- exec-finished-result [proc]
  (let [output (read-process-output proc)
        exit   (process-exit-value proc)]
    (if (zero? exit)
      {:result (str/trim output)}
      {:isError true :error (str "exit " exit ": " (str/trim output))})))

(defn- wait-for-process! [proc session-key timeout-ms]
  (bridge/on-cancel! session-key #(destroy-process! proc))
  (cond
    (process-finished? proc timeout-ms)
    (if (bridge/cancelled? session-key)
      {:error :cancelled}
      (exec-finished-result proc))

    :else
    (do
      (destroy-process! proc 10)
      {:isError true :error "timeout exceeded"})))

(defn exec-tool
  "Execute a shell command.
   Args: command, workdir, timeout."
  [args]
  (let [args        (bounds/string-key-map args)
        session-key (get args "session_key")
        timeout-ms  (or (bounds/arg-int args "timeout" nil) default-timeout)
        args        (resolve-exec-args args)]
    (try
      (wait-for-process! (start-process args) session-key timeout-ms)
      (catch Exception e
        {:isError true :error (.getMessage e)}))))

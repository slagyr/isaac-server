;; mutation-tested: 2026-05-06
(ns isaac.tool.builtin
  (:require
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.tool.exec :as exec]
    [isaac.tool.file :as file]
    [isaac.tool.glob :as glob]
    [isaac.tool.grep :as grep]
    [isaac.tool.memory :as memory]
    [isaac.tool.session :as session]
    [isaac.tool.web-fetch :as web-fetch]
    [isaac.tool.web-search :as web-search]))

;; region ----- Registration -----

(def ^:private ordered-built-in-tools
  ["read" "write" "edit" "grep" "glob" "web_fetch" "web_search" "memory_write" "memory_get" "memory_search" "exec" "session_info" "session_model" "load_skill" "list_skills" "hail-send"])

(def ^:private built-in-tool-specs
  {"read"          {:name        "read"
                     :description "Read file contents or list a directory"
                     :parameters  {:type       "object"
                                   :properties {"file_path" {:type "string" :description "Path to file or directory"}
                                                "offset"    {:type "integer" :description "Start line (1-indexed)"}
                                                "limit"     {:type "integer" :description "Max lines to return"}}
                                   :required   ["file_path"]}
                     :handler     #'file/read-tool}
   "write"         {:name        "write"
                     :description "Write content to a file"
                     :parameters  {:type       "object"
                                   :properties {"file_path" {:type "string" :description "Path to write"}
                                                "content"   {:type "string" :description "Content to write"}}
                                   :required   ["file_path" "content"]}
                     :handler     #'file/write-tool}
   "edit"          {:name        "edit"
                     :description "Replace text in a file"
                     :parameters  {:type       "object"
                                   :properties {"file_path"   {:type "string" :description "File to edit"}
                                                "old_string"  {:type "string" :description "Text to replace"}
                                                "new_string"  {:type "string" :description "Replacement text"}
                                                "replace_all" {:type "boolean" :description "Replace all occurrences"}}
                                   :required   ["file_path" "old_string" "new_string"]}
                     :handler     #'file/edit-tool}
   "grep"          {:name        "grep"
                     :description "Search file contents with ripgrep"
                     :parameters  {:type       "object"
                                   :properties {"pattern"     {:type "string" :description "Regex pattern to search for"}
                                                "path"        {:type "string" :description "File or directory to search"}
                                                "glob"        {:type "string" :description "Optional file glob filter"}
                                                "type"        {:type "string" :description "Optional file type shorthand"}
                                                "-i"          {:type "boolean" :description "Case-insensitive search"}
                                                "-n"          {:type "boolean" :description "Include line numbers in content mode"}
                                                "-A"          {:type "integer" :description "Context lines after each match"}
                                                "-B"          {:type "integer" :description "Context lines before each match"}
                                                "-C"          {:type "integer" :description "Context lines before and after each match"}
                                                "multiline"   {:type "boolean" :description "Enable multiline matching"}
                                                "output_mode" {:type "string" :description "content, files_with_matches, or count"}
                                                "head_limit"  {:type "integer" :description "Maximum rows to return; 0 means unlimited"}
                                                "offset"      {:type "integer" :description "Rows to skip before returning results"}}
                                   :required   ["pattern" "path"]}
                     :available?  #(grep/available?)
                     :handler     #'grep/grep-tool}
   "glob"          {:name        "glob"
                     :description "List files matching a glob pattern"
                     :parameters  {:type       "object"
                                   :properties {"pattern"    {:type "string" :description "Glob pattern to match"}
                                                "path"       {:type "string" :description "Directory to search; defaults to cwd or root"}
                                                "head_limit" {:type "integer" :description "Maximum rows to return"}}
                                   :required   ["pattern"]}
                     :handler     #'glob/glob-tool}
   "web_fetch"     {:name        "web_fetch"
                     :description "Fetch URL content via HTTP GET"
                     :parameters  {:type       "object"
                                   :properties {"url"     {:type "string" :description "HTTP or HTTPS URL to fetch"}
                                                "format"  {:type "string" :description "text or raw"}
                                                "timeout" {:type "integer" :description "Timeout in milliseconds"}}
                                   :required   ["url"]}
                     :handler     #'web-fetch/web-fetch-tool}
   "web_search"    {:name        "web_search"
                     :description "Search the web via Brave Search"
                     :parameters  {:type       "object"
                                   :properties {"query"       {:type "string" :description "Search query"}
                                                "num_results" {:type "integer" :description "Maximum results to return"}}
                                   :required   ["query"]}
                     :handler     #'web-search/web-search-tool}
   "memory_write"  {:name        "memory_write"
                     :description "Append content to today's crew memory note"
                     :parameters  {:type       "object"
                                   :properties {"content" {:type "string" :description "Text to append"}}
                                   :required   ["content"]}
                     :handler     #'memory/memory-write-tool}
   "memory_get"    {:name        "memory_get"
                     :description "Read crew memory notes in an inclusive date range"
                     :parameters  {:type       "object"
                                   :properties {"start_time" {:type "string" :description "Start date YYYY-MM-DD"}
                                                "end_time"   {:type "string" :description "End date YYYY-MM-DD"}}
                                   :required   ["start_time" "end_time"]}
                     :handler     #'memory/memory-get-tool}
   "memory_search" {:name        "memory_search"
                     :description "Search crew memory notes"
                     :parameters  {:type       "object"
                                   :properties {"query" {:type "string" :description "Regex query to search for"}}
                                   :required   ["query"]}
                     :handler     #'memory/memory-search-tool}
   "exec"          {:name        "exec"
                     :description "Execute a shell command"
                     :parameters  {:type       "object"
                                   :properties {"command" {:type "string" :description "Command to run"}
                                                "workdir" {:type "string" :description "Working directory"}
                                                "timeout" {:type "integer" :description "Timeout in ms"}}
                                   :required   ["command"]}
                     :handler     #'exec/exec-tool}
   "session_info"  {:name        "session_info"
                     :description "Report the current session's crew, model, provider, origin, timing, context, and compaction count"
                     :parameters  {:type "object" :properties {}}
                     :handler     #'session/session-info-tool}
   "session_model" {:name        "session_model"
                    :description "Switch or reset the calling session's model; returns new session state"
                    :parameters  {:type       "object"
                                  :properties {"model" {:type "string" :description "Model alias to switch to"}
                                               "reset" {:type "boolean" :description "Revert to crew's default model"}}
                                  :required   []}
                    :handler     #'session/session-model-tool}})

(defn- spec-for [tool-name]
  (some-> (get built-in-tool-specs tool-name)
          (dissoc :name :available?)))

(defn read-tool-factory [_] (spec-for "read"))
(defn write-tool-factory [_] (spec-for "write"))
(defn edit-tool-factory [_] (spec-for "edit"))
(defn grep-tool-factory [_] (spec-for "grep"))
(defn glob-tool-factory [_] (spec-for "glob"))
(defn web-fetch-tool-factory [_] (spec-for "web_fetch"))
(defn web-search-tool-factory [_] (spec-for "web_search"))
(defn memory-write-tool-factory [_] (spec-for "memory_write"))
(defn memory-get-tool-factory [_] (spec-for "memory_get"))
(defn memory-search-tool-factory [_] (spec-for "memory_search"))
(defn exec-tool-factory [_] (spec-for "exec"))
(defn session-info-tool-factory [_] (spec-for "session_info"))
(defn session-model-tool-factory [_] (spec-for "session_model"))

(defn- normalize-allowed-tools [allowed-tools]
  (when-not (= ::all allowed-tools)
    (some->> allowed-tools
             (map (fn [tool]
                    (cond
                      (keyword? tool) (name tool)
                      (string? tool)  tool
                      :else           (str tool))))
             set)))

(defn- allowed-tool? [allowed-tools normalized tool-name]
  (or (= ::all allowed-tools)
      (boolean (and normalized (contains? normalized tool-name)))))

(defn- register-built-in-tool! [tool-name]
  (when-let [spec (get built-in-tool-specs tool-name)]
    (if-let [pred (:available? spec)]
      (if (pred)
        (module-loader/register-builtin-berth-entry! :isaac.server/tools tool-name)
        (log/warn :tool/register-skipped :tool tool-name :reason "available? returned false"))
      (module-loader/register-builtin-berth-entry! :isaac.server/tools tool-name))))

(defn register-all!
  "Register all built-in tools with the tool registry.
   With 0-arity, registers every built-in tool.
   With 1-arity, registers only the tools in the allow list (nil registers none)."
  ([] (register-all! ::all))
  ([allowed-tools]
   (let [normalized (normalize-allowed-tools allowed-tools)]
     (doseq [tool-name ordered-built-in-tools]
       (when (allowed-tool? allowed-tools normalized tool-name)
         (register-built-in-tool! tool-name))))))

;; endregion ^^^^^ Registration ^^^^^

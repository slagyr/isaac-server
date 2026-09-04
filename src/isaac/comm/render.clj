(ns isaac.comm.render
  "Presentation helpers for Comm text chunks.

  Ground-level formatters (bridge/status, slash :message strings) emit plain
  text. When a reply is a fixed-width block (e.g. /status), the bridge tags the
  on-text-chunk payload so markdown-oriented comms can wrap without guessing:

    {:isaac.comm/text \"...\" :isaac.comm/format :preformatted}

  CLI and other raw clients call `chunk-text` and print as-is. Markdown clients
  call `present-for-markdown` to optionally fence preformatted blocks.")

(def ^:private text-key :isaac.comm/text)
(def ^:private format-key :isaac.comm/format)

(def preformatted :preformatted)

(defn preformatted-chunk [text]
  {text-key text format-key preformatted})

(defn chunk-text
  "Plain text from a string chunk or a tagged preformatted chunk."
  [chunk]
  (cond
    (string? chunk)     chunk
    (map? chunk)        (str (get chunk text-key ""))
    :else               (str chunk)))

(defn chunk-format [chunk]
  (when (map? chunk)
    (get chunk format-key)))

(defn preformatted? [chunk]
  (= preformatted (chunk-format chunk)))

(defn wrap-preformatted
  "Fence a plain block for markdown clients."
  [text]
  (str "```text\n" text "\n```"))

(defn present-for-markdown
  "Render a chunk for markdown-capable surfaces. Preformatted blocks are fenced;
   normal streaming text passes through unchanged."
  [chunk]
  (let [text (chunk-text chunk)]
    (if (and (seq text) (preformatted? chunk))
      (wrap-preformatted text)
      text)))
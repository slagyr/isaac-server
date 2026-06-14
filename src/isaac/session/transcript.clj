(ns isaac.session.transcript)

(defn content->text [content]
  (cond
    (string? content)
    content

    (and (vector? content) (every? map? content))
    (->> content
         (filter #(= "text" (:type %)))
         (map :text)
         (apply str))

    :else
    nil))

(defn tool-calls [message]
  (cond
    (= "toolCall" (:type message))
    [{:type "toolCall" :id (:id message) :name (:name message) :arguments (:arguments message)}]

    (vector? (:content message))
    (let [calls (filterv #(= "toolCall" (:type %)) (:content message))]
      (when (seq calls) calls))

    :else
    nil))

(defn first-tool-call [message]
  (first (tool-calls message)))

(defn truncate-tool-result
  "Truncate a tool result string using head-and-tail strategy.
   max-chars defaults to 30% of context-window * 4 (chars per token estimate)."
  [content context-window]
  (let [max-chars (int (* 0.3 context-window 4))
        len       (count content)]
    (if (<= len max-chars)
      content
      (let [half (quot max-chars 2)
            head (subs content 0 half)
            tail (subs content (- len half))]
        (str head "\n\n... [" (- len max-chars) " characters truncated] ...\n\n" tail)))))

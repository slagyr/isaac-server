(ns isaac.llm.followup)

(defn map-tool-results [tool-calls tool-results f]
  (mapv f tool-calls tool-results))

(defn append-followup-messages [request assistant-msg result-msgs]
  (into (conj (vec (:messages request)) assistant-msg) result-msgs))

(defn raw-tool-call-followup-messages [request assistant-msg tool-calls tool-results]
  (let [result-msgs (map-tool-results tool-calls tool-results
                                      (fn [_tc result]
                                        {:role    "tool"
                                         :content result}))]
    (append-followup-messages request assistant-msg result-msgs)))

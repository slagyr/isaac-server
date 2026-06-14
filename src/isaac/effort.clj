(ns isaac.effort)

(def default-effort 7)

(defn effort->string [n]
  (cond
    (nil? n)  nil
    (zero? n) nil
    (<= n 3)  "low"
    (<= n 6)  "medium"
    :else     "high"))

(defn resolve-effort
  "Resolves effort integer from the chain: session > crew > model > provider > defaults > 7.
   Each map may contain an :effort key. Returns the first non-nil value or 7."
  [session crew-cfg model-cfg provider-cfg defaults]
  (or (:effort session)
      (:effort crew-cfg)
      (:effort model-cfg)
      (:effort provider-cfg)
      (:effort defaults)
      default-effort))

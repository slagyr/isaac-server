(ns isaac.hail.bands
  (:require
    [isaac.reconfigurable :as reconfigurable]))

(defprotocol BandRegistry
  (lookup [this band-name])
  (all-bands [this]))

(defn- with-band-defaults [band]
  (cond-> band
    (and band (nil? (:reach band))) (assoc :reach :one)))

(deftype HailBands [bands*]
  BandRegistry
  (lookup [_ band-name]
    (get @bands* band-name))
  (all-bands [_]
    @bands*)

  reconfigurable/Reconfigurable
  (on-startup! [_ slice]
    (reset! bands* (into {} (map (fn [[band-name band]] [band-name (with-band-defaults band)])) (or slice {}))))
  (on-config-change! [_ _old-slice new-slice]
    (reset! bands* (into {} (map (fn [[band-name band]] [band-name (with-band-defaults band)])) (or new-slice {})))))

(defn make [_host]
  (->HailBands (atom {})))

(def registry
  {:kind    :component
   :path    [:hail]
   :impl    "hail-bands"
   :factory make})

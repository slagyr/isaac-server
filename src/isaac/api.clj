(ns isaac.api
  "Thin compatibility surface for comm modules extracted from the monolith.
   Re-exports the protocols third-party comm modules expect."
  (:require
    [isaac.comm.protocol :as comm-impl]
    [isaac.reconfigurable :as reconfigurable]))

(def Comm comm-impl/Comm)

(def Reconfigurable reconfigurable/Reconfigurable)
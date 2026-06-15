(ns isaac.service.fixture.events)

(defonce timeline (atom []))

(defn record! [service-id event]
  (swap! timeline conj [(keyword (name service-id)) event]))

(defn clear! []
  (reset! timeline []))

(defn events []
  @timeline)
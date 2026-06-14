(ns isaac.config.check-contributions
  "Canonical :isaac.config/check contribution map for the agent module.
   modules/isaac.agent/resources/isaac-manifest.edn must stay aligned with
   this data.")

(def checks
  {:comm-reserved-schema {:fn 'isaac.config.checks/check-comm-reserved-schema}
   :manifest-refs         {:fn 'isaac.config.checks/check-manifest-refs}
   :resolved-providers    {:fn 'isaac.config.checks/check-resolved-providers}})

(def server checks)
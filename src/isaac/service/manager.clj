(ns isaac.service.manager
  "The per-platform seam behind `isaac service`. One implementor per service
   manager (launchd on macOS, systemd on Linux); `isaac.service.cli` picks the
   implementor from the OS name and never talks to launchctl/systemctl itself.")

(defprotocol Manager
  (service-name [this]
    "The name the platform knows the service by (launchd label, systemd unit).")
  (install! [this opts]
    "Write the service definition and enable it. `opts` carries :mode
     (:packaged/:dev), :isaac-bin, :bb-bin, :bb-edn, :root, :runtime, :path and
     an optional :fs. Returns a map; Linux adds :linger? (false when the user
     session will not outlive logout).")
  (uninstall! [this opts]
    "Disable the service and remove its definition. Idempotent.")
  (start! [this opts])
  (stop! [this opts])
  (restart! [this opts])
  (status! [this opts]
    "Returns {:installed? false} or {:installed? true :state .. :pid .. :last-exit .. :runtime ..}.")
  (logs! [this opts]
    "Returns {:log-path .. :content ..}; with :follow? true streams via tail -f
     and returns nil :content."))

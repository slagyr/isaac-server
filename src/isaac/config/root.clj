(ns isaac.config.root
  "Bootstrap root resolution: where Isaac keeps config and state on disk.
   Sibling to config.paths / config.nav — requires only fs and logger, not
   config.loader.

   After the isaac-root collapse, --root <dir> points at the data directory
   directly; the only place the .isaac literal survives is as the
   default-root value when nothing else is provided.

   Lookup chain (first hit wins):
     1. --root <dir>            CLI flag (new, preferred)
     2. fallback                test-injection slot
     3. ISAAC_ROOT              environment variable
     4. ~/.config/isaac.edn     {:root \"/some/dir\"}
     5. ~/.isaac.edn            {:root \"/some/dir\"}
     6. ~/.isaac                default."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.logger :as log]))

(def ^:dynamic *root* nil)
(def ^:dynamic *user-home* nil)

(defonce ^:private process-root* (atom nil))

(defn user-home []
  (or *user-home* (System/getProperty "user.home")))

(defn default-root
  "The default Isaac root. No args: ~/.isaac. A string home: <home>/.isaac.
   A CLI opts map: :root wins, else :home (or user-home) + /.isaac."
  ([] (str (user-home) "/.isaac"))
  ([home-or-opts]
   (if (map? home-or-opts)
     (or (:root home-or-opts)
         (default-root (or (:home home-or-opts) (user-home))))
     (str home-or-opts "/.isaac"))))

(defn current-root
  "Returns the currently-active Isaac root. Thread-local binding wins,
   then the process-wide value set by init-root!, then the default."
  []
  (or *root* @process-root* (default-root)))

(defn init-root!
  "Sets the process-wide root. Called at server boot so all threads can
   reach the root without explicit threading."
  [dir]
  (reset! process-root* dir))

(defn- absolute-path [path]
  (if (and (string? path) (str/starts-with? path "/"))
    path
    (str (System/getProperty "user.dir") "/" path)))

(defn- expand-tilde [path]
  (cond
    (not (string? path))         path
    (= "~" path)                 (user-home)
    (str/starts-with? path "~/") (str (user-home) (subs path 1))
    :else                        path))

(defn- pointer-value [path fs*]
  (when (fs/exists? fs* path)
    (try
      (let [data (edn/read-string (fs/slurp fs* path))
            r    (:root data)]
        (when (string? r)
          (expand-tilde r)))
      (catch Exception _
        (log/warn :root/pointer-file-invalid :path path)
        nil))))

(defn- pointer-root [fs*]
  (or (pointer-value (str (user-home) "/.config/isaac.edn") fs*)
      (pointer-value (str (user-home) "/.isaac.edn") fs*)))

(defn- env-root []
  (let [v (System/getenv "ISAAC_ROOT")]
    (when-not (str/blank? v) v)))

(defn resolve-root
  "Walks the lookup chain. `explicit-root` is the --root value (or nil);
   `fallback-root` is a test-injection slot (or nil). Returns an absolute
   path."
  [explicit-root fallback-root fs*]
  (-> (or explicit-root
          fallback-root
          (env-root)
          (pointer-root fs*)
          (default-root))
      absolute-path))
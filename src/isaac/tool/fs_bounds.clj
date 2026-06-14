;; mutation-tested: 2026-05-06
(ns isaac.tool.fs-bounds
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.session.store.spi :as store]
    [isaac.session.store.sidecar :as sidecar-store]
    [isaac.nexus :as nexus])
  (:import
    [java.io File]))

(defn canonical-path [path]
  (.getCanonicalPath (io/file path)))

(defn path-inside? [parent child]
  (let [parent (canonical-path parent)
        child  (canonical-path child)]
    (or (= parent child)
        (str/starts-with? child (str parent File/separator)))))

(defn config-directories [root]
  #{(str root "/config")})

(defn crew-quarters [root crew-id]
  (str root "/crew/" crew-id))

(defn string-key-map [m]
  (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]) m)))

(defn filesystem [args]
  (let [args (string-key-map args)]
    (or (get args "fs")
        (fs/instance)
        (throw (ex-info "fs-bounds requires :fs in args or system" {})))))

(defn root [args]
  (let [args (string-key-map args)]
    (or (get args "state_dir")
        (loader/root))))

(defn session-store [args]
  (let [args      (string-key-map args)
        root (root args)]
    (or (get args "session_store")
        (nexus/get-in [:sessions :store])
        (when root
          (sidecar-store/create-store root (filesystem args))))))

(defn arg-bool [args k default]
  (let [value (get args k)]
    (cond
      (nil? value)     default
      (boolean? value) value
      (string? value)  (= "true" (str/lower-case value))
      :else            (boolean value))))

(defn arg-int [args k default]
  (let [value (get args k)]
    (cond
      (nil? value)     default
      (integer? value) value
      (string? value)  (parse-long value)
      :else            default)))

(defn session-workdir
  "Return the session's cwd as a string if it exists as a directory, else nil."
  [session-key-or-args]
  (let [args        (if (map? session-key-or-args)
                      (string-key-map session-key-or-args)
                      {"session_key" session-key-or-args})
        session-key (get args "session_key")
        store       (session-store args)]
    (when (and session-key store)
      (when-let [cwd (:cwd (store/get-session store session-key))]
        ;; Exec and grep/glob operate on the host filesystem, so session cwd must
        ;; still be a real OS directory here even when other tool paths use an
        ;; explicit isaac.fs implementation.
        (when (.isDirectory (io/file cwd))
          cwd)))))

(defn resolve-path
  "Resolve a path against session-cwd:
   nil/blank/'.' → session-cwd, relative → joined with session-cwd, absolute → as-is.
   Returns nil when both path is nil/blank and session-cwd is nil."
  [path session-cwd]
  (cond
    (or (nil? path) (str/blank? path) (= "." path)) session-cwd
    (.isAbsolute (io/file path))                      path
    session-cwd                                       (.getCanonicalPath (io/file session-cwd path))
    :else                                             path))

(defn allowed-directories [args]
  (let [args        (string-key-map args)
        fs*         (filesystem args)
        session-key (get args "session_key")
        root   (root args)
        store       (session-store args)]
    (when (and session-key root)
      (when-let [session (store/get-session store session-key)]
        (let [crew-id     (or (:crew session) "main")
              quarters    (crew-quarters root crew-id)
              _           (fs/mkdirs fs* quarters)
              cfg         (loader/snapshot "tool fs-bounds: crew tool directories")
              directories (or (get-in cfg [:crew crew-id :tools :directories]) [])]
          (vec (concat [quarters]
                       (keep (fn [directory]
                               (cond
                                 (= :cwd directory) (:cwd session)
                                 (= "cwd" directory) (:cwd session)
                                 (string? directory) directory
                                 :else nil))
                             directories))))))))

(defn path-outside-error [file-path]
  {:isError true :error (str "path outside allowed directories: " file-path)})

(defn ensure-path-allowed [args file-path]
  (when-let [directories (seq (allowed-directories args))]
    (let [root      (root args)
          denied-config? (some #(path-inside? % file-path) (config-directories root))]
      (when (or denied-config?
                (not-any? #(path-inside? % file-path) directories))
        (path-outside-error file-path)))))

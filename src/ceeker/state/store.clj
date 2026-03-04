(ns ceeker.state.store
  "Persistent State Store for ceeker sessions.
   Uses sessions.edn with file locking for concurrent access."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io File RandomAccessFile]
           [java.nio.channels FileLock]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]))

(defn state-dir
  "Returns the state directory path."
  []
  (let [xdg (System/getenv "XDG_RUNTIME_DIR")
        uid (System/getProperty "user.name")]
    (if (seq xdg)
      (str xdg "/ceeker")
      (str "/tmp/ceeker-" uid))))

(defn state-file-path
  "Returns the path to sessions.edn."
  ([] (state-file-path (state-dir)))
  ([dir] (str dir "/sessions.edn")))

(defn- reject-symlink!
  "Throws if the path is a symbolic link."
  [^File f]
  (when (Files/isSymbolicLink (.toPath f))
    (throw (ex-info "State directory is a symlink (rejected)"
                    {:path (.getAbsolutePath f)}))))

(defn- reject-non-directory!
  "Throws if the path exists but is not a directory."
  [^File f]
  (when (and (.exists f) (not (.isDirectory f)))
    (throw (ex-info "State path exists but is not a directory"
                    {:path (.getAbsolutePath f)}))))

(defn- validate-owner!
  "Throws if the directory is owned by another user."
  [^File f]
  (try
    (let [owner (-> (Files/getOwner
                     (.toPath f)
                     (make-array java.nio.file.LinkOption 0))
                    (.getName))
          current (System/getProperty "user.name")]
      (when (and owner current (not= owner current))
        (throw
         (ex-info "State directory owned by another user"
                  {:path (.getAbsolutePath f)
                   :owner owner
                   :expected current}))))
    (catch UnsupportedOperationException _ nil)))

(defn- validate-state-dir!
  "Validates that the state directory is safe to use."
  [^File f]
  (reject-symlink! f)
  (when (.exists f)
    (reject-non-directory! f)
    (validate-owner! f)))

(defn- create-state-dir!
  "Creates the state directory with secure permissions."
  [^File f]
  (.mkdirs f)
  (try
    (Files/setPosixFilePermissions
     (.toPath f)
     (PosixFilePermissions/fromString "rwx------"))
    (catch UnsupportedOperationException _ nil)))

(defn ensure-state-dir!
  "Creates the state directory if it doesn't exist."
  ([] (ensure-state-dir! (state-dir)))
  ([dir]
   (let [f (io/file dir)]
     (if (.exists f)
       (validate-state-dir! f)
       (create-state-dir! f)))))

(defn- read-state-file
  "Reads and parses the sessions.edn file."
  [path]
  (let [f (io/file path)]
    (if (and (.exists f) (pos? (.length f)))
      (edn/read-string (slurp f))
      {:sessions {}})))

(defn- write-state-file!
  "Writes state to sessions.edn file."
  [path state]
  (spit path (pr-str state)))

(defn with-file-lock
  "Executes f while holding an exclusive file lock."
  [dir f]
  (let [lock-path (str dir "/sessions.lock")
        _ (ensure-state-dir! dir)
        lock-file (RandomAccessFile. ^String lock-path "rw")
        channel (.getChannel lock-file)]
    (try
      (let [^FileLock lock (.lock channel)]
        (try
          (f)
          (finally
            (.release lock))))
      (finally
        (.close channel)
        (.close lock-file)))))

(defn read-sessions
  "Reads all sessions from the state store."
  ([] (read-sessions (state-dir)))
  ([dir]
   (ensure-state-dir! dir)
   (let [path (state-file-path dir)]
     (with-file-lock dir
       #(read-state-file path)))))

(defn- supersede-key
  "Returns the supersede key for a session, or nil if
   pane-id is empty (supersede disabled)."
  [session]
  (let [pane-id (:pane-id session)]
    (when (seq pane-id)
      [pane-id (:agent-type session) (:cwd session)])))

(defn- supersede-old-sessions
  "Closes running sessions that share the same supersede key
   as the new session, excluding the new session itself."
  [sessions session-id session-data now]
  (if-let [key (supersede-key session-data)]
    (reduce-kv
     (fn [m sid session]
       (assoc m sid
              (if (and (not= sid session-id)
                       (= :running (:agent-status session))
                       (= key (supersede-key session)))
                (merge session
                       {:agent-status :closed
                        :last-message "superseded"
                        :last-updated now})
                session)))
     {}
     sessions)
    sessions))

(defn- should-supersede?
  "Returns true if the incoming session data represents
   a running session that should trigger superseding."
  [session-data]
  (= :running (:agent-status session-data)))

(defn update-session!
  "Updates a session in the state store.
   Supersedes running sessions with the same pane key
   only when the incoming session is in running state."
  ([session-id session-data]
   (update-session! (state-dir) session-id session-data))
  ([dir session-id session-data]
   (ensure-state-dir! dir)
   (let [path (state-file-path dir)]
     (with-file-lock dir
       (fn []
         (let [state (read-state-file path)
               now (.toString (java.time.Instant/now))
               sessions (if (should-supersede?
                             session-data)
                          (supersede-old-sessions
                           (:sessions state)
                           session-id session-data now)
                          (:sessions state))
               existing (get sessions session-id {})
               updated (merge existing session-data)]
           (write-state-file!
            path
            {:sessions
             (assoc sessions
                    session-id updated)})))))))

(defn remove-session!
  "Removes a session from the state store."
  ([session-id]
   (remove-session! (state-dir) session-id))
  ([dir session-id]
   (ensure-state-dir! dir)
   (let [path (state-file-path dir)]
     (with-file-lock dir
       (fn []
         (let [state (read-state-file path)]
           (write-state-file!
            path
            (update state :sessions dissoc session-id))))))))

(defn clear-sessions!
  "Clears all sessions from the state store."
  ([] (clear-sessions! (state-dir)))
  ([dir]
   (ensure-state-dir! dir)
   (let [path (state-file-path dir)]
     (with-file-lock dir
       #(write-state-file! path {:sessions {}})))))

(defn- stale-running?
  "Returns true if session is running with a cwd
   not present in pane-cwds."
  [session pane-cwds]
  (and (= :running (:agent-status session))
       (seq (:cwd session))
       (not (contains? pane-cwds (:cwd session)))))

(defn- mark-stale-sessions
  "Returns updated sessions map with stale ones closed."
  [sessions pane-cwds now]
  (reduce-kv
   (fn [m sid session]
     (assoc m sid
            (if (stale-running? session pane-cwds)
              (merge session
                     {:agent-status :closed
                      :last-message "pane closed"
                      :last-updated now})
              session)))
   {}
   sessions))

(defn close-stale-sessions!
  "Marks running sessions as :closed when their cwd
   is not in pane-cwds set. Atomic under file lock."
  ([pane-cwds]
   (close-stale-sessions! (state-dir) pane-cwds))
  ([dir pane-cwds]
   (ensure-state-dir! dir)
   (let [path (state-file-path dir)
         now (.toString (java.time.Instant/now))]
     (with-file-lock dir
       (fn []
         (let [state (read-state-file path)
               updated (mark-stale-sessions
                        (:sessions state)
                        pane-cwds now)]
           (write-state-file!
            path
            {:sessions updated})))))))

(defn- apply-stale-pred
  "Returns updated sessions map, closing running sessions
   for which stale-pred returns true."
  [sessions stale-pred now]
  (reduce-kv
   (fn [m sid session]
     (assoc m sid
            (if (and (= :running (:agent-status session))
                     (stale-pred sid session))
              (merge session
                     {:agent-status :closed
                      :last-message "pane closed"
                      :last-updated now})
              session)))
   {}
   sessions))

(defn close-sessions-by-pred!
  "Atomically marks running sessions as :closed when
   stale-pred returns true. stale-pred takes [sid session]."
  ([stale-pred]
   (close-sessions-by-pred! (state-dir) stale-pred))
  ([dir stale-pred]
   (ensure-state-dir! dir)
   (let [path (state-file-path dir)
         now (.toString (java.time.Instant/now))]
     (with-file-lock dir
       (fn []
         (let [state (read-state-file path)
               updated (apply-stale-pred
                        (:sessions state)
                        stale-pred now)]
           (write-state-file!
            path
            {:sessions updated})))))))

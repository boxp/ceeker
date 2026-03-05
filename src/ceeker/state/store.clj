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
    (let [supersede-data {:agent-status :closed
                          :superseded true
                          :last-updated now}]
      (reduce-kv
       (fn [m sid session]
         (if (and (not= sid session-id)
                  (= :running (:agent-status session))
                  (= key (supersede-key session)))
           (assoc m sid (merge session supersede-data))
           m))
       sessions
       sessions))
    sessions))

(defn- should-supersede?
  "Returns true if the incoming session data represents
   a running session that should trigger superseding."
  [session-data]
  (= :running (:agent-status session-data)))

(defn- maybe-supersede
  "Applies supersede if session is new and running.
   Returns the sessions map after potential supersede."
  [sessions session-id session-data now]
  (if (and (should-supersede? session-data)
           (not (contains? sessions session-id)))
    (supersede-old-sessions
     sessions session-id session-data now)
    sessions))

(defn- superseded?
  "Returns true if the session was marked as superseded."
  [session]
  (:superseded session))

(defn- merge-session-data
  "Merges new data into existing session.
   Blocks running updates on superseded sessions.
   Non-running updates are allowed but the superseded
   flag is preserved through merges."
  [existing session-data]
  (if (and (superseded? existing)
           (= :running (:agent-status session-data)))
    existing
    (merge existing session-data)))

(defn update-session!
  "Updates a session in the state store.
   Supersedes running sessions with the same pane key
   only for newly created running sessions.
   Ignores running updates for already-superseded sessions."
  ([session-id session-data]
   (update-session! (state-dir) session-id session-data))
  ([dir session-id session-data]
   (ensure-state-dir! dir)
   (let [path (state-file-path dir)]
     (with-file-lock dir
       (fn []
         (let [state (read-state-file path)
               now (.toString (java.time.Instant/now))
               sessions (maybe-supersede
                         (:sessions state)
                         session-id session-data now)
               existing (get sessions session-id {})
               updated (merge-session-data
                        existing session-data)]
           (write-state-file!
            path
            (assoc state :sessions
                   (assoc sessions
                          session-id updated)))))))))

(def ^:private capturable-statuses
  "Session statuses eligible for capture-based updates."
  #{:running :idle :waiting})

(defn update-session-if-active!
  "Atomically updates a session only if its current
   status is active (:running, :idle, :waiting).
   Prevents capture-based updates from overwriting newer
   hook-written states (e.g. :completed, :closed).
   Returns true if the update was applied."
  ([session-id session-data]
   (update-session-if-active!
    (state-dir) session-id session-data))
  ([dir session-id session-data]
   (ensure-state-dir! dir)
   (let [path (state-file-path dir)]
     (with-file-lock dir
       (fn []
         (let [state (read-state-file path)
               existing (get-in state
                                [:sessions session-id])]
           (if (contains? capturable-statuses
                          (:agent-status existing))
             (let [updated (merge existing session-data)]
               (write-state-file!
                path
                (assoc-in state
                          [:sessions session-id]
                          updated))
               true)
             false)))))))

(defn reactivate-closed-session!
  "Atomically updates a session only if it is :closed and
   not superseded. Used to reactivate sessions where the
   agent has reappeared in the pane.
   Returns true if the update was applied."
  ([session-id session-data]
   (reactivate-closed-session!
    (state-dir) session-id session-data))
  ([dir session-id session-data]
   (ensure-state-dir! dir)
   (let [path (state-file-path dir)]
     (with-file-lock dir
       (fn []
         (let [state (read-state-file path)
               existing (get-in state
                                [:sessions session-id])]
           (if (and (= :closed (:agent-status existing))
                    (not (superseded? existing)))
             (let [updated (merge existing session-data)]
               (write-state-file!
                path
                (assoc-in state
                          [:sessions session-id]
                          updated))
               true)
             false)))))))

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

(defn- stale-active?
  "Returns true if session is active with a cwd
   not present in pane-cwds."
  [session pane-cwds]
  (and (contains? capturable-statuses
                  (:agent-status session))
       (seq (:cwd session))
       (not (contains? pane-cwds (:cwd session)))))

(defn- mark-stale-sessions
  "Returns updated sessions map with stale ones closed."
  [sessions pane-cwds now]
  (let [close-data {:agent-status :closed
                    :last-updated now}]
    (update-vals sessions
                 (fn [session]
                   (if (stale-active? session pane-cwds)
                     (merge session close-data)
                     session)))))

(defn close-stale-sessions!
  "Marks active sessions as :closed when their cwd
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
            (assoc state :sessions updated))))))))

(def ^:const closed-ttl-ms
  "Time-to-live (ms) for closed sessions before purging.
   Default: 5 minutes."
  300000)

(def ^:private terminal-statuses
  "Session statuses that represent a finished session."
  #{:closed :completed :error})

(defn- expired-terminal?
  "Returns true if session has a terminal status, is not
   superseded, and its last-updated timestamp is older than
   ttl-ms. Superseded sessions are never purged to preserve
   the guard record against late hook updates."
  [session now-ms ttl-ms]
  (and (contains? terminal-statuses (:agent-status session))
       (not (superseded? session))
       (if-let [ts (:last-updated session)]
         (try
           (let [updated-ms (.toEpochMilli
                             (java.time.Instant/parse ts))]
             (> (- now-ms updated-ms) ttl-ms))
           (catch Exception _ true))
         true)))

(defn- purgeable?
  "Returns true if session should be purged: expired terminal
   and pane-id not in live-pane-ids."
  [session now-ms ttl-ms live-pane-ids]
  (and (expired-terminal? session now-ms ttl-ms)
       (not (contains? live-pane-ids
                       (:pane-id session)))))

(defn- purge-sessions
  "Removes purgeable sessions from the sessions map."
  [sessions now-ms ttl-ms live-pane-ids]
  (into {}
        (remove (fn [[_sid session]]
                  (purgeable? session now-ms
                              ttl-ms live-pane-ids))
                sessions)))

(defn purge-expired-closed-sessions!
  "Removes terminal sessions (closed/completed/error) that
   have exceeded the TTL and whose pane-id is not in the
   live pane-ids set. Atomic under file lock."
  ([live-pane-ids]
   (purge-expired-closed-sessions!
    (state-dir) live-pane-ids))
  ([dir live-pane-ids]
   (purge-expired-closed-sessions!
    dir live-pane-ids closed-ttl-ms))
  ([dir live-pane-ids ttl-ms]
   (ensure-state-dir! dir)
   (let [path (state-file-path dir)
         now-ms (.toEpochMilli (java.time.Instant/now))]
     (with-file-lock dir
       (fn []
         (let [state (read-state-file path)
               remaining (purge-sessions
                          (:sessions state)
                          now-ms ttl-ms live-pane-ids)]
           (write-state-file!
            path
            (assoc state :sessions remaining))))))))

(defn- apply-stale-pred
  "Returns updated sessions map, closing active sessions
   for which stale-pred returns true."
  [sessions stale-pred now]
  (let [close-data {:agent-status :closed
                    :last-updated now}]
    (reduce-kv
     (fn [m sid session]
       (if (and (contains? capturable-statuses
                           (:agent-status session))
                (stale-pred sid session))
         (assoc m sid (merge session close-data))
         m))
     sessions
     sessions)))

(defn close-sessions-by-pred!
  "Atomically marks active sessions as :closed when
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
            (assoc state :sessions updated))))))))

(ns ceeker.state.store
  "Persistent State Store for ceeker sessions.
   Uses sessions.edn with file locking for concurrent access.
   Pane-centric: pane-id is the primary key when available,
   falling back to session-id for non-tmux sessions."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io File RandomAccessFile]
           [java.nio.channels FileLock]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]
           [java.util.concurrent.locks ReentrantLock]))

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

(def ^:private jvm-lock
  "JVM-level lock to coordinate threads within the same
   process. Prevents OverlappingFileLockException when
   async pane checks and the render loop both access
   the state file concurrently. The file lock handles
   cross-process coordination."
  (ReentrantLock.))

(defn- with-file-lock
  "Executes f while holding an exclusive file lock.
   Acquires a JVM-level lock first to prevent
   OverlappingFileLockException from concurrent threads."
  [dir f]
  (.lock ^ReentrantLock jvm-lock)
  (try
    (let [lock-path (str dir "/sessions.lock")
          _ (ensure-state-dir! dir)
          lock-file (RandomAccessFile.
                     ^String lock-path "rw")
          channel (.getChannel lock-file)]
      (try
        (let [^FileLock lock (.lock channel)]
          (try
            (f)
            (finally
              (.release lock))))
        (finally
          (.close channel)
          (.close lock-file))))
    (finally
      (.unlock ^ReentrantLock jvm-lock))))

(defn read-sessions
  "Reads all sessions from the state store."
  ([] (read-sessions (state-dir)))
  ([dir]
   (let [path (state-file-path dir)]
     (with-file-lock dir
       #(read-state-file path)))))

(def capturable-statuses
  "Session statuses eligible for capture-based updates."
  #{:running :idle :waiting})

(def ^:private terminal-statuses
  "Session statuses that represent a finished session."
  #{:closed :completed :error})

(defn update-session!
  "Updates a session in the state store.
   Merges new data into the existing entry at the given key."
  ([session-key session-data]
   (update-session! (state-dir) session-key session-data))
  ([dir session-key session-data]
   (let [path (state-file-path dir)]
     (with-file-lock dir
       (fn []
         (let [state (read-state-file path)
               existing (get-in state
                                [:sessions session-key] {})
               updated (merge existing session-data)]
           (write-state-file!
            path
            (assoc state :sessions
                   (assoc (:sessions state)
                          session-key updated)))))))))

(defn update-session-if-active!
  "Atomically updates a session only if its current
   status is active (:running, :idle, :waiting).
   Prevents capture-based updates from overwriting newer
   hook-written states (e.g. :completed, :closed).
   Returns true if the update was applied."
  ([session-key session-data]
   (update-session-if-active!
    (state-dir) session-key session-data))
  ([dir session-key session-data]
   (let [path (state-file-path dir)]
     (with-file-lock dir
       (fn []
         (let [state (read-state-file path)
               existing (get-in state
                                [:sessions session-key])]
           (if (contains? capturable-statuses
                          (:agent-status existing))
             (let [updated (merge existing session-data)]
               (write-state-file!
                path
                (assoc-in state
                          [:sessions session-key]
                          updated))
               true)
             false)))))))

(defn reactivate-closed-session!
  "Atomically updates a session only if it is :closed.
   Used to reactivate sessions where the agent has
   reappeared in the pane.
   Returns true if the update was applied."
  ([session-key session-data]
   (reactivate-closed-session!
    (state-dir) session-key session-data))
  ([dir session-key session-data]
   (let [path (state-file-path dir)]
     (with-file-lock dir
       (fn []
         (let [state (read-state-file path)
               existing (get-in state
                                [:sessions session-key])]
           (if (= :closed (:agent-status existing))
             (let [updated (merge existing session-data)]
               (write-state-file!
                path
                (assoc-in state
                          [:sessions session-key]
                          updated))
               true)
             false)))))))

(defn remove-session!
  "Removes a session from the state store."
  ([session-key]
   (remove-session! (state-dir) session-key))
  ([dir session-key]
   (let [path (state-file-path dir)]
     (with-file-lock dir
       (fn []
         (let [state (read-state-file path)]
           (write-state-file!
            path
            (update state :sessions dissoc session-key))))))))

(defn clear-sessions!
  "Clears all sessions from the state store."
  ([] (clear-sessions! (state-dir)))
  ([dir]
   (let [path (state-file-path dir)]
     (with-file-lock dir
       #(write-state-file! path {:sessions {}})))))

(def ^:const closed-ttl-ms
  "Time-to-live (ms) for closed sessions before purging.
   Default: 5 minutes."
  300000)

(defn- expired-terminal?
  "Returns true if session has a terminal status and its
   last-updated timestamp is older than ttl-ms."
  [session now-ms ttl-ms]
  (and (contains? terminal-statuses (:agent-status session))
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
        (remove (fn [[_key session]]
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
     (fn [m key session]
       (if (and (contains? capturable-statuses
                           (:agent-status session))
                (stale-pred key session))
         (assoc m key (merge session close-data))
         m))
     sessions
     sessions)))

(defn close-sessions-by-pred!
  "Atomically marks active sessions as :closed when
   stale-pred returns true. stale-pred takes [key session]."
  ([stale-pred]
   (close-sessions-by-pred! (state-dir) stale-pred))
  ([dir stale-pred]
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

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
  "Returns the state directory path.
   Uses XDG_RUNTIME_DIR/ceeker if available,
   falls back to /tmp/ceeker-<uid>/."
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

(defn- validate-state-dir!
  "Validates that the state directory is safe to use.
   Rejects symlinks, non-directories, and dirs owned by others."
  [^File f]
  (let [path (.toPath f)]
    (when (Files/isSymbolicLink path)
      (throw (ex-info "State directory is a symlink (rejected)"
                      {:path (.getAbsolutePath f)})))
    (when (.exists f)
      (when-not (.isDirectory f)
        (throw (ex-info "State path exists but is not a directory"
                        {:path (.getAbsolutePath f)})))
      (try
        (let [owner (-> (Files/getOwner path (make-array
                                              java.nio.file.LinkOption
                                              0))
                        (.getName))
              current-user (System/getProperty "user.name")]
          (when (and owner current-user
                     (not= owner current-user))
            (throw
             (ex-info "State directory owned by another user"
                      {:path (.getAbsolutePath f)
                       :owner owner
                       :expected current-user}))))
        (catch UnsupportedOperationException _ nil)))))

(defn ensure-state-dir!
  "Creates the state directory if it doesn't exist.
   Validates safety (rejects symlinks)."
  ([] (ensure-state-dir! (state-dir)))
  ([dir]
   (let [f (io/file dir)]
     (if (.exists f)
       (validate-state-dir! f)
       (do
         (.mkdirs f)
         (try
           (Files/setPosixFilePermissions
            (.toPath f)
            (PosixFilePermissions/fromString "rwx------"))
           (catch UnsupportedOperationException _ nil)))))))

(defn- read-state-file
  "Reads and parses the sessions.edn file.
   Returns empty map if file doesn't exist or is empty."
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
  "Executes f while holding an exclusive file lock on the state file.
   Creates lock file alongside the state file."
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

(defn update-session!
  "Updates a session in the state store.
   session-id is the key, session-data is merged into existing data."
  ([session-id session-data]
   (update-session! (state-dir) session-id session-data))
  ([dir session-id session-data]
   (ensure-state-dir! dir)
   (let [path (state-file-path dir)]
     (with-file-lock dir
       (fn []
         (let [state (read-state-file path)
               existing (get-in state [:sessions session-id] {})
               updated (merge existing session-data)]
           (write-state-file!
            path
            (assoc-in state [:sessions session-id] updated))))))))

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

(ns ceeker.tui.watcher
  "File watcher for sessions.edn using WatchService (inotify)."
  (:require [ceeker.state.store :as store]
            [clojure.java.io :as io])
  (:import [java.nio.file FileSystems Path StandardWatchEventKinds
            WatchEvent WatchEvent$Kind WatchKey WatchService]
           [java.util.concurrent TimeUnit]))

(def ^:private sessions-file-name
  "Target file name monitored by WatchService."
  "sessions.edn")

(defn- close-watch-service!
  "Closes WatchService safely."
  [^WatchService ws]
  (when ws
    (.close ws)))

(defn- register-state-dir!
  "Registers state directory events to WatchService."
  [^Path dir-path ^WatchService ws]
  (.register dir-path ws
             (into-array
              WatchEvent$Kind
              [StandardWatchEventKinds/ENTRY_MODIFY
               StandardWatchEventKinds/ENTRY_CREATE])))

(defn- state-file-last-modified
  "Returns sessions.edn last-modified millis, or 0 when absent."
  [state-dir]
  (let [f (io/file (store/state-file-path state-dir))]
    (if (.exists f) (.lastModified f) 0)))

(defn- changed-sessions-event?
  "Returns true when any event targets sessions.edn."
  [events]
  (boolean
   (some (fn [^WatchEvent evt]
           (= (str (.context evt))
              sessions-file-name))
         events)))

(defn- poll-watch-service-once
  "Polls WatchService once and returns true when sessions.edn changed."
  [^WatchService ws timeout-ms]
  (let [^WatchKey key (.poll ws timeout-ms
                             TimeUnit/MILLISECONDS)]
    (when key
      (let [changed? (changed-sessions-event?
                      (.pollEvents key))]
        (.reset key)
        changed?))))

(defn- file-modified-since-last-check?
  "Returns true when sessions.edn mtime advanced since the last poll."
  [watcher]
  (let [state-dir (:state-dir watcher)
        current (state-file-last-modified state-dir)
        previous @(:last-modified watcher)]
    (reset! (:last-modified watcher) current)
    (> current previous)))

(defn create-watcher
  "Creates a file watcher for sessions.edn directory.
   Returns a watcher map or nil if WatchService is unavailable."
  ([] (create-watcher (store/state-dir)))
  ([state-dir]
   (let [^WatchService ws
         (.newWatchService (FileSystems/getDefault))]
     (try
       (let [^Path dir-path (.toPath (io/file state-dir))]
         (store/ensure-state-dir! state-dir)
         (register-state-dir! dir-path ws)
         {:watch-service ws
          :state-dir state-dir
          :last-modified (atom (state-file-last-modified
                                state-dir))})
       (catch Exception e
         (close-watch-service! ws)
         (throw e))))))

(defn- poll-watch-service-until
  "Polls WatchService until timeout and returns true on sessions.edn change."
  [^WatchService ws timeout-ms]
  (let [deadline (+ (System/currentTimeMillis)
                    (max 0 timeout-ms))]
    (loop []
      (let [remaining (- deadline
                         (System/currentTimeMillis))]
        (cond
          (neg? remaining) false
          (poll-watch-service-once ws remaining) true
          :else (recur))))))

(defn poll-change
  "Polls for file changes with timeout.
   Returns true if sessions.edn was modified, false otherwise."
  [watcher timeout-ms]
  (when watcher
    (let [^WatchService ws (:watch-service watcher)
          watched? (poll-watch-service-until ws timeout-ms)]
      (or watched?
          (file-modified-since-last-check? watcher)))))

(defn close-watcher
  "Closes the file watcher."
  [watcher]
  (when watcher
    (close-watch-service! (:watch-service watcher))))

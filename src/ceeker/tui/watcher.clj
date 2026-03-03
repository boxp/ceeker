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
         {:watch-service ws :state-dir state-dir})
       (catch Exception e
         (close-watch-service! ws)
         (throw e))))))

(defn poll-change
  "Polls for file changes with timeout.
   Returns true if sessions.edn was modified, false otherwise."
  [watcher timeout-ms]
  (when watcher
    (let [^WatchService ws (:watch-service watcher)
          ^WatchKey key (.poll ws timeout-ms TimeUnit/MILLISECONDS)]
      (if key
        (let [changed?
              (some
               (fn [^WatchEvent evt]
                 (= (str (.context evt))
                    sessions-file-name))
               (.pollEvents key))]
          (.reset key)
          (boolean changed?))
        false))))

(defn close-watcher
  "Closes the file watcher."
  [watcher]
  (when watcher
    (close-watch-service! (:watch-service watcher))))

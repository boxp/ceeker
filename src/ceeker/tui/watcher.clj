(ns ceeker.tui.watcher
  "File watcher for sessions.edn using WatchService (inotify)."
  (:require [ceeker.state.store :as store]
            [clojure.java.io :as io])
  (:import [java.nio.file FileSystems StandardWatchEventKinds]
           [java.util.concurrent TimeUnit]))

(defn create-watcher
  "Creates a file watcher for sessions.edn directory.
   Returns a watcher map or nil if WatchService is unavailable."
  ([] (create-watcher (store/state-dir)))
  ([state-dir]
   (try
     (let [dir-path (.toPath (io/file state-dir))
           ws (.newWatchService (FileSystems/getDefault))]
       (store/ensure-state-dir! state-dir)
       (.register dir-path ws
                  (into-array
                   [StandardWatchEventKinds/ENTRY_MODIFY
                    StandardWatchEventKinds/ENTRY_CREATE]))
       {:watch-service ws :state-dir state-dir})
     (catch Exception _
       nil))))

(defn poll-change
  "Polls for file changes with timeout.
   Returns true if sessions.edn was modified, false otherwise."
  [watcher timeout-ms]
  (when watcher
    (try
      (let [ws (:watch-service watcher)
            key (.poll ws timeout-ms TimeUnit/MILLISECONDS)]
        (if key
          (let [changed? (some
                          (fn [evt]
                            (= (str (.context evt))
                               "sessions.edn"))
                          (.pollEvents key))]
            (.reset key)
            (boolean changed?))
          false))
      (catch Exception _
        false))))

(defn close-watcher
  "Closes the file watcher."
  [watcher]
  (when watcher
    (try
      (.close (:watch-service watcher))
      (catch Exception _ nil))))

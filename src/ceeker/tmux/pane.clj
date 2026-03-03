(ns ceeker.tmux.pane
  "tmux pane liveness checking.
   Detects stale sessions by checking tmux pane existence."
  (:require [ceeker.state.store :as store]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn list-pane-cwds
  "Runs tmux list-panes -a once, returns a set of pane cwds.
   Returns nil if tmux is unavailable."
  []
  (try
    (let [result (shell/sh
                  "tmux" "list-panes" "-a"
                  "-F" "#{pane_current_path}")]
      (when (zero? (:exit result))
        (set (remove str/blank?
                     (str/split-lines
                      (:out result))))))
    (catch Exception _ nil)))

(defn close-stale-sessions!
  "Checks running sessions and marks stale ones as closed.
   Does nothing if tmux is unavailable."
  ([] (close-stale-sessions! nil))
  ([state-dir]
   (when-let [pane-cwds (list-pane-cwds)]
     (if state-dir
       (store/close-stale-sessions! state-dir pane-cwds)
       (store/close-stale-sessions! pane-cwds)))))

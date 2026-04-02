(ns ceeker.session-list
  "Shared session list access for TUI and CLI."
  (:require [ceeker.state.store :as store]
            [ceeker.tmux.pane :as pane]))

(defn sort-sessions
  "Sorts sessions the same way as the TUI list."
  [sessions]
  (sort-by
   (fn [session]
     [(if (= :running (:agent-status session)) 0 1)
      (or (:last-updated session) "")])
   sessions))

(defn read-session-list
  "Reads all sessions from the state store and sorts them."
  ([] (read-session-list nil))
  ([state-dir]
   (let [state (if state-dir
                 (store/read-sessions state-dir)
                 (store/read-sessions))]
     (sort-sessions (vals (:sessions state))))))

(defn refresh-session-state!
  "Refreshes pane liveness and capture-based session states once."
  [state-dir]
  (pane/close-stale-sessions! state-dir)
  (pane/refresh-session-states! state-dir))

(defn refresh-and-read-session-list
  "Refreshes session state once, then reads the current session list.
   Refresh failures are logged and do not prevent returning stored state."
  ([] (refresh-and-read-session-list nil))
  ([state-dir]
   (try
     (refresh-session-state! state-dir)
     (catch Exception e
       (binding [*out* *err*]
         (println
          (str "ceeker: session list refresh failed: "
               (.getMessage e))))))
   (sort-sessions (read-session-list state-dir))))

(defn session->external
  "Converts an internal session map to a JSON-ready map."
  [session]
  {:session_id (:session-id session)
   :agent_type (some-> (:agent-type session) name)
   :agent_status (some-> (:agent-status session) name)
   :cwd (:cwd session)
   :pane_id (or (:pane-id session) "")
   :last_message (:last-message session)
   :last_updated (:last-updated session)})

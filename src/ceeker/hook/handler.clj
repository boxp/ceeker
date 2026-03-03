(ns ceeker.hook.handler
  "Hook event handler for Claude Code and Codex.
   Normalizes hook payloads and writes to State Store."
  (:require [ceeker.state.store :as store]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- current-timestamp
  "Returns current ISO-8601 timestamp string."
  []
  (.toString (java.time.Instant/now)))

(defn- parse-hook-payload
  "Parses JSON hook payload from stdin string.
   Returns nil if input is empty or invalid."
  [input]
  (when (and input (seq (str/trim input)))
    (try
      (json/parse-string input true)
      (catch Exception _
        nil))))

(defn- normalize-claude-event
  "Normalizes a Claude Code hook event into session state."
  [event-type payload]
  (let [session-id (or (:session_id payload)
                       (get-in payload [:session :session_id])
                       (str (java.util.UUID/randomUUID)))
        cwd (or (:cwd payload)
                (get-in payload [:session :cwd])
                "")]
    (case event-type
      "Notification"
      {:session-id session-id
       :agent-type :claude-code
       :agent-status :running
       :cwd cwd
       :last-message (or (:title payload)
                         (:message payload)
                         "notification")
       :last-updated (current-timestamp)}

      "Stop"
      {:session-id session-id
       :agent-type :claude-code
       :agent-status :completed
       :cwd cwd
       :last-message "session ended"
       :last-updated (current-timestamp)}

      "SubagentStop"
      {:session-id session-id
       :agent-type :claude-code
       :agent-status :running
       :cwd cwd
       :last-message "subagent completed"
       :last-updated (current-timestamp)}

      "PreToolUse"
      {:session-id session-id
       :agent-type :claude-code
       :agent-status :running
       :cwd cwd
       :last-message (str "using: "
                          (or (:tool_name payload) "tool"))
       :last-updated (current-timestamp)}

      "PostToolUse"
      {:session-id session-id
       :agent-type :claude-code
       :agent-status :running
       :cwd cwd
       :last-message (str "used: "
                          (or (:tool_name payload) "tool"))
       :last-updated (current-timestamp)}

      ;; default
      {:session-id session-id
       :agent-type :claude-code
       :agent-status :running
       :cwd cwd
       :last-message (str "event: " event-type)
       :last-updated (current-timestamp)})))

(defn- normalize-codex-event
  "Normalizes a Codex hook event into session state."
  [event-type payload]
  (let [session-id (or (:session_id payload)
                       (str (java.util.UUID/randomUUID)))
        cwd (or (:cwd payload) "")]
    (case event-type
      "notification"
      {:session-id session-id
       :agent-type :codex
       :agent-status :running
       :cwd cwd
       :last-message (or (:message payload) "notification")
       :last-updated (current-timestamp)}

      "stop"
      {:session-id session-id
       :agent-type :codex
       :agent-status :completed
       :cwd cwd
       :last-message "session ended"
       :last-updated (current-timestamp)}

      ;; default
      {:session-id session-id
       :agent-type :codex
       :agent-status :running
       :cwd cwd
       :last-message (str "event: " event-type)
       :last-updated (current-timestamp)})))

(defn normalize-event
  "Normalizes a hook event based on agent type and event type."
  [agent-type event-type payload]
  (case agent-type
    "claude" (normalize-claude-event event-type payload)
    "codex" (normalize-codex-event event-type payload)
    (throw (ex-info (str "Unknown agent type: " agent-type)
                    {:agent-type agent-type}))))

(defn handle-hook!
  "Handles a hook event: parses payload, normalizes, writes to store."
  ([agent-type event-type stdin-input]
   (handle-hook! nil agent-type event-type stdin-input))
  ([state-dir agent-type event-type stdin-input]
   (let [payload (or (parse-hook-payload stdin-input) {})
         session-data (normalize-event agent-type event-type payload)
         session-id (:session-id session-data)]
     (if state-dir
       (store/update-session! state-dir session-id session-data)
       (store/update-session! session-id session-data))
     session-data)))

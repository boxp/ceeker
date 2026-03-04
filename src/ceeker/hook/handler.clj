(ns ceeker.hook.handler
  "Hook event handler for Claude Code and Codex.
   Normalizes hook payloads and writes to State Store."
  (:require [ceeker.state.store :as store]
            [ceeker.tmux.pane :as pane]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- current-timestamp
  "Returns current ISO-8601 timestamp string."
  []
  (.toString (java.time.Instant/now)))

(defn- parse-hook-payload
  "Parses JSON hook payload from stdin string.
   Returns nil if input is empty, otherwise parsed map."
  [input]
  (when (and input (seq (str/trim input)))
    (json/parse-string input true)))

(defn current-pane-id
  "Returns the current tmux pane ID from $TMUX_PANE,
   or empty string if not in tmux."
  []
  (or (System/getenv "TMUX_PANE") ""))

(defn- make-session
  "Creates a normalized session state map."
  [session-id agent-type status cwd message]
  {:session-id session-id
   :agent-type agent-type
   :agent-status status
   :cwd cwd
   :pane-id (current-pane-id)
   :last-message message
   :last-updated (current-timestamp)})

(defn- extract-claude-identity
  "Extracts session-id and cwd from Claude payload.
   Per official spec, session_id and cwd are top-level fields."
  [payload]
  {:session-id (or (:session_id payload)
                   (str (java.util.UUID/randomUUID)))
   :cwd (or (:cwd payload) "")})

(defn- tool-message
  "Builds a tool event message string."
  [prefix payload]
  (str prefix (or (:tool_name payload) "tool")))

(defn- claude-event-fields
  "Returns [status message] for a Claude event type.
   Supports all official hook events per the spec."
  [event-type payload]
  (case event-type
    "SessionStart" [:running "session started"]
    "Notification" [:running
                    (or (:title payload)
                        (:message payload)
                        "notification")]
    "Stop" [:completed "session ended"]
    "SessionEnd" [:completed "session terminated"]
    "SubagentStart" [:running "subagent spawned"]
    "SubagentStop" [:running "subagent completed"]
    "PreToolUse" [:running (tool-message "using: " payload)]
    "PostToolUse" [:running (tool-message "used: " payload)]
    "PostToolUseFailure" [:running
                          (tool-message "failed: " payload)]
    "TaskCompleted" [:completed "task completed"]
    [:running (str "event: " event-type)]))

(defn- normalize-claude-event
  "Normalizes a Claude Code hook event into session state."
  [event-type payload]
  (let [id (extract-claude-identity payload)
        [status message] (claude-event-fields
                          event-type payload)]
    (make-session (:session-id id) :claude-code
                  status (:cwd id) message)))

(defn- extract-codex-identity
  "Extracts session-id and cwd from Codex payload.
   Supports both snake_case (legacy) and kebab-case (Codex notify)."
  [payload]
  {:session-id (or (:session_id payload)
                   (:thread-id payload)
                   (str (java.util.UUID/randomUUID)))
   :cwd (or (:cwd payload) "")})

(defn- codex-type->event
  "Maps Codex notify type field to internal event type."
  [type-field]
  (case type-field
    "agent-turn-complete" "notification"
    type-field))

(defn- codex-event-fields
  "Returns [status message] for a Codex event type."
  [event-type payload]
  (case event-type
    "notification" [:running
                    (or (:message payload)
                        (:last-assistant-message payload)
                        "notification")]
    "stop" [:completed "session ended"]
    [:running (str "event: " event-type)]))

(defn- normalize-codex-event
  "Normalizes a Codex hook event into session state."
  [event-type payload]
  (let [id (extract-codex-identity payload)
        [status message] (codex-event-fields
                          event-type payload)]
    (make-session (:session-id id) :codex
                  status (:cwd id) message)))

(defn normalize-event
  "Normalizes a hook event based on agent type."
  [agent-type event-type payload]
  (case agent-type
    "claude" (normalize-claude-event event-type payload)
    "codex" (normalize-codex-event event-type payload)
    (throw (ex-info (str "Unknown agent type: " agent-type)
                    {:agent-type agent-type}))))

(defn- resolve-codex-event
  "Resolves event type for Codex when not explicitly given."
  [event-type payload]
  (or event-type
      (when-let [t (:type payload)]
        (codex-type->event t))
      "notification"))

(defn- resolve-claude-event
  "Resolves event type for Claude.
   Uses hook_event_name from payload per official spec."
  [event-type payload]
  (or event-type
      (:hook_event_name payload)
      "Notification"))

(defn handle-hook!
  "Handles a hook event: parses, normalizes, writes to store."
  ([agent-type event-type stdin-input]
   (handle-hook! nil agent-type event-type stdin-input))
  ([state-dir agent-type event-type stdin-input]
   (let [payload (or (parse-hook-payload stdin-input) {})
         effective-event
         (case agent-type
           "codex" (resolve-codex-event
                    event-type payload)
           (resolve-claude-event
            event-type payload))
         session-data (normalize-event
                       agent-type effective-event
                       payload)
         session-id (:session-id session-data)]
     (if state-dir
       (store/update-session!
        state-dir session-id session-data)
       (store/update-session!
        session-id session-data))
     (pane/close-stale-sessions! state-dir)
     session-data)))

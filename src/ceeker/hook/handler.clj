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

(defn- make-session
  "Creates a normalized session state map."
  [session-id agent-type status cwd message]
  {:session-id session-id
   :agent-type agent-type
   :agent-status status
   :cwd cwd
   :last-message message
   :last-updated (current-timestamp)})

(defn- extract-claude-identity
  "Extracts session-id and cwd from Claude payload."
  [payload]
  {:session-id (or (:session_id payload)
                   (get-in payload [:session :session_id])
                   (str (java.util.UUID/randomUUID)))
   :cwd (or (:cwd payload)
             (get-in payload [:session :cwd])
             "")})

(defn- claude-event-fields
  "Returns [status message] for a Claude event type."
  [event-type payload]
  (case event-type
    "Notification" [:running (or (:title payload)
                                 (:message payload)
                                 "notification")]
    "Stop" [:completed "session ended"]
    "SubagentStop" [:running "subagent completed"]
    "PreToolUse" [:running (str "using: "
                                (or (:tool_name payload) "tool"))]
    "PostToolUse" [:running (str "used: "
                                 (or (:tool_name payload) "tool"))]
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
  "Extracts session-id and cwd from Codex payload."
  [payload]
  {:session-id (or (:session_id payload)
                   (str (java.util.UUID/randomUUID)))
   :cwd (or (:cwd payload) "")})

(defn- codex-event-fields
  "Returns [status message] for a Codex event type."
  [event-type payload]
  (case event-type
    "notification" [:running (or (:message payload)
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

(defn handle-hook!
  "Handles a hook event: parses, normalizes, writes to store."
  ([agent-type event-type stdin-input]
   (handle-hook! nil agent-type event-type stdin-input))
  ([state-dir agent-type event-type stdin-input]
   (let [payload (or (parse-hook-payload stdin-input) {})
         session-data (normalize-event
                       agent-type event-type payload)
         session-id (:session-id session-data)]
     (if state-dir
       (store/update-session! state-dir session-id session-data)
       (store/update-session! session-id session-data))
     session-data)))

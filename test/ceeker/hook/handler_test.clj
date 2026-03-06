(ns ceeker.hook.handler-test
  (:require [ceeker.hook.handler :as handler]
            [ceeker.state.store :as store]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- temp-dir
  "Creates a temporary directory for testing."
  []
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/ceeker-handler-test-"
                 (System/nanoTime))]
    (.mkdirs (io/file dir))
    dir))

(defn- cleanup-dir
  "Removes temporary test directory."
  [dir]
  (let [f (io/file dir)]
    (when (.exists f)
      (doseq [file (reverse (file-seq f))]
        (.delete file)))))

;; --- Claude Code: Notification priority tests ---

(deftest test-notification-message-over-title
  (let [result (handler/normalize-event
                "claude" "Notification"
                {:session_id "notif-1"
                 :cwd "/tmp/work"
                 :hook_event_name "Notification"
                 :message "from message field"
                 :title "from title field"})]
    (is (= "from message field"
           (:last-message result))
        ":message takes priority over :title")))

(deftest test-notification-title-fallback
  (let [result (handler/normalize-event
                "claude" "Notification"
                {:session_id "notif-2"
                 :cwd "/tmp/work"
                 :hook_event_name "Notification"
                 :title "Working on task"})]
    (is (= "Working on task"
           (:last-message result))
        ":title is used when :message is absent")))

(deftest test-notification-default-fallback
  (let [result (handler/normalize-event
                "claude" "Notification"
                {:session_id "notif-3"
                 :cwd "/tmp/work"
                 :hook_event_name "Notification"})]
    (is (= "notification"
           (:last-message result))
        "falls back to \"notification\" string")))

(deftest test-normalize-claude-notification
  (let [result (handler/normalize-event
                "claude" "Notification"
                {:session_id "test-123"
                 :transcript_path "/tmp/transcript.json"
                 :cwd "/tmp/work"
                 :permission_mode "default"
                 :hook_event_name "Notification"
                 :title "Working on task"})]
    (is (= "test-123" (:session-id result)))
    (is (= :claude-code (:agent-type result)))
    (is (= :running (:agent-status result)))
    (is (= "Working on task" (:last-message result)))
    (is (some? (:last-updated result)))))

;; --- Claude Code: SessionEnd updates last-message ---

(deftest test-normalize-claude-session-end
  (let [result (handler/normalize-event
                "claude" "SessionEnd"
                {:session_id "sess-end"
                 :transcript_path "/tmp/transcript.json"
                 :cwd "/home/user/project"
                 :permission_mode "default"
                 :hook_event_name "SessionEnd"})]
    (is (= "sess-end" (:session-id result)))
    (is (= :completed (:agent-status result)))
    (is (= "session terminated"
           (:last-message result)))))

;; --- Claude Code: non-updating events omit :last-message ---

(deftest test-normalize-claude-stop
  (let [result (handler/normalize-event
                "claude" "Stop"
                {:session_id "test-456"
                 :transcript_path "/tmp/transcript.json"
                 :cwd "/tmp/work"
                 :permission_mode "default"
                 :hook_event_name "Stop"})]
    (is (= "test-456" (:session-id result)))
    (is (= :claude-code (:agent-type result)))
    (is (= :completed (:agent-status result)))
    (is (not (contains? result :last-message))
        "Stop should not include :last-message")))

(deftest test-normalize-claude-pre-tool-use
  (let [result (handler/normalize-event
                "claude" "PreToolUse"
                {:session_id "test-789"
                 :transcript_path "/tmp/transcript.json"
                 :cwd "/tmp/work"
                 :permission_mode "default"
                 :hook_event_name "PreToolUse"
                 :tool_name "Bash"
                 :tool_input {:command "npm test"}})]
    (is (= "test-789" (:session-id result)))
    (is (= :running (:agent-status result)))
    (is (not (contains? result :last-message))
        "PreToolUse should not include :last-message")))

(deftest test-normalize-claude-post-tool-use
  (let [result (handler/normalize-event
                "claude" "PostToolUse"
                {:session_id "test-post"
                 :transcript_path "/tmp/transcript.json"
                 :cwd "/tmp/work"
                 :permission_mode "default"
                 :hook_event_name "PostToolUse"
                 :tool_name "Edit"
                 :tool_input {:file_path "/tmp/f.clj"}
                 :tool_output "OK"})]
    (is (= "test-post" (:session-id result)))
    (is (= :running (:agent-status result)))
    (is (not (contains? result :last-message))
        "PostToolUse should not include :last-message")))

(deftest test-normalize-claude-post-tool-use-failure
  (let [result (handler/normalize-event
                "claude" "PostToolUseFailure"
                {:session_id "test-fail"
                 :transcript_path "/tmp/transcript.json"
                 :cwd "/tmp/work"
                 :permission_mode "default"
                 :hook_event_name "PostToolUseFailure"
                 :tool_name "Bash"
                 :tool_input {:command "make build"}})]
    (is (= "test-fail" (:session-id result)))
    (is (= :running (:agent-status result)))
    (is (not (contains? result :last-message))
        "PostToolUseFailure should not include :last-message")))

(deftest test-normalize-claude-session-start
  (let [result (handler/normalize-event
                "claude" "SessionStart"
                {:session_id "sess-start"
                 :transcript_path "/tmp/transcript.json"
                 :cwd "/home/user/project"
                 :permission_mode "default"
                 :hook_event_name "SessionStart"})]
    (is (= "sess-start" (:session-id result)))
    (is (= :claude-code (:agent-type result)))
    (is (= :running (:agent-status result)))
    (is (not (contains? result :last-message))
        "SessionStart should not include :last-message")
    (is (= "/home/user/project" (:cwd result)))))

(deftest test-normalize-claude-subagent-start
  (let [result (handler/normalize-event
                "claude" "SubagentStart"
                {:session_id "sub-start"
                 :transcript_path "/tmp/transcript.json"
                 :cwd "/tmp/work"
                 :permission_mode "default"
                 :hook_event_name "SubagentStart"})]
    (is (= "sub-start" (:session-id result)))
    (is (= :running (:agent-status result)))
    (is (not (contains? result :last-message))
        "SubagentStart should not include :last-message")))

(deftest test-normalize-claude-subagent-stop
  (let [result (handler/normalize-event
                "claude" "SubagentStop"
                {:session_id "sub-stop"
                 :transcript_path "/tmp/transcript.json"
                 :cwd "/tmp/work"
                 :permission_mode "default"
                 :hook_event_name "SubagentStop"})]
    (is (= "sub-stop" (:session-id result)))
    (is (= :running (:agent-status result)))
    (is (not (contains? result :last-message))
        "SubagentStop should not include :last-message")))

(deftest test-normalize-claude-task-completed
  (let [result (handler/normalize-event
                "claude" "TaskCompleted"
                {:session_id "task-done"
                 :transcript_path "/tmp/transcript.json"
                 :cwd "/tmp/work"
                 :permission_mode "default"
                 :hook_event_name "TaskCompleted"})]
    (is (= "task-done" (:session-id result)))
    (is (= :completed (:agent-status result)))
    (is (not (contains? result :last-message))
        "TaskCompleted should not include :last-message")))

;; --- Claude: hook_event_name fallback ---

(deftest test-claude-hook-event-name-fallback
  (let [dir (temp-dir)]
    (try
      (let [payload
            (json/generate-string
             {:session_id "fallback-1"
              :transcript_path "/tmp/t.json"
              :cwd "/tmp/work"
              :permission_mode "default"
              :hook_event_name "Stop"})
            result (handler/handle-hook!
                    dir "claude" nil payload)]
        (is (= "fallback-1" (:session-id result)))
        (is (= :completed (:agent-status result)))
        (is (not (contains? result :last-message))
            "Stop via fallback should not include :last-message"))
      (finally
        (cleanup-dir dir)))))

;; --- Claude: E2E with store ---

(deftest test-claude-non-updating-event-preserves-message
  (let [dir (temp-dir)]
    (try
      ;; First: Notification sets last-message
      (let [notif-payload
            (json/generate-string
             {:session_id "preserve-1"
              :cwd "/tmp/work"
              :hook_event_name "Notification"
              :message "Important update"})]
        (handler/handle-hook!
         dir "claude" "Notification" notif-payload))
      ;; Second: PreToolUse should preserve last-message
      (let [tool-payload
            (json/generate-string
             {:session_id "preserve-1"
              :cwd "/tmp/work"
              :hook_event_name "PreToolUse"
              :tool_name "Bash"
              :tool_input {:command "ls"}})
            result (handler/handle-hook!
                    dir "claude" "PreToolUse" tool-payload)
            stored (store/read-sessions dir)
            session (get-in stored
                            [:sessions "preserve-1"])]
        (is (not (contains? result :last-message))
            "PreToolUse result should not contain :last-message")
        (is (= "Important update"
               (:last-message session))
            "Store should preserve last-message from Notification"))
      (finally
        (cleanup-dir dir)))))

(deftest test-claude-real-payload-e2e
  (let [dir (temp-dir)]
    (try
      ;; PreToolUse should not set last-message in store
      (let [payload
            (json/generate-string
             {:session_id "abc123"
              :transcript_path
              "/home/user/.claude/projects/p/t.json"
              :cwd "/home/user/project"
              :permission_mode "default"
              :hook_event_name "PreToolUse"
              :tool_name "Bash"
              :tool_input {:command "npm test"}})
            result (handler/handle-hook!
                    dir "claude" "PreToolUse"
                    payload)]
        (is (= "abc123" (:session-id result)))
        (is (= :claude-code (:agent-type result)))
        (is (= :running (:agent-status result)))
        (is (not (contains? result :last-message)))
        (is (= "/home/user/project" (:cwd result)))
        (let [stored (store/read-sessions dir)
              session (get-in stored
                              [:sessions "abc123"])]
          (is (some? session))
          (is (= :claude-code
                 (:agent-type session)))))
      (finally
        (cleanup-dir dir)))))

;; --- Codex tests (unchanged behavior) ---

(deftest test-normalize-codex-notification
  (let [result (handler/normalize-event
                "codex" "notification"
                {:session_id "codex-1"
                 :message "Running tests"
                 :cwd "/tmp/codex"})]
    (is (= "codex-1" (:session-id result)))
    (is (= :codex (:agent-type result)))
    (is (= :running (:agent-status result)))
    (is (= "Running tests" (:last-message result)))))

(deftest test-normalize-codex-stop
  (let [result (handler/normalize-event
                "codex" "stop"
                {:session_id "codex-2"
                 :cwd "/tmp/codex"})]
    (is (= "codex-2" (:session-id result)))
    (is (= :codex (:agent-type result)))
    (is (= :completed (:agent-status result)))))

(deftest test-normalize-unknown-agent
  (is (thrown? clojure.lang.ExceptionInfo
               (handler/normalize-event
                "unknown" "event" {}))))

(deftest test-handle-hook-with-json-payload
  (let [dir (temp-dir)]
    (try
      (let [payload (json/generate-string
                     {:session_id "hook-test-1"
                      :transcript_path "/tmp/t.json"
                      :cwd "/tmp/hook-test"
                      :permission_mode "default"
                      :hook_event_name "Notification"
                      :title "Testing hook"})
            result (handler/handle-hook!
                    dir "claude" "Notification"
                    payload)]
        (is (= "hook-test-1" (:session-id result)))
        (is (= :claude-code (:agent-type result)))
        (is (= "Testing hook" (:last-message result)))
        (let [stored (store/read-sessions dir)
              session (get-in stored
                              [:sessions
                               "hook-test-1"])]
          (is (some? session))
          (is (= :claude-code
                 (:agent-type session)))))
      (finally
        (cleanup-dir dir)))))

(deftest test-codex-notify-real-payload
  (let [dir (temp-dir)]
    (try
      (let [payload
            (json/generate-string
             {:type "agent-turn-complete"
              :thread-id
              "b5f6c1c2-1111-2222-3333-444455556666"
              :turn-id "12345"
              :cwd "/home/user/project"
              :client "codex-tui"
              :input-messages ["Fix the tests"]
              :last-assistant-message
              "All tests pass now."})
            result (handler/handle-hook!
                    dir "codex" nil payload)]
        (is (= "b5f6c1c2-1111-2222-3333-444455556666"
               (:session-id result)))
        (is (= :codex (:agent-type result)))
        (is (= :running (:agent-status result)))
        (is (= "All tests pass now."
               (:last-message result)))
        (is (= "/home/user/project" (:cwd result)))
        (let [stored (store/read-sessions dir)
              session
              (get-in
               stored
               [:sessions
                "b5f6c1c2-1111-2222-3333-444455556666"])]
          (is (some? session))
          (is (= :codex (:agent-type session)))
          (is (= "/home/user/project"
                 (:cwd session)))))
      (finally
        (cleanup-dir dir)))))

(deftest test-codex-notify-no-message
  (let [dir (temp-dir)]
    (try
      (let [payload
            (json/generate-string
             {:type "agent-turn-complete"
              :thread-id "abc-123"
              :cwd "/tmp/work"
              :last-assistant-message nil})
            result (handler/handle-hook!
                    dir "codex" nil payload)]
        (is (= "abc-123" (:session-id result)))
        (is (= "notification"
               (:last-message result))))
      (finally
        (cleanup-dir dir)))))

(deftest test-codex-legacy-explicit-event
  (let [dir (temp-dir)]
    (try
      (let [payload
            (json/generate-string
             {:session_id "legacy-1"
              :message "Running"
              :cwd "/tmp/legacy"})
            result (handler/handle-hook!
                    dir "codex" "notification"
                    payload)]
        (is (= "legacy-1" (:session-id result)))
        (is (= "Running" (:last-message result))))
      (finally
        (cleanup-dir dir)))))

(deftest test-handle-hook-empty-payload
  (let [dir (temp-dir)]
    (try
      (let [result (handler/handle-hook!
                    dir "claude" "Notification" "")]
        (is (= :claude-code (:agent-type result)))
        (is (some? (:session-id result))))
      (finally
        (cleanup-dir dir)))))

(deftest test-handle-hook-invalid-json
  (let [dir (temp-dir)]
    (try
      (is (thrown? Exception
                   (handler/handle-hook!
                    dir "claude" "Notification"
                    "not json")))
      (finally
        (cleanup-dir dir)))))

;; --- Pane ID tests (D: Agent ID + Pane Fallback) ---

(deftest test-session-includes-pane-id
  (let [result (handler/normalize-event
                "claude" "SessionStart"
                {:session_id "pane-test-1"
                 :cwd "/tmp/work"
                 :hook_event_name "SessionStart"})]
    (is (contains? result :pane-id))
    (is (string? (:pane-id result)))))

(deftest test-current-pane-id-returns-string
  (let [pane-id (handler/current-pane-id)]
    (is (string? pane-id))))

(ns ceeker.hook.handler-test
  (:require [ceeker.hook.handler :as handler]
            [ceeker.state.store :as store]
            [ceeker.tmux.pane :as pane]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

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

;; --- Claude Code: Stop/SubagentStop use last_assistant_message ---

(deftest test-normalize-claude-stop
  (let [result (handler/normalize-event
                "claude" "Stop"
                {:session_id "test-456"
                 :transcript_path "/tmp/transcript.json"
                 :cwd "/tmp/work"
                 :permission_mode "default"
                 :hook_event_name "Stop"
                 :last_assistant_message "Refactoring complete."})]
    (is (= "test-456" (:session-id result)))
    (is (= :claude-code (:agent-type result)))
    (is (= :completed (:agent-status result)))
    (is (= "Refactoring complete."
           (:last-message result)))))

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
    (is (not (contains? result :last-message)))))

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
    (is (not (contains? result :last-message)))))

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
    (is (not (contains? result :last-message)))))

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
    (is (not (contains? result :last-message)))
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
    (is (not (contains? result :last-message)))))

(deftest test-normalize-claude-subagent-stop
  (let [result (handler/normalize-event
                "claude" "SubagentStop"
                {:session_id "sub-stop"
                 :transcript_path "/tmp/transcript.json"
                 :cwd "/tmp/work"
                 :permission_mode "default"
                 :hook_event_name "SubagentStop"
                 :last_assistant_message "Subtask finished."})]
    (is (= "sub-stop" (:session-id result)))
    (is (= :running (:agent-status result)))
    (is (= "Subtask finished."
           (:last-message result)))))

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
    (is (not (contains? result :last-message)))))

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
        (is (not (contains? result :last-message))))
      (finally
        (cleanup-dir dir)))))

;; --- Claude: E2E with store ---

(deftest test-claude-non-updating-event-preserves-message
  (let [dir (temp-dir)]
    (try
      (with-redefs [handler/current-pane-id
                    (constantly "%99")]
        (let [notif-payload
              (json/generate-string
               {:session_id "preserve-1"
                :cwd "/tmp/work"
                :hook_event_name "Notification"
                :message "Important update"})]
          (handler/handle-hook!
           dir "claude" "Notification" notif-payload))
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
              session (get-in stored [:sessions "%99"])]
          (is (not (contains? result :last-message)))
          (is (= "Important update"
                 (:last-message session)))))
      (finally
        (cleanup-dir dir)))))

(deftest test-claude-stop-overwrites-last-message-when-present
  (let [dir (temp-dir)]
    (try
      (with-redefs [handler/current-pane-id
                    (constantly "%99")]
        (let [notif-payload
              (json/generate-string
               {:session_id "stop-msg-1"
                :cwd "/tmp/work"
                :hook_event_name "Notification"
                :message "Working on it"})]
          (handler/handle-hook!
           dir "claude" "Notification" notif-payload))
        (let [stop-payload
              (json/generate-string
               {:session_id "stop-msg-1"
                :cwd "/tmp/work"
                :hook_event_name "Stop"
                :last_assistant_message "Done. Updated 2 files."})
              result (handler/handle-hook!
                      dir "claude" "Stop" stop-payload)
              stored (store/read-sessions dir)
              session (get-in stored [:sessions "%99"])]
          (is (= :completed (:agent-status result)))
          (is (= "Done. Updated 2 files."
                 (:last-message result)))
          (is (= "Done. Updated 2 files."
                 (:last-message session)))))
      (finally
        (cleanup-dir dir)))))

(deftest test-claude-real-payload-e2e
  (let [dir (temp-dir)]
    (try
      (with-redefs [handler/current-pane-id
                    (constantly "%10")]
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
                session (get-in stored [:sessions "%10"])]
            (is (some? session))
            (is (= :claude-code
                   (:agent-type session))))))
      (finally
        (cleanup-dir dir)))))

(deftest test-handle-hook-does-not-close-stale-sessions
  (testing "hook path only parses, normalizes, and writes state"
    (let [dir (temp-dir)]
      (try
        (with-redefs [handler/current-pane-id
                      (constantly "%10")
                      pane/close-stale-sessions!
                      (fn [_]
                        (throw
                         (ex-info "should not run in hook path" {})))]
          (let [payload (json/generate-string
                         {:session_id "fast-hook-1"
                          :cwd "/tmp/work"
                          :hook_event_name "Notification"
                          :message "Working"})
                result (handler/handle-hook!
                        dir "claude" nil payload)
                stored (store/read-sessions dir)]
            (is (= "fast-hook-1" (:session-id result)))
            (is (some? (get-in stored [:sessions "%10"])))))
        (finally
          (cleanup-dir dir))))))

;; --- Codex tests ---

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

(deftest test-normalize-pi-notification
  (let [result (handler/normalize-event
                "pi" "Notification"
                {:session_id "pi-1"
                 :cwd "/tmp/pi"
                 :message "Pi is working"})]
    (is (= "pi-1" (:session-id result)))
    (is (= :pi (:agent-type result)))
    (is (= :running (:agent-status result)))
    (is (= "Pi is working" (:last-message result)))))

(deftest test-normalize-unknown-agent
  (is (thrown? clojure.lang.ExceptionInfo
               (handler/normalize-event
                "unknown" "event" {}))))

(deftest test-handle-hook-with-json-payload
  (let [dir (temp-dir)]
    (try
      (with-redefs [handler/current-pane-id
                    (constantly "%5")]
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
                session (get-in stored [:sessions "%5"])]
            (is (some? session))
            (is (= :claude-code
                   (:agent-type session))))))
      (finally
        (cleanup-dir dir)))))

(deftest test-codex-notify-real-payload
  (let [dir (temp-dir)]
    (try
      (with-redefs [handler/current-pane-id
                    (constantly "%7")]
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
                session (get-in stored [:sessions "%7"])]
            (is (some? session))
            (is (= :codex (:agent-type session)))
            (is (= "/home/user/project"
                   (:cwd session))))))
      (finally
        (cleanup-dir dir)))))

(deftest test-codex-notify-no-message
  (let [dir (temp-dir)]
    (try
      (with-redefs [handler/current-pane-id
                    (constantly "%8")]
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
                 (:last-message result)))))
      (finally
        (cleanup-dir dir)))))

(deftest test-codex-legacy-explicit-event
  (let [dir (temp-dir)]
    (try
      (with-redefs [handler/current-pane-id
                    (constantly "%9")]
        (let [payload
              (json/generate-string
               {:session_id "legacy-1"
                :message "Running"
                :cwd "/tmp/legacy"})
              result (handler/handle-hook!
                      dir "codex" "notification"
                      payload)]
          (is (= "legacy-1" (:session-id result)))
          (is (= "Running" (:last-message result)))))
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

;; --- Pane ID tests ---

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

;; --- Pane-centric store key tests ---

(deftest test-store-key-uses-pane-id-when-available
  (testing "handle-hook! uses pane-id as store key"
    (let [dir (temp-dir)]
      (try
        (with-redefs [handler/current-pane-id
                      (constantly "%42")]
          (handler/handle-hook!
           dir "claude" "SessionStart"
           (json/generate-string
            {:session_id "sess-A"
             :cwd "/tmp/work"}))
          (handler/handle-hook!
           dir "claude" "Notification"
           (json/generate-string
            {:session_id "sess-B"
             :cwd "/tmp/work"
             :message "from B"}))
          (let [sessions (:sessions
                          (store/read-sessions dir))]
            (is (= 1 (count sessions))
                "Same pane should have 1 entry")
            (is (some? (get sessions "%42"))
                "Key should be pane-id")
            (is (= "from B"
                   (:last-message
                    (get sessions "%42"))))))
        (finally
          (cleanup-dir dir))))))

;; --- Codex hooks (v0.114.0+) tests ---

(deftest test-normalize-codex-hooks-session-start
  (testing "Codex hooks SessionStart normalizes to :running"
    (let [result (handler/normalize-event
                  "codex" "SessionStart"
                  {:session_id "codex-hooks-1"
                   :cwd "/home/user/project"
                   :hook_event_name "SessionStart"})]
      (is (= "codex-hooks-1" (:session-id result)))
      (is (= :codex (:agent-type result)))
      (is (= :running (:agent-status result)))
      (is (not (contains? result :last-message)))
      (is (= "/home/user/project" (:cwd result))))))

(deftest test-normalize-codex-hooks-stop
  (testing "Codex hooks Stop normalizes to :completed"
    (let [result (handler/normalize-event
                  "codex" "Stop"
                  {:session_id "codex-hooks-2"
                   :cwd "/home/user/project"
                   :hook_event_name "Stop"
                   :last_assistant_message "Done."})]
      (is (= "codex-hooks-2" (:session-id result)))
      (is (= :codex (:agent-type result)))
      (is (= :completed (:agent-status result)))
      (is (= "Done." (:last-message result))))))

(deftest test-normalize-codex-hooks-stop-no-message
  (testing "Codex hooks Stop falls back to default message"
    (let [result (handler/normalize-event
                  "codex" "Stop"
                  {:session_id "codex-hooks-3"
                   :cwd "/tmp/work"
                   :hook_event_name "Stop"})]
      (is (= :completed (:agent-status result)))
      (is (= "session ended" (:last-message result))))))

(deftest test-codex-hooks-hook-event-name-fallback
  (testing "resolve-codex-event uses hook_event_name from payload"
    (let [dir (temp-dir)]
      (try
        (with-redefs [handler/current-pane-id
                      (constantly "%20")]
          (let [payload
                (json/generate-string
                 {:session_id "codex-hooks-fb"
                  :cwd "/home/user/project"
                  :hook_event_name "SessionStart"})
                result (handler/handle-hook!
                        dir "codex" nil payload)]
            (is (= "codex-hooks-fb" (:session-id result)))
            (is (= :codex (:agent-type result)))
            (is (= :running (:agent-status result)))
            (is (not (contains? result :last-message)))))
        (finally
          (cleanup-dir dir))))))

(deftest test-codex-hooks-e2e-session-lifecycle
  (testing "Codex hooks SessionStart → Stop lifecycle"
    (let [dir (temp-dir)]
      (try
        (with-redefs [handler/current-pane-id
                      (constantly "%21")]
          (let [start-payload
                (json/generate-string
                 {:session_id "lifecycle-1"
                  :cwd "/home/user/project"
                  :hook_event_name "SessionStart"})
                _ (handler/handle-hook!
                   dir "codex" "SessionStart" start-payload)
                stored-after-start
                (store/read-sessions dir)
                session-started
                (get-in stored-after-start [:sessions "%21"])]
            (is (= :running (:agent-status session-started)))
            (let [stop-payload
                  (json/generate-string
                   {:session_id "lifecycle-1"
                    :cwd "/home/user/project"
                    :hook_event_name "Stop"
                    :last_assistant_message "All done."})
                  result (handler/handle-hook!
                          dir "codex" "Stop" stop-payload)
                  stored-after-stop
                  (store/read-sessions dir)
                  session-stopped
                  (get-in stored-after-stop [:sessions "%21"])]
              (is (= :completed (:agent-status result)))
              (is (= "All done." (:last-message result)))
              (is (= :completed
                     (:agent-status session-stopped)))
              (is (= "All done."
                     (:last-message session-stopped))))))
        (finally
          (cleanup-dir dir))))))

(deftest test-codex-hooks-session-start-snake-case
  (testing "session_start via hook_event_name maps to SessionStart"
    (let [dir (temp-dir)]
      (try
        (with-redefs [handler/current-pane-id
                      (constantly "%22")]
          (let [payload
                (json/generate-string
                 {:session_id "snake-1"
                  :cwd "/tmp/work"
                  :type "session_start"})
                result (handler/handle-hook!
                        dir "codex" nil payload)]
            (is (= :running (:agent-status result)))
            (is (not (contains? result :last-message)))))
        (finally
          (cleanup-dir dir))))))

;; --- Codex notify fallback regression tests ---

(deftest test-codex-notify-still-works
  (testing "Existing notify pathway is not broken"
    (let [dir (temp-dir)]
      (try
        (with-redefs [handler/current-pane-id
                      (constantly "%23")]
          (let [payload
                (json/generate-string
                 {:type "agent-turn-complete"
                  :thread-id "notify-regression"
                  :cwd "/tmp/work"
                  :last-assistant-message "Still works."})
                result (handler/handle-hook!
                        dir "codex" nil payload)]
            (is (= "notify-regression"
                   (:session-id result)))
            (is (= :running (:agent-status result)))
            (is (= "Still works."
                   (:last-message result)))))
        (finally
          (cleanup-dir dir))))))

(deftest test-codex-hooks-full-payload-fields
  (testing "Codex hooks payload with all fields works correctly"
    (let [dir (temp-dir)]
      (try
        (with-redefs [handler/current-pane-id
                      (constantly "%30")]
          (let [start-payload
                (json/generate-string
                 {:session_id "full-fields-1"
                  :cwd "/home/user/project"
                  :hook_event_name "SessionStart"
                  :model "o4-mini"
                  :permission_mode "default"
                  :source "startup"
                  :transcript_path "/home/user/.codex/transcript.json"})
                result (handler/handle-hook!
                        dir "codex" nil start-payload)]
            (is (= "full-fields-1" (:session-id result)))
            (is (= :codex (:agent-type result)))
            (is (= :running (:agent-status result)))
            (is (= "/home/user/project" (:cwd result)))
            (is (not (contains? result :last-message)))
            ;; Stop with full fields
            (let [stop-payload
                  (json/generate-string
                   {:session_id "full-fields-1"
                    :cwd "/home/user/project"
                    :hook_event_name "Stop"
                    :model "o4-mini"
                    :permission_mode "default"
                    :stop_hook_active true
                    :transcript_path "/home/user/.codex/transcript.json"
                    :last_assistant_message "Task completed."})
                  stop-result (handler/handle-hook!
                               dir "codex" nil stop-payload)]
              (is (= :completed (:agent-status stop-result)))
              (is (= "Task completed."
                     (:last-message stop-result))))))
        (finally
          (cleanup-dir dir))))))

(deftest test-codex-hooks-stop-null-last-message
  (testing "Codex hooks Stop with null last_assistant_message falls back to default"
    (let [result (handler/normalize-event
                  "codex" "Stop"
                  {:session_id "null-msg-1"
                   :cwd "/tmp/work"
                   :hook_event_name "Stop"
                   :last_assistant_message nil
                   :model "o4-mini"
                   :permission_mode "default"
                   :stop_hook_active false})]
      (is (= :completed (:agent-status result)))
      (is (= "session ended" (:last-message result))))))

(deftest test-non-tmux-claude-lifecycle-uses-one-session-id-key
  (testing "SessionStart and Stop merge by session-id when no pane"
    (let [dir (temp-dir)]
      (try
        (with-redefs [handler/current-pane-id
                      (constantly "")]
          (handler/handle-hook!
           dir "claude" "SessionStart"
           (json/generate-string
            {:session_id "outside-tmux"
             :cwd "/tmp/work"}))
          (handler/handle-hook!
           dir "claude" "Stop"
           (json/generate-string
            {:session_id "outside-tmux"
             :cwd "/tmp/work"
             :last_assistant_message "Finished outside tmux."}))
          (let [sessions (:sessions
                          (store/read-sessions dir))
                session (get sessions "outside-tmux")]
            (is (= 1 (count sessions)))
            (is (= :completed (:agent-status session)))
            (is (= "Finished outside tmux."
                   (:last-message session)))))
        (finally
          (cleanup-dir dir))))))

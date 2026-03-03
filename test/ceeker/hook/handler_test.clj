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

(deftest test-normalize-claude-notification
  (let [result (handler/normalize-event
                "claude" "Notification"
                {:session_id "test-123"
                 :title "Working on task"
                 :cwd "/tmp/work"})]
    (is (= "test-123" (:session-id result)))
    (is (= :claude-code (:agent-type result)))
    (is (= :running (:agent-status result)))
    (is (= "Working on task" (:last-message result)))
    (is (some? (:last-updated result)))))

(deftest test-normalize-claude-stop
  (let [result (handler/normalize-event
                "claude" "Stop"
                {:session_id "test-456"
                 :cwd "/tmp/work"})]
    (is (= "test-456" (:session-id result)))
    (is (= :claude-code (:agent-type result)))
    (is (= :completed (:agent-status result)))))

(deftest test-normalize-claude-pre-tool-use
  (let [result (handler/normalize-event
                "claude" "PreToolUse"
                {:session_id "test-789"
                 :tool_name "Bash"
                 :cwd "/tmp/work"})]
    (is (= "test-789" (:session-id result)))
    (is (= :running (:agent-status result)))
    (is (= "using: Bash" (:last-message result)))))

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
                      :title "Testing hook"
                      :cwd "/tmp/hook-test"})
            result (handler/handle-hook!
                    dir "claude" "Notification" payload)]
        (is (= "hook-test-1" (:session-id result)))
        (is (= :claude-code (:agent-type result)))
        (let [stored (store/read-sessions dir)
              session (get-in stored
                              [:sessions "hook-test-1"])]
          (is (some? session))
          (is (= :claude-code (:agent-type session)))))
      (finally
        (cleanup-dir dir)))))

(deftest test-codex-notify-real-payload
  (let [dir (temp-dir)]
    (try
      (let [payload
            (json/generate-string
             {:type "agent-turn-complete"
              :thread-id "b5f6c1c2-1111-2222-3333-444455556666"
              :turn-id "12345"
              :cwd "/home/user/project"
              :client "codex-tui"
              :input-messages ["Fix the tests"]
              :last-assistant-message "All tests pass now."})
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
              session (get-in
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
        (is (= "notification" (:last-message result))))
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
                    dir "codex" "notification" payload)]
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
      (let [result (handler/handle-hook!
                    dir "claude" "Notification"
                    "not json")]
        (is (= :claude-code (:agent-type result)))
        (is (some? (:session-id result))))
      (finally
        (cleanup-dir dir)))))

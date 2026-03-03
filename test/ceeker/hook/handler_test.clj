(ns ceeker.hook.handler-test
  (:require [ceeker.hook.handler :as handler]
            [ceeker.state.store :as store]
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

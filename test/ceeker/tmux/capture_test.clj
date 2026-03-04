(ns ceeker.tmux.capture-test
  (:require [ceeker.tmux.capture :as capture]
            [clojure.test :refer [deftest is testing]]))

;; --- Claude Code state detection ---

(deftest test-claude-spinner-detected-as-running
  (testing "Claude spinner with interrupt hint -> running"
    (let [lines ["some output"
                 "\u273B Thinking\u2026 (esc to interrupt \u00B7 5s)"
                 ""]
          result (capture/detect-claude-state lines)]
      (is (= :running (:status result)))
      (is (nil? (:waiting-reason result))))))

(deftest test-claude-spinner-with-ellipsis
  (testing "Claude spinner with ellipsis only -> running"
    (let [lines ["\u2722 Compacting\u2026"]
          result (capture/detect-claude-state lines)]
      (is (= :running (:status result))))))

(deftest test-claude-file-activity-running
  (testing "File activity line with esc to interrupt -> running"
    (let [lines ["4 files +20 -0 \u00B7 esc to interrupt"]
          result (capture/detect-claude-state lines)]
      (is (= :running (:status result))))))

(deftest test-claude-question-dialog-waiting
  (testing "Question dialog -> waiting"
    (let [lines ["What would you like to do?"
                 "  1. Option A"
                 "  2. Option B"
                 "Enter to select"]
          result (capture/detect-claude-state lines)]
      (is (= :waiting (:status result)))
      (is (= "respond" (:waiting-reason result))))))

(deftest test-claude-plan-approval-waiting
  (testing "Plan approval selector -> waiting"
    (let [lines ["Plan:"
                 "  1. Create file"
                 "  2. Run tests"
                 "  3. Deploy"
                 "\u276F 1. Create file"]
          result (capture/detect-claude-state lines)]
      (is (= :waiting (:status result)))
      (is (= "respond" (:waiting-reason result))))))

(deftest test-claude-prompt-starship-idle
  (testing "Starship prompt -> idle"
    (let [lines ["previous output"
                 ""
                 "\u276F"]
          result (capture/detect-claude-state lines)]
      (is (= :idle (:status result))))))

(deftest test-claude-prompt-bash-idle
  (testing "Bash prompt -> idle"
    (let [lines ["some output"
                 "user@host:~/project$ "]
          result (capture/detect-claude-state lines)]
      (is (= :idle (:status result))))))

(deftest test-claude-prompt-zsh-idle
  (testing "Zsh prompt -> idle"
    (let [lines ["output"
                 "user@host % "]
          result (capture/detect-claude-state lines)]
      (is (= :idle (:status result))))))

(deftest test-claude-empty-lines-inconclusive
  (testing "All empty lines -> nil (inconclusive)"
    (let [lines ["" "" ""]
          result (capture/detect-claude-state lines)]
      (is (nil? result)))))

(deftest test-claude-spinner-priority-over-prompt
  (testing "Spinner takes priority over prompt in same output"
    (let [lines ["\u276F"
                 "\u273D Working\u2026 (esc to interrupt)"]
          result (capture/detect-claude-state lines)]
      (is (= :running (:status result))))))

;; --- Codex state detection ---

(deftest test-codex-running-detected
  (testing "Codex running line -> running"
    (let [lines ["\u2022 Working (48s \u2022 esc to interrupt)"]
          result (capture/detect-codex-state lines)]
      (is (= :running (:status result))))))

(deftest test-codex-prompt-idle
  (testing "Codex prompt \u203A -> idle"
    (let [lines ["previous output"
                 "\u203A "]
          result (capture/detect-codex-state lines)]
      (is (= :idle (:status result))))))

(deftest test-codex-selection-not-idle
  (testing "Codex selection \u203A N. is not prompt"
    (let [lines ["\u203A 1. First option"
                 "  2. Second option"]
          result (capture/detect-codex-state lines)]
      (is (not= :idle (:status result))))))

(deftest test-codex-question-dialog-waiting
  (testing "Codex question dialog -> waiting"
    (let [lines ["Choose an option:"
                 "Enter to select"]
          result (capture/detect-codex-state lines)]
      (is (= :waiting (:status result))))))

(deftest test-codex-empty-inconclusive
  (testing "Empty lines -> nil"
    (is (nil? (capture/detect-codex-state ["" ""])))))

;; --- detect-agent-state dispatch ---

(deftest test-detect-agent-state-nil-pane
  (testing "nil pane-id returns nil"
    (is (nil? (capture/detect-agent-state nil :claude-code)))
    (is (nil? (capture/detect-agent-state "" :claude-code)))))

;; --- capture-pane-content ---

(deftest test-capture-pane-content-nil-pane
  (testing "nil pane-id returns nil"
    (is (nil? (capture/capture-pane-content nil)))
    (is (nil? (capture/capture-pane-content "")))))

(ns ceeker.core-test
  (:require [ceeker.core :as core]
            [ceeker.tui.view :as view]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest payload-from-cli-accepts-inline-json
  (let [expected "{\"session_id\":\"foo\",\"message\":\"hi\"}"
        args ["codex" "notification" expected]]
    (is (= expected (core/payload-from-cli args)))))

(deftest payload-from-cli-ignores-empty-args
  (is (nil? (core/payload-from-cli
             ["codex" "notification"]))))

(deftest payload-from-cli-codex-notify-format
  (let [json-payload "{\"type\":\"agent-turn-complete\"}"
        args ["codex" json-payload]]
    (is (nil? (core/payload-from-cli args))
        "JSON in event-type position should not be extracted as payload")))

(deftest render-backward-compatibility
  (testing "2-arity render still works"
    (let [result (view/render [] 0)]
      (is (string? result))
      (is (str/includes? result "0 session(s)")))))

(deftest render-table-mode
  (testing "wide terminal uses table layout with column headers"
    (let [sessions [{:session-id "test1"
                     :agent-type :claude-code
                     :agent-status :running
                     :cwd "/home/user/project"
                     :last-message "Working..."
                     :last-updated "2026-01-01T12:00:00Z"}]
          result (view/render sessions 0 120 :auto)]
      (is (str/includes? result "SESSION"))
      (is (str/includes? result "AGENT"))
      (is (str/includes? result "MESSAGE")))))

(deftest render-compact-mode
  (testing "narrow terminal uses card layout without column headers"
    (let [sessions [{:session-id "test1"
                     :agent-type :claude-code
                     :agent-status :running
                     :cwd "/home/user/project"
                     :last-message "Working..."
                     :last-updated "2026-01-01T12:00:00Z"}]
          result (view/render sessions 0 40 :auto)]
      (is (not (str/includes? result "SESSION")))
      (is (str/includes? result "\u250c"))
      (is (str/includes? result "\u2502"))
      (is (str/includes? result "\u2514")))))

(deftest render-forced-card-mode
  (testing ":card mode forces card layout even on wide terminal"
    (let [sessions [{:session-id "test1"
                     :agent-type :codex
                     :agent-status :completed
                     :cwd "/tmp/work"
                     :last-message "Done"
                     :last-updated "2026-01-01T12:00:00Z"}]
          result (view/render sessions 0 120 :card)]
      (is (str/includes? result "\u250c"))
      (is (not (str/includes? result "SESSION"))))))

(deftest render-forced-table-mode
  (testing ":table mode forces table layout even on narrow terminal"
    (let [sessions [{:session-id "test1"
                     :agent-type :codex
                     :agent-status :completed
                     :cwd "/tmp/work"
                     :last-message "Done"
                     :last-updated "2026-01-01T12:00:00Z"}]
          result (view/render sessions 0 40 :table)]
      (is (str/includes? result "SESSION"))
      (is (not (str/includes? result "\u250c"))))))

(deftest render-footer-shows-display-mode
  (testing "footer reflects current display mode"
    (is (str/includes? (view/render [] 0 120 :auto)
                       "View:Auto"))
    (is (str/includes? (view/render [] 0 120 :table)
                       "View:Table"))
    (is (str/includes? (view/render [] 0 120 :card)
                       "View:Card"))))

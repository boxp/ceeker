(ns ceeker.core-test
  (:require [ceeker.core :as core]
            [ceeker.tui.view :as view]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.cli :as cli]))

(deftest version-is-loaded
  (testing "version reads from CEEKER_VERSION resource"
    (is (string? core/version))
    (is (not= "" core/version))))

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
      (is (str/includes? result "0 pane(s)")))))

(deftest render-table-mode
  (testing "wide terminal uses table layout with column headers"
    (let [sessions [{:agent-type :claude-code
                     :agent-status :running
                     :cwd "/home/user/project"
                     :pane-id "%1"
                     :last-message "Working..."
                     :last-updated "2026-01-01T12:00:00Z"}]
          result (view/render sessions 0 120 :auto)]
      (is (not (str/includes? result "SESSION"))
          "SESSION column should be removed")
      (is (str/includes? result "AGENT"))
      (is (str/includes? result "MESSAGE")))))

(deftest render-compact-mode
  (testing "narrow terminal uses card layout without column headers"
    (let [sessions [{:agent-type :claude-code
                     :agent-status :running
                     :cwd "/home/user/project"
                     :pane-id "%1"
                     :last-message "Working..."
                     :last-updated "2026-01-01T12:00:00Z"}]
          result (view/render sessions 0 40 :auto)]
      (is (not (str/includes? result "SESSION")))
      (is (str/includes? result "\u250c"))
      (is (str/includes? result "\u2502"))
      (is (str/includes? result "\u2514")))))

(deftest render-forced-card-mode
  (testing ":card mode forces card layout even on wide terminal"
    (let [sessions [{:agent-type :codex
                     :agent-status :completed
                     :cwd "/tmp/work"
                     :pane-id "%1"
                     :last-message "Done"
                     :last-updated "2026-01-01T12:00:00Z"}]
          result (view/render sessions 0 120 :card)]
      (is (str/includes? result "\u250c"))
      (is (not (str/includes? result "SESSION"))))))

(deftest render-forced-table-mode
  (testing ":table mode forces table layout even on narrow terminal"
    (let [sessions [{:agent-type :codex
                     :agent-status :completed
                     :cwd "/tmp/work"
                     :pane-id "%1"
                     :last-message "Done"
                     :last-updated "2026-01-01T12:00:00Z"}]
          result (view/render sessions 0 40 :table)]
      (is (str/includes? result "AGENT"))
      (is (not (str/includes? result "\u250c"))))))

(deftest render-footer-shows-display-mode
  (testing "footer reflects current display mode"
    (is (str/includes? (view/render [] 0 120 :auto) "View:Auto"))
    (is (str/includes? (view/render [] 0 120 :table) "View:Table"))
    (is (str/includes? (view/render [] 0 120 :card) "View:Card"))))

(deftest cli-options-include-startup-profile
  (testing "--startup-profile is parsed as a boolean option"
    (let [{:keys [options errors]}
          (cli/parse-opts ["--startup-profile"]
                          core/cli-options
                          :in-order true)]
      (is (nil? errors))
      (is (true? (:startup-profile options))))))

(deftest cli-accepts-view-option
  (testing "--view accepts supported startup views"
    (doseq [mode ["auto" "table" "card"]]
      (let [{:keys [errors options]} (cli/parse-opts
                                      ["--view" mode]
                                      core/cli-options
                                      :in-order true)]
        (is (nil? errors))
        (is (= (keyword mode)
               (:view options)))))))

(deftest cli-rejects-invalid-view-option
  (testing "--view rejects unsupported startup views"
    (let [{:keys [errors]} (cli/parse-opts
                            ["--view" "grid"]
                            core/cli-options
                            :in-order true)]
      (is (= 1 (count errors)))
      (is (str/includes? (first errors) "--view"))
      (is (str/includes? (first errors)
                         "Must be one of: auto, table, card")))))

(deftest cli-view-option-defaults-to-auto
  (testing "--view defaults to :auto when omitted"
    (let [{:keys [errors options]} (cli/parse-opts
                                    []
                                    core/cli-options
                                    :in-order true)]
      (is (nil? errors))
      (is (= :auto (:view options))))))

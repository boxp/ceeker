(ns ceeker.tui.app-test
  (:require [ceeker.tui.app :as app]
            [ceeker.tui.filter :as f]
            [clojure.test :refer [deftest is testing]]))

(deftest test-handle-search-key-backspace-delete
  (testing "delete/backspace removes last char and applies filter"
    (let [filter-state f/empty-filter
          result-delete (#'ceeker.tui.app/handle-search-key
                         \u007f "abc" filter-state)
          result-backspace (#'ceeker.tui.app/handle-search-key
                            \backspace "abc" filter-state)]
      (is (= "ab" (:sb result-delete)))
      (is (= "ab" (:sb result-backspace)))
      (is (= "ab" (get-in result-delete [:fs :search-query])))
      (is (= "ab" (get-in result-backspace [:fs :search-query]))))))

(deftest test-handle-search-key-backspace-empty
  (testing "backspace on empty buffer clears search query"
    (let [filter-state (f/set-search-query f/empty-filter "old")
          result (#'ceeker.tui.app/handle-search-key
                  \u007f "" filter-state)]
      (is (nil? (:sb result)))
      (is (nil? (get-in result [:fs :search-query]))))))

(deftest test-handle-search-key-enter-commits-query
  (testing "enter exits search mode and keeps current filter"
    (let [filter-state f/empty-filter
          result (#'ceeker.tui.app/handle-search-key
                  :enter "hello" filter-state)]
      (is (false? (:sm? result)))
      (is (nil? (:sb result)))
      (is (= "hello" (get-in result [:fs :search-query]))))))

(deftest test-handle-search-key-escape-clears
  (testing "escape exits search mode and clears search query"
    (let [filter-state (assoc f/empty-filter :search-query "old")
          result (#'ceeker.tui.app/handle-search-key
                  :escape "new" filter-state)]
      (is (false? (:sm? result)))
      (is (nil? (:sb result)))
      (is (nil? (get-in result [:fs :search-query]))))))

(deftest test-handle-search-key-char-applies-filter
  (testing "typing a character applies filter immediately"
    (let [filter-state f/empty-filter
          result (#'ceeker.tui.app/handle-search-key
                  \x "ab" filter-state)]
      (is (true? (:sm? result)))
      (is (= "abx" (:sb result)))
      (is (= "abx" (get-in result [:fs :search-query]))))))

(deftest test-nav-key-q-returns-quit
  (testing "q key returns explicit quit signal"
    (let [result (#'ceeker.tui.app/nav-key-result
                  \q 0 5 [] f/empty-filter :auto)]
      (is (true? (:quit result))))))

(deftest test-next-loop-state-quit
  (testing "quit result returns nil to exit loop"
    (let [result (#'ceeker.tui.app/next-loop-state
                  {:quit true} 0 f/empty-filter
                  false nil :auto)]
      (is (nil? result)))))

(deftest test-next-loop-state-idle
  (testing "idle result preserves current state"
    (let [result (#'ceeker.tui.app/next-loop-state
                  {:idle true} 2 f/empty-filter
                  false nil :table)]
      (is (= [2 nil f/empty-filter false nil :table] result)))))

(deftest test-handle-normal-key-q-propagates-quit
  (testing "handle-normal-key propagates quit from nav-key-result"
    (let [result (#'ceeker.tui.app/handle-normal-key
                  \q 0 5 [] f/empty-filter :auto)]
      (is (true? (:quit result)))
      (is (nil? (#'ceeker.tui.app/next-loop-state
                 result 0 f/empty-filter false nil :auto))))))

(deftest test-process-key-q-in-normal-mode
  (testing "process-key returns quit when q pressed in normal mode"
    (let [result (#'ceeker.tui.app/process-key
                  \q 0 false nil [] 0 f/empty-filter :auto)]
      (is (true? (:quit result))))))

(deftest test-process-key-q-in-search-mode
  (testing "q in search mode adds to buffer instead of quitting"
    (let [result (#'ceeker.tui.app/process-key
                  \q 0 true "" [] 0 f/empty-filter :auto)]
      (is (not (:quit result)))
      (is (true? (:sm? result))))))

;; --- tmux-jump! pane resolution tests ---

(deftest test-tmux-jump-prefers-pane-id
  (testing "uses pane-id directly when available"
    (with-redefs [ceeker.tui.app/switch-tmux-pane!
                  (fn [target]
                    {:success true :target target})]
      (let [session {:cwd "/home/user/project"
                     :pane-id "%5"
                     :session-id "test-1"
                     :agent-type :claude-code
                     :agent-status :running}
            result (#'ceeker.tui.app/tmux-jump! session)]
        (is (true? (:success result)))
        (is (= "%5" (:target result)))))))

(deftest test-tmux-jump-falls-back-to-cwd
  (testing "falls back to cwd search when pane-id is empty"
    (with-redefs [ceeker.tui.app/switch-tmux-pane!
                  (fn [target]
                    {:success true :target target})
                  ceeker.tui.app/find-tmux-pane
                  (fn [cwd]
                    (when (= cwd "/home/user/project")
                      "main:0.1"))]
      (let [session {:cwd "/home/user/project"
                     :pane-id ""
                     :session-id "test-2"
                     :agent-type :claude-code
                     :agent-status :running}
            result (#'ceeker.tui.app/tmux-jump! session)]
        (is (true? (:success result)))
        (is (= "main:0.1" (:target result)))))))

(deftest test-tmux-jump-pane-id-fallback-on-failure
  (testing "falls back to cwd when pane-id switch fails"
    (let [calls (atom [])]
      (with-redefs [ceeker.tui.app/switch-tmux-pane!
                    (fn [target]
                      (swap! calls conj target)
                      (if (= target "%99")
                        {:success false
                         :error "no such pane"}
                        {:success true :target target}))
                    ceeker.tui.app/find-tmux-pane
                    (fn [cwd]
                      (when (= cwd "/home/user/project")
                        "main:0.1"))]
        (let [session {:cwd "/home/user/project"
                       :pane-id "%99"
                       :session-id "test-3"
                       :agent-type :claude-code
                       :agent-status :running}
              result (#'ceeker.tui.app/tmux-jump! session)]
          (is (true? (:success result)))
          (is (= "main:0.1" (:target result)))
          (is (= ["%99" "main:0.1"] @calls)))))))

(deftest test-tmux-jump-same-cwd-different-panes
  (testing "two sessions with same cwd jump to correct panes"
    (with-redefs [ceeker.tui.app/switch-tmux-pane!
                  (fn [target]
                    {:success true :target target})]
      (let [session-a {:cwd "/home/user/project"
                       :pane-id "%1"
                       :session-id "a"
                       :agent-type :claude-code
                       :agent-status :running}
            session-b {:cwd "/home/user/project"
                       :pane-id "%5"
                       :session-id "b"
                       :agent-type :claude-code
                       :agent-status :running}
            result-a (#'ceeker.tui.app/tmux-jump!
                      session-a)
            result-b (#'ceeker.tui.app/tmux-jump!
                      session-b)]
        (is (= "%1" (:target result-a)))
        (is (= "%5" (:target result-b)))))))

(deftest test-tmux-jump-no-pane-id-no-cwd
  (testing "returns nil when session has no pane-id or cwd"
    (let [session {:session-id "test-4"
                   :agent-type :claude-code
                   :agent-status :running}
          result (#'ceeker.tui.app/tmux-jump! session)]
      (is (nil? result)))))

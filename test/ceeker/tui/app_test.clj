(ns ceeker.tui.app-test
  (:require [ceeker.tmux.pane :as pane]
            [ceeker.tui.app :as app]
            [ceeker.tui.filter :as f]
            [clojure.core.async :as async]
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
      (is (= "ab"
             (get-in result-backspace
                     [:fs :search-query]))))))

(deftest test-handle-search-key-backspace-empty
  (testing "backspace on empty buffer clears search query"
    (let [filter-state (f/set-search-query
                        f/empty-filter "old")
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
      (is (= "hello"
             (get-in result [:fs :search-query]))))))

(deftest test-handle-search-key-escape-clears
  (testing "escape exits search mode and clears search query"
    (let [filter-state (assoc f/empty-filter
                              :search-query "old")
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
      (is (= "abx"
             (get-in result [:fs :search-query]))))))

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
      (is (= [2 nil f/empty-filter false nil :table]
             result)))))

(deftest test-handle-normal-key-q-propagates-quit
  (testing "handle-normal-key propagates quit"
    (let [result (#'ceeker.tui.app/handle-normal-key
                  \q 0 5 [] f/empty-filter :auto)]
      (is (true? (:quit result)))
      (is (nil? (#'ceeker.tui.app/next-loop-state
                 result 0 f/empty-filter
                 false nil :auto))))))

(deftest test-process-key-q-in-normal-mode
  (testing "process-key returns quit when q pressed"
    (let [result (#'ceeker.tui.app/process-key
                  \q 0 false nil [] 0
                  f/empty-filter :auto)]
      (is (true? (:quit result))))))

(deftest test-process-key-q-in-search-mode
  (testing "q in search mode adds to buffer"
    (let [result (#'ceeker.tui.app/process-key
                  \q 0 true "" [] 0
                  f/empty-filter :auto)]
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
            result (#'ceeker.tui.app/tmux-jump!
                    session)]
        (is (true? (:success result)))
        (is (= "%5" (:target result)))))))

(deftest test-tmux-jump-falls-back-to-cwd
  (testing "falls back to cwd search when pane-id empty"
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
            result (#'ceeker.tui.app/tmux-jump!
                    session)]
        (is (true? (:success result)))
        (is (= "main:0.1" (:target result)))))))

(deftest test-tmux-jump-pane-id-fallback-on-failure
  (testing "falls back to cwd when pane-id switch fails"
    (let [calls (atom [])]
      (with-redefs
       [ceeker.tui.app/switch-tmux-pane!
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
              result (#'ceeker.tui.app/tmux-jump!
                      session)]
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
  (testing "returns nil when no pane-id or cwd"
    (let [session {:session-id "test-4"
                   :agent-type :claude-code
                   :agent-status :running}
          result (#'ceeker.tui.app/tmux-jump!
                  session)]
      (is (nil? result)))))

;; --- filter toggle tests ---

(deftest test-filter-key-a-toggles-agent
  (testing "pressing 'a' toggles agent filter"
    (let [result (#'ceeker.tui.app/filter-key-result
                  \a f/empty-filter)]
      (is (some? result))
      (is (= 0 (:sel result)))
      (is (= :claude-code
             (get-in result [:fs :agent-filter]))))))

(deftest test-filter-key-s-toggles-status
  (testing "pressing 's' toggles status filter"
    (let [result (#'ceeker.tui.app/filter-key-result
                  \s f/empty-filter)]
      (is (some? result))
      (is (= 0 (:sel result)))
      (is (= :running
             (get-in result [:fs :status-filter]))))))

(deftest test-filter-key-a-full-cycle-no-crash
  (testing "'a' key cycles through all agent filters"
    (let [r1 (#'ceeker.tui.app/filter-key-result
              \a f/empty-filter)
          r2 (#'ceeker.tui.app/filter-key-result
              \a (:fs r1))
          r3 (#'ceeker.tui.app/filter-key-result
              \a (:fs r2))]
      (is (= :claude-code
             (get-in r1 [:fs :agent-filter])))
      (is (= :codex
             (get-in r2 [:fs :agent-filter])))
      (is (nil? (get-in r3 [:fs :agent-filter]))))))

(deftest test-filter-key-s-full-cycle-no-crash
  (testing "'s' key cycles through all status filters"
    (loop [fs f/empty-filter
           expected [nil :running :completed :error
                     :waiting :idle nil]
           i 0]
      (when (< i (count expected))
        (is (= (nth expected i) (:status-filter fs))
            (str "iteration " i))
        (let [r (#'ceeker.tui.app/filter-key-result
                 \s fs)]
          (recur (:fs r) expected (inc i)))))))

;; --- core.async pane checker tests ---

(deftest test-start-pane-checker-returns-immediately
  (testing "start-pane-checker! returns without blocking"
    (let [blocker (promise)]
      (with-redefs [pane/close-stale-sessions!
                    (fn [_] @blocker)
                    pane/refresh-session-states!
                    (fn [_])]
        (let [t0 (System/nanoTime)
              stop-ch (#'ceeker.tui.app/start-pane-checker!
                       nil 100)
              elapsed (/ (- (System/nanoTime) t0)
                         1000000.0)]
          (is (< elapsed 500)
              "should return immediately")
          (async/close! stop-ch)
          (deliver blocker :done))))))

(deftest test-pane-checker-runs-immediately
  (testing "checker runs initial check before first interval"
    (let [called (promise)]
      (with-redefs [pane/close-stale-sessions!
                    (fn [_] (deliver called true))
                    pane/refresh-session-states!
                    (fn [_])]
        (let [stop-ch (#'ceeker.tui.app/start-pane-checker!
                       nil 60000)]
          (is (deref called 2000 false)
              "initial check should run immediately")
          (async/close! stop-ch))))))

(deftest test-pane-checker-executes-periodically
  (testing "checker runs pane check after interval"
    (let [second-call (promise)]
      (with-redefs [pane/close-stale-sessions!
                    (let [cnt (atom 0)]
                      (fn [_]
                        (when (>= (swap! cnt inc) 2)
                          (deliver second-call true))))
                    pane/refresh-session-states!
                    (fn [_])]
        (let [stop-ch (#'ceeker.tui.app/start-pane-checker!
                       nil 50)]
          (is (deref second-call 5000 false)
              "initial + periodic runs")
          (async/close! stop-ch))))))

(deftest test-pane-checker-continues-after-error
  (testing "checker continues when pane check throws"
    (let [second-call (promise)]
      (with-redefs [pane/close-stale-sessions!
                    (let [cnt (atom 0)]
                      (fn [_]
                        (when (>= (swap! cnt inc) 2)
                          (deliver second-call true))
                        (throw
                         (Exception. "tmux unavailable"))))
                    pane/refresh-session-states!
                    (fn [_])]
        (let [stop-ch (#'ceeker.tui.app/start-pane-checker!
                       nil 50)]
          (is (deref second-call 5000 false)
              "should retry after error")
          (async/close! stop-ch))))))

(deftest test-pane-checker-stops-on-close
  (testing "no checks after stop-ch is closed"
    (let [call-count (atom 0)
          first-call (promise)]
      (with-redefs [pane/close-stale-sessions!
                    (fn [_]
                      (swap! call-count inc)
                      (deliver first-call true))
                    pane/refresh-session-states!
                    (fn [_])]
        (let [stop-ch (#'ceeker.tui.app/start-pane-checker!
                       nil 60000)]
          (deref first-call 5000 false)
          (async/close! stop-ch)
          (Thread/sleep 200)
          (let [count-at-stop @call-count]
            (Thread/sleep 500)
            (is (= count-at-stop @call-count)
                "no more checks after stop")))))))

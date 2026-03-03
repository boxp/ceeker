(ns ceeker.tui.watcher-test
  (:require [ceeker.state.store :as store]
            [ceeker.tui.watcher :as watcher]
            [clojure.test :refer [deftest is testing]]))

(deftest test-create-watcher
  (testing "creates a watcher for valid state directory"
    (let [dir (store/state-dir)
          _ (store/ensure-state-dir! dir)
          w (watcher/create-watcher dir)]
      (is (some? w))
      (is (contains? w :watch-service))
      (is (= dir (:state-dir w)))
      (watcher/close-watcher w))))

(deftest test-create-watcher-invalid-dir
  (testing "returns nil for non-existent directory"
    (let [w (watcher/create-watcher
             "/nonexistent/path/ceeker-test")]
      (is (nil? w)))))

(deftest test-poll-change-nil-watcher
  (testing "poll-change returns nil for nil watcher"
    (is (nil? (watcher/poll-change nil 0)))))

(deftest test-poll-change-no-events
  (testing "poll-change returns false when no changes"
    (let [dir (store/state-dir)
          _ (store/ensure-state-dir! dir)
          w (watcher/create-watcher dir)]
      (is (false? (watcher/poll-change w 10)))
      (watcher/close-watcher w))))

(deftest test-poll-change-detects-modification
  (testing "poll-change detects sessions.edn changes"
    (let [dir (store/state-dir)
          _ (store/ensure-state-dir! dir)
          w (watcher/create-watcher dir)]
      ;; Write to sessions.edn
      (store/update-session!
       dir "test-watch" {:session-id "test-watch"
                         :agent-type :claude-code
                         :agent-status :running})
      ;; Give inotify time to register event
      (Thread/sleep 100)
      (let [changed? (watcher/poll-change w 500)]
        (is (true? changed?)))
      (store/remove-session! dir "test-watch")
      (watcher/close-watcher w))))

(deftest test-close-watcher-nil
  (testing "close-watcher handles nil gracefully"
    (is (nil? (watcher/close-watcher nil)))))

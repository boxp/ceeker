(ns ceeker.tui.filter-test
  (:require [ceeker.tui.filter :as f]
            [clojure.test :refer [deftest is testing]]))

(def sample-sessions
  [{:session-id "s1"
    :agent-type :claude-code
    :agent-status :running
    :cwd "/home/user/project-a"
    :last-message "Working"}
   {:session-id "s2"
    :agent-type :codex
    :agent-status :completed
    :cwd "/home/user/project-b"
    :last-message "Done"}
   {:session-id "s3"
    :agent-type :claude-code
    :agent-status :error
    :cwd "/home/user/project-c"
    :last-message "Failed"}
   {:session-id "s4"
    :agent-type :codex
    :agent-status :running
    :cwd "/home/user/project-d"
    :last-message "Active"}])

(deftest test-empty-filter
  (testing "empty filter passes all sessions"
    (let [result (f/apply-filters f/empty-filter
                                  sample-sessions)]
      (is (= 4 (count result))))))

(deftest test-agent-filter
  (testing "filter by claude-code"
    (let [fs (assoc f/empty-filter
                    :agent-filter :claude-code)
          result (f/apply-filters fs sample-sessions)]
      (is (= 2 (count result)))
      (is (every? #(= :claude-code (:agent-type %))
                  result))))

  (testing "filter by codex"
    (let [fs (assoc f/empty-filter
                    :agent-filter :codex)
          result (f/apply-filters fs sample-sessions)]
      (is (= 2 (count result)))
      (is (every? #(= :codex (:agent-type %))
                  result)))))

(deftest test-status-filter
  (testing "filter by running"
    (let [fs (assoc f/empty-filter
                    :status-filter :running)
          result (f/apply-filters fs sample-sessions)]
      (is (= 2 (count result)))
      (is (every? #(= :running (:agent-status %))
                  result))))

  (testing "filter by completed"
    (let [fs (assoc f/empty-filter
                    :status-filter :completed)
          result (f/apply-filters fs sample-sessions)]
      (is (= 1 (count result)))
      (is (= "s2" (:session-id (first result)))))))

(deftest test-search-filter
  (testing "search by session-id"
    (let [fs (f/set-search-query f/empty-filter "s1")
          result (f/apply-filters fs sample-sessions)]
      (is (= 1 (count result)))
      (is (= "s1" (:session-id (first result))))))

  (testing "search by cwd substring"
    (let [fs (f/set-search-query f/empty-filter
                                 "project-b")
          result (f/apply-filters fs sample-sessions)]
      (is (= 1 (count result)))
      (is (= "s2" (:session-id (first result))))))

  (testing "case-insensitive search"
    (let [fs (f/set-search-query f/empty-filter
                                 "PROJECT-A")
          result (f/apply-filters fs sample-sessions)]
      (is (= 1 (count result))))))

(deftest test-combined-filters
  (testing "agent + status filter"
    (let [fs {:agent-filter :claude-code
              :status-filter :running
              :search-query nil}
          result (f/apply-filters fs sample-sessions)]
      (is (= 1 (count result)))
      (is (= "s1" (:session-id (first result))))))

  (testing "agent + search filter"
    (let [fs {:agent-filter :codex
              :status-filter nil
              :search-query "project-d"}
          result (f/apply-filters fs sample-sessions)]
      (is (= 1 (count result)))
      (is (= "s4" (:session-id (first result)))))))

(deftest test-toggle-agent-filter
  (testing "cycles through agent filters"
    (let [f0 f/empty-filter
          f1 (f/toggle-agent-filter f0)
          f2 (f/toggle-agent-filter f1)
          f3 (f/toggle-agent-filter f2)]
      (is (nil? (:agent-filter f0)))
      (is (= :claude-code (:agent-filter f1)))
      (is (= :codex (:agent-filter f2)))
      (is (nil? (:agent-filter f3))))))

(deftest test-toggle-status-filter
  (testing "cycles through status filters"
    (let [f0 f/empty-filter
          f1 (f/toggle-status-filter f0)
          f2 (f/toggle-status-filter f1)]
      (is (nil? (:status-filter f0)))
      (is (= :running (:status-filter f1)))
      (is (= :completed (:status-filter f2))))))

(deftest test-clear-filters
  (testing "resets all filters"
    (let [fs {:agent-filter :codex
              :status-filter :running
              :search-query "test"}
          cleared (f/clear-filters fs)]
      (is (= f/empty-filter cleared)))))

(deftest test-active?
  (testing "empty filter is not active"
    (is (false? (f/active? f/empty-filter))))

  (testing "agent filter is active"
    (is (true? (f/active?
                (assoc f/empty-filter
                       :agent-filter :codex)))))

  (testing "search query is active"
    (is (true? (f/active?
                (assoc f/empty-filter
                       :search-query "test"))))))

(deftest test-describe-filters
  (testing "nil for empty filter"
    (is (nil? (f/describe-filters f/empty-filter))))

  (testing "describes agent filter"
    (let [fs (assoc f/empty-filter
                    :agent-filter :claude-code)]
      (is (= "agent:claude-code"
             (f/describe-filters fs)))))

  (testing "describes combined filters"
    (let [fs {:agent-filter :codex
              :status-filter :running
              :search-query "foo"}]
      (is (= "agent:codex | status:running | search:\"foo\""
             (f/describe-filters fs))))))

(deftest test-set-search-query
  (testing "sets non-empty query"
    (let [fs (f/set-search-query f/empty-filter "test")]
      (is (= "test" (:search-query fs)))))

  (testing "clears empty query"
    (let [fs (f/set-search-query f/empty-filter "")]
      (is (nil? (:search-query fs)))))

  (testing "trims whitespace"
    (let [fs (f/set-search-query f/empty-filter
                                 "  test  ")]
      (is (= "test" (:search-query fs))))))

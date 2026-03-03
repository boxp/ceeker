(ns ceeker.tui.app-test
  (:require [ceeker.tui.app :as app]
            [ceeker.tui.filter :as f]
            [clojure.test :refer [deftest is testing]]))

(deftest test-handle-search-key-backspace-delete
  (testing "delete/backspace removes the last character"
    (let [filter-state f/empty-filter
          result-delete (#'ceeker.tui.app/handle-search-key
                         \u007f "abc" filter-state)
          result-backspace (#'ceeker.tui.app/handle-search-key
                            \backspace "abc" filter-state)]
      (is (= "ab" (:sb result-delete)))
      (is (= "ab" (:sb result-backspace)))
      (is (= filter-state (:fs result-delete)))
      (is (= filter-state (:fs result-backspace))))))

(deftest test-handle-search-key-enter-commits-query
  (testing "enter exits search mode and applies query"
    (let [filter-state f/empty-filter
          result (#'ceeker.tui.app/handle-search-key
                  :enter "hello" filter-state)]
      (is (false? (:sm? result)))
      (is (nil? (:sb result)))
      (is (= "hello" (get-in result [:fs :search-query]))))))

(deftest test-handle-search-key-escape-cancels
  (testing "escape exits search mode without changing filters"
    (let [filter-state (assoc f/empty-filter :search-query "old")
          result (#'ceeker.tui.app/handle-search-key
                  :escape "new" filter-state)]
      (is (false? (:sm? result)))
      (is (nil? (:sb result)))
      (is (= filter-state (:fs result))))))

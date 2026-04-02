(ns ceeker.tui.input-test
  (:require [ceeker.tui.input :as input]
            [clojure.test :refer [deftest is testing]]))

(deftest test-create-terminal-profile-returns-breakdown
  (testing "create-terminal-profile reports build/raw-mode/total"
    (let [events (atom [])
          terminal ::terminal]
      (with-redefs [ceeker.tui.input/build-terminal
                    (fn []
                      (swap! events conj :build)
                      terminal)
                    ceeker.tui.input/enter-raw-mode!
                    (fn [t]
                      (swap! events conj [:raw-mode t])
                      t)]
        (let [profile (input/create-terminal-profile)]
          (is (= terminal (:terminal profile)))
          (is (number? (:build-ms profile)))
          (is (number? (:enter-raw-mode-ms profile)))
          (is (number? (:total-ms profile)))
          (is (<= 0.0 (:build-ms profile)))
          (is (<= 0.0 (:enter-raw-mode-ms profile)))
          (is (<= 0.0 (:total-ms profile)))
          (is (= [:build [:raw-mode terminal]]
                 @events)))))))

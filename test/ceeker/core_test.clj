(ns ceeker.core-test
  (:require [ceeker.core :as core]
            [clojure.test :refer [deftest is]]))

(deftest payload-from-cli-accepts-inline-json
  (let [expected "{\"session_id\":\"foo\",\"message\":\"hi\"}"
        args ["codex" "notification" expected]]
    (is (= expected (core/payload-from-cli args)))))

(deftest payload-from-cli-ignores-empty-args
  (is (nil? (core/payload-from-cli
             ["codex" "notification"]))))

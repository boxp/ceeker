(ns ceeker.core-test
  (:require [ceeker.core :as core]
            [clojure.test :refer [deftest is]]))

(deftest payload-from-cli-accepts-inline-json
  (is (= "{\"session_id\":\"foo\",\"message\":\"hi\"}"
         (core/payload-from-cli ["hook" "codex" "notification" "{\"session_id\":\"foo\",\"message\":\"hi\"}"]))))

(deftest payload-from-cli-ignores-empty-args
  (is (nil? (core/payload-from-cli ["hook" "codex" "notification"]))))

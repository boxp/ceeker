(ns ceeker.state.store-test
  (:require [ceeker.state.store :as store]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(defn- temp-dir
  "Creates a temporary directory for testing."
  []
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/ceeker-test-"
                 (System/nanoTime))]
    (.mkdirs (io/file dir))
    dir))

(defn- cleanup-dir
  "Removes temporary test directory."
  [dir]
  (let [f (io/file dir)]
    (when (.exists f)
      (doseq [file (reverse (file-seq f))]
        (.delete file)))))

(deftest test-ensure-state-dir
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/ceeker-ensure-test-"
                 (System/nanoTime))]
    (try
      (is (not (.exists (io/file dir))))
      (store/ensure-state-dir! dir)
      (is (.exists (io/file dir)))
      (is (.isDirectory (io/file dir)))
      (finally
        (cleanup-dir dir)))))

(deftest test-read-sessions-empty
  (let [dir (temp-dir)]
    (try
      (let [result (store/read-sessions dir)]
        (is (= {:sessions {}} result)))
      (finally
        (cleanup-dir dir)))))

(deftest test-update-and-read-session
  (let [dir (temp-dir)]
    (try
      (store/update-session! dir "%1"
                             {:agent-type :claude-code
                              :agent-status :running
                              :cwd "/tmp/work"
                              :pane-id "%1"
                              :last-message "working"})
      (let [result (store/read-sessions dir)
            session (get-in result [:sessions "%1"])]
        (is (some? session))
        (is (= :claude-code (:agent-type session)))
        (is (= :running (:agent-status session)))
        (is (= "/tmp/work" (:cwd session)))
        (is (= "working" (:last-message session))))
      (finally
        (cleanup-dir dir)))))

(deftest test-update-merges-data
  (let [dir (temp-dir)]
    (try
      (store/update-session! dir "%1"
                             {:agent-type :claude-code
                              :agent-status :running
                              :cwd "/tmp/work"
                              :pane-id "%1"
                              :last-message "first"})
      (store/update-session! dir "%1"
                             {:agent-status :completed
                              :last-message "done"})
      (let [result (store/read-sessions dir)
            session (get-in result [:sessions "%1"])]
        (is (= :completed (:agent-status session)))
        (is (= "done" (:last-message session)))
        (is (= :claude-code (:agent-type session)))
        (is (= "/tmp/work" (:cwd session))))
      (finally
        (cleanup-dir dir)))))

(deftest test-remove-session
  (let [dir (temp-dir)]
    (try
      (store/update-session! dir "%1"
                             {:agent-type :claude-code
                              :agent-status :running
                              :pane-id "%1"})
      (store/remove-session! dir "%1")
      (let [result (store/read-sessions dir)]
        (is (empty? (:sessions result))))
      (finally
        (cleanup-dir dir)))))

(deftest test-clear-sessions
  (let [dir (temp-dir)]
    (try
      (store/update-session! dir "%1"
                             {:agent-type :claude-code
                              :agent-status :running
                              :pane-id "%1"})
      (store/update-session! dir "%2"
                             {:agent-type :codex
                              :agent-status :running
                              :pane-id "%2"})
      (store/clear-sessions! dir)
      (let [result (store/read-sessions dir)]
        (is (= {:sessions {}} result)))
      (finally
        (cleanup-dir dir)))))

;; --- Pane-centric: same pane overwrites entry ---

(deftest test-same-pane-different-sessions-one-entry
  (testing "Hooks from different session-ids but same pane-id
            result in a single entry when using pane-id as key"
    (let [dir (temp-dir)]
      (try
        (store/update-session! dir "%42"
                               {:session-id "old-session"
                                :agent-type :claude-code
                                :agent-status :running
                                :cwd "/tmp/work"
                                :pane-id "%42"
                                :last-message "first"})
        (store/update-session! dir "%42"
                               {:session-id "new-session"
                                :agent-type :claude-code
                                :agent-status :running
                                :cwd "/tmp/work"
                                :pane-id "%42"
                                :last-message "second"})
        (let [state (store/read-sessions dir)
              sessions (:sessions state)]
          (is (= 1 (count sessions))
              "Same pane-id should produce exactly 1 entry")
          (is (= "second"
                 (:last-message (get sessions "%42")))
              "Latest data should overwrite"))
        (finally
          (cleanup-dir dir))))))

(deftest test-same-pane-different-cwd-one-entry
  (testing "CWD changes within same pane update the same entry"
    (let [dir (temp-dir)]
      (try
        (store/update-session! dir "%42"
                               {:agent-type :claude-code
                                :agent-status :running
                                :cwd "/tmp/old-cwd"
                                :pane-id "%42"})
        (store/update-session! dir "%42"
                               {:agent-type :claude-code
                                :agent-status :running
                                :cwd "/tmp/new-cwd"
                                :pane-id "%42"})
        (let [session (get-in (store/read-sessions dir)
                              [:sessions "%42"])]
          (is (= "/tmp/new-cwd" (:cwd session))))
        (finally
          (cleanup-dir dir))))))

(deftest test-different-panes-separate-entries
  (testing "Different pane-ids produce separate entries"
    (let [dir (temp-dir)]
      (try
        (store/update-session! dir "%1"
                               {:agent-type :claude-code
                                :agent-status :running
                                :cwd "/tmp/a"
                                :pane-id "%1"})
        (store/update-session! dir "%2"
                               {:agent-type :claude-code
                                :agent-status :running
                                :cwd "/tmp/b"
                                :pane-id "%2"})
        (let [sessions (:sessions (store/read-sessions dir))]
          (is (= 2 (count sessions)))
          (is (some? (get sessions "%1")))
          (is (some? (get sessions "%2"))))
        (finally
          (cleanup-dir dir))))))

(deftest test-normalize-dedupes-by-session-id-prefers-pane-key
  (testing "same session-id is collapsed and pane-id key wins"
    (let [sessions {"sess-1" {:session-id "sess-1"
                              :agent-type :codex
                              :agent-status :running
                              :last-updated "2026-01-01T00:00:01Z"}
                    "%7" {:session-id "sess-1"
                          :agent-type :codex
                          :agent-status :completed
                          :pane-id "%7"
                          :last-updated "2026-01-01T00:00:00Z"}}
          normalized (store/normalize-sessions sessions)]
      (is (= #{"%7"} (set (keys normalized))))
      (is (= :completed
             (:agent-status (get normalized "%7")))))))

(deftest test-normalize-dedupes-by-session-id-prefers-newer-without-pane
  (testing "same session-id without pane keeps the newest entry"
    (let [sessions {"old-key" {:session-id "sess-1"
                               :agent-status :running
                               :last-updated "2026-01-01T00:00:00Z"}
                    "new-key" {:session-id "sess-1"
                               :agent-status :completed
                               :last-updated "2026-01-01T00:00:02Z"}}
          normalized (store/normalize-sessions sessions)]
      (is (= 1 (count normalized)))
      (is (= :completed
             (:agent-status (first (vals normalized))))))))

;; --- update-session-if-active! ---

(deftest test-update-session-if-active-running
  (let [dir (temp-dir)]
    (try
      (store/update-session! dir "%1"
                             {:agent-type :claude-code
                              :agent-status :running
                              :pane-id "%1"})
      (is (true? (store/update-session-if-active!
                  dir "%1"
                  {:agent-status :waiting})))
      (let [s (get-in (store/read-sessions dir)
                      [:sessions "%1"])]
        (is (= :waiting (:agent-status s))))
      (finally
        (cleanup-dir dir)))))

(deftest test-update-session-if-active-completed-blocked
  (let [dir (temp-dir)]
    (try
      (store/update-session! dir "%1"
                             {:agent-type :claude-code
                              :agent-status :completed
                              :pane-id "%1"})
      (is (false? (store/update-session-if-active!
                   dir "%1"
                   {:agent-status :running})))
      (let [s (get-in (store/read-sessions dir)
                      [:sessions "%1"])]
        (is (= :completed (:agent-status s))))
      (finally
        (cleanup-dir dir)))))

;; --- reactivate-closed-session! ---

(deftest test-reactivate-closed-session
  (let [dir (temp-dir)]
    (try
      (store/update-session! dir "%1"
                             {:agent-type :claude-code
                              :agent-status :closed
                              :pane-id "%1"})
      (is (true? (store/reactivate-closed-session!
                  dir "%1"
                  {:agent-status :running})))
      (let [s (get-in (store/read-sessions dir)
                      [:sessions "%1"])]
        (is (= :running (:agent-status s))))
      (finally
        (cleanup-dir dir)))))

(deftest test-reactivate-non-closed-blocked
  (let [dir (temp-dir)]
    (try
      (store/update-session! dir "%1"
                             {:agent-type :claude-code
                              :agent-status :completed
                              :pane-id "%1"})
      (is (false? (store/reactivate-closed-session!
                   dir "%1"
                   {:agent-status :running})))
      (let [s (get-in (store/read-sessions dir)
                      [:sessions "%1"])]
        (is (= :completed (:agent-status s))))
      (finally
        (cleanup-dir dir)))))

;; --- close-sessions-by-pred! ---

(deftest test-close-sessions-by-pred-cwd
  (let [dir (temp-dir)]
    (try
      (store/update-session! dir "%1"
                             {:agent-type :claude-code
                              :agent-status :running
                              :cwd "/tmp/alive"
                              :pane-id "%1"})
      (store/update-session! dir "%2"
                             {:agent-type :codex
                              :agent-status :running
                              :cwd "/tmp/dead"
                              :pane-id "%2"})
      (store/close-sessions-by-pred!
       dir
       (fn [_key session]
         (= "/tmp/dead" (:cwd session))))
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "%1"])
            s2 (get-in state [:sessions "%2"])]
        (is (= :running (:agent-status s1)))
        (is (= :closed (:agent-status s2))))
      (finally
        (cleanup-dir dir)))))

;; --- purge-expired-closed-sessions! ---

(deftest test-purge-expired-sessions
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "%1"
       {:agent-type :claude-code
        :agent-status :closed
        :pane-id "%1"
        :last-updated (.toString
                       (.minusSeconds
                        (java.time.Instant/now) 600))})
      (store/update-session!
       dir "%2"
       {:agent-type :codex
        :agent-status :running
        :pane-id "%2"})
      (store/purge-expired-closed-sessions!
       dir #{"%2"} 1000)
      (let [sessions (:sessions
                      (store/read-sessions dir))]
        (is (nil? (get sessions "%1"))
            "Expired closed session should be purged")
        (is (some? (get sessions "%2"))
            "Running session should remain"))
      (finally
        (cleanup-dir dir)))))

(deftest test-purge-keeps-live-pane-sessions
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "%1"
       {:agent-type :claude-code
        :agent-status :closed
        :pane-id "%1"
        :last-updated (.toString
                       (.minusSeconds
                        (java.time.Instant/now) 600))})
      (store/purge-expired-closed-sessions!
       dir #{"%1"} 1000)
      (let [sessions (:sessions
                      (store/read-sessions dir))]
        (is (some? (get sessions "%1"))
            "Session with live pane should not be purged"))
      (finally
        (cleanup-dir dir)))))

;; --- close-sessions-by-pred! ---

(deftest test-close-sessions-by-pred
  (let [dir (temp-dir)]
    (try
      (store/update-session! dir "%1"
                             {:agent-type :claude-code
                              :agent-status :running
                              :cwd "/tmp/a"
                              :pane-id "%1"})
      (store/update-session! dir "%2"
                             {:agent-type :codex
                              :agent-status :running
                              :cwd "/tmp/b"
                              :pane-id "%2"})
      (store/close-sessions-by-pred!
       dir
       (fn [_key session]
         (= "/tmp/b" (:cwd session))))
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "%1"])
            s2 (get-in state [:sessions "%2"])]
        (is (= :running (:agent-status s1)))
        (is (= :closed (:agent-status s2))))
      (finally
        (cleanup-dir dir)))))

;; --- Non-tmux sessions use session-id as key ---

(deftest test-non-tmux-session-uses-session-id-key
  (testing "Sessions without pane-id are stored by session-id"
    (let [dir (temp-dir)]
      (try
        (store/update-session! dir "some-session-id"
                               {:agent-type :claude-code
                                :agent-status :running
                                :cwd "/tmp/work"
                                :pane-id ""})
        (let [s (get-in (store/read-sessions dir)
                        [:sessions "some-session-id"])]
          (is (some? s))
          (is (= :running (:agent-status s))))
        (finally
          (cleanup-dir dir))))))

;; --- Pane-centric migration: normalize-sessions ---

(deftest test-normalize-nil-sessions
  (testing "nil sessions returns empty map"
    (is (= {} (store/normalize-sessions nil)))))

(deftest test-normalize-mixed-keys
  (testing "Mixed session-id and pane-id keys are normalized
            to a single pane-id entry"
    (let [sessions {"0769ea34-abcd-1234-5678-000000000000"
                    {:session-id "0769ea34"
                     :agent-type :claude-code
                     :agent-status :running
                     :cwd "/tmp/work"
                     :pane-id "%66"
                     :last-updated "2026-03-10T10:00:00Z"
                     :last-message "old"}
                    "%66"
                    {:session-id "newer-session"
                     :agent-type :claude-code
                     :agent-status :running
                     :cwd "/tmp/work"
                     :pane-id "%66"
                     :last-updated "2026-03-10T11:00:00Z"
                     :last-message "new"}}
          result (store/normalize-sessions sessions)]
      (is (= 1 (count result))
          "Same pane-id entries should be merged into one")
      (is (some? (get result "%66"))
          "Canonical key should be pane-id")
      (is (nil? (get result
                     "0769ea34-abcd-1234-5678-000000000000"))
          "Old session-id key should be removed")
      (is (= "new" (:last-message (get result "%66")))
          "Entry with latest last-updated should win"))))

(deftest test-normalize-keeps-newer-entry
  (testing "When session-id key has newer timestamp, it wins"
    (let [sessions {"old-uuid"
                    {:session-id "old-uuid"
                     :agent-type :claude-code
                     :agent-status :completed
                     :pane-id "%10"
                     :last-updated "2026-03-11T12:00:00Z"
                     :last-message "later"}
                    "%10"
                    {:session-id "new-uuid"
                     :agent-type :claude-code
                     :agent-status :running
                     :pane-id "%10"
                     :last-updated "2026-03-10T08:00:00Z"
                     :last-message "earlier"}}
          result (store/normalize-sessions sessions)]
      (is (= 1 (count result)))
      (is (= "later" (:last-message (get result "%10")))
          "Entry with later timestamp should win"))))

(deftest test-normalize-different-panes-separate
  (testing "Entries with different pane-ids stay separate"
    (let [sessions {"uuid-a"
                    {:session-id "uuid-a"
                     :agent-type :claude-code
                     :agent-status :running
                     :pane-id "%1"
                     :last-updated "2026-03-10T10:00:00Z"}
                    "uuid-b"
                    {:session-id "uuid-b"
                     :agent-type :codex
                     :agent-status :running
                     :pane-id "%2"
                     :last-updated "2026-03-10T10:00:00Z"}}
          result (store/normalize-sessions sessions)]
      (is (= 2 (count result)))
      (is (some? (get result "%1")))
      (is (some? (get result "%2"))))))

(deftest test-normalize-empty-pane-id-keeps-original-key
  (testing "Entries without pane-id keep their original key"
    (let [sessions {"some-uuid"
                    {:session-id "some-uuid"
                     :agent-type :claude-code
                     :agent-status :running
                     :pane-id ""
                     :last-updated "2026-03-10T10:00:00Z"}}
          result (store/normalize-sessions sessions)]
      (is (= 1 (count result)))
      (is (some? (get result "some-uuid"))
          "Non-tmux session keeps session-id as key"))))

(deftest test-normalize-multiple-legacy-same-pane
  (testing "Multiple legacy session-id keys for same pane
            are collapsed to one"
    (let [sessions {"uuid-1"
                    {:session-id "uuid-1"
                     :agent-type :claude-code
                     :agent-status :completed
                     :pane-id "%42"
                     :last-updated "2026-03-10T08:00:00Z"
                     :last-message "first"}
                    "uuid-2"
                    {:session-id "uuid-2"
                     :agent-type :claude-code
                     :agent-status :running
                     :pane-id "%42"
                     :last-updated "2026-03-10T10:00:00Z"
                     :last-message "second"}
                    "uuid-3"
                    {:session-id "uuid-3"
                     :agent-type :claude-code
                     :agent-status :idle
                     :pane-id "%42"
                     :last-updated "2026-03-10T09:00:00Z"
                     :last-message "third"}}
          result (store/normalize-sessions sessions)]
      (is (= 1 (count result))
          "All entries for same pane-id collapse to one")
      (is (= "second" (:last-message (get result "%42")))
          "Entry with latest timestamp should win"))))

(deftest test-mixed-state-read-write-cleanup
  (testing "Reading mixed state then writing produces clean file"
    (let [dir (temp-dir)]
      (try
        (store/ensure-state-dir! dir)
        (spit (store/state-file-path dir)
              (pr-str
               {:sessions
                {"old-uuid"
                 {:session-id "old-uuid"
                  :agent-type :claude-code
                  :agent-status :completed
                  :pane-id "%50"
                  :last-updated "2026-03-10T08:00:00Z"
                  :last-message "old"}
                 "%50"
                 {:session-id "new-uuid"
                  :agent-type :claude-code
                  :agent-status :running
                  :pane-id "%50"
                  :last-updated "2026-03-10T12:00:00Z"
                  :last-message "current"}}}))
        (let [state (store/read-sessions dir)
              sessions (:sessions state)]
          (is (= 1 (count sessions))
              "Mixed state normalized on read")
          (is (= "current"
                 (:last-message (get sessions "%50")))))
        (store/update-session! dir "%50"
                               {:last-message "updated"})
        (let [raw (edn/read-string
                   (slurp (store/state-file-path dir)))
              ks (keys (:sessions raw))]
          (is (= 1 (count ks))
              "File contains only canonical keys after write")
          (is (= "%50" (first ks))
              "Key should be pane-id"))
        (finally
          (cleanup-dir dir))))))

(deftest test-update-after-normalize-no-legacy-remnants
  (testing "update-session! on normalized state leaves
            no legacy key remnants"
    (let [dir (temp-dir)]
      (try
        (store/ensure-state-dir! dir)
        (spit (store/state-file-path dir)
              (pr-str
               {:sessions
                {"legacy-uuid"
                 {:session-id "legacy-uuid"
                  :agent-type :claude-code
                  :agent-status :running
                  :pane-id "%77"
                  :last-updated "2026-03-10T08:00:00Z"
                  :last-message "legacy"}}}))
        (store/update-session! dir "%77"
                               {:agent-status :completed
                                :last-message "done"})
        (let [state (store/read-sessions dir)
              sessions (:sessions state)]
          (is (= 1 (count sessions)))
          (is (some? (get sessions "%77")))
          (is (nil? (get sessions "legacy-uuid"))
              "Legacy key should not exist after update")
          (is (= :completed
                 (:agent-status (get sessions "%77")))))
        (finally
          (cleanup-dir dir))))))

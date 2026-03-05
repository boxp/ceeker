(ns ceeker.state.store-test
  (:require [ceeker.state.store :as store]
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
      (store/update-session! dir "session-1"
                             {:agent-type :claude-code
                              :agent-status :running
                              :cwd "/tmp/work"
                              :last-message "working"})
      (let [result (store/read-sessions dir)
            session (get-in result [:sessions "session-1"])]
        (is (some? session))
        (is (= :claude-code (:agent-type session)))
        (is (= :running (:agent-status session)))
        (is (= "/tmp/work" (:cwd session)))
        (is (= "working" (:last-message session))))
      (finally
        (cleanup-dir dir)))))

(deftest test-update-session-merge
  (let [dir (temp-dir)]
    (try
      (store/update-session! dir "session-1"
                             {:agent-type :claude-code
                              :agent-status :running
                              :cwd "/tmp/work"})
      (store/update-session! dir "session-1"
                             {:agent-status :completed
                              :last-message "done"})
      (let [session (get-in
                     (store/read-sessions dir)
                     [:sessions "session-1"])]
        (is (= :claude-code (:agent-type session)))
        (is (= :completed (:agent-status session)))
        (is (= "/tmp/work" (:cwd session)))
        (is (= "done" (:last-message session))))
      (finally
        (cleanup-dir dir)))))

(deftest test-multiple-sessions
  (let [dir (temp-dir)]
    (try
      (store/update-session! dir "s1"
                             {:agent-type :claude-code
                              :agent-status :running})
      (store/update-session! dir "s2"
                             {:agent-type :codex
                              :agent-status :waiting})
      (let [result (store/read-sessions dir)]
        (is (= 2 (count (:sessions result))))
        (is (= :claude-code
               (get-in result [:sessions "s1" :agent-type])))
        (is (= :codex
               (get-in result [:sessions "s2" :agent-type]))))
      (finally
        (cleanup-dir dir)))))

(deftest test-remove-session
  (let [dir (temp-dir)]
    (try
      (store/update-session! dir "s1"
                             {:agent-type :claude-code})
      (store/update-session! dir "s2"
                             {:agent-type :codex})
      (store/remove-session! dir "s1")
      (let [result (store/read-sessions dir)]
        (is (= 1 (count (:sessions result))))
        (is (nil? (get-in result [:sessions "s1"])))
        (is (some? (get-in result [:sessions "s2"]))))
      (finally
        (cleanup-dir dir)))))

(deftest test-clear-sessions
  (let [dir (temp-dir)]
    (try
      (store/update-session! dir "s1"
                             {:agent-type :claude-code})
      (store/update-session! dir "s2"
                             {:agent-type :codex})
      (store/clear-sessions! dir)
      (let [result (store/read-sessions dir)]
        (is (= 0 (count (:sessions result)))))
      (finally
        (cleanup-dir dir)))))

(deftest test-close-stale-sessions
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "s1"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/alive"})
      (store/update-session!
       dir "s2"
       {:agent-type :codex
        :agent-status :running
        :cwd "/dead"})
      (store/update-session!
       dir "s3"
       {:agent-type :claude-code
        :agent-status :completed
        :cwd "/also-dead"})
      (store/close-stale-sessions!
       dir #{"/alive"})
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "s1"])
            s2 (get-in state [:sessions "s2"])
            s3 (get-in state [:sessions "s3"])]
        (is (= :running (:agent-status s1)))
        (is (= :closed (:agent-status s2)))
        (is (= "pane closed"
               (:last-message s2)))
        (is (= :completed (:agent-status s3))))
      (finally
        (cleanup-dir dir)))))

(deftest test-close-stale-empty-cwd-untouched
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "s1"
       {:agent-type :codex
        :agent-status :running
        :cwd ""})
      (store/close-stale-sessions! dir #{})
      (let [s1 (get-in (store/read-sessions dir)
                       [:sessions "s1"])]
        (is (= :running (:agent-status s1))))
      (finally
        (cleanup-dir dir)))))

(deftest test-close-stale-idle-session
  (testing "Idle sessions are also closed when stale"
    (let [dir (temp-dir)]
      (try
        (store/update-session!
         dir "s1"
         {:agent-type :claude-code
          :agent-status :idle
          :cwd "/gone"
          :last-message "idle"})
        (store/close-stale-sessions! dir #{})
        (let [s1 (get-in (store/read-sessions dir)
                         [:sessions "s1"])]
          (is (= :closed (:agent-status s1)))
          (is (= "pane closed" (:last-message s1))))
        (finally
          (cleanup-dir dir))))))

(deftest test-close-stale-waiting-session
  (testing "Waiting sessions are also closed when stale"
    (let [dir (temp-dir)]
      (try
        (store/update-session!
         dir "s1"
         {:agent-type :codex
          :agent-status :waiting
          :cwd "/gone"
          :last-message "waiting"})
        (store/close-stale-sessions! dir #{})
        (let [s1 (get-in (store/read-sessions dir)
                         [:sessions "s1"])]
          (is (= :closed (:agent-status s1))))
        (finally
          (cleanup-dir dir))))))

;; --- Supersede-per-Key tests (B) ---

(deftest test-supersede-closes-old-session
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "old-session"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/work"
        :pane-id "%42"
        :last-message "working"})
      (store/update-session!
       dir "new-session"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/work"
        :pane-id "%42"
        :last-message "resumed"})
      (let [state (store/read-sessions dir)
            old (get-in state [:sessions "old-session"])
            new (get-in state [:sessions "new-session"])]
        (is (= :closed (:agent-status old)))
        (is (= "superseded" (:last-message old)))
        (is (= :running (:agent-status new))))
      (finally
        (cleanup-dir dir)))))

(deftest test-supersede-different-pane-no-close
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "s1"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/work"
        :pane-id "%42"})
      (store/update-session!
       dir "s2"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/work"
        :pane-id "%99"})
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "s1"])
            s2 (get-in state [:sessions "s2"])]
        (is (= :running (:agent-status s1)))
        (is (= :running (:agent-status s2))))
      (finally
        (cleanup-dir dir)))))

(deftest test-supersede-different-cwd-no-close
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "s1"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/work-a"
        :pane-id "%42"})
      (store/update-session!
       dir "s2"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/work-b"
        :pane-id "%42"})
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "s1"])
            s2 (get-in state [:sessions "s2"])]
        (is (= :running (:agent-status s1)))
        (is (= :running (:agent-status s2))))
      (finally
        (cleanup-dir dir)))))

(deftest test-supersede-different-agent-no-close
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "s1"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/work"
        :pane-id "%42"})
      (store/update-session!
       dir "s2"
       {:agent-type :codex
        :agent-status :running
        :cwd "/tmp/work"
        :pane-id "%42"})
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "s1"])
            s2 (get-in state [:sessions "s2"])]
        (is (= :running (:agent-status s1)))
        (is (= :running (:agent-status s2))))
      (finally
        (cleanup-dir dir)))))

(deftest test-supersede-empty-pane-id-no-close
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "s1"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/work"
        :pane-id ""})
      (store/update-session!
       dir "s2"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/work"
        :pane-id ""})
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "s1"])
            s2 (get-in state [:sessions "s2"])]
        (is (= :running (:agent-status s1)))
        (is (= :running (:agent-status s2))))
      (finally
        (cleanup-dir dir)))))

(deftest test-supersede-completed-not-closed
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "s1"
       {:agent-type :claude-code
        :agent-status :completed
        :cwd "/tmp/work"
        :pane-id "%42"})
      (store/update-session!
       dir "s2"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/work"
        :pane-id "%42"})
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "s1"])]
        (is (= :completed (:agent-status s1))))
      (finally
        (cleanup-dir dir)))))

(deftest test-supersede-non-running-update-no-close
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "s1"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/work"
        :pane-id "%42"
        :last-message "active"})
      (store/update-session!
       dir "s2"
       {:agent-type :claude-code
        :agent-status :completed
        :cwd "/tmp/work"
        :pane-id "%42"
        :last-message "done"})
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "s1"])]
        (is (= :running (:agent-status s1)))
        (is (= "active" (:last-message s1))))
      (finally
        (cleanup-dir dir)))))

(deftest test-superseded-session-stays-closed
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "old"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/work"
        :pane-id "%42"
        :last-message "working"})
      (store/update-session!
       dir "new"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/work"
        :pane-id "%42"
        :last-message "resumed"})
      ;; old is now superseded
      (let [s (get-in (store/read-sessions dir)
                      [:sessions "old"])]
        (is (= :closed (:agent-status s)))
        (is (= "superseded" (:last-message s))))
      ;; delayed hook tries to set old back to running
      (store/update-session!
       dir "old"
       {:agent-status :running
        :last-message "delayed event"})
      ;; old must stay closed
      (let [s (get-in (store/read-sessions dir)
                      [:sessions "old"])]
        (is (= :closed (:agent-status s)))
        (is (= "superseded" (:last-message s))))
      ;; non-running update merges but flag is kept
      (store/update-session!
       dir "old"
       {:agent-status :completed
        :last-message "stop event"})
      (let [s (get-in (store/read-sessions dir)
                      [:sessions "old"])]
        (is (= :completed (:agent-status s)))
        (is (= "stop event" (:last-message s)))
        (is (true? (:superseded s))))
      ;; running update still blocked after non-running
      (store/update-session!
       dir "old"
       {:agent-status :running
        :last-message "late resume"})
      (let [s (get-in (store/read-sessions dir)
                      [:sessions "old"])]
        (is (= :completed (:agent-status s)))
        (is (true? (:superseded s))))
      (finally
        (cleanup-dir dir)))))

;; --- update-session-if-active! tests ---

(deftest test-update-if-active-applies-when-running
  (testing "Updates session when it is still :running"
    (let [dir (temp-dir)]
      (try
        (store/update-session!
         dir "s1"
         {:agent-type :claude-code
          :agent-status :running
          :cwd "/tmp/work"
          :last-message "working"})
        (let [applied (store/update-session-if-active!
                       dir "s1"
                       {:agent-status :idle
                        :last-message "idle"})]
          (is (true? applied))
          (let [s (get-in (store/read-sessions dir)
                          [:sessions "s1"])]
            (is (= :idle (:agent-status s)))
            (is (= "idle" (:last-message s)))
            (is (= :claude-code (:agent-type s)))))
        (finally
          (cleanup-dir dir))))))

(deftest test-update-if-active-applies-when-idle
  (testing "Updates session when it is :idle"
    (let [dir (temp-dir)]
      (try
        (store/update-session!
         dir "s1"
         {:agent-type :claude-code
          :agent-status :idle
          :cwd "/tmp/work"
          :last-message "idle"})
        (let [applied (store/update-session-if-active!
                       dir "s1"
                       {:agent-status :running
                        :last-message "running"})]
          (is (true? applied))
          (let [s (get-in (store/read-sessions dir)
                          [:sessions "s1"])]
            (is (= :running (:agent-status s)))))
        (finally
          (cleanup-dir dir))))))

(deftest test-update-if-active-applies-when-waiting
  (testing "Updates session when it is :waiting"
    (let [dir (temp-dir)]
      (try
        (store/update-session!
         dir "s1"
         {:agent-type :claude-code
          :agent-status :waiting
          :cwd "/tmp/work"
          :last-message "waiting"})
        (let [applied (store/update-session-if-active!
                       dir "s1"
                       {:agent-status :running
                        :last-message "running"})]
          (is (true? applied))
          (let [s (get-in (store/read-sessions dir)
                          [:sessions "s1"])]
            (is (= :running (:agent-status s)))))
        (finally
          (cleanup-dir dir))))))

(deftest test-update-if-active-skips-completed
  (testing "Skips update when session is :completed"
    (let [dir (temp-dir)]
      (try
        (store/update-session!
         dir "s1"
         {:agent-type :claude-code
          :agent-status :completed
          :cwd "/tmp/work"
          :last-message "done"})
        (let [applied (store/update-session-if-active!
                       dir "s1"
                       {:agent-status :idle
                        :last-message "idle"})]
          (is (false? applied))
          (let [s (get-in (store/read-sessions dir)
                          [:sessions "s1"])]
            (is (= :completed (:agent-status s)))
            (is (= "done" (:last-message s)))))
        (finally
          (cleanup-dir dir))))))

(deftest test-update-if-active-skips-nonexistent
  (testing "Skips update for non-existent session"
    (let [dir (temp-dir)]
      (try
        (let [applied (store/update-session-if-active!
                       dir "missing"
                       {:agent-status :idle})]
          (is (false? applied)))
        (finally
          (cleanup-dir dir))))))

;; --- reactivate-closed-session! tests ---

(deftest test-reactivate-closed-non-superseded
  (testing "Closed non-superseded session can be reactivated"
    (let [dir (temp-dir)]
      (try
        (store/update-session!
         dir "s1"
         {:agent-type :claude-code
          :agent-status :running
          :cwd "/tmp/work"
          :pane-id "%42"
          :last-message "working"})
        ;; Close it via stale detection
        (store/close-stale-sessions! dir #{})
        (let [s (get-in (store/read-sessions dir)
                        [:sessions "s1"])]
          (is (= :closed (:agent-status s))))
        ;; Reactivate it
        (let [applied (store/reactivate-closed-session!
                       dir "s1"
                       {:agent-status :running
                        :last-message "reactivated: running"
                        :last-updated
                        (.toString
                         (java.time.Instant/now))})]
          (is (true? applied))
          (let [s (get-in (store/read-sessions dir)
                          [:sessions "s1"])]
            (is (= :running (:agent-status s)))
            (is (= "reactivated: running"
                   (:last-message s)))))
        (finally
          (cleanup-dir dir))))))

(deftest test-reactivate-superseded-blocked
  (testing "Superseded closed session cannot be reactivated"
    (let [dir (temp-dir)]
      (try
        (store/update-session!
         dir "old"
         {:agent-type :claude-code
          :agent-status :running
          :cwd "/tmp/work"
          :pane-id "%42"
          :last-message "working"})
        (store/update-session!
         dir "new"
         {:agent-type :claude-code
          :agent-status :running
          :cwd "/tmp/work"
          :pane-id "%42"
          :last-message "resumed"})
        ;; old is superseded
        (let [s (get-in (store/read-sessions dir)
                        [:sessions "old"])]
          (is (true? (:superseded s))))
        ;; Reactivate attempt should fail
        (let [applied (store/reactivate-closed-session!
                       dir "old"
                       {:agent-status :running
                        :last-message "reactivated"})]
          (is (false? applied))
          (let [s (get-in (store/read-sessions dir)
                          [:sessions "old"])]
            (is (= :closed (:agent-status s)))))
        (finally
          (cleanup-dir dir))))))

(deftest test-reactivate-running-session-noop
  (testing "Running session is not reactivated (already active)"
    (let [dir (temp-dir)]
      (try
        (store/update-session!
         dir "s1"
         {:agent-type :claude-code
          :agent-status :running
          :cwd "/tmp/work"
          :last-message "working"})
        (let [applied (store/reactivate-closed-session!
                       dir "s1"
                       {:agent-status :running
                        :last-message "reactivated"})]
          (is (false? applied))
          (let [s (get-in (store/read-sessions dir)
                          [:sessions "s1"])]
            (is (= :running (:agent-status s)))
            (is (= "working" (:last-message s)))))
        (finally
          (cleanup-dir dir))))))

;; --- purge-expired-closed-sessions! tests ---

(deftest test-purge-expired-closed-sessions
  (testing "Expired closed sessions are purged when pane is gone"
    (let [dir (temp-dir)]
      (try
        ;; Create a closed session with old timestamp
        (store/update-session!
         dir "s1"
         {:agent-type :claude-code
          :agent-status :closed
          :cwd "/tmp/gone"
          :pane-id "%42"
          :last-message "pane closed"
          :last-updated (.toString
                         (.minusSeconds
                          (java.time.Instant/now) 600))})
        ;; Create a running session
        (store/update-session!
         dir "s2"
         {:agent-type :codex
          :agent-status :running
          :cwd "/tmp/alive"
          :pane-id "%99"
          :last-message "working"})
        ;; Purge with no live pane-ids
        (store/purge-expired-closed-sessions!
         dir #{} 1000)
        (let [state (store/read-sessions dir)]
          (is (nil? (get-in state [:sessions "s1"]))
              "Expired closed session should be purged")
          (is (some? (get-in state [:sessions "s2"]))
              "Running session should remain"))
        (finally
          (cleanup-dir dir))))))

(deftest test-purge-keeps-recent-closed
  (testing "Recently closed sessions are not purged"
    (let [dir (temp-dir)]
      (try
        (store/update-session!
         dir "s1"
         {:agent-type :claude-code
          :agent-status :closed
          :cwd "/tmp/gone"
          :pane-id "%42"
          :last-message "pane closed"
          :last-updated (.toString
                         (java.time.Instant/now))})
        (store/purge-expired-closed-sessions!
         dir #{} 300000)
        (let [state (store/read-sessions dir)]
          (is (some? (get-in state [:sessions "s1"]))
              "Recently closed session should not be purged"))
        (finally
          (cleanup-dir dir))))))

(deftest test-purge-keeps-superseded-sessions
  (testing "Superseded closed sessions are never purged"
    (let [dir (temp-dir)]
      (try
        (store/update-session!
         dir "old"
         {:agent-type :claude-code
          :agent-status :running
          :cwd "/tmp/work"
          :pane-id "%42"
          :last-message "working"})
        (store/update-session!
         dir "new"
         {:agent-type :claude-code
          :agent-status :running
          :cwd "/tmp/work"
          :pane-id "%42"
          :last-message "resumed"})
        ;; old is now superseded+closed
        (let [s (get-in (store/read-sessions dir)
                        [:sessions "old"])]
          (is (= :closed (:agent-status s)))
          (is (true? (:superseded s))))
        ;; Purge with 0 TTL and no live panes
        (store/purge-expired-closed-sessions!
         dir #{} 0)
        (let [state (store/read-sessions dir)]
          (is (some? (get-in state [:sessions "old"]))
              "Superseded session must not be purged"))
        (finally
          (cleanup-dir dir))))))

(deftest test-purge-keeps-closed-with-live-pane
  (testing "Closed session with live pane-id is not purged"
    (let [dir (temp-dir)]
      (try
        (store/update-session!
         dir "s1"
         {:agent-type :claude-code
          :agent-status :closed
          :cwd "/tmp/gone"
          :pane-id "%42"
          :last-message "pane closed"
          :last-updated (.toString
                         (.minusSeconds
                          (java.time.Instant/now) 600))})
        (store/purge-expired-closed-sessions!
         dir #{"%42"} 1000)
        (let [state (store/read-sessions dir)]
          (is (some? (get-in state [:sessions "s1"]))
              "Closed session with live pane should not be purged"))
        (finally
          (cleanup-dir dir))))))

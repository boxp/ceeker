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

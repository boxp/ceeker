(ns ceeker.state.store-test
  (:require [ceeker.state.store :as store]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

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
      ;; even a non-running update should not clear
      ;; superseded state
      (store/update-session!
       dir "old"
       {:agent-status :completed
        :last-message "stop event"})
      (let [s (get-in (store/read-sessions dir)
                      [:sessions "old"])]
        (is (= :closed (:agent-status s)))
        (is (= "superseded" (:last-message s))))
      (finally
        (cleanup-dir dir)))))

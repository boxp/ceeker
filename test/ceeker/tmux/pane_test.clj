(ns ceeker.tmux.pane-test
  (:require [ceeker.state.store :as store]
            [ceeker.tmux.capture :as capture]
            [ceeker.tmux.pane :as pane]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(defn- temp-dir
  "Creates a temporary directory for testing."
  []
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/ceeker-pane-test-"
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

(deftest test-list-pane-cwds-returns-nil-or-set
  (let [result (pane/list-pane-cwds)]
    (is (or (nil? result) (set? result)))))

(deftest test-close-stale-via-store
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "s1"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/alive"
        :last-message "working"})
      (store/update-session!
       dir "s2"
       {:agent-type :codex
        :agent-status :running
        :cwd "/tmp/dead"
        :last-message "working"})
      (store/close-stale-sessions!
       dir #{"/tmp/alive"})
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "s1"])
            s2 (get-in state [:sessions "s2"])]
        (is (= :running (:agent-status s1)))
        (is (= :closed (:agent-status s2)))
        (is (= "working" (:last-message s2))
            "last-message preserved when pane closes"))
      (finally
        (cleanup-dir dir)))))

(deftest test-completed-not-affected
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "s1"
       {:agent-type :claude-code
        :agent-status :completed
        :cwd "/tmp/gone"
        :last-message "done"})
      (store/close-stale-sessions! dir #{})
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "s1"])]
        (is (= :completed (:agent-status s1)))
        (is (= "done" (:last-message s1))))
      (finally
        (cleanup-dir dir)))))

(deftest test-empty-cwd-not-closed
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "s1"
       {:agent-type :codex
        :agent-status :running
        :cwd ""
        :last-message "no cwd"})
      (store/close-stale-sessions! dir #{})
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "s1"])]
        (is (= :running (:agent-status s1))))
      (finally
        (cleanup-dir dir)))))

(deftest test-all-panes-alive
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "s1"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/a"
        :last-message "ok"})
      (store/update-session!
       dir "s2"
       {:agent-type :codex
        :agent-status :running
        :cwd "/tmp/b"
        :last-message "ok"})
      (store/close-stale-sessions!
       dir #{"/tmp/a" "/tmp/b"})
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "s1"])
            s2 (get-in state [:sessions "s2"])]
        (is (= :running (:agent-status s1)))
        (is (= :running (:agent-status s2))))
      (finally
        (cleanup-dir dir)))))

;; --- Process tree liveness tests (C) ---

(deftest test-list-pane-info-returns-nil-or-list
  (let [result (pane/list-pane-info)]
    (is (or (nil? result) (sequential? result)))
    (when (seq result)
      (is (contains? (first result) :cwd))
      (is (contains? (first result) :pid))
      (is (contains? (first result) :pane-id)))))

;; --- parse-pane-line tests ---

(deftest test-parse-pane-line-three-parts
  (testing "Parses pid|pane-id|cwd correctly"
    (let [parse (#'ceeker.tmux.pane/parse-pane-line
                 "12345|||%0|||/home/user")]
      (is (= "12345" (:pid parse)))
      (is (= "%0" (:pane-id parse)))
      (is (= "/home/user" (:cwd parse))))))

(deftest test-parse-pane-line-two-parts-returns-nil
  (testing "Two-part line returns nil (old format)"
    (is (nil? (#'ceeker.tmux.pane/parse-pane-line
               "12345|||/home/user")))))

;; --- recently-updated? tests ---

(deftest test-recently-updated-fresh
  (testing "Session updated just now is recent"
    (let [now (.toString (java.time.Instant/now))]
      (is (true? (#'ceeker.tmux.pane/recently-updated?
                  {:last-updated now}))))))

(deftest test-recently-updated-old
  (testing "Session updated long ago is not recent"
    (let [old (.toString
               (.minusSeconds (java.time.Instant/now) 60))]
      (is (false? (#'ceeker.tmux.pane/recently-updated?
                   {:last-updated old}))))))

(deftest test-recently-updated-nil
  (testing "No timestamp returns false"
    (is (false? (#'ceeker.tmux.pane/recently-updated?
                 {:last-updated nil})))))

(deftest test-find-agent-in-tree-nonexistent-pid
  (is (= :not-found (pane/find-agent-in-tree
                     "999999999" :claude-code))))

(deftest test-find-agent-in-tree-current-process
  (let [pid (str (.pid (java.lang.ProcessHandle/current)))]
    (is (= :not-found (pane/find-agent-in-tree
                       pid :claude-code)))))

;; --- capture-state-for-closed-session reactivation tests ---
;; Note: capture-state-for-closed-session now takes [session pane-infos]
;; and requires the agent to be alive in the process tree before
;; reactivating from terminal capture.

(defn- make-pane-infos
  "Creates pane-infos matching a session's pane-id."
  [pane-id pid]
  [{:pane-id pane-id :pid pid :cwd "/tmp/work"}])

(deftest test-closed-session-idle-not-reactivated
  (testing "Closed session with :idle detection is NOT reactivated
            (prevents closed->idle flapping when pane has plain shell)"
    (let [capture-fn #'ceeker.tmux.pane/capture-state-for-closed-session
          session {:agent-status :closed
                   :superseded false
                   :pane-id "%99"
                   :agent-type :claude-code}
          pane-infos (make-pane-infos "%99" "12345")]
      (with-redefs [capture/detect-agent-state
                    (fn [_ _] {:status :idle :waiting-reason nil})
                    pane/find-agent-in-tree
                    (fn [_ _] :found)]
        (is (nil? (capture-fn session pane-infos)))))))

(deftest test-closed-session-running-reactivated
  (testing "Closed session with :running detection and live agent IS reactivated"
    (let [capture-fn #'ceeker.tmux.pane/capture-state-for-closed-session
          session {:agent-status :closed
                   :superseded false
                   :pane-id "%99"
                   :agent-type :claude-code}
          pane-infos (make-pane-infos "%99" "12345")]
      (with-redefs [capture/detect-agent-state
                    (fn [_ _] {:status :running :waiting-reason nil})
                    pane/find-agent-in-tree
                    (fn [_ _] :found)]
        (let [result (capture-fn session pane-infos)]
          (is (some? result))
          (is (= :running (:agent-status result))))))))

(deftest test-closed-session-waiting-reactivated
  (testing "Closed session with :waiting detection and live agent IS reactivated"
    (let [capture-fn #'ceeker.tmux.pane/capture-state-for-closed-session
          session {:agent-status :closed
                   :superseded false
                   :pane-id "%99"
                   :agent-type :claude-code}
          pane-infos (make-pane-infos "%99" "12345")]
      (with-redefs [capture/detect-agent-state
                    (fn [_ _] {:status :waiting
                               :waiting-reason "respond"})
                    pane/find-agent-in-tree
                    (fn [_ _] :found)]
        (let [result (capture-fn session pane-infos)]
          (is (some? result))
          (is (= :waiting (:agent-status result))))))))

(deftest test-closed-superseded-not-reactivated
  (testing "Superseded closed session is never reactivated"
    (let [capture-fn #'ceeker.tmux.pane/capture-state-for-closed-session
          session {:agent-status :closed
                   :superseded true
                   :pane-id "%99"
                   :agent-type :claude-code}
          pane-infos (make-pane-infos "%99" "12345")]
      (with-redefs [capture/detect-agent-state
                    (fn [_ _] {:status :running :waiting-reason nil})
                    pane/find-agent-in-tree
                    (fn [_ _] :found)]
        (is (nil? (capture-fn session pane-infos)))))))

(deftest test-closed-no-pane-not-reactivated
  (testing "Closed session without pane-id is never reactivated"
    (let [capture-fn #'ceeker.tmux.pane/capture-state-for-closed-session
          session {:agent-status :closed
                   :superseded false
                   :pane-id nil
                   :agent-type :claude-code}
          pane-infos (make-pane-infos "%99" "12345")]
      (with-redefs [capture/detect-agent-state
                    (fn [_ _] {:status :running :waiting-reason nil})
                    pane/find-agent-in-tree
                    (fn [_ _] :found)]
        (is (nil? (capture-fn session pane-infos)))))))

;; --- session-has-live-agent? unit tests (D) ---

(deftest test-session-has-live-agent-dead-process
  (testing "Returns :dead when pane process tree has no agent"
    (let [session {:pane-id "%5" :cwd "/tmp/work"
                   :agent-type :claude-code}
          pane-infos [{:pane-id "%5" :pid "12345"
                       :cwd "/tmp/work"}]
          has-live? #'ceeker.tmux.pane/session-has-live-agent?]
      (with-redefs [pane/find-agent-in-tree
                    (fn [_ _] :not-found)]
        (is (= :dead (has-live? session pane-infos)))))))

(deftest test-session-has-live-agent-alive
  (testing "Returns :alive when agent found in process tree"
    (let [session {:pane-id "%5" :cwd "/tmp/work"
                   :agent-type :claude-code}
          pane-infos [{:pane-id "%5" :pid "12345"
                       :cwd "/tmp/work"}]
          has-live? #'ceeker.tmux.pane/session-has-live-agent?]
      (with-redefs [pane/find-agent-in-tree
                    (fn [_ _] :found)]
        (is (= :alive (has-live? session pane-infos)))))))

(deftest test-session-has-live-agent-no-matching-pane
  (testing "Returns :dead when pane-id not in pane-infos"
    (let [session {:pane-id "%5" :cwd "/tmp/work"
                   :agent-type :claude-code}
          pane-infos [{:pane-id "%99" :pid "12345"
                       :cwd "/tmp/other"}]
          has-live? #'ceeker.tmux.pane/session-has-live-agent?]
      (with-redefs [pane/find-agent-in-tree
                    (fn [_ _] :found)]
        (is (= :dead (has-live? session pane-infos)))))))

;; --- find-agent-in-tree dead process detection (E) ---

(deftest test-find-agent-dead-process-returns-not-found
  (testing "Dead process (nil cmdline + not alive) returns
            :not-found instead of :unknown"
    (with-redefs [ceeker.tmux.pane/read-proc-cmdline
                  (fn [_] nil)
                  ceeker.tmux.pane/process-alive?
                  (fn [_] false)]
      (is (= :not-found (pane/find-agent-in-tree
                         "999999" :claude-code))))))

(deftest test-find-agent-unreadable-process-returns-unknown
  (testing "Live process with unreadable cmdline still
            returns :unknown"
    (with-redefs [ceeker.tmux.pane/read-proc-cmdline
                  (fn [_] nil)
                  ceeker.tmux.pane/process-alive?
                  (fn [_] true)]
      (is (= :unknown (pane/find-agent-in-tree
                       "999999" :claude-code))))))

;; --- stale-session per-pane dedup tests (F) ---

(deftest test-stale-closes-older-duplicate-pane-session
  (testing "When two active sessions share a pane-id with
            different agent types, the older one is closed
            by dedup even when agent is alive.
            (Supersede only handles same agent-type, so this
            tests the dedup path.)"
    (let [dir (temp-dir)]
      (try
        (store/update-session!
         dir "old"
         {:agent-type :claude-code
          :agent-status :running
          :cwd "/tmp/work-a"
          :pane-id "%5"
          :last-updated (.toString
                         (.minusSeconds
                          (java.time.Instant/now) 30))})
        (store/update-session!
         dir "new"
         {:agent-type :codex
          :agent-status :running
          :cwd "/tmp/work-b"
          :pane-id "%5"
          :last-updated (.toString
                         (java.time.Instant/now))})
        (with-redefs [pane/list-pane-info
                      (fn [] [{:pane-id "%5"
                               :pid "12345"
                               :cwd "/tmp/work-b"}])
                      pane/find-agent-in-tree
                      (fn [_ _] :found)]
          (pane/close-stale-sessions! dir))
        (let [state (store/read-sessions dir)
              old (get-in state [:sessions "old"])
              new-s (get-in state [:sessions "new"])]
          (is (= :closed (:agent-status old))
              "Older session sharing pane must be closed")
          (is (= :running (:agent-status new-s))
              "Newest session in pane stays running"))
        (finally
          (cleanup-dir dir))))))

(deftest test-stale-keeps-sole-live-session
  (testing "Single active session with live agent not closed"
    (let [dir (temp-dir)]
      (try
        (store/update-session!
         dir "s1"
         {:agent-type :claude-code
          :agent-status :running
          :cwd "/tmp/work"
          :pane-id "%5"
          :last-updated (.toString
                         (java.time.Instant/now))})
        (with-redefs [pane/list-pane-info
                      (fn [] [{:pane-id "%5"
                               :pid "12345"
                               :cwd "/tmp/work"}])
                      pane/find-agent-in-tree
                      (fn [_ _] :found)]
          (pane/close-stale-sessions! dir))
        (let [s1 (get-in (store/read-sessions dir)
                         [:sessions "s1"])]
          (is (= :running (:agent-status s1))
              "Sole session with live agent stays running"))
        (finally
          (cleanup-dir dir))))))

;; --- New tests for stale session / pane reuse fixes ---

(deftest test-closed-session-dead-agent-not-reactivated
  (testing "Closed session with :running capture but dead agent is NOT reactivated
            (prevents false reactivation from stale terminal output)"
    (let [capture-fn #'ceeker.tmux.pane/capture-state-for-closed-session
          session {:agent-status :closed
                   :superseded false
                   :pane-id "%99"
                   :agent-type :claude-code}
          pane-infos (make-pane-infos "%99" "12345")]
      (with-redefs [capture/detect-agent-state
                    (fn [_ _] {:status :running :waiting-reason nil})
                    pane/find-agent-in-tree
                    (fn [_ _] :not-found)]
        (is (nil? (capture-fn session pane-infos))
            "Must not reactivate when agent process is dead")))))

(deftest test-closed-session-unknown-agent-not-reactivated
  (testing "Closed session with :unknown liveness is NOT reactivated
            (conservative: requires confirmed :alive)"
    (let [capture-fn #'ceeker.tmux.pane/capture-state-for-closed-session
          session {:agent-status :closed
                   :superseded false
                   :pane-id "%99"
                   :agent-type :claude-code}
          pane-infos (make-pane-infos "%99" "12345")]
      (with-redefs [capture/detect-agent-state
                    (fn [_ _] {:status :running :waiting-reason nil})
                    pane/find-agent-in-tree
                    (fn [_ _] :unknown)]
        (is (nil? (capture-fn session pane-infos))
            "Must not reactivate when agent liveness is unknown")))))

(deftest test-closed-session-no-matching-pane-not-reactivated
  (testing "Closed session whose pane-id is not in pane-infos is NOT reactivated"
    (let [capture-fn #'ceeker.tmux.pane/capture-state-for-closed-session
          session {:agent-status :closed
                   :superseded false
                   :pane-id "%99"
                   :agent-type :claude-code}
          pane-infos [{:pane-id "%50" :pid "999" :cwd "/tmp/other"}]]
      (with-redefs [capture/detect-agent-state
                    (fn [_ _] {:status :running :waiting-reason nil})
                    pane/find-agent-in-tree
                    (fn [_ _] :found)]
        (is (nil? (capture-fn session pane-infos))
            "Must not reactivate when pane-id does not match any live pane")))))

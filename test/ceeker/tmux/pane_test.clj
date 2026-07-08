(ns ceeker.tmux.pane-test
  (:require [ceeker.state.store :as store]
            [ceeker.tmux.capture :as capture]
            [ceeker.tmux.pane :as pane]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
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

(deftest test-close-stale-via-pred
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "%1"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/alive"
        :pane-id "%1"
        :last-message "working"})
      (store/update-session!
       dir "%2"
       {:agent-type :codex
        :agent-status :running
        :cwd "/tmp/dead"
        :pane-id "%2"
        :last-message "working"})
      (let [alive-cwds #{"/tmp/alive"}]
        (store/close-sessions-by-pred!
         dir
         (fn [_key session]
           (and (seq (:cwd session))
                (not (contains? alive-cwds
                                (:cwd session)))))))
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "%1"])
            s2 (get-in state [:sessions "%2"])]
        (is (= :running (:agent-status s1)))
        (is (= :closed (:agent-status s2)))
        (is (= "working" (:last-message s2))))
      (finally
        (cleanup-dir dir)))))

(deftest test-completed-not-affected-by-pred
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "%1"
       {:agent-type :claude-code
        :agent-status :completed
        :cwd "/tmp/gone"
        :pane-id "%1"
        :last-message "done"})
      (store/close-sessions-by-pred!
       dir
       (fn [_key session]
         (seq (:cwd session))))
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "%1"])]
        (is (= :completed (:agent-status s1)))
        (is (= "done" (:last-message s1))))
      (finally
        (cleanup-dir dir)))))

(deftest test-empty-cwd-not-closed-by-pred
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "%1"
       {:agent-type :codex
        :agent-status :running
        :cwd ""
        :pane-id "%1"
        :last-message "no cwd"})
      (store/close-sessions-by-pred!
       dir
       (fn [_key session]
         (and (seq (:cwd session))
              (not (contains? #{} (:cwd session))))))
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "%1"])]
        (is (= :running (:agent-status s1))))
      (finally
        (cleanup-dir dir)))))

(deftest test-all-panes-alive-via-pred
  (let [dir (temp-dir)]
    (try
      (store/update-session!
       dir "%1"
       {:agent-type :claude-code
        :agent-status :running
        :cwd "/tmp/a"
        :pane-id "%1"
        :last-message "ok"})
      (store/update-session!
       dir "%2"
       {:agent-type :codex
        :agent-status :running
        :cwd "/tmp/b"
        :pane-id "%2"
        :last-message "ok"})
      (let [alive #{"/tmp/a" "/tmp/b"}]
        (store/close-sessions-by-pred!
         dir
         (fn [_key session]
           (and (seq (:cwd session))
                (not (contains? alive
                                (:cwd session)))))))
      (let [state (store/read-sessions dir)
            s1 (get-in state [:sessions "%1"])
            s2 (get-in state [:sessions "%2"])]
        (is (= :running (:agent-status s1)))
        (is (= :running (:agent-status s2))))
      (finally
        (cleanup-dir dir)))))

;; --- Process tree liveness tests ---

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

(defn- make-pane-infos
  "Creates pane-infos matching a session's pane-id."
  [pane-id pid]
  [{:pane-id pane-id :pid pid :cwd "/tmp/work"}])

(deftest test-closed-session-idle-not-reactivated
  (testing "Closed session with :idle detection is NOT reactivated"
    (let [capture-fn #'ceeker.tmux.pane/capture-state-for-closed-session
          session {:agent-status :closed
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

(deftest test-closed-no-pane-not-reactivated
  (testing "Closed session without pane-id is never reactivated"
    (let [capture-fn #'ceeker.tmux.pane/capture-state-for-closed-session
          session {:agent-status :closed
                   :pane-id nil
                   :agent-type :claude-code}
          pane-infos (make-pane-infos "%99" "12345")]
      (with-redefs [capture/detect-agent-state
                    (fn [_ _] {:status :running :waiting-reason nil})
                    pane/find-agent-in-tree
                    (fn [_ _] :found)]
        (is (nil? (capture-fn session pane-infos)))))))

;; --- session-has-live-agent? unit tests ---

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

;; --- find-agent-in-tree dead process detection ---

(deftest test-find-agent-dead-process-returns-not-found
  (testing "Dead process returns :not-found"
    (with-redefs [ceeker.tmux.pane/read-proc-cmdline
                  (fn [_] nil)
                  ceeker.tmux.pane/process-alive?
                  (fn [_] false)]
      (is (= :not-found (pane/find-agent-in-tree
                         "999999" :claude-code))))))

(deftest test-find-agent-unreadable-process-returns-unknown
  (testing "Live process with unreadable cmdline returns :unknown"
    (with-redefs [ceeker.tmux.pane/read-proc-cmdline
                  (fn [_] nil)
                  ceeker.tmux.pane/process-alive?
                  (fn [_] true)]
      (is (= :unknown (pane/find-agent-in-tree
                       "999999" :claude-code))))))

(deftest test-find-agent-pid-in-tree-returns-matching-pid
  (testing "returns the pid whose command line matches the agent"
    (with-redefs [ceeker.tmux.pane/read-proc-cmdline
                  (fn [pid]
                    (case (str pid)
                      "10" "zsh"
                      "11" "/usr/bin/codex"
                      nil))
                  ceeker.tmux.pane/child-pids
                  (fn [pid]
                    (case (str pid)
                      "10" ["11"]
                      []))
                  ceeker.tmux.pane/process-alive?
                  (fn [_] true)]
      (is (= "11" (pane/find-agent-pid-in-tree
                   "10" :codex))))))

(deftest test-child-pids-falls-back-to-pgrep-when-proc-read-fails
  (testing "uses pgrep fallback when /proc children exists but slurp fails"
    (let [calls (atom [])]
      (with-redefs [clojure.java.io/file
                    (fn [& _]
                      (proxy [java.io.File] [""]
                        (exists [] true)))
                    clojure.core/slurp
                    (fn [_]
                      (throw (java.io.IOException.
                              "Invalid argument")))
                    shell/sh
                    (fn [& args]
                      (swap! calls conj args)
                      {:exit 0 :out "311293\n" :err ""})]
        (is (= ["311293"]
               (#'ceeker.tmux.pane/child-pids "1486")))
        (is (= [["pgrep" "-P" "1486"]] @calls))))))

;; --- stale session tests ---

(deftest test-stale-keeps-sole-live-session
  (testing "Single active session with live agent not closed"
    (let [dir (temp-dir)]
      (try
        (store/update-session!
         dir "%5"
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
        (let [s (get-in (store/read-sessions dir)
                        [:sessions "%5"])]
          (is (= :running (:agent-status s))))
        (finally
          (cleanup-dir dir))))))

(deftest test-stale-pane-id-less-cwd-match-dead-agent
  (testing "Pane-id-less session is stale when cwd pane has no live agent"
    (let [stale? #'ceeker.tmux.pane/stale-session?
          session {:agent-type :claude-code
                   :agent-status :running
                   :cwd "/tmp/work"
                   :pane-id ""}
          pane-infos [{:pane-id "%5"
                       :pid "12345"
                       :cwd "/tmp/work"}]
          pane-cwds #{"/tmp/work"}]
      (with-redefs [pane/find-agent-in-tree
                    (fn [_ _] :not-found)]
        (is (true? (stale? session pane-cwds pane-infos)))))))

(deftest test-stale-pane-id-less-cwd-match-unknown-agent
  (testing "Pane-id-less session remains non-stale when liveness is unknown"
    (let [stale? #'ceeker.tmux.pane/stale-session?
          session {:agent-type :claude-code
                   :agent-status :running
                   :cwd "/tmp/work"
                   :pane-id ""}
          pane-infos [{:pane-id "%5"
                       :pid "12345"
                       :cwd "/tmp/work"}]
          pane-cwds #{"/tmp/work"}]
      (with-redefs [pane/find-agent-in-tree
                    (fn [_ _] :unknown)]
        (is (false? (stale? session pane-cwds pane-infos)))))))

;; --- Closed session with dead agent not reactivated ---

(deftest test-closed-session-dead-agent-not-reactivated
  (testing "Closed session with dead agent is NOT reactivated"
    (let [capture-fn #'ceeker.tmux.pane/capture-state-for-closed-session
          session {:agent-status :closed
                   :pane-id "%99"
                   :agent-type :claude-code}
          pane-infos (make-pane-infos "%99" "12345")]
      (with-redefs [capture/detect-agent-state
                    (fn [_ _] {:status :running :waiting-reason nil})
                    pane/find-agent-in-tree
                    (fn [_ _] :not-found)]
        (is (nil? (capture-fn session pane-infos)))))))

(deftest test-closed-session-unknown-agent-not-reactivated
  (testing "Closed session with :unknown liveness is NOT reactivated"
    (let [capture-fn #'ceeker.tmux.pane/capture-state-for-closed-session
          session {:agent-status :closed
                   :pane-id "%99"
                   :agent-type :claude-code}
          pane-infos (make-pane-infos "%99" "12345")]
      (with-redefs [capture/detect-agent-state
                    (fn [_ _] {:status :running :waiting-reason nil})
                    pane/find-agent-in-tree
                    (fn [_ _] :unknown)]
        (is (nil? (capture-fn session pane-infos)))))))

(deftest test-closed-session-no-matching-pane-not-reactivated
  (testing "Closed session whose pane-id is not in pane-infos is NOT reactivated"
    (let [capture-fn #'ceeker.tmux.pane/capture-state-for-closed-session
          session {:agent-status :closed
                   :pane-id "%99"
                   :agent-type :claude-code}
          pane-infos [{:pane-id "%50" :pid "999"
                       :cwd "/tmp/other"}]]
      (with-redefs [capture/detect-agent-state
                    (fn [_ _] {:status :running :waiting-reason nil})
                    pane/find-agent-in-tree
                    (fn [_ _] :found)]
        (is (nil? (capture-fn session pane-infos)))))))

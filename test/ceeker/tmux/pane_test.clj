(ns ceeker.tmux.pane-test
  (:require [ceeker.state.store :as store]
            [ceeker.tmux.pane :as pane]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

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
        (is (= "pane closed"
               (:last-message s2))))
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

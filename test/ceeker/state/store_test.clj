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

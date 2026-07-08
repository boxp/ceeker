(ns ceeker.watch.sessions-test
  (:require [ceeker.state.store :as store]
            [ceeker.tmux.pane :as pane]
            [ceeker.watch.sessions :as sessions]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(defn- temp-dir []
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/ceeker-session-watch-test-"
                 (System/nanoTime))]
    (.mkdirs (io/file dir))
    dir))

(defn- cleanup-dir [dir]
  (let [f (io/file dir)]
    (when (.exists f)
      (doseq [file (reverse (file-seq f))]
        (.delete file)))))

(deftest test-parse-claude-line
  (let [line (str "{\"sessionId\":\"claude-1\",\"cwd\":\"/tmp/w\","
                  "\"timestamp\":\"2026-01-01T00:00:00Z\","
                  "\"type\":\"assistant\","
                  "\"message\":{\"content\":\"done\"}}")
        result (sessions/parse-jsonl-line :claude-code line)]
    (is (= {:session-id "claude-1"
            :agent-type :claude-code
            :agent-status :running
            :cwd "/tmp/w"
            :last-message "done"
            :last-updated "2026-01-01T00:00:00Z"}
           result))))

(deftest test-parse-claude-line-nil-safe
  (is (nil? (sessions/parse-jsonl-line
             :claude-code
             "{\"type\":\"file-history-snapshot\"}"))))

(deftest test-parse-codex-meta-and-complete
  (let [meta-line (str "{\"timestamp\":\"2026-01-01T00:00:00Z\","
                       "\"type\":\"session_meta\","
                       "\"payload\":{\"session_id\":\"codex-1\","
                       "\"cwd\":\"/tmp/w\"}}")
        complete-line (str "{\"timestamp\":\"2026-01-01T00:00:02Z\","
                           "\"type\":\"event_msg\","
                           "\"payload\":{\"type\":\"task_complete\","
                           "\"last_agent_message\":\"done\"}}")]
    (is (= {:session-id "codex-1"
            :agent-type :codex
            :agent-status :running
            :cwd "/tmp/w"
            :last-updated "2026-01-01T00:00:00Z"}
           (sessions/parse-jsonl-line :codex meta-line)))
    (is (= {:agent-type :codex
            :agent-status :completed
            :last-message "done"
            :last-updated "2026-01-01T00:00:02Z"}
           (sessions/parse-jsonl-line :codex complete-line)))))

(deftest test-tail-new-lines-uses-offset
  (let [dir (temp-dir)
        file (io/file dir "session.jsonl")
        offsets (atom {})]
    (try
      (spit file "a\n")
      (is (= ["a"] (sessions/tail-new-lines! offsets file)))
      (spit file "b\n" :append true)
      (is (= ["b"] (sessions/tail-new-lines! offsets file)))
      (is (= [] (sessions/tail-new-lines! offsets file)))
      (finally
        (cleanup-dir dir)))))

(deftest test-resolve-pane-id-from-proc-environ
  (testing "Linux path reads TMUX_PANE from the matched agent pid environ"
    (with-redefs [pane/list-pane-info
                  (fn [] [{:pid "100" :pane-id "%fallback"
                           :cwd "/tmp/w"}])
                  pane/find-agent-pid-in-tree
                  (fn [pid agent-type]
                    (when (and (= "100" pid)
                               (= :codex agent-type))
                      "222"))
                  sessions/read-proc-environ
                  (fn [pid]
                    (when (= "222" pid)
                      {"TMUX_PANE" "%3"}))]
      (is (= "%3" (sessions/resolve-pane-id
                   {:cwd "/tmp/w" :agent-type :codex}))))))

(deftest test-resolve-pane-id-falls-back-on-single-candidate
  (testing "without /proc environ, a single matching live pane is used"
    (with-redefs [pane/list-pane-info
                  (fn [] [{:pid "100" :pane-id "%8"
                           :cwd "/tmp/w"}])
                  pane/find-agent-pid-in-tree
                  (fn [_ _] "222")
                  sessions/read-proc-environ
                  (fn [_] nil)]
      (is (= "%8" (sessions/resolve-pane-id
                   {:cwd "/tmp/w" :agent-type :codex}))))))

(deftest test-scan-recent-sessions
  (let [root (temp-dir)
        state-dir (temp-dir)
        codex-dir (io/file root "codex/2026/01/01")
        claude-dir (io/file root "claude/-tmp-w")]
    (try
      (.mkdirs codex-dir)
      (.mkdirs claude-dir)
      (spit (io/file codex-dir "rollout-2026-01-01T00-00-00Z-u.jsonl")
            (str "{\"timestamp\":\"2026-01-01T00:00:00Z\","
                 "\"type\":\"session_meta\","
                 "\"payload\":{\"session_id\":\"codex-1\","
                 "\"cwd\":\"/tmp/w\"}}\n"
                 "{\"timestamp\":\"2026-01-01T00:00:01Z\","
                 "\"type\":\"event_msg\","
                 "\"payload\":{\"type\":\"task_complete\","
                 "\"last_agent_message\":\"done\"}}\n"))
      (spit (io/file claude-dir "claude-1.jsonl")
            (str "{\"sessionId\":\"claude-1\",\"cwd\":\"/tmp/w\","
                 "\"timestamp\":\"2026-01-01T00:00:02Z\","
                 "\"type\":\"user\"}\n"))
      (with-redefs [sessions/resolve-pane-id (fn [_] nil)]
        (sessions/scan-recent-sessions!
         {:claude-root (.getPath (io/file root "claude"))
          :codex-root (.getPath (io/file root "codex"))
          :state-dir state-dir
          :since-hours 24}))
      (let [sessions-map (:sessions (store/read-sessions state-dir))]
        (is (= :completed
               (get-in sessions-map ["codex-1" :agent-status])))
        (is (= "done"
               (get-in sessions-map ["codex-1" :last-message])))
        (is (= :running
               (get-in sessions-map ["claude-1" :agent-status]))))
      (finally
        (cleanup-dir root)
        (cleanup-dir state-dir)))))

(deftest test-scan-keeps-claude-assistant-last-message
  (let [root (temp-dir)
        state-dir (temp-dir)
        claude-dir (io/file root "claude/-tmp-w")]
    (try
      (.mkdirs claude-dir)
      (spit (io/file claude-dir "claude-1.jsonl")
            (str "{\"sessionId\":\"claude-1\",\"cwd\":\"/tmp/w\","
                 "\"timestamp\":\"2026-01-01T00:00:00Z\","
                 "\"type\":\"assistant\","
                 "\"message\":{\"content\":\"working on it\"}}\n"
                 "{\"sessionId\":\"claude-1\",\"cwd\":\"/tmp/w\","
                 "\"timestamp\":\"2026-01-01T00:00:01Z\","
                 "\"type\":\"user\"}\n"))
      (with-redefs [sessions/resolve-pane-id (fn [_] nil)]
        (sessions/scan-recent-sessions!
         {:claude-root (.getPath (io/file root "claude"))
          :codex-root (.getPath (io/file root "codex"))
          :state-dir state-dir
          :since-hours 24}))
      (let [sessions-map (:sessions (store/read-sessions state-dir))]
        (is (= :running
               (get-in sessions-map ["claude-1" :agent-status])))
        (is (= "working on it"
               (get-in sessions-map ["claude-1" :last-message]))))
      (finally
        (cleanup-dir root)
        (cleanup-dir state-dir)))))

(deftest test-scan-skips-files-deleted-before-open
  (let [state-dir (temp-dir)
        missing-file (io/file "/tmp/ceeker-missing-session.jsonl")]
    (try
      (with-redefs [sessions/session-files
                    (fn [root _cutoff-ms]
                      (if (= root "/missing-root")
                        [missing-file]
                        []))
                    sessions/resolve-pane-id (fn [_] nil)]
        (let [err (java.io.StringWriter.)]
          (binding [*err* err]
            (sessions/scan-recent-sessions!
             {:claude-root "/missing-root"
              :codex-root "/empty-root"
              :state-dir state-dir
              :since-hours 24}))
          (is (re-find #"Skipping unreadable session file"
                       (str err)))))
      (finally
        (cleanup-dir state-dir)))))

(deftest test-start-session-watcher-stops
  (testing "worker stops after stop channel close"
    (let [calls (atom 0)
          started (promise)
          stopped (promise)]
      (with-redefs [sessions/run-watch-loop!
                    (fn [stop-ch _opts]
                      (swap! calls inc)
                      (deliver started true)
                      (async/<!! stop-ch)
                      (swap! calls inc)
                      (deliver stopped true))]
        (let [stop-ch (sessions/start-session-watcher! {})]
          (is (true? (deref started 1000 false)))
          (is (= 1 @calls))
          (async/close! stop-ch)
          (is (true? (deref stopped 1000 false)))
          (is (= 2 @calls)))))))

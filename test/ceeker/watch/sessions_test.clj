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

(defn- iso-before [millis]
  (.toString (.minusMillis (java.time.Instant/now) millis)))

(defn- codex-session-lines [session-id cwd timestamp status]
  (str "{\"timestamp\":\"" timestamp "\","
       "\"type\":\"session_meta\","
       "\"payload\":{\"session_id\":\"" session-id "\","
       "\"cwd\":\"" cwd "\"}}\n"
       "{\"timestamp\":\"" timestamp "\","
       "\"type\":\"event_msg\","
       "\"payload\":{\"type\":\"" status "\","
       "\"last_agent_message\":\"done\"}}\n"))

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

(deftest test-parse-claude-tool-use-only-omits-last-message
  (let [line (str "{\"sessionId\":\"claude-1\",\"cwd\":\"/tmp/w\","
                  "\"timestamp\":\"2026-01-01T00:00:01Z\","
                  "\"type\":\"assistant\","
                  "\"message\":{\"content\":["
                  "{\"type\":\"tool_use\",\"id\":\"toolu_1\","
                  "\"name\":\"Bash\",\"input\":{\"command\":\"pwd\"}}"
                  "]}}")
        result (sessions/parse-jsonl-line :claude-code line)]
    (is (not (contains? result :last-message)))))

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
        claude-dir (io/file root "claude/-tmp-w")
        recent-ts (iso-before 1000)]
    (try
      (.mkdirs codex-dir)
      (.mkdirs claude-dir)
      (spit (io/file codex-dir "rollout-2026-01-01T00-00-00Z-u.jsonl")
            (str "{\"timestamp\":\"" recent-ts "\","
                 "\"type\":\"session_meta\","
                 "\"payload\":{\"session_id\":\"codex-1\","
                 "\"cwd\":\"/tmp/w\"}}\n"
                 "{\"timestamp\":\"" recent-ts "\","
                 "\"type\":\"event_msg\","
                 "\"payload\":{\"type\":\"task_complete\","
                 "\"last_agent_message\":\"done\"}}\n"))
      (spit (io/file claude-dir "claude-1.jsonl")
            (str "{\"sessionId\":\"claude-1\",\"cwd\":\"/tmp/w\","
                 "\"timestamp\":\"" recent-ts "\","
                 "\"type\":\"user\"}\n"))
      (with-redefs [sessions/resolve-pane-id (fn [_] nil)
                    pane/list-pane-info (fn [] nil)]
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

(deftest test-scan-skips-terminal-session-older-than-closed-ttl
  (let [root (temp-dir)
        state-dir (temp-dir)
        codex-dir (io/file root "codex/2026/01/01")
        old-ts (iso-before (+ store/closed-ttl-ms 1000))]
    (try
      (.mkdirs codex-dir)
      (spit (io/file codex-dir "rollout-old.jsonl")
            (codex-session-lines "codex-old" "/tmp/w"
                                 old-ts "task_complete"))
      (with-redefs [sessions/resolve-pane-id (fn [_] nil)
                    pane/list-pane-info (fn [] nil)]
        (sessions/scan-recent-sessions!
         {:claude-root (.getPath (io/file root "claude"))
          :codex-root (.getPath (io/file root "codex"))
          :state-dir state-dir
          :since-hours 24}))
      (is (nil? (get-in (store/read-sessions state-dir)
                        [:sessions "codex-old"])))
      (finally
        (cleanup-dir root)
        (cleanup-dir state-dir)))))

(deftest test-scan-keeps-terminal-session-within-closed-ttl
  (let [root (temp-dir)
        state-dir (temp-dir)
        codex-dir (io/file root "codex/2026/01/01")
        recent-ts (iso-before 1000)]
    (try
      (.mkdirs codex-dir)
      (spit (io/file codex-dir "rollout-recent.jsonl")
            (codex-session-lines "codex-recent" "/tmp/w"
                                 recent-ts "task_complete"))
      (with-redefs [sessions/resolve-pane-id (fn [_] nil)
                    pane/list-pane-info (fn [] nil)]
        (sessions/scan-recent-sessions!
         {:claude-root (.getPath (io/file root "claude"))
          :codex-root (.getPath (io/file root "codex"))
          :state-dir state-dir
          :since-hours 24}))
      (is (= :completed
             (get-in (store/read-sessions state-dir)
                     [:sessions "codex-recent" :agent-status])))
      (finally
        (cleanup-dir root)
        (cleanup-dir state-dir)))))

(deftest test-scan-skips-active-session-without-live-process
  (let [root (temp-dir)
        state-dir (temp-dir)
        claude-dir (io/file root "claude/-tmp-w")]
    (try
      (.mkdirs claude-dir)
      (spit (io/file claude-dir "claude-dead.jsonl")
            (str "{\"sessionId\":\"claude-dead\","
                 "\"cwd\":\"/tmp/w\","
                 "\"timestamp\":\"" (iso-before 1000) "\","
                 "\"type\":\"user\"}\n"))
      (with-redefs [sessions/resolve-pane-id (fn [_] nil)
                    pane/list-pane-info
                    (fn [] [{:pid "100" :pane-id "%1"
                             :cwd "/tmp/w"}])
                    pane/find-agent-pid-in-tree (fn [_ _] nil)]
        (sessions/scan-recent-sessions!
         {:claude-root (.getPath (io/file root "claude"))
          :codex-root (.getPath (io/file root "codex"))
          :state-dir state-dir
          :since-hours 24}))
      (is (nil? (get-in (store/read-sessions state-dir)
                        [:sessions "claude-dead"])))
      (finally
        (cleanup-dir root)
        (cleanup-dir state-dir)))))

(deftest test-scan-keeps-active-session-with-live-process-fallback
  (let [root (temp-dir)
        state-dir (temp-dir)
        claude-dir (io/file root "claude/-tmp-w")]
    (try
      (.mkdirs claude-dir)
      (spit (io/file claude-dir "claude-live.jsonl")
            (str "{\"sessionId\":\"claude-live\","
                 "\"cwd\":\"/tmp/w\","
                 "\"timestamp\":\"" (iso-before 1000) "\","
                 "\"type\":\"user\"}\n"))
      (with-redefs [sessions/resolve-pane-id (fn [_] nil)
                    pane/list-pane-info
                    (fn [] [{:pid "100" :pane-id "%1"
                             :cwd "/tmp/w"}])
                    pane/find-agent-pid-in-tree
                    (fn [pid agent-type]
                      (when (and (= "100" pid)
                                 (= :claude-code agent-type))
                        "200"))]
        (sessions/scan-recent-sessions!
         {:claude-root (.getPath (io/file root "claude"))
          :codex-root (.getPath (io/file root "codex"))
          :state-dir state-dir
          :since-hours 24}))
      (is (= :running
             (get-in (store/read-sessions state-dir)
                     [:sessions "claude-live" :agent-status])))
      (finally
        (cleanup-dir root)
        (cleanup-dir state-dir)))))

(deftest test-scan-keeps-active-session-when-tmux-unavailable
  (let [root (temp-dir)
        state-dir (temp-dir)
        claude-dir (io/file root "claude/-tmp-w")]
    (try
      (.mkdirs claude-dir)
      (spit (io/file claude-dir "claude-no-tmux.jsonl")
            (str "{\"sessionId\":\"claude-no-tmux\","
                 "\"cwd\":\"/tmp/w\","
                 "\"timestamp\":\"" (iso-before 1000) "\","
                 "\"type\":\"user\"}\n"))
      (with-redefs [sessions/resolve-pane-id (fn [_] nil)
                    pane/list-pane-info (fn [] nil)
                    pane/find-agent-pid-in-tree
                    (fn [_ _] (throw (ex-info "not called" {})))]
        (sessions/scan-recent-sessions!
         {:claude-root (.getPath (io/file root "claude"))
          :codex-root (.getPath (io/file root "codex"))
          :state-dir state-dir
          :since-hours 24}))
      (is (= :running
             (get-in (store/read-sessions state-dir)
                     [:sessions "claude-no-tmux" :agent-status])))
      (finally
        (cleanup-dir root)
        (cleanup-dir state-dir)))))

(deftest test-watch-path-does-not-apply-scan-liveness-check
  (let [state-dir (temp-dir)
        file-states (atom {})
        file (io/file (temp-dir) "claude-live-event.jsonl")]
    (try
      (with-redefs [sessions/resolve-pane-id (fn [_] nil)
                    pane/list-pane-info
                    (fn [] [{:pid "100" :pane-id "%1"
                             :cwd "/tmp/w"}])
                    pane/find-agent-pid-in-tree (fn [_ _] nil)]
        (#'sessions/process-lines!
         state-dir file-states file
         [(str "{\"sessionId\":\"claude-event\","
               "\"cwd\":\"/tmp/w\","
               "\"timestamp\":\"" (iso-before 1000) "\","
               "\"type\":\"user\"}")]))
      (is (= :running
             (get-in (store/read-sessions state-dir)
                     [:sessions "claude-event" :agent-status])))
      (finally
        (cleanup-dir (.getParent file))
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
      (with-redefs [sessions/resolve-pane-id (fn [_] nil)
                    pane/list-pane-info (fn [] nil)]
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

(deftest test-scan-keeps-claude-last-message-after-tool-use-only-assistant
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
                 "\"type\":\"assistant\","
                 "\"message\":{\"content\":["
                 "{\"type\":\"tool_use\",\"id\":\"toolu_1\","
                 "\"name\":\"Bash\",\"input\":{\"command\":\"pwd\"}}"
                 "]}}\n"))
      (with-redefs [sessions/resolve-pane-id (fn [_] nil)
                    pane/list-pane-info (fn [] nil)]
        (sessions/scan-recent-sessions!
         {:claude-root (.getPath (io/file root "claude"))
          :codex-root (.getPath (io/file root "codex"))
          :state-dir state-dir
          :since-hours 24}))
      (let [sessions-map (:sessions (store/read-sessions state-dir))]
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

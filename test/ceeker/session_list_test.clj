(ns ceeker.session-list-test
  (:require [ceeker.session-list :as session-list]
            [ceeker.tmux.pane :as pane]
            [clojure.test :refer [deftest is testing]]))

(deftest refresh-and-read-session-list-sorts-like-tui
  (testing "running sessions come first, then by last-updated"
    (with-redefs [ceeker.session-list/refresh-session-state!
                  (fn [_])
                  ceeker.session-list/read-session-list
                  (fn [_]
                    [{:session-id "done"
                      :agent-status :completed
                      :pane-id "%3"
                      :last-updated "2026-04-02T03:00:00Z"}
                     {:session-id "run-new"
                      :agent-status :running
                      :pane-id "%2"
                      :last-updated "2026-04-02T02:00:00Z"}
                     {:session-id "run-old"
                      :agent-status :running
                      :pane-id "%1"
                      :last-updated "2026-04-02T01:00:00Z"}])]
      (is (= ["run-old" "run-new" "done"]
             (map :session-id
                  (session-list/refresh-and-read-session-list
                   nil)))))))

(deftest refresh-and-read-session-list-fails-open
  (testing "refresh failure still returns stored sessions"
    (let [err (java.io.StringWriter.)]
      (binding [*err* err]
        (with-redefs [ceeker.session-list/refresh-session-state!
                      (fn [_]
                        (throw (ex-info "tmux unavailable" {})))
                      ceeker.session-list/read-session-list
                      (fn [_]
                        [{:session-id "sess-1"
                          :agent-status :running
                          :pane-id "%1"
                          :last-updated "2026-04-02T01:00:00Z"}])]
          (is (= ["sess-1"]
                 (map :session-id
                      (session-list/refresh-and-read-session-list
                       nil))))))
      (is (re-find #"session list refresh failed"
                   (str err))))))

(deftest refresh-session-state-closes-stale-sessions
  (testing "list-sessions refresh still performs stale cleanup"
    (let [closed (atom nil)
          refreshed (atom nil)]
      (with-redefs [pane/close-stale-sessions!
                    (fn [state-dir]
                      (reset! closed state-dir))
                    pane/refresh-session-states!
                    (fn [state-dir]
                      (reset! refreshed state-dir))]
        (session-list/refresh-session-state! "/tmp/ceeker-state")
        (is (= "/tmp/ceeker-state" @closed))
        (is (= "/tmp/ceeker-state" @refreshed))))))

(deftest session->external-includes-pane-id-and-stringifies-enums
  (testing "JSON-ready map uses snake_case keys for LLM consumers"
    (is (= {:session_id "sess-1"
            :agent_type "claude-code"
            :agent_status "waiting"
            :cwd "/tmp/work"
            :pane_id ""
            :last_message nil
            :last_updated "2026-04-02T00:00:00Z"}
           (session-list/session->external
            {:session-id "sess-1"
             :agent-type :claude-code
             :agent-status :waiting
             :cwd "/tmp/work"
             :pane-id nil
             :last-message nil
             :last-updated "2026-04-02T00:00:00Z"})))))

(deftest session->external-stringifies-pi-agent-type
  (testing "pi agent type is exposed as pi"
    (is (= "pi"
           (:agent_type
            (session-list/session->external
             {:session-id "pi-1"
              :agent-type :pi
              :agent-status :running
              :cwd "/tmp/pi"
              :pane-id "%5"
              :last-message "working"
              :last-updated "2026-04-02T00:00:00Z"}))))))

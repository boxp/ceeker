(ns ceeker.tmux.pane
  "tmux pane liveness checking.
   Detects stale sessions by checking tmux pane existence
   and agent process liveness in the process tree."
  (:require [ceeker.state.store :as store]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn list-pane-cwds
  "Runs tmux list-panes -a once, returns a set of pane cwds.
   Returns nil if tmux is unavailable."
  []
  (try
    (let [result (shell/sh
                  "tmux" "list-panes" "-a"
                  "-F" "#{pane_current_path}")]
      (when (zero? (:exit result))
        (set (remove str/blank?
                     (str/split-lines
                      (:out result))))))
    (catch Exception _ nil)))

(defn list-pane-info
  "Returns a list of maps with :cwd and :pid for each pane.
   Returns nil if tmux is unavailable."
  []
  (try
    (let [result (shell/sh
                  "tmux" "list-panes" "-a"
                  "-F"
                  "#{pane_current_path}\t#{pane_pid}")]
      (when (zero? (:exit result))
        (keep
         (fn [line]
           (let [parts (str/split line #"\t" 2)]
             (when (= 2 (count parts))
               {:cwd (first parts)
                :pid (second parts)})))
         (str/split-lines (:out result)))))
    (catch Exception _ nil)))

(defn- read-proc-cmdline
  "Reads /proc/<pid>/cmdline on Linux, falls back to ps on
   macOS. Returns the command string or nil on failure."
  [pid]
  (try
    (let [f (io/file (str "/proc/" pid "/cmdline"))]
      (if (.exists f)
        (str/replace (slurp f) "\0" " ")
        (let [result (shell/sh
                      "ps" "-p" (str pid) "-o"
                      "command=")]
          (when (zero? (:exit result))
            (str/trim (:out result))))))
    (catch Exception _ nil)))

(defn- child-pids
  "Returns direct child PIDs of the given pid via /proc.
   Returns empty seq on failure."
  [pid]
  (try
    (let [f (io/file (str "/proc/" pid "/task/"
                          pid "/children"))]
      (if (.exists f)
        (remove str/blank?
                (str/split (str/trim (slurp f)) #"\s+"))
        (let [result (shell/sh
                      "pgrep" "-P" (str pid))]
          (when (zero? (:exit result))
            (remove str/blank?
                    (str/split-lines
                     (:out result)))))))
    (catch Exception _ nil)))

(defn- agent-pattern
  "Returns a regex pattern matching the agent type name."
  [agent-type]
  (case agent-type
    :claude-code #"(?i)claude"
    :codex #"(?i)codex"
    #"(?i)claude|codex"))

(defn find-agent-in-tree
  "Searches the process tree rooted at pid for an agent
   process matching the given agent-type.
   Returns true if found, false otherwise.
   Max depth prevents infinite recursion."
  ([pid agent-type] (find-agent-in-tree pid agent-type 5))
  ([pid agent-type max-depth]
   (if (neg? max-depth)
     false
     (let [pat (agent-pattern agent-type)
           cmdline (read-proc-cmdline pid)]
       (if (and cmdline (re-find pat cmdline))
         true
         (let [children (child-pids pid)]
           (boolean
            (some #(find-agent-in-tree
                    % agent-type (dec max-depth))
                  children))))))))

(defn- session-has-live-agent?
  "Checks if a session's agent is alive by searching the
   process tree of matching tmux panes."
  [session pane-infos]
  (let [cwd (:cwd session)
        agent-type (:agent-type session)
        matching-panes (filter #(= cwd (:cwd %))
                               pane-infos)]
    (boolean
     (some #(find-agent-in-tree (:pid %) agent-type)
           matching-panes))))

(defn- stale-session-id
  "Returns session-id if the session is stale, nil otherwise."
  [[sid session] pane-cwds pane-infos]
  (when (and (= :running (:agent-status session))
             (seq (:cwd session)))
    (cond
      (not (contains? pane-cwds (:cwd session))) sid
      (not (session-has-live-agent?
            session pane-infos)) sid)))

(defn- find-stale-ids
  "Returns a seq of stale session IDs."
  [state-dir pane-cwds pane-infos]
  (let [state (if state-dir
                (store/read-sessions state-dir)
                (store/read-sessions))]
    (keep #(stale-session-id % pane-cwds pane-infos)
          (:sessions state))))

(defn- close-session-ids!
  "Marks the given session IDs as closed."
  [state-dir stale-ids]
  (let [now (.toString (java.time.Instant/now))
        close-data {:agent-status :closed
                    :last-message "pane closed"
                    :last-updated now}]
    (doseq [sid stale-ids]
      (if state-dir
        (store/update-session!
         state-dir sid close-data)
        (store/update-session!
         sid close-data)))))

(defn close-stale-sessions!
  "Checks running sessions and marks stale ones as closed.
   Uses process tree liveness when pane info is available.
   Does nothing if tmux is unavailable."
  ([] (close-stale-sessions! nil))
  ([state-dir]
   (when-let [pane-infos (list-pane-info)]
     (let [pane-cwds (set (map :cwd pane-infos))
           stale-ids (find-stale-ids
                      state-dir pane-cwds pane-infos)]
       (close-session-ids! state-dir stale-ids)))))

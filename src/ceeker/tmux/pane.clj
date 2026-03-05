(ns ceeker.tmux.pane
  "tmux pane liveness checking and state refresh.
   Detects stale sessions by checking tmux pane existence
   and agent process liveness in the process tree.
   Also refreshes running session states via capture-pane."
  (:require [ceeker.state.store :as store]
            [ceeker.tmux.capture :as capture]
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

(def ^:private pane-separator "|||")

(def ^:private pane-sep-re
  "Compiled regex for splitting pane info lines."
  (re-pattern
   (java.util.regex.Pattern/quote pane-separator)))

(defn- parse-pane-line
  "Parses a pane info line into a map with :pid, :cwd,
   and :pane-id. Uses 3-part split: pid|pane-id|path."
  [line]
  (let [parts (str/split line pane-sep-re 3)]
    (when (= 3 (count parts))
      {:pid (nth parts 0)
       :pane-id (nth parts 1)
       :cwd (nth parts 2)})))

(defn list-pane-info
  "Returns a list of maps with :cwd, :pid, and :pane-id
   for each pane. Returns empty list when tmux has no panes.
   Returns nil only if tmux is unavailable."
  []
  (try
    (let [fmt (str "#{pane_pid}"
                   pane-separator "#{pane_id}"
                   pane-separator "#{pane_current_path}")
          result (shell/sh
                  "tmux" "list-panes" "-a"
                  "-F" fmt)]
      (when (zero? (:exit result))
        (vec (keep parse-pane-line
                   (str/split-lines
                    (:out result))))))
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
   Falls back to pgrep on non-Linux. Returns empty list
   when no children exist, nil only on unexpected errors."
  [pid]
  (try
    (let [f (io/file (str "/proc/" pid "/task/"
                          pid "/children"))]
      (if (.exists f)
        (remove str/blank?
                (str/split (str/trim (slurp f)) #"\s+"))
        (let [result (shell/sh
                      "pgrep" "-P" (str pid))]
          (if (zero? (:exit result))
            (remove str/blank?
                    (str/split-lines
                     (:out result)))
            ()))))
    (catch Exception _ nil)))

(defn- agent-pattern
  "Returns a regex pattern matching the agent type name."
  [agent-type]
  (case agent-type
    :claude-code #"(?i)claude"
    :codex #"(?i)codex"
    #"(?i)claude|codex"))

(declare find-agent-in-tree)

(defn- search-children
  "Searches child processes for an agent, returning the best
   result across all children via single-pass reduce."
  [children agent-type max-depth]
  (reduce (fn [best child]
            (let [r (find-agent-in-tree
                     child agent-type max-depth)]
              (case r
                :found (reduced :found)
                :unknown :unknown
                best)))
          :not-found
          children))

(defn find-agent-in-tree
  "Searches the process tree rooted at pid for an agent
   process matching the given agent-type.
   Returns :found, :not-found, or :unknown (when process
   info is unavailable). Max depth prevents infinite loops."
  ([pid agent-type] (find-agent-in-tree pid agent-type 5))
  ([pid agent-type max-depth]
   (if (neg? max-depth)
     :not-found
     (let [pat (agent-pattern agent-type)
           cmdline (read-proc-cmdline pid)]
       (cond
         (nil? cmdline) :unknown
         (re-find pat cmdline) :found
         :else
         (let [children (child-pids pid)]
           (if (nil? children)
             :unknown
             (search-children
              children agent-type
              (dec max-depth)))))))))

(defn- session-has-live-agent?
  "Checks if a session's agent is alive by searching the
   process tree of matching tmux panes.
   Prefers pane-id match over cwd-only match.
   Returns :alive, :dead, or :unknown."
  [session pane-infos]
  (let [pane-id (:pane-id session)
        cwd (:cwd session)
        agent-type (:agent-type session)
        by-pane-id (when (seq pane-id)
                     (filter #(= pane-id (:pane-id %))
                             pane-infos))
        matching-panes (if (seq by-pane-id)
                         by-pane-id
                         (filter #(= cwd (:cwd %))
                                 pane-infos))
        results (map #(find-agent-in-tree
                       (:pid %) agent-type)
                     matching-panes)]
    (cond
      (some #{:found} results) :alive
      (some #{:unknown} results) :unknown
      :else :dead)))

(defn- pane-id-exists?
  "Returns true if pane-id is found in pane-infos."
  [pane-id pane-infos]
  (some #(= pane-id (:pane-id %)) pane-infos))

(defn- stale-session?
  "Returns true if the session is stale given pane state.
   Conservative: returns false when liveness is unknown.
   When a session has a pane-id and that pane still exists,
   uses process-tree check even if cwd changed.
   Skips process-tree check when session has no pane-id
   (started outside tmux)."
  [session pane-cwds pane-infos]
  (let [cwd (:cwd session)
        pane-id (:pane-id session)
        cwd-present? (contains? pane-cwds cwd)
        pane-exists? (and (seq pane-id)
                          (pane-id-exists? pane-id pane-infos))]
    (and (seq cwd)
         (cond
           pane-exists?
           (= :dead (session-has-live-agent?
                     session pane-infos))
           (not cwd-present?) true
           (seq pane-id)
           (= :dead (session-has-live-agent?
                     session pane-infos))
           :else false))))

(defn close-stale-sessions!
  "Checks running sessions and marks stale ones as closed.
   Also purges expired closed sessions whose pane is gone.
   Atomically updates all stale sessions under file lock.
   Does nothing if tmux is unavailable (nil)."
  ([] (close-stale-sessions! nil))
  ([state-dir]
   (let [pane-infos (list-pane-info)]
     (when (some? pane-infos)
       (let [pane-cwds (into #{} (map :cwd) pane-infos)
             pane-ids (into #{} (map :pane-id) pane-infos)
             pred (fn [_sid session]
                    (stale-session?
                     session pane-cwds pane-infos))]
         (if state-dir
           (do (store/close-sessions-by-pred!
                state-dir pred)
               (store/purge-expired-closed-sessions!
                state-dir pane-ids))
           (do (store/close-sessions-by-pred! pred)
               (store/purge-expired-closed-sessions!
                pane-ids))))))))

(def ^:private debounce-ms
  "Minimum time (ms) since last hook update before
   capture-based state refresh is applied."
  2000)

(defn- recently-updated?
  "Returns true if the session was updated within
   debounce-ms milliseconds. Returns false when
   timestamp is missing or unparseable."
  [session]
  (if-let [ts (:last-updated session)]
    (try
      (let [updated (.toEpochMilli
                     (java.time.Instant/parse ts))
            now (.toEpochMilli (java.time.Instant/now))]
        (< (- now updated) debounce-ms))
      (catch Exception _ false))
    false))

(def ^:private capturable-statuses
  "Session statuses eligible for capture-pane refresh."
  #{:running :idle :waiting})

(defn- capture-state-for-session
  "Detects state for an active session via capture-pane.
   Returns updated session data or nil if no change is
   needed. Processes running, idle, and waiting sessions
   so intermediate transitions are tracked."
  [session]
  (when (and (contains? capturable-statuses
                        (:agent-status session))
             (seq (:pane-id session))
             (not (recently-updated? session)))
    (when-let [detected (capture/detect-agent-state
                         (:pane-id session)
                         (:agent-type session))]
      (when (and (:status detected)
                 (not= (:status detected)
                       (:agent-status session)))
        {:agent-status (:status detected)
         :last-updated (.toString
                        (java.time.Instant/now))}))))

(def ^:private reactivatable-statuses
  "Statuses that justify reopening a closed session.
   :idle alone is excluded because detect-agent-state can
   classify a plain shell prompt as :idle even when no agent
   process is present, causing closed->idle flapping."
  #{:running :waiting})

(defn- capture-state-for-closed-session
  "Detects state for a closed (non-superseded) session.
   Returns updated session data only when the agent is
   actively running or waiting, not merely idle."
  [session]
  (when (and (= :closed (:agent-status session))
             (not (:superseded session))
             (seq (:pane-id session)))
    (when-let [detected (capture/detect-agent-state
                         (:pane-id session)
                         (:agent-type session))]
      (when (contains? reactivatable-statuses
                       (:status detected))
        {:agent-status (:status detected)
         :last-updated (.toString
                        (java.time.Instant/now))}))))

(defn refresh-session-states!
  "Refreshes active session states via capture-pane.
   Processes sessions in :running, :idle, and :waiting
   so intermediate transitions are continuously tracked.
   Also checks closed (non-superseded) sessions for agent
   reactivation in their pane.
   Uses update-session-if-active! to atomically verify
   the session is still active before writing, preventing
   overwrite of newer hook-driven state transitions."
  ([] (refresh-session-states! nil))
  ([state-dir]
   (let [state (if state-dir
                 (store/read-sessions state-dir)
                 (store/read-sessions))
         sessions (:sessions state)]
     (doseq [[sid session] sessions]
       (if-let [update-data
                (capture-state-for-session session)]
         (if state-dir
           (store/update-session-if-active!
            state-dir sid update-data)
           (store/update-session-if-active!
            sid update-data))
         (when-let [reactivate-data
                    (capture-state-for-closed-session
                     session)]
           (if state-dir
             (store/reactivate-closed-session!
              state-dir sid reactivate-data)
             (store/reactivate-closed-session!
              sid reactivate-data))))))))

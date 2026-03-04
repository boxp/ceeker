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

(def ^:private pane-separator "|||")

(def ^:private pane-sep-re
  "Compiled regex for splitting pane info lines."
  (re-pattern
   (java.util.regex.Pattern/quote pane-separator)))

(defn- parse-pane-line
  "Parses a pane info line into a map with :cwd and :pid."
  [line]
  (let [parts (str/split line pane-sep-re 2)]
    (when (= 2 (count parts))
      {:cwd (first parts) :pid (second parts)})))

(defn list-pane-info
  "Returns a list of maps with :cwd and :pid for each pane.
   Returns nil if tmux is unavailable or no panes found."
  []
  (try
    (let [fmt (str "#{pane_current_path}"
                   pane-separator "#{pane_pid}")
          result (shell/sh
                  "tmux" "list-panes" "-a"
                  "-F" fmt)]
      (when (zero? (:exit result))
        (let [infos (keep parse-pane-line
                          (str/split-lines
                           (:out result)))]
          (when (seq infos) infos))))
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
             (let [results (map #(find-agent-in-tree
                                  % agent-type
                                  (dec max-depth))
                                children)]
               (cond
                 (some #{:found} results) :found
                 (some #{:unknown} results) :unknown
                 :else :not-found)))))))))

(defn- session-has-live-agent?
  "Checks if a session's agent is alive by searching the
   process tree of matching tmux panes.
   Returns :alive, :dead, or :unknown."
  [session pane-infos]
  (let [cwd (:cwd session)
        agent-type (:agent-type session)
        matching-panes (filter #(= cwd (:cwd %))
                               pane-infos)
        results (map #(find-agent-in-tree
                       (:pid %) agent-type)
                     matching-panes)]
    (cond
      (some #{:found} results) :alive
      (some #{:unknown} results) :unknown
      :else :dead)))

(defn- stale-session?
  "Returns true if the session is stale given pane state.
   Conservative: returns false when liveness is unknown."
  [session pane-cwds pane-infos]
  (and (seq (:cwd session))
       (if (not (contains? pane-cwds (:cwd session)))
         true
         (= :dead (session-has-live-agent?
                   session pane-infos)))))

(defn close-stale-sessions!
  "Checks running sessions and marks stale ones as closed.
   Atomically updates all stale sessions under file lock.
   Does nothing if tmux is unavailable."
  ([] (close-stale-sessions! nil))
  ([state-dir]
   (when-let [pane-infos (list-pane-info)]
     (let [pane-cwds (set (map :cwd pane-infos))
           pred (fn [_sid session]
                  (stale-session?
                   session pane-cwds pane-infos))]
       (if state-dir
         (store/close-sessions-by-pred!
          state-dir pred)
         (store/close-sessions-by-pred! pred))))))

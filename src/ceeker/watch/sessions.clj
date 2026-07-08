(ns ceeker.watch.sessions
  "Session history file watcher for Claude Code and Codex JSONL files."
  (:require [ceeker.state.store :as store]
            [ceeker.tmux.pane :as pane]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader File FileInputStream InputStreamReader]
           [java.nio.charset StandardCharsets]
           [java.nio.file FileSystems Path StandardWatchEventKinds
            WatchEvent WatchEvent$Kind WatchKey WatchService]
           [java.util.concurrent TimeUnit]))

(def ^:private max-line-chars 1048576)
(def ^:private poll-timeout-ms 500)
(def ^:private default-since-hours 24)

(defn default-claude-root []
  (str (System/getProperty "user.home") "/.claude/projects"))

(defn default-codex-root []
  (str (System/getProperty "user.home") "/.codex/sessions"))

(defn- now-iso []
  (.toString (java.time.Instant/now)))

(defn- parse-json [line]
  (try
    (json/parse-string line true)
    (catch Exception _ nil)))

(defn- assistant-content [message]
  (let [content (:content message)]
    (cond
      (string? content) content
      (seq content) (str/join
                     "\n"
                     (keep (fn [part]
                             (or (:text part)
                                 (when (string? part) part)))
                           content))
      :else nil)))

(defn- parse-claude [m]
  (let [session-id (:sessionId m)
        cwd (:cwd m)
        timestamp (:timestamp m)]
    (when (or session-id cwd timestamp)
      (cond-> {:agent-type :claude-code
               :agent-status :running}
        session-id (assoc :session-id session-id)
        cwd (assoc :cwd cwd)
        timestamp (assoc :last-updated timestamp)
        (= "assistant" (:type m))
        (assoc :last-message
               (assistant-content (:message m)))))))

(defn- parse-codex-event [timestamp payload]
  (case (:type payload)
    "task_started"
    (cond-> {:agent-type :codex
             :agent-status :running}
      timestamp (assoc :last-updated timestamp))
    "task_complete"
    (cond-> {:agent-type :codex
             :agent-status :completed}
      (:last_agent_message payload)
      (assoc :last-message
             (:last_agent_message payload))
      timestamp
      (assoc :last-updated timestamp))
    "agent_message"
    (cond-> {:agent-type :codex
             :agent-status :running}
      timestamp (assoc :last-updated timestamp))
    nil))

(defn- parse-codex-meta [timestamp payload]
  (cond-> {:agent-type :codex
           :agent-status :running}
    (:session_id payload)
    (assoc :session-id (:session_id payload))
    (:cwd payload)
    (assoc :cwd (:cwd payload))
    timestamp
    (assoc :last-updated timestamp)))

(defn- parse-codex [m]
  (let [timestamp (:timestamp m)
        payload (:payload m)]
    (case (:type m)
      "session_meta" (parse-codex-meta timestamp payload)
      "event_msg" (parse-codex-event timestamp payload)
      nil)))

(defn parse-jsonl-line
  "Parses a single session JSONL line for agent-type."
  [agent-type line]
  (when-let [m (parse-json line)]
    (case agent-type
      :claude-code (parse-claude m)
      :codex (parse-codex m)
      nil)))

(defn- safe-line [line]
  (when (<= (count line) max-line-chars)
    line))

(defn tail-new-lines!
  "Reads only bytes appended since the last offset for file."
  [offsets ^File file]
  (let [path (.getAbsolutePath file)
        start (long (get @offsets path 0))
        length (.length file)
        start (if (> start length) 0 start)]
    (with-open [in (FileInputStream. file)]
      (.skip in start)
      (let [reader (BufferedReader.
                    (InputStreamReader.
                     in StandardCharsets/UTF_8))
            lines (doall (keep safe-line
                               (line-seq reader)))]
        (swap! offsets assoc path length)
        lines))))

(defn read-proc-environ
  "Reads /proc/<pid>/environ into a string map, or nil."
  [pid]
  (try
    (let [f (io/file (str "/proc/" pid "/environ"))]
      (when (.exists f)
        (into {}
              (keep (fn [entry]
                      (let [[k v] (str/split entry #"=" 2)]
                        (when (seq k) [k v]))))
              (str/split (slurp f) #"\u0000"))))
    (catch Exception _ nil)))

(defn resolve-pane-id
  "Resolves tmux pane id from session cwd and agent process."
  [{:keys [cwd agent-type]}]
  (when (and (seq cwd) agent-type)
    (let [candidates (filter #(= cwd (:cwd %))
                             (or (pane/list-pane-info) []))
          matches (keep
                   (fn [p]
                     (when-let [agent-pid
                                (pane/find-agent-pid-in-tree
                                 (:pid p) agent-type)]
                       (assoc p :agent-pid agent-pid)))
                   candidates)
          environ-pane (some (fn [p]
                               (get (read-proc-environ
                                     (:agent-pid p))
                                    "TMUX_PANE"))
                             matches)]
      (or environ-pane
          (when (= 1 (count matches))
            (:pane-id (first matches)))))))

(defn- session-time-ms [session]
  (if-let [ts (:last-updated session)]
    (try
      (.toEpochMilli (java.time.Instant/parse ts))
      (catch Exception _ 0))
    0))

(defn- existing-session [sessions session]
  (let [sid (:session-id session)]
    (some (fn [[_ s]]
            (when (= sid (:session-id s)) s))
          sessions)))

(defn- should-write? [state session]
  (if-let [existing (existing-session (:sessions state) session)]
    (>= (session-time-ms session)
        (session-time-ms existing))
    true))

(defn- write-session! [state-dir session]
  (when (seq (:session-id session))
    (let [state (store/read-sessions state-dir)
          session (cond-> session
                    (nil? (:last-updated session))
                    (assoc :last-updated (now-iso))
                    (nil? (:pane-id session))
                    (assoc :pane-id (resolve-pane-id session)))
          key (or (:pane-id session) (:session-id session))]
      (when (should-write? state session)
        (store/update-session! state-dir key session)))))

(defn- merge-event [acc event]
  (let [merged (merge acc event)]
    (if (and (= :completed (:agent-status event))
             (:last-message event))
      merged
      (dissoc merged :last-message))))

(defn- file-agent-type [^File file]
  (let [path (.getPath file)]
    (cond
      (str/includes? path ".claude") :claude-code
      (str/includes? path ".codex") :codex
      (str/starts-with? (.getName file) "rollout-") :codex
      :else :claude-code)))

(defn- jsonl-file? [^File file]
  (and (.isFile file)
       (str/ends-with? (.getName file) ".jsonl")))

(defn- process-lines! [state-dir file-states ^File file lines]
  (let [agent-type (file-agent-type file)
        path (.getAbsolutePath file)
        current (get @file-states path {})
        next-state (reduce
                    (fn [acc line]
                      (if-let [event (parse-jsonl-line
                                      agent-type line)]
                        (merge-event acc event)
                        acc))
                    current
                    lines)]
    (swap! file-states assoc path next-state)
    (when (seq (:session-id next-state))
      (write-session! state-dir next-state))))

(defn- recent-file? [cutoff-ms ^File file]
  (and (jsonl-file? file)
       (>= (.lastModified file) cutoff-ms)))

(defn- session-files [root cutoff-ms]
  (let [dir (io/file root)]
    (if (.exists dir)
      (filter #(recent-file? cutoff-ms %)
              (file-seq dir))
      [])))

(defn scan-recent-sessions!
  "Synchronously scans recent JSONL session files into state store."
  ([] (scan-recent-sessions! {}))
  ([{:keys [claude-root codex-root state-dir since-hours]
     :or {since-hours default-since-hours}}]
   (let [state-dir (or state-dir (store/state-dir))
         claude-root (or claude-root (default-claude-root))
         codex-root (or codex-root (default-codex-root))
         cutoff-ms (- (System/currentTimeMillis)
                      (* since-hours 60 60 1000))
         file-states (atom {})]
     (doseq [file (concat (session-files claude-root cutoff-ms)
                          (session-files codex-root cutoff-ms))]
       (with-open [reader (io/reader file)]
         (process-lines! state-dir file-states file
                         (doall (line-seq reader))))))))

(defn- close-watch-service! [^WatchService ws]
  (when ws (.close ws)))

(defn- register-dir! [^WatchService ws key->dir ^File dir]
  (when (.isDirectory dir)
    (let [^Path path (.toPath dir)
          key (.register
               path ws
               (into-array
                WatchEvent$Kind
                [StandardWatchEventKinds/ENTRY_CREATE
                 StandardWatchEventKinds/ENTRY_MODIFY]))]
      (swap! key->dir assoc key path))))

(defn- register-recursive! [^WatchService ws key->dir root]
  (let [dir (io/file root)]
    (when (.exists dir)
      (doseq [^File f (file-seq dir)]
        (when (.isDirectory f)
          (register-dir! ws key->dir f))))))

(defn- event-file [key->dir ^WatchKey key ^WatchEvent evt]
  (let [^Path dir (get @key->dir key)
        context ^Path (.context evt)]
    (.toFile (.resolve dir context))))

(defn- handle-watch-event!
  [^WatchService ws key->dir offsets file-states state-dir
   ^WatchKey key ^WatchEvent evt]
  (let [kind (.kind evt)
        file (event-file key->dir key evt)]
    (when (and (= kind StandardWatchEventKinds/ENTRY_CREATE)
               (.isDirectory file))
      (register-recursive! ws key->dir (.getPath file)))
    (when (jsonl-file? file)
      (process-lines! state-dir file-states file
                      (tail-new-lines! offsets file)))))

(defn- watch-context [claude-root codex-root]
  (let [ws (.newWatchService (FileSystems/getDefault))
        key->dir (atom {})]
    (register-recursive! ws key->dir claude-root)
    (register-recursive! ws key->dir codex-root)
    {:watch-service ws
     :key->dir key->dir
     :offsets (atom {})
     :file-states (atom {})}))

(defn- poll-watch-once! [ctx state-dir]
  (when-let [^WatchKey key
             (.poll ^WatchService (:watch-service ctx)
                    0 TimeUnit/MILLISECONDS)]
    (doseq [evt (.pollEvents key)]
      (handle-watch-event!
       (:watch-service ctx) (:key->dir ctx)
       (:offsets ctx) (:file-states ctx)
       state-dir key evt))
    (.reset key)))

(defn run-watch-loop!
  "Runs the blocking WatchService loop until stop-ch is closed."
  [stop-ch {:keys [claude-root codex-root state-dir]
            :or {state-dir nil}}]
  (let [state-dir (or state-dir (store/state-dir))
        claude-root (or claude-root (default-claude-root))
        codex-root (or codex-root (default-codex-root))
        ctx (watch-context claude-root codex-root)]
    (try
      (loop []
        (let [[_ ch] (async/alts!!
                      [stop-ch (async/timeout poll-timeout-ms)]
                      :priority true)]
          (when-not (= ch stop-ch)
            (poll-watch-once! ctx state-dir)
            (recur))))
      (finally
        (close-watch-service! (:watch-service ctx))))))

(defn start-session-watcher!
  "Starts a background session-file watcher. Returns stop channel."
  ([] (start-session-watcher! {}))
  ([opts]
   (let [stop-ch (async/chan)]
     (async/thread
       (try
         (run-watch-loop! stop-ch opts)
         (catch Exception e
           (.println System/err
                     (str "ceeker: session watcher failed: "
                          (.getMessage e))))))
     stop-ch)))

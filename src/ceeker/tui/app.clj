(ns ceeker.tui.app
  "TUI application main loop."
  (:require [ceeker.state.store :as store]
            [ceeker.tui.input :as input]
            [ceeker.tui.view :as view]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn- get-session-list
  "Gets sorted session list from state store."
  ([] (get-session-list nil))
  ([state-dir]
   (let [state (if state-dir
                 (store/read-sessions state-dir)
                 (store/read-sessions))]
     (vals (:sessions state)))))

(defn- find-tmux-pane
  "Finds a tmux pane matching the given cwd."
  [cwd]
  (let [result (shell/sh
                "tmux" "list-panes" "-a"
                "-F"
                "#{session_name}:#{window_index}.#{pane_index} #{pane_current_path}")
        panes (when (zero? (:exit result))
                (str/split-lines (:out result)))]
    (first
     (keep
      (fn [pane-line]
        (let [parts (str/split pane-line #" " 2)]
          (when (= (second parts) cwd)
            (first parts))))
      panes))))

(defn- switch-tmux-pane!
  "Switches to the specified tmux pane target.
   Returns result map with :success and optional :error."
  [target]
  (let [result (shell/sh "tmux" "switch-client" "-t" target)]
    (if (zero? (:exit result))
      {:success true :target target}
      {:success false
       :error (str "switch-client failed: "
                   (str/trim (:err result)))})))

(defn- tmux-jump!
  "Jumps to the tmux pane for the given session."
  [session]
  (let [cwd (:cwd session)]
    (when (seq cwd)
      (try
        (if-let [target (find-tmux-pane cwd)]
          (switch-tmux-pane! target)
          {:success false
           :error (str "No tmux pane found for: " cwd)})
        (catch Exception e
          {:success false :error (.getMessage e)})))))

(defn- clamp
  "Clamps value between min-val and max-val."
  [value min-val max-val]
  (max min-val (min value max-val)))

(defn- sort-sessions
  "Sorts sessions: running first, then by last-updated."
  [sessions]
  (sort-by
   (fn [s]
     [(if (= :running (:agent-status s)) 0 1)
      (or (:last-updated s) "")])
   sessions))

(defn- render-screen
  "Renders the screen with sessions and optional message."
  [sorted-sessions selected message]
  (str (view/render sorted-sessions selected)
       (when message (str "\n" message))))

(defn- handle-enter-key
  "Handles Enter key press on selected session."
  [sorted-sessions selected]
  (if (empty? sorted-sessions)
    (view/render-error "No sessions")
    (let [session (nth sorted-sessions selected)
          result (tmux-jump! session)]
      (if (:success result)
        (view/render-message
         (str "Jumped to: " (:target result)))
        (view/render-error (:error result))))))

(defn- handle-key-input
  "Processes a key and returns [new-selected new-message] or nil to quit."
  [key selected max-idx sorted-sessions]
  (cond
    (= key \q) nil
    (or (= key :up) (= key \k))
    [(max 0 (dec selected)) nil]
    (or (= key :down) (= key \j))
    [(min max-idx (inc selected)) nil]
    (= key :enter)
    [selected (handle-enter-key sorted-sessions selected)]
    (= key \r)
    [selected (view/render-message "Refreshed")]
    :else [selected nil]))

(defn start-tui!
  "Runs the TUI application loop."
  ([] (start-tui! nil))
  ([state-dir]
   (let [terminal (input/create-terminal)]
     (try
       (loop [selected 0, message nil]
         (let [sessions (get-session-list state-dir)
               max-idx (max 0 (dec (count sessions)))
               selected (clamp selected 0 max-idx)
               sorted (sort-sessions sessions)
               screen (render-screen sorted selected message)]
           (print screen)
           (flush)
           (let [key (input/read-key terminal 1000)
                 result (handle-key-input
                         key selected max-idx sorted)]
             (when result
               (recur (first result) (second result))))))
       (finally
         (print "\033[2J\033[H")
         (flush)
         (input/close-terminal terminal))))))

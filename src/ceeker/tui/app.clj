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

(defn- get-terminal-width
  "Gets the current terminal width from a JLine terminal."
  [^org.jline.terminal.Terminal terminal]
  (let [w (.getWidth terminal)]
    (if (pos? w) w 120)))

(defn- next-display-mode
  "Cycles display mode: :auto -> :table -> :card -> :auto."
  [current]
  (case current
    :auto :table
    :table :card
    :card :auto
    :auto))

(defn- display-mode-label
  "Returns display label for the current mode."
  [mode]
  (case mode
    :auto "Auto"
    :table "Table"
    :card "Card"
    "Auto"))

(defn- render-screen
  "Renders the screen with sessions and optional message."
  [sorted-sessions selected message terminal-width display-mode]
  (str (view/render sorted-sessions selected terminal-width display-mode)
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
  "Processes a key and returns [new-selected new-message new-display-mode]
   or nil to quit."
  [key selected max-idx sorted-sessions display-mode]
  (cond
    (= key \q) nil
    (or (= key :up) (= key \k))
    [(max 0 (dec selected)) nil display-mode]
    (or (= key :down) (= key \j))
    [(min max-idx (inc selected)) nil display-mode]
    (= key :enter)
    [selected (handle-enter-key sorted-sessions selected) display-mode]
    (= key \r)
    [selected (view/render-message "Refreshed") display-mode]
    (= key \v)
    (let [new-mode (next-display-mode display-mode)]
      [selected
       (view/render-message
        (str "View: " (display-mode-label new-mode)))
       new-mode])
    :else [selected nil display-mode]))

(defn start-tui!
  "Runs the TUI application loop."
  ([] (start-tui! nil))
  ([state-dir]
   (let [terminal (input/create-terminal)]
     (try
       (loop [selected 0, message nil, display-mode :auto]
         (let [sessions (get-session-list state-dir)
               max-idx (max 0 (dec (count sessions)))
               selected (clamp selected 0 max-idx)
               sorted (sort-sessions sessions)
               width (get-terminal-width terminal)
               screen (render-screen
                       sorted selected message width display-mode)]
           (print screen)
           (flush)
           (let [key (input/read-key terminal 1000)
                 result (handle-key-input
                         key selected max-idx sorted display-mode)]
             (when result
               (recur (first result)
                      (second result)
                      (nth result 2))))))
       (finally
         (print "\033[2J\033[H")
         (flush)
         (input/close-terminal terminal))))))

(ns ceeker.tui.app
  "TUI application main loop."
  (:require [ceeker.state.store :as store]
            [ceeker.tmux.pane :as pane]
            [ceeker.tui.filter :as f]
            [ceeker.tui.input :as input]
            [ceeker.tui.view :as view]
            [ceeker.tui.watcher :as watcher]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def ^:private check-interval
  "Pane liveness check interval in ticks (~500ms each)."
  20)

(defn- maybe-check-panes!
  "Runs pane liveness check when tick is due."
  [tick state-dir]
  (when (zero? (mod tick check-interval))
    (pane/close-stale-sessions! state-dir)))

(defn- get-session-list
  "Gets session list from state store."
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
                "tmux" "list-panes" "-a" "-F"
                (str "#{session_name}:#{window_index}"
                     ".#{pane_index}"
                     " #{pane_current_path}"))
        panes (when (zero? (:exit result))
                (str/split-lines (:out result)))]
    (first
     (keep
      (fn [line]
        (let [parts (str/split line #" " 2)]
          (when (= (second parts) cwd)
            (first parts))))
      panes))))

(defn- switch-tmux-pane!
  "Switches to the specified tmux pane target."
  [target]
  (let [result (shell/sh
                "tmux" "switch-client" "-t" target)]
    (if (zero? (:exit result))
      {:success true :target target}
      {:success false
       :error (str "switch failed: "
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
           :error (str "No pane for: " cwd)})
        (catch Exception e
          {:success false :error (.getMessage e)})))))

(defn- clamp
  "Clamps value between min-val and max-val."
  [value min-val max-val]
  (max min-val (min value max-val)))

(defn- filtered-sorted
  "Applies filters then sorts sessions."
  [sessions filter-state]
  (sort-by
   (fn [s]
     [(if (= :running (:agent-status s)) 0 1)
      (or (:last-updated s) "")])
   (f/apply-filters filter-state sessions)))

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
  "Renders the screen with sessions and message."
  [sessions sel filt sm? sb msg terminal-width display-mode]
  (str (view/render sessions sel terminal-width display-mode
                    filt sm? sb)
       (when msg (str "\n" msg))))

(defn- handle-enter-key
  "Handles Enter key press on selected session."
  [visible selected]
  (if (empty? visible)
    (view/render-error "No sessions")
    (let [result (tmux-jump! (nth visible selected))]
      (if (:success result)
        (view/render-message
         (str "Jumped to: " (:target result)))
        (view/render-error (:error result))))))

(defn- handle-search-key
  "Handles a key press in search mode.
   Applies filter interactively on each keystroke."
  [key search-buf filter-state]
  (cond
    (= key :enter)
    {:sm? false :sb nil
     :fs (f/set-search-query filter-state search-buf)}
    (= key :escape)
    {:sm? false :sb nil
     :fs (f/set-search-query filter-state nil)}
    (or (= key \u007f) (= key \backspace))
    (let [new-buf (when (seq search-buf)
                    (subs search-buf
                          0 (dec (count search-buf))))]
      {:sm? true :sb new-buf
       :fs (f/set-search-query filter-state new-buf)})
    (char? key)
    (let [new-buf (str search-buf key)]
      {:sm? true :sb new-buf
       :fs (f/set-search-query filter-state new-buf)})
    :else
    {:sm? true :sb search-buf :fs filter-state}))

(defn- nav-key-result
  "Handles navigation and action keys."
  [key sel max-idx visible fs display-mode]
  (cond
    (= key \q) {:quit true}
    (or (= key :up) (= key \k))
    {:sel (max 0 (dec sel)) :fs fs}
    (or (= key :down) (= key \j))
    {:sel (min max-idx (inc sel)) :fs fs}
    (= key :enter)
    {:sel sel :fs fs
     :msg (handle-enter-key visible sel)}
    (= key \r)
    {:sel sel :fs fs
     :msg (view/render-message "Refreshed")}
    (= key \v)
    (let [new-mode (next-display-mode display-mode)]
      {:sel sel :fs fs :dm new-mode
       :msg (view/render-message
             (str "View: "
                  (display-mode-label new-mode)))})
    :else nil))

(defn- filter-key-result
  "Handles filter toggle keys."
  [key fs]
  (case key
    \a {:sel 0 :fs (f/toggle-agent-filter fs)}
    \s {:sel 0 :fs (f/toggle-status-filter fs)}
    \/ {:sm? true :sb "" :fs fs}
    \c {:sel 0 :fs (f/clear-filters fs)}
    nil))

(defn- handle-normal-key
  "Processes a key in normal mode."
  [key sel max-idx visible fs display-mode]
  (let [result (or (nav-key-result
                    key sel max-idx visible fs display-mode)
                   (filter-key-result key fs)
                   {:sel sel :fs fs})]
    (assoc result :dm (get result :dm display-mode))))

(defn- process-key
  "Dispatches key to appropriate handler."
  [key clamped sm? sb visible max-idx fs display-mode]
  (cond
    (nil? key) {:idle true}
    sm? (let [r (handle-search-key key sb fs)]
          {:sel 0 :fs (:fs r)
           :sm? (:sm? r) :sb (:sb r)
           :dm display-mode})
    :else (handle-normal-key
           key clamped max-idx visible
           fs display-mode)))

(defn- wait-for-input
  "Waits for key or file change, returns key or nil.
   Returns nil after a single 500ms poll to allow periodic tasks."
  [terminal w]
  (let [key (input/read-key terminal 500)]
    (cond
      (some? key) key
      :else (do (when w (watcher/poll-change w 0)) nil))))

(defn- create-watcher-for
  "Creates a watcher for the given state dir.
   Returns nil if WatchService is unavailable."
  [state-dir]
  (try
    (if state-dir
      (watcher/create-watcher state-dir)
      (watcher/create-watcher))
    (catch Exception _ nil)))

(defn- next-loop-state
  "Applies process-key result to loop state.
   Returns nil to exit the loop on quit."
  [r cl fs sm? sb display-mode]
  (cond
    (:quit r) nil
    (:idle r) [cl nil fs sm? sb display-mode]
    (nil? (:fs r)) nil
    :else [(get r :sel cl) (:msg r) (:fs r)
           (get r :sm? false) (:sb r) (:dm r)]))

(defn- tui-loop
  "Main TUI render-input loop."
  [terminal w state-dir]
  (loop [sel 0 msg nil fs f/empty-filter
         sm? false sb nil display-mode :auto tick 0]
    (maybe-check-panes! tick state-dir)
    (let [sessions (get-session-list state-dir)
          visible (filtered-sorted sessions fs)
          mx (max 0 (dec (count visible)))
          cl (clamp sel 0 mx)
          width (get-terminal-width terminal)
          scr (render-screen sessions cl fs sm? sb msg
                             width display-mode)]
      (print scr)
      (flush)
      (let [r (process-key
               (wait-for-input terminal w)
               cl sm? sb visible mx fs display-mode)]
        (when-let [next-state
                   (next-loop-state r cl fs sm? sb display-mode)]
          (let [[nsel nmsg nfs nsm? nsb ndm]
                next-state]
            (recur nsel nmsg nfs nsm? nsb ndm (inc tick))))))))

(defn start-tui!
  "Runs the TUI application loop."
  ([] (start-tui! nil))
  ([state-dir]
   (let [terminal (input/create-terminal)
         w (create-watcher-for state-dir)]
     (try
       (tui-loop terminal w state-dir)
       (finally
         (print "\033[2J\033[H")
         (flush)
         (watcher/close-watcher w)
         (input/close-terminal terminal))))))

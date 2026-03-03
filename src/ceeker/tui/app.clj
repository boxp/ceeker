(ns ceeker.tui.app
  "TUI application main loop."
  (:require [ceeker.state.store :as store]
            [ceeker.tui.filter :as f]
            [ceeker.tui.input :as input]
            [ceeker.tui.view :as view]
            [ceeker.tui.watcher :as watcher]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

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

(defn- render-screen
  "Renders the screen with sessions and message."
  [sessions sel filt sm? sb msg]
  (str (view/render sessions sel filt sm? sb)
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
  "Handles a key press in search mode."
  [key search-buf filter-state]
  (cond
    (= key :enter)
    {:sm? false :sb nil
     :fs (f/set-search-query filter-state search-buf)}
    (or (= key 27) (= key (char 27)))
    {:sm? false :sb nil :fs filter-state}
    (or (= key (char 127)) (= key (char 8)))
    {:sm? true :fs filter-state
     :sb (when (seq search-buf)
           (subs search-buf
                 0 (dec (count search-buf))))}
    (char? key)
    {:sm? true :sb (str search-buf key)
     :fs filter-state}
    :else
    {:sm? true :sb search-buf :fs filter-state}))

(defn- nav-key-result
  "Handles navigation and action keys."
  [key sel max-idx visible fs]
  (cond
    (= key \q) nil
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
  [key sel max-idx visible fs]
  (or (nav-key-result key sel max-idx visible fs)
      (filter-key-result key fs)
      {:sel sel :fs fs}))

(defn- process-key
  "Dispatches key to appropriate handler."
  [key clamped sm? sb visible max-idx fs]
  (cond
    (nil? key) {:idle true}
    sm? (let [r (handle-search-key key sb fs)]
          {:sel 0 :fs (:fs r)
           :sm? (:sm? r) :sb (:sb r)})
    :else (handle-normal-key
           key clamped max-idx visible fs)))

(defn- create-watcher-for
  "Creates a watcher for the given state dir."
  [state-dir]
  (if state-dir
    (watcher/create-watcher state-dir)
    (watcher/create-watcher)))

(defn- tui-loop
  "Main TUI render-input loop."
  [terminal w state-dir]
  (loop [sel 0 msg nil fs f/empty-filter
         sm? false sb nil]
    (let [sessions (get-session-list state-dir)
          visible (filtered-sorted sessions fs)
          mx (max 0 (dec (count visible)))
          cl (clamp sel 0 mx)
          scr (render-screen sessions cl fs sm? sb msg)]
      (print scr)
      (flush)
      (let [r (process-key
               (input/read-key terminal 500)
               cl sm? sb visible mx fs)]
        (cond
          (:idle r)
          (do (watcher/poll-change w 0)
              (recur cl nil fs sm? sb))
          (nil? (:fs r)) nil
          :else
          (recur (get r :sel cl) (:msg r) (:fs r)
                 (get r :sm? false) (:sb r)))))))

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

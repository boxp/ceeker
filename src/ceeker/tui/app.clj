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

(defn- tmux-jump!
  "Jumps to the tmux pane for the given session.
   Uses cwd to find matching tmux pane."
  [session]
  (let [cwd (:cwd session)]
    (when (seq cwd)
      (try
        (let [result (shell/sh
                      "tmux" "list-panes" "-a"
                      "-F"
                      "#{session_name}:#{window_index}.#{pane_index} #{pane_current_path}")
              panes (when (zero? (:exit result))
                      (str/split-lines (:out result)))
              matching (first
                        (filter
                         (fn [pane-line]
                           (let [parts (str/split
                                        pane-line #" " 2)
                                 pane-cwd (second parts)]
                             (= pane-cwd cwd)))
                         panes))]
          (if matching
            (let [target (first (str/split matching #" "))]
              (shell/sh
               "tmux" "switch-client" "-t" target)
              {:success true :target target})
            {:success false
             :error (str "No tmux pane found for: " cwd)}))
        (catch Exception e
          {:success false
           :error (.getMessage e)})))))

(defn- clamp
  "Clamps value between min-val and max-val."
  [value min-val max-val]
  (max min-val (min value max-val)))

(defn start-tui!
  "Runs the TUI application loop."
  ([] (start-tui! nil))
  ([state-dir]
   (let [terminal (input/create-terminal)]
     (try
       (loop [selected 0
              message nil]
         (let [sessions (get-session-list state-dir)
               max-idx (max 0 (dec (count sessions)))
               selected (clamp selected 0 max-idx)
               sorted-sessions
               (sort-by
                (fn [s]
                  [(if (= :running (:agent-status s))
                     0 1)
                   (or (:last-updated s) "")])
                sessions)
               screen (str (view/render
                            sorted-sessions selected)
                           (when message
                             (str "\n" message)))]
           (print screen)
           (flush)
           (let [key (input/read-key terminal 1000)]
             (cond
               (= key \q)
               (do
                 (print "\033[2J\033[H")
                 (flush))

               (or (= key :up) (= key \k))
               (recur (max 0 (dec selected)) nil)

               (or (= key :down) (= key \j))
               (recur (min max-idx (inc selected)) nil)

               (= key :enter)
               (if (empty? sorted-sessions)
                 (recur selected
                        (view/render-error "No sessions"))
                 (let [session (nth sorted-sessions
                                    selected)
                       result (tmux-jump! session)]
                   (if (:success result)
                     (recur selected
                            (view/render-message
                             (str "Jumped to: "
                                  (:target result))))
                     (recur selected
                            (view/render-error
                             (:error result))))))

               (= key \r)
               (recur selected
                      (view/render-message "Refreshed"))

               :else
               (recur selected nil)))))
       (finally
         (input/close-terminal terminal))))))

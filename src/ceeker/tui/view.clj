(ns ceeker.tui.view
  "TUI rendering using ANSI escape sequences."
  (:require [ceeker.tui.filter :as f]
            [clojure.string :as str]))

(def ^:private ansi-reset "\033[0m")
(def ^:private ansi-bold "\033[1m")
(def ^:private ansi-dim "\033[2m")
(def ^:private ansi-reverse "\033[7m")
(def ^:private ansi-green "\033[32m")
(def ^:private ansi-yellow "\033[33m")
(def ^:private ansi-red "\033[31m")
(def ^:private ansi-cyan "\033[36m")
(def ^:private ansi-blue "\033[34m")
(def ^:private ansi-magenta "\033[35m")

(defn- clear-screen []
  "\033[2J\033[H")

(defn- status-badge
  "Returns colored status badge string."
  [status]
  (case status
    :running (str ansi-green "● Running" ansi-reset)
    :waiting (str ansi-yellow "◉ Waiting" ansi-reset)
    :completed (str ansi-dim "○ Done" ansi-reset)
    :closed (str ansi-dim "✕ Closed" ansi-reset)
    :error (str ansi-red "✗ Error" ansi-reset)
    :idle (str ansi-dim "◌ Idle" ansi-reset)
    (str ansi-dim "? Unknown" ansi-reset)))

(defn- agent-badge
  "Returns colored agent type badge."
  [agent-type]
  (case agent-type
    :claude-code (str ansi-cyan "[Claude]" ansi-reset)
    :codex (str ansi-blue "[Codex]" ansi-reset)
    (str ansi-dim "[???]" ansi-reset)))

(defn- truncate [s max-len]
  (if (and s (> (count s) max-len))
    (str (subs s 0 (- max-len 1)) "…")
    (or s "")))

(defn- format-time [updated]
  (if (and updated (>= (count (str updated)) 19))
    (subs (str updated) 11 19)
    (or updated "")))

(defn- cwd-short-name [cwd]
  (when (seq cwd)
    (last (str/split cwd #"/"))))

(defn- format-session-line
  "Formats a single session line for display."
  [session selected? _index]
  (let [pfx (if selected?
              (str ansi-reverse " > " ansi-reset
                   ansi-reverse)
              "   ")
        sfx (if selected? ansi-reset "")]
    (str pfx
         (format " %-12s %s %s %-12s %-40s %s"
                 (truncate (:session-id session) 12)
                 (agent-badge (:agent-type session))
                 (status-badge (:agent-status session))
                 (or (cwd-short-name (:cwd session)) "")
                 (truncate (:last-message session) 40)
                 (format-time (:last-updated session)))
         sfx)))

(defn- header-line
  "Returns the header line with filter info."
  [total shown fs]
  (let [base (format "  ceeker — %d session(s)" total)
        desc (when (f/active? fs)
               (str " [" (f/describe-filters fs)
                    " → " shown " shown]"))]
    (str ansi-bold base ansi-reset
         (when desc
           (str ansi-magenta desc ansi-reset)))))

(defn- separator-line []
  (str ansi-dim
       "  ──────────────────────────────────────"
       "──────────────────────────────────────"
       ansi-reset))

(defn- column-headers []
  (str ansi-dim
       (format "   %-12s %-9s %-11s %-12s %-40s %s"
               "SESSION" "AGENT" "STATUS"
               "WORKTREE" "MESSAGE" "UPDATED")
       ansi-reset))

(defn- footer-line [search-mode? search-buf]
  (if search-mode?
    (str ansi-cyan "  Search: " (or search-buf "") "▌"
         ansi-dim "  [Enter] Apply  [Esc] Cancel"
         ansi-reset)
    (str ansi-dim
         "  [j/k] Nav  [Enter] Jump  [r] Refresh"
         "  [a] Agent  [s] Status  [/] Search"
         "  [c] Clear  [q] Quit"
         ansi-reset)))

(defn- sort-for-display
  "Sorts sessions: running first, then by last-updated."
  [sessions]
  (sort-by
   (fn [s]
     [(if (= :running (:agent-status s)) 0 1)
      (or (:last-updated s) "")])
   sessions))

(defn- session-lines
  "Renders session rows or empty placeholder."
  [sorted sel]
  (if (empty? sorted)
    [(str "   " ansi-dim "(no sessions)" ansi-reset)]
    (map-indexed
     (fn [i s] (format-session-line s (= i sel) i))
     sorted)))

(defn render
  "Renders the full TUI screen."
  ([sessions sel]
   (render sessions sel f/empty-filter false nil))
  ([sessions sel fs sm? sb]
   (let [filtered (f/apply-filters fs sessions)
         sorted (sort-for-display filtered)]
     (str/join
      "\n"
      (concat
       [(clear-screen)
        (header-line (count sessions)
                     (count sorted) fs)
        (separator-line) (column-headers)
        (separator-line)]
       (session-lines sorted sel)
       [(separator-line) (footer-line sm? sb)])))))

(defn render-error [message]
  (str "\n" ansi-red "  Error: " message ansi-reset))

(defn render-message [message]
  (str "\n" ansi-cyan "  " message ansi-reset))

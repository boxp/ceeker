(ns ceeker.tui.view
  "TUI rendering using ANSI escape sequences."
  (:require [clojure.string :as str]))

(def ^:private ansi-reset "\033[0m")
(def ^:private ansi-bold "\033[1m")
(def ^:private ansi-dim "\033[2m")
(def ^:private ansi-reverse "\033[7m")
(def ^:private ansi-green "\033[32m")
(def ^:private ansi-yellow "\033[33m")
(def ^:private ansi-red "\033[31m")
(def ^:private ansi-cyan "\033[36m")
(def ^:private ansi-blue "\033[34m")

(defn- clear-screen
  "Returns ANSI escape to clear screen and move cursor to top."
  []
  "\033[2J\033[H")

(defn- status-badge
  "Returns colored status badge string."
  [status]
  (case status
    :running (str ansi-green "● Running" ansi-reset)
    :waiting (str ansi-yellow "◉ Waiting" ansi-reset)
    :completed (str ansi-dim "○ Done" ansi-reset)
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

(defn- truncate
  "Truncates string to max-len, adding ellipsis if needed."
  [s max-len]
  (if (and s (> (count s) max-len))
    (str (subs s 0 (- max-len 1)) "…")
    (or s "")))

(defn- format-session-line
  "Formats a single session line for display."
  [session selected? _index]
  (let [prefix (if selected?
                 (str ansi-reverse " > " ansi-reset ansi-reverse)
                 "   ")
        suffix (if selected? ansi-reset "")
        sid (truncate (:session-id session) 12)
        agent (agent-badge (:agent-type session))
        status (status-badge (:agent-status session))
        cwd-short (when-let [cwd (:cwd session)]
                    (let [parts (str/split cwd #"/")]
                      (last parts)))
        msg (truncate (:last-message session) 40)
        updated (:last-updated session)]
    (str prefix
         (format " %-12s %s %s %-12s %-40s %s"
                 sid agent status
                 (or cwd-short "")
                 msg
                 (or (when updated
                       (let [s (str updated)]
                         (if (>= (count s) 19)
                           (subs s 11 19)
                           s)))
                     ""))
         suffix)))

(defn- header-line
  "Returns the header line."
  [session-count]
  (str ansi-bold
       (format "  ceeker — %d session(s)" session-count)
       ansi-reset))

(defn- separator-line
  "Returns a separator line."
  []
  (str ansi-dim
       "  ──────────────────────────────────────"
       "──────────────────────────────────────"
       ansi-reset))

(defn- column-headers
  "Returns column header line."
  []
  (str ansi-dim
       (format "   %-12s %-9s %-11s %-12s %-40s %s"
               "SESSION" "AGENT" "STATUS"
               "WORKTREE" "MESSAGE" "UPDATED")
       ansi-reset))

(defn- footer-line
  "Returns the footer help line."
  []
  (str ansi-dim
       "  [j/k] Navigate  [Enter] Jump to tmux  "
       "[r] Refresh  [q] Quit"
       ansi-reset))

(defn render
  "Renders the full TUI screen. Returns the string to print."
  [sessions selected-index]
  (let [session-list (sort-by
                      (fn [s]
                        [(if (= :running (:agent-status s))
                           0 1)
                         (or (:last-updated s) "")])
                      sessions)
        lines (concat
               [(clear-screen)
                (header-line (count sessions))
                (separator-line)
                (column-headers)
                (separator-line)]
               (map-indexed
                (fn [i session]
                  (format-session-line
                   session (= i selected-index) i))
                session-list)
               [(separator-line)
                (footer-line)])]
    (str/join "\n" lines)))

(defn render-error
  "Renders an error message at the bottom of screen."
  [message]
  (str "\n" ansi-red "  Error: " message ansi-reset))

(defn render-message
  "Renders an info message at the bottom of screen."
  [message]
  (str "\n" ansi-cyan "  " message ansi-reset))

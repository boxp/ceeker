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

(def ^:const compact-threshold
  "Terminal width (columns) below which compact card view is used."
  80)

(def ^:const max-card-message-lines
  "Maximum number of message lines shown in a card."
  3)

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

(defn- char-display-width
  "Returns terminal display width of a character (2 for CJK/fullwidth, 1 otherwise)."
  [c]
  (let [cp (int c)]
    (if (or (<= 0x1100 cp 0x115F)
            (<= 0x2E80 cp 0x33FF)
            (<= 0x3400 cp 0x4DBF)
            (<= 0x4E00 cp 0x9FFF)
            (<= 0xAC00 cp 0xD7AF)
            (<= 0xF900 cp 0xFAFF)
            (<= 0xFF01 cp 0xFF60)
            (<= 0xFFE0 cp 0xFFE6)
            (<= 0x3040 cp 0x30FF)
            (<= 0x3000 cp 0x303F))
      2 1)))

(defn- str-display-width
  "Returns total terminal display width of a string."
  [s]
  (if (seq s)
    (reduce + 0 (map char-display-width s))
    0))

(defn- substr-by-width
  "Extracts the longest prefix of s that fits within max-width terminal columns."
  [s max-width]
  (let [s (or s "")]
    (cond
      (<= max-width 0) ""
      (<= (str-display-width s) max-width) s
      :else
      (loop [chars (seq s)
             width 0
             result []]
        (if (empty? chars)
          (apply str result)
          (let [c (first chars)
                cw (char-display-width c)
                new-width (+ width cw)]
            (if (> new-width max-width)
              (apply str result)
              (recur (rest chars) new-width (conj result c)))))))))

(defn- truncate-by-width
  "Truncates string to fit within max-width terminal columns, appending ellipsis."
  [s max-width]
  (let [s (or s "")]
    (cond
      (<= max-width 0) ""
      (<= (str-display-width s) max-width) s
      :else (str (substr-by-width s (dec max-width)) "…"))))

(defn- wrap-by-width
  "Wraps string into lines that each fit within max-width terminal columns.
   A single character wider than max-width is placed on its own line."
  [s max-width]
  (let [s (or s "")
        max-width (max 1 max-width)]
    (if (empty? s)
      [""]
      (loop [chars (seq s)
             width 0
             current-line []
             lines []]
        (if (empty? chars)
          (conj lines (apply str current-line))
          (let [c (first chars)
                cw (char-display-width c)
                new-width (+ width cw)]
            (if (and (> new-width max-width) (seq current-line))
              (recur chars 0 [] (conj lines (apply str current-line)))
              (recur (rest chars) new-width
                     (conj current-line c) lines))))))))

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
              (str ansi-reverse " > " ansi-reset ansi-reverse)
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

(defn- card-line1 [session selected? sel-start sel-end]
  (str sel-start
       "  ┌ " (truncate (:session-id session) 12)
       " " (agent-badge (:agent-type session))
       (when selected? ansi-reverse)
       " " (status-badge (:agent-status session))
       (when selected? ansi-reverse)
       sel-end))

(defn- card-line2 [session sel-start sel-end content-width]
  (let [time-str (format-time (:last-updated session))
        wt-max (max 5 (- content-width (str-display-width time-str) 2))]
    (str sel-start
         "  │ " time-str
         "  " (truncate-by-width (or (cwd-short-name (:cwd session)) "")
                                 wt-max)
         sel-end)))

(defn- card-message-lines
  "Wraps message text into card lines with border prefix."
  [message content-width sel-start sel-end]
  (let [normalized (str/replace (or message "") #"\r?\n" " ")
        wrapped (wrap-by-width normalized content-width)
        truncated? (> (count wrapped) max-card-message-lines)
        visible (if truncated?
                  (subvec (vec wrapped) 0 max-card-message-lines)
                  wrapped)
        final (if truncated?
                (conj (pop visible)
                      (str (substr-by-width (peek visible)
                                            (dec content-width))
                           "…"))
                visible)]
    (mapv (fn [line] (str sel-start "  │ " line sel-end)) final)))

(defn- format-session-card
  "Formats a single session as a compact card for narrow terminals."
  [session selected? _index width]
  (let [content-width (max 10 (- width 4))
        sel-start (if selected? ansi-reverse "")
        sel-end (if selected? ansi-reset "")
        line1 (card-line1 session selected? sel-start sel-end)
        line2 (card-line2 session sel-start sel-end content-width)
        msg-lines (card-message-lines
                   (:last-message session) content-width
                   sel-start sel-end)
        line-end (str sel-start "  └─" sel-end)]
    (str/join "\n" (concat [line1 line2] msg-lines [line-end]))))

(defn- display-mode-label [display-mode]
  (case display-mode
    :auto "Auto"
    :table "Table"
    :card "Card"
    "Auto"))

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

(defn- separator-line [width]
  (let [bar-len (max 20 (- width 4))]
    (str ansi-dim
         "  " (apply str (repeat bar-len "─"))
         ansi-reset)))

(defn- column-headers []
  (str ansi-dim
       (format "   %-12s %-9s %-11s %-12s %-40s %s"
               "SESSION" "AGENT" "STATUS"
               "WORKTREE" "MESSAGE" "UPDATED")
       ansi-reset))

(defn- footer-line [display-mode search-mode? search-buf]
  (if search-mode?
    (str ansi-cyan "  Search: " (or search-buf "") "▌"
         ansi-dim "  [Enter] Done  [Esc] Clear"
         "  [v] View:" (display-mode-label display-mode)
         ansi-reset)
    (str ansi-dim
         "  [j/k] Nav  [Enter] Jump  [r] Refresh"
         "  [a] Agent  [s] Status  [/] Search"
         "  [c] Clear  [v] View:" (display-mode-label display-mode)
         "  [q] Quit"
         ansi-reset)))

(defn- sort-for-display
  "Sorts sessions: running first, then by last-updated."
  [sessions]
  (sort-by
   (fn [s]
     [(if (= :running (:agent-status s)) 0 1)
      (or (:last-updated s) "")])
   sessions))

(defn- use-compact?
  "Determines if compact card view should be used."
  [display-mode width]
  (case display-mode
    :table false
    :card true
    (< width compact-threshold)))

(defn- session-lines
  "Renders session rows or empty placeholder."
  [sorted sel compact? width]
  (if (empty? sorted)
    [(str "   " ansi-dim "(no sessions)" ansi-reset)]
    (if compact?
      (map-indexed
       (fn [i s] (format-session-card s (= i sel) i width))
       sorted)
      (map-indexed
       (fn [i s] (format-session-line s (= i sel) i))
       sorted))))

(defn render
  "Renders the full TUI screen."
  ([sessions sel]
   (render sessions sel 120 :auto f/empty-filter false nil))
  ([sessions sel terminal-width display-mode]
   (render sessions sel terminal-width display-mode
           f/empty-filter false nil))
  ([sessions sel fs sm? sb]
   (render sessions sel 120 :auto fs sm? sb))
  ([sessions sel terminal-width display-mode fs sm? sb]
   (let [width (or terminal-width 120)
         mode (or display-mode :auto)
         fs* (or fs f/empty-filter)
         filtered (f/apply-filters fs* sessions)
         sorted (sort-for-display filtered)
         compact? (use-compact? mode width)]
     (str/join
      "\n"
      (concat
       [(clear-screen)
        (header-line (count sessions) (count sorted) fs*)
        (separator-line width)]
       (if compact?
         (session-lines sorted sel true width)
         (concat
          [(column-headers) (separator-line width)]
          (session-lines sorted sel false width)))
       [(separator-line width)
        (footer-line mode sm? sb)])))))

(defn render-error [message]
  (str "\n" ansi-red "  Error: " message ansi-reset))

(defn render-message [message]
  (str "\n" ansi-cyan "  " message ansi-reset))

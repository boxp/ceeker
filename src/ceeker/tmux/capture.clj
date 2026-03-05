(ns ceeker.tmux.capture
  "tmux pane capture and agent state detection.
   Reads terminal content via capture-pane and detects
   intermediate agent states (running, waiting, idle)
   based on patterns derived from agentoast analysis."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

;; --- Claude Code detection patterns (from agentoast) ---

(def ^:private claude-spinner-chars
  "Spinner characters used by Claude Code TUI."
  #{\u2722 \u273D \u2736 \u2733 \u273B \u00B7})

(defn- claude-running-line?
  "Returns true if the line indicates Claude is running.
   Checks for spinner chars + interrupt hint or ellipsis."
  [line]
  (when (seq line)
    (let [first-char (first line)]
      (and (contains? claude-spinner-chars first-char)
           (or (str/includes? line "esc to interrupt")
               (str/includes? line "\u2026"))))))

(defn- claude-file-activity-line?
  "Returns true if the line shows file activity with
   interrupt option."
  [line]
  (and (str/includes? line "esc to interrupt")
       (re-find #"\d+\s+files?\s" line)))

(def ^:private prompt-patterns
  "Patterns indicating the shell prompt is visible."
  [#"^\u276F"          ; starship
   #"\$\s*$"          ; bash $
   #"%\s*$"           ; zsh %
   #"^>\s*$"])         ; generic REPL >

(defn- strip-box-border
  "Strips common TUI box-drawing border characters."
  [line]
  (str/replace line
               #"^[\u2500-\u257F\u2502\u250C\u2510\u2514\u2518\s]+"
               ""))

(defn- prompt-line?
  "Returns true if line matches a shell prompt pattern."
  [line]
  (let [cleaned (strip-box-border (str/trim line))]
    (some #(re-find % cleaned) prompt-patterns)))

(defn- question-dialog?
  "Returns true if line indicates a question dialog."
  [line]
  (str/includes? line "Enter to select"))

(defn- plan-approval-line?
  "Returns true if lines contain a plan approval selector.
   Looks for numbered options with selector pattern."
  [lines]
  (let [tail (take-last 15 lines)
        selector (some #(re-find #"\u276F\s+\d+\." %) tail)
        choices (count
                 (filter #(re-find #"^\s+\d+\.\s" %) tail))]
    (and selector (>= choices 2))))

(defn- detect-claude-running
  "Returns running result if Claude spinner/activity found."
  [tail]
  (when (or (some claude-running-line? tail)
            (some claude-file-activity-line? tail))
    {:status :running :waiting-reason nil}))

(defn- detect-claude-waiting
  "Returns waiting result if dialog/approval found."
  [tail]
  (when (or (some question-dialog? tail)
            (plan-approval-line? tail))
    {:status :waiting :waiting-reason "respond"}))

(defn- detect-claude-idle
  "Returns idle result if prompt is visible."
  [non-blank]
  (when (some prompt-line? (take-last 5 non-blank))
    {:status :idle :waiting-reason nil}))

(defn detect-claude-state
  "Detects Claude Code agent state from pane lines.
   Returns map with :status and :waiting-reason,
   or nil when detection is inconclusive."
  [lines]
  (let [non-blank (remove str/blank? lines)
        tail (take-last 30 non-blank)]
    (or (detect-claude-running tail)
        (detect-claude-waiting tail)
        (detect-claude-idle non-blank))))

;; --- Codex detection patterns ---

(defn- codex-running-line?
  "Returns true if line indicates Codex is running."
  [line]
  (and (str/includes? line "esc to interrupt")
       (str/includes? line "(")))

(defn- codex-prompt-line?
  "Returns true if line contains Codex prompt char."
  [line]
  (let [trimmed (str/trim line)]
    (and (str/starts-with? trimmed "\u203A")
         (not (re-find #"\u203A\s+\d+\." trimmed)))))

(defn- detect-codex-running
  "Returns running result if Codex activity found."
  [tail]
  (when (some codex-running-line? tail)
    {:status :running :waiting-reason nil}))

(defn- detect-codex-waiting
  "Returns waiting result if dialog/approval found."
  [tail]
  (when (or (some question-dialog? tail)
            (plan-approval-line? tail))
    {:status :waiting :waiting-reason "respond"}))

(defn- detect-codex-idle
  "Returns idle result if Codex prompt is visible."
  [non-blank]
  (when (some codex-prompt-line? (take-last 5 non-blank))
    {:status :idle :waiting-reason nil}))

(defn detect-codex-state
  "Detects Codex agent state from captured pane lines.
   Returns map with :status and :waiting-reason,
   or nil when inconclusive."
  [lines]
  (let [non-blank (remove str/blank? lines)
        tail (take-last 30 non-blank)]
    (or (detect-codex-running tail)
        (detect-codex-waiting tail)
        (detect-codex-idle non-blank))))

;; --- tmux capture-pane interface ---

(defn capture-pane-content
  "Captures the visible content of a tmux pane.
   Returns a vector of lines, or nil if capture fails."
  [pane-id]
  (when (seq pane-id)
    (try
      (let [result (shell/sh
                    "tmux" "capture-pane" "-p"
                    "-t" pane-id)]
        (when (zero? (:exit result))
          (str/split-lines (:out result))))
      (catch Exception _ nil))))

(defn detect-agent-state
  "Detects the current state of an agent in a tmux pane.
   Returns map with :status and :waiting-reason,
   or nil if detection is not possible."
  [pane-id agent-type]
  (when-let [lines (capture-pane-content pane-id)]
    (case agent-type
      :claude-code (detect-claude-state lines)
      :codex (detect-codex-state lines)
      nil)))

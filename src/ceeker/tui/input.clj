(ns ceeker.tui.input
  "Keyboard input handling for TUI using JLine3."
  (:import [org.jline.terminal TerminalBuilder]
           [org.jline.utils NonBlockingReader]))

(defn create-terminal
  "Creates a JLine3 terminal in raw mode."
  []
  (-> (TerminalBuilder/builder)
      (.system true)
      (.jansi false)
      (.build)))

(defn read-key
  "Reads a single key from terminal. Returns keyword for special keys.
   Blocks until a key is available or timeout (ms) expires.
   Returns nil on timeout."
  [^org.jline.terminal.Terminal terminal timeout-ms]
  (let [^NonBlockingReader reader (.reader terminal)
        ch (.read reader (long timeout-ms))]
    (cond
      (= ch -1) nil
      (= ch -2) nil

      ;; ESC sequence
      (= ch 27)
      (let [ch2 (.read reader 50)]
        (if (= ch2 91) ;; [
          (let [ch3 (.read reader 50)]
            (case ch3
              65 :up
              66 :down
              67 :right
              68 :left
              nil))
          nil))

      ;; Enter
      (or (= ch 13) (= ch 10))
      :enter

      ;; Regular character
      :else
      (char ch))))

(defn close-terminal
  "Closes the JLine3 terminal."
  [^org.jline.terminal.Terminal terminal]
  (.close terminal))

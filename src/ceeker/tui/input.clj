(ns ceeker.tui.input
  "Keyboard input handling for TUI using JLine3."
  (:import [org.jline.terminal TerminalBuilder]
           [org.jline.utils NonBlockingReader]))

(defn create-terminal
  "Creates a JLine3 terminal and enters raw mode."
  []
  (let [terminal (-> (TerminalBuilder/builder)
                     (.system true)
                     (.jansi false)
                     (.build))]
    (.enterRawMode terminal)
    terminal))

(defn- read-escape-seq
  "Reads an escape sequence from reader. Returns keyword or nil."
  [^NonBlockingReader reader]
  (let [ch2 (.read reader 50)]
    (when (= ch2 91)
      (case (.read reader 50)
        65 :up
        66 :down
        67 :right
        68 :left
        nil))))

(defn read-key
  "Reads a single key from terminal.
   Blocks until a key is available or timeout (ms) expires.
   Returns nil on timeout."
  [^org.jline.terminal.Terminal terminal timeout-ms]
  (let [^NonBlockingReader reader (.reader terminal)
        ch (.read reader (long timeout-ms))]
    (cond
      (or (= ch -1) (= ch -2)) nil
      (= ch 27) (read-escape-seq reader)
      (or (= ch 13) (= ch 10)) :enter
      :else (char ch))))

(defn close-terminal
  "Closes the JLine3 terminal."
  [^org.jline.terminal.Terminal terminal]
  (.close terminal))

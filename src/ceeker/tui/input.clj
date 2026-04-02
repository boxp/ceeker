(ns ceeker.tui.input
  "Keyboard input handling for TUI using JLine3."
  (:import [org.jline.terminal TerminalBuilder]
           [org.jline.utils NonBlockingReader]))

(defn- elapsed-ms
  "Returns elapsed milliseconds since started-at."
  [started-at]
  (/ (- (System/nanoTime) started-at) 1000000.0))

(defn build-terminal
  "Builds the system terminal."
  []
  (-> (TerminalBuilder/builder)
      (.system true)
      (.jansi false)
      (.build)))

(defn enter-raw-mode!
  "Puts terminal into raw mode."
  [^org.jline.terminal.Terminal terminal]
  (.enterRawMode terminal)
  terminal)

(defn create-terminal-profile
  "Creates a terminal and returns step timings."
  []
  (let [started-at (System/nanoTime)
        build-started-at (System/nanoTime)
        terminal (build-terminal)
        build-ms (elapsed-ms build-started-at)
        raw-started-at (System/nanoTime)]
    (enter-raw-mode! terminal)
    {:terminal terminal
     :build-ms build-ms
     :enter-raw-mode-ms (elapsed-ms raw-started-at)
     :total-ms (elapsed-ms started-at)}))

(defn create-terminal
  "Creates a JLine3 terminal and enters raw mode."
  []
  (:terminal (create-terminal-profile)))

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
      (= ch 27) (or (read-escape-seq reader) :escape)
      (or (= ch 13) (= ch 10)) :enter
      :else (char ch))))

(defn close-terminal
  "Closes the JLine3 terminal."
  [^org.jline.terminal.Terminal terminal]
  (.close terminal))

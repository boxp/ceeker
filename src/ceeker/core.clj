(ns ceeker.core
  "Entry point for ceeker CLI."
  (:require [ceeker.hook.handler :as hook]
            [ceeker.tui.app :as tui]
            [clojure.string :as str]
            [clojure.tools.cli :as cli])
  (:gen-class))

(def cli-options
  [["-h" "--help" "Show help"]])

(defn- usage
  "Returns usage string."
  [summary]
  (str/join
   \newline
   ["ceeker - AI Coding Agent Session Monitor"
    ""
    "Usage:"
    "  ceeker              Start the TUI"
    "  ceeker hook <agent> <event> [<payload>]"
    "                      Handle a hook event"
    "                      agent: claude | codex"
    "                      event: Notification | Stop | etc."
    "                      payload: JSON string (optional, falls back to STDIN)"
    ""
    "Options:"
    summary]))

(defn- read-stdin
  "Reads all stdin input. Blocks until EOF."
  []
  (try
    (slurp System/in)
    (catch Exception _
      "")))

(defn payload-from-cli
  "Returns the optional JSON payload provided as CLI arguments after agent/event." 
  [args]
  (let [payload-args (drop 2 args)
        joined (when (seq payload-args)
                 (str/join " " payload-args))]
    (when (and joined (seq (str/trim joined)))
      joined)))

(defn- handle-hook-command
  "Handles the 'hook' subcommand."
  [args]
  (let [agent-type (first args)
        event-type (second args)]
    (when (or (nil? agent-type) (nil? event-type))
      (binding [*out* *err*]
        (println "Usage: ceeker hook <agent> <event>")
        (println "  agent: claude | codex")
        (println "  event: Notification | Stop | etc."))
      (System/exit 1))
    (let [payload-arg (payload-from-cli args)
          stdin (if payload-arg
                  payload-arg
                  (read-stdin))
          result (hook/handle-hook! agent-type event-type stdin)]
      (binding [*out* *err*]
        (println (str "ceeker: recorded "
                      (:agent-type result) " "
                      event-type " for "
                      (:session-id result)))))))

(defn -main
  "Main entry point."
  [& args]
  (let [{:keys [options arguments summary errors]}
        (cli/parse-opts args cli-options :in-order true)]
    (cond
      errors
      (do
        (binding [*out* *err*]
          (doseq [e errors]
            (println e)))
        (System/exit 1))

      (:help options)
      (do
        (println (usage summary))
        (System/exit 0))

      (= "hook" (first arguments))
      (handle-hook-command (rest arguments))

      :else
      (tui/start-tui!))))

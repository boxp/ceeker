(ns ceeker.core
  "Entry point for ceeker CLI."
  (:require [ceeker.hook.handler :as hook]
            [ceeker.session-list :as session-list]
            [ceeker.tui.app :as tui]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli])
  (:gen-class))

(def version
  (or (some-> (io/resource "CEEKER_VERSION") slurp str/trim)
      "dev"))

(def cli-options
  [["-h" "--help" "Show help"]
   ["-V" "--version" "Show version"]
   [nil "--view MODE" "Startup view: auto | table | card"
    :default :auto
    :parse-fn keyword
    :validate [#{:auto :table :card}
               "Must be one of: auto, table, card"]]
   [nil "--list-sessions"
    "Print the current session list as JSON and exit"]
   [nil "--exit-on-jump" "Exit after a successful jump"]
   [nil "--startup-profile"
    "Log startup profiling to stderr"]])

(defn- usage
  "Returns usage string."
  [summary]
  (str/join
   \newline
   ["ceeker - AI Coding Agent Session Monitor"
    ""
    "Usage:"
    "  ceeker              Start the TUI"
    "  ceeker --list-sessions"
    "                      Print sessions as JSON"
    "  ceeker hook <agent> <event> [<payload>]"
    "                      Handle a hook event"
    "  ceeker hook <agent> <json-payload>"
    "                      Handle a Codex notify event"
    "                      agent: claude | codex | pi"
    ""
    "Options:"
    summary]))

(defn- read-stdin
  "Reads all stdin input. Blocks until EOF."
  []
  (slurp System/in))

(defn payload-from-cli
  "Returns the optional JSON payload provided as CLI arguments after agent/event."
  [args]
  (let [payload-args (drop 2 args)
        joined (when (seq payload-args)
                 (str/join " " payload-args))]
    (when (and joined (seq (str/trim joined)))
      joined)))

(defn- json-string?
  "Returns true if s looks like a JSON object string."
  [s]
  (and (string? s)
       (str/starts-with? (str/trim s) "{")))

(defn- resolve-hook-args
  "Resolves event-type and payload from CLI args."
  [args raw-second]
  (let [json-arg? (json-string? raw-second)]
    {:event-type (when-not json-arg? raw-second)
     :payload (or (if json-arg?
                    raw-second
                    (payload-from-cli args))
                  (read-stdin))}))

(defn- handle-hook-command
  "Handles the 'hook' subcommand."
  [args]
  (let [agent-type (first args)
        raw-second (second args)]
    (if (or (nil? agent-type) (nil? raw-second))
      (do
        (binding [*out* *err*]
          (println "Usage: ceeker hook <agent> <event>")
          (println "  agent: claude | codex | pi"))
        1)
      (let [{:keys [event-type payload]}
            (resolve-hook-args args raw-second)
            result (hook/handle-hook!
                    agent-type event-type payload)]
        (binding [*out* *err*]
          (println (str "ceeker: recorded "
                        (:agent-type result) " "
                        (or event-type "notify")
                        " for pane "
                        (:pane-id result))))
        0))))

(defn- print-errors!
  "Prints CLI errors to stderr."
  [errors]
  (binding [*out* *err*]
    (doseq [e errors] (println e))))

(defn- tui-opts
  "Builds TUI opts from parsed CLI options."
  [options]
  {:exit-on-jump (:exit-on-jump options)
   :startup-profile (:startup-profile options)
   :initial-display-mode (:view options)})

(defn list-sessions-for-cli
  "Returns the current session list for CLI output."
  [state-dir]
  (map session-list/session->external
       (session-list/refresh-and-read-session-list state-dir)))

(defn- print-session-list!
  "Prints the current session list as JSON."
  [state-dir]
  (println (json/generate-string
            (list-sessions-for-cli state-dir))))

(defn- run-parsed-cli!
  "Executes the parsed CLI command and returns an exit code."
  [{:keys [options arguments summary errors]}]
  (cond
    errors
    (do (print-errors! errors)
        1)
    (:version options)
    (do (println (str "ceeker " version))
        0)
    (:help options)
    (do (println (usage summary))
        0)
    (:list-sessions options)
    (do (print-session-list! nil)
        0)
    (= "hook" (first arguments))
    (handle-hook-command (rest arguments))
    :else
    (do (tui/start-tui!
         nil
         (tui-opts options))
        0)))

;; musl? is evaluated at AOT compile time (macro expansion time).
;; For musl static builds, set CEEKER_STATIC=true CEEKER_MUSL=true
;; when running `clojure -T:build uber` to produce a musl-compatible binary.
;; This follows the same pattern used by clj-kondo and jet.
(def ^:private musl?
  (and (= "true" (System/getenv "CEEKER_STATIC"))
       (= "true" (System/getenv "CEEKER_MUSL"))))

(defmacro ^:private run [expr]
  (if musl?
    `(let [r# (async/<!!
               (async/thread
                 (try [nil ~expr]
                      (catch Throwable t# [t# nil]))))]
       (when-let [t# (first r#)]
         (throw t#))
       (second r#))
    `(do ~expr)))

(defn -main
  "Main entry point."
  [& args]
  (run
   (let [{:keys [options arguments summary errors]}
         (cli/parse-opts args cli-options :in-order true)]
     (System/exit
      (run-parsed-cli!
       {:options options
        :arguments arguments
        :summary summary
        :errors errors})))))

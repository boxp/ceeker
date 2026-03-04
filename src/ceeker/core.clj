(ns ceeker.core
  "Entry point for ceeker CLI."
  (:require [ceeker.hook.handler :as hook]
            [ceeker.tui.app :as tui]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli])
  (:gen-class))

(def version
  (or (some-> (io/resource "CEEKER_VERSION") slurp str/trim)
      "dev"))

(def cli-options
  [["-h" "--help" "Show help"]
   ["-V" "--version" "Show version"]])

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
    "  ceeker hook <agent> <json-payload>"
    "                      Handle a Codex notify event"
    "                      agent: claude | codex"
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
    (when (or (nil? agent-type) (nil? raw-second))
      (binding [*out* *err*]
        (println "Usage: ceeker hook <agent> <event>")
        (println "  agent: claude | codex"))
      (System/exit 1))
    (let [{:keys [event-type payload]}
          (resolve-hook-args args raw-second)
          result (hook/handle-hook!
                  agent-type event-type payload)]
      (binding [*out* *err*]
        (println (str "ceeker: recorded "
                      (:agent-type result) " "
                      (or event-type "notify")
                      " for "
                      (:session-id result)))))))

(defn- print-errors!
  "Prints CLI errors to stderr and exits."
  [errors]
  (binding [*out* *err*]
    (doseq [e errors] (println e)))
  (System/exit 1))

;; musl? is evaluated at AOT compile time (macro expansion time).
;; For musl static builds, set CEEKER_STATIC=true CEEKER_MUSL=true
;; when running `clojure -T:build uber` to produce a musl-compatible binary.
;; This follows the same pattern used by clj-kondo and jet.
(def ^:private musl?
  (and (= "true" (System/getenv "CEEKER_STATIC"))
       (= "true" (System/getenv "CEEKER_MUSL"))))

(defmacro ^:private run [expr]
  (if musl?
    `(let [v# (volatile! nil)
           ex# (volatile! nil)
           f# (fn []
                 (try
                   (vreset! v# ~expr)
                   (catch Throwable t#
                     (vreset! ex# t#))))]
       (doto (Thread. nil f# "ceeker-main")
         (.start)
         (.join))
       (when-let [t# @ex#]
         (throw t#))
       @v#)
    `(do ~expr)))

(defn -main
  "Main entry point."
  [& args]
  (run
   (let [{:keys [options arguments summary errors]}
         (cli/parse-opts args cli-options :in-order true)]
     (cond
       errors
       (print-errors! errors)
       (:version options)
       (do (println (str "ceeker " version))
           (System/exit 0))
       (:help options)
       (do (println (usage summary))
           (System/exit 0))
       (= "hook" (first arguments))
       (handle-hook-command (rest arguments))
       :else
       (do (tui/start-tui!)
           (System/exit 0))))))

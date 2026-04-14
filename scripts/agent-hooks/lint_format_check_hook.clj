#!/usr/bin/env bb

(ns lint-format-check-hook
  (:require [cheshire.core :as json]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def edit-tools #{"Write" "Edit" "MultiEdit"})

(def validation-commands
  #{"make lint"
    "make format-check"
    "make ci"
    "clojure -M:lint"
    "clojure -M:format-check"
    "clojure -M:test"})

(def mutating-bash-patterns
  [#"\bapply_patch\b"
   #"\bgit\s+apply\b"
   #"\bpatch\b"
   #"\bsed\s+-i\b"
   #"\bperl\s+-pi\b"
   #"\bmv\b"
   #"\bcp\b"
   #"\brm\b"
   #"\bmkdir\b"
   #"\btouch\b"
   #"\btee\b"
   #"\bchmod\b"
   #"\bchown\b"
   #">>"
   #"(^|[^>])>([^>]|$)"])

(defn read-payload []
  (let [raw (str/trim (slurp *in*))]
    (if (str/blank? raw)
      {}
      (json/parse-string raw true))))

(defn normalize-command [command]
  (->> (str/split (str/trim command) #"\s+")
       (remove str/blank?)
       (str/join " ")))

(defn should-run [payload]
  (if (not= "PostToolUse" (:hook_event_name payload))
    [false nil]
    (let [tool-name (str (:tool_name payload))
          command (str (get-in payload [:tool_input :command] ""))
          normalized-command (normalize-command command)]
      (cond
        (contains? edit-tools tool-name)
        [true tool-name]

        (not= "Bash" tool-name)
        [false nil]

        (contains? validation-commands normalized-command)
        [false nil]

        (some #(re-find % command) mutating-bash-patterns)
        [true "Bash command"]

        :else
        [false nil]))))

(defn summarize-output [output]
  (let [trimmed (str/trim output)]
    (if (str/blank? trimmed)
      ""
      (let [summary (->> (str/split-lines trimmed)
                         (take 20)
                         (str/join "\n"))]
        (if (> (count summary) 2000)
          (str (subs summary 0 2000) "\n...")
          summary)))))

(defn run-target [target]
  (let [{:keys [exit out err]} (sh/sh "make" target)
        combined (->> [out err]
                      (map str/trim)
                      (remove str/blank?)
                      (str/join "\n"))]
    [(zero? exit) combined]))

(defn emit-message [message]
  (println (json/generate-string {:systemMessage message})))

(defn failure-message [target trigger output]
  (let [summary (summarize-output output)
        message (str target " failed after " trigger ".")]
    (if (str/blank? summary)
      message
      (str message "\n" summary))))

(defn -main []
  (let [payload (read-payload)
        [should-validate trigger] (should-run payload)]
    (when should-validate
      (let [[format-ok format-output] (run-target "format-check")]
        (if-not format-ok
          (emit-message (failure-message "format-check" trigger format-output))
          (let [[lint-ok lint-output] (run-target "lint")]
            (if lint-ok
              (emit-message (str "format-check and lint passed after " trigger "."))
              (emit-message (failure-message "lint" trigger lint-output))))))))
  0)

(System/exit (-main))

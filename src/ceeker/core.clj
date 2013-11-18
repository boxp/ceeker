(ns ceeker.core
  (:use [ceeker.options]
        [ceeker.data])
  (:gen-class))

(defn -main
  [& args]
  (let [as (vec args)]
    (do
      (cond 
        (contains? as "--log")
        (reset! logging-mode true))
      (case (as 0)
        "new" (ceeker-new (as 1))
        "run" (ceeker-run)
        "build" (ceeker-build)
        "auto" (ceeker-auto)
        nil))))

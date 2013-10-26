(ns ceeker.core
  (:use [ceeker.options])
  (:gen-class))

(defn -main
  [& args]
  (let [as (vec args)]
    (do
      (case (as 0)
        "new" (ceeker-new (as 1))
        "run" (ceeker-run)
        "build" (ceeker-build)
        "auto" (ceeker-auto)
        nil))))

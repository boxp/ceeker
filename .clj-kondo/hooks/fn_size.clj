(ns hooks.fn-size
  "Custom clj-kondo hook to enforce function size limits.
   Functions exceeding 20 lines will trigger a warning."
  (:require [clj-kondo.hooks-api :as api]))

(def ^:private max-fn-lines 20)

(defn defn-hook
  [{:keys [node]}]
  (let [children (:children node)
        after-name (drop 2 children)
        after-doc (if (api/string-node? (first after-name))
                    (rest after-name)
                    after-name)
        after-attr (if (api/map-node? (first after-doc))
                     (rest after-doc)
                     after-doc)
        first-form (first after-attr)
        single-arity? (api/vector-node? first-form)
        body-nodes
        (if single-arity?
          (rest after-attr)
          (when (api/list-node? first-form)
            (let [arities after-attr
                  largest (apply
                           max-key
                           (fn [arity]
                             (let [body (rest (:children arity))]
                               (if (seq body)
                                 (- (or (:end-row (meta (last body))) 0)
                                    (or (:row (meta (first body))) 0))
                                 0)))
                           arities)]
              (rest (:children largest)))))
        fn-name (second children)]
    (when (seq body-nodes)
      (let [start-row (:row (meta (first body-nodes)))
            end-row (:end-row (meta (last body-nodes)))
            line-count (when (and start-row end-row)
                         (inc (- end-row start-row)))]
        (when (and line-count (> line-count max-fn-lines))
          (api/reg-finding!
           (assoc (meta fn-name)
                  :message
                  (format "Function body exceeds %d lines (%d lines)."
                          max-fn-lines line-count)
                  :type :fn-size-limit))))))
  {:node node})

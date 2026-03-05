(ns ceeker.tui.filter
  "Session filtering logic for TUI."
  (:require [clojure.string :as str]))

(def empty-filter
  "Default filter state with no active filters."
  {:agent-filter nil
   :status-filter nil
   :search-query nil})

(def ^:private agent-cycle
  "Cycle order for agent filter toggle."
  [nil :claude-code :codex])

(def ^:private status-cycle
  "Cycle order for status filter toggle."
  [nil :running :completed :error :waiting :idle])

(defn- next-in-cycle
  "Returns the next value in a cycle after current."
  [cycle-vec current]
  (let [idx (.indexOf cycle-vec current)]
    (nth cycle-vec (mod (inc idx) (count cycle-vec)))))

(defn toggle-agent-filter
  "Cycles agent filter to the next value."
  [filter-state]
  (update filter-state :agent-filter
          (partial next-in-cycle agent-cycle)))

(defn toggle-status-filter
  "Cycles status filter to the next value."
  [filter-state]
  (update filter-state :status-filter
          (partial next-in-cycle status-cycle)))

(defn set-search-query
  "Sets the search query string."
  [filter-state query]
  (assoc filter-state :search-query
         (when (and query (seq (str/trim query)))
           (str/trim query))))

(defn clear-filters
  "Resets all filters to default."
  [_filter-state]
  empty-filter)

(defn- match-agent?
  "Returns true if session matches agent filter."
  [agent-filter session]
  (or (nil? agent-filter)
      (= agent-filter (:agent-type session))))

(defn- match-status?
  "Returns true if session matches status filter."
  [status-filter session]
  (or (nil? status-filter)
      (= status-filter (:agent-status session))))

(defn- match-search?
  "Returns true if session matches search query."
  [query session]
  (or (nil? query)
      (let [q (str/lower-case query)]
        (or (str/includes?
             (str/lower-case (or (:session-id session) ""))
             q)
            (str/includes?
             (str/lower-case (or (:cwd session) ""))
             q)))))

(defn apply-filters
  "Filters sessions based on the current filter state."
  [filter-state sessions]
  (let [{:keys [agent-filter status-filter
                search-query]} filter-state]
    (filter
     (fn [s]
       (and (match-agent? agent-filter s)
            (match-status? status-filter s)
            (match-search? search-query s)))
     sessions)))

(defn active?
  "Returns true if any filter is active."
  [filter-state]
  (or (some? (:agent-filter filter-state))
      (some? (:status-filter filter-state))
      (some? (:search-query filter-state))))

(defn describe-filters
  "Returns a human-readable description of active filters."
  [filter-state]
  (let [parts (cond-> []
                (:agent-filter filter-state)
                (conj (str "agent:"
                           (name (:agent-filter filter-state))))
                (:status-filter filter-state)
                (conj (str "status:"
                           (name (:status-filter filter-state))))
                (:search-query filter-state)
                (conj (str "search:\""
                           (:search-query filter-state) "\"")))]
    (when (seq parts)
      (str/join " | " parts))))

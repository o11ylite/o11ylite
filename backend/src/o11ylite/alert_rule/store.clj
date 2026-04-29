;; ---------------------------------------------------------
;; o11ylite.alert-rule.store
;;
;; SQLite CRUD operations for the alert_rules table.
;; Query payloads are stored as nippy-frozen BLOBs, thawed on read.
;; ---------------------------------------------------------

(ns o11ylite.alert-rule.store
  (:require
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [taoensso.nippy :as nippy]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -now-ms
  []
  (System/currentTimeMillis))

(defn- -parse-query
  "Thaw a nippy-frozen query BLOB back into Clojure data."
  [^bytes query-bytes]
  (nippy/thaw query-bytes))

(defn- -serialize-query
  "Freeze a query map into a nippy byte array for BLOB storage."
  [query-map]
  (nippy/freeze query-map))

(defn- -parse-row
  "Parse a raw DB row, thawing the query BLOB and converting enabled to boolean."
  [row]
  (when row
    (-> row
        (update :query -parse-query)
        (update :enabled pos?))))

;; ---------------------------------------------------------
;; Public API

(defn create!
  "Insert a new alert rule.
   id should be a string representation of the snowflake ID."
  [sqlite id {:keys [name description query_mode query
                     eval_window_ms eval_interval_ms alert_on alert_target]}]
  (let [now (-now-ms)]
    (jdbc/execute!
      sqlite
      ["INSERT INTO alert_rules
        (id, name, description, enabled, query_mode, query,
         eval_window_ms, eval_interval_ms, alert_on, alert_target,
         state, created_at, updated_at)
        VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, 'ok', ?, ?)"
       id name description query_mode (-serialize-query query)
       eval_window_ms eval_interval_ms (or alert_on "result") alert_target now now])
    id))

(defn update!
  "Update an alert rule by ID.
   Only updates the provided fields."
  [sqlite id {:keys [name description enabled query_mode query
                     eval_window_ms eval_interval_ms alert_on alert_target]}]
  (let [now (-now-ms)]
    (jdbc/execute!
      sqlite
      ["UPDATE alert_rules
        SET name = ?, description = ?, enabled = ?,
            query_mode = ?, query = ?,
            eval_window_ms = ?, eval_interval_ms = ?,
            alert_on = ?, alert_target = ?,
            updated_at = ?
        WHERE id = ?"
       name description (if enabled 1 0) query_mode (-serialize-query query)
       eval_window_ms eval_interval_ms (or alert_on "result") alert_target now id])))

(defn delete!
  "Delete an alert rule by ID."
  [sqlite id]
  (jdbc/execute!
    sqlite
    ["DELETE FROM alert_rules WHERE id = ?" id]))

(defn get-by-id
  "Fetch a single alert rule by ID. Returns nil if not found."
  [sqlite id]
  (-> (jdbc/execute-one!
        sqlite
        ["SELECT * FROM alert_rules WHERE id = ?" id]
        {:builder-fn rs/as-unqualified-lower-maps})
      -parse-row))

(defn list-all
  "Fetch all alert rules, ordered by created_at desc."
  [sqlite]
  (->> (jdbc/execute!
         sqlite
         ["SELECT * FROM alert_rules ORDER BY created_at DESC"]
         {:builder-fn rs/as-unqualified-lower-maps})
       (mapv -parse-row)))

(defn get-enabled-due
  "Fetch enabled rules that are due for evaluation.
   A rule is due if last_eval_at is NULL or now - last_eval_at >= eval_interval_ms."
  [sqlite]
  (let [now (-now-ms)]
    (->> (jdbc/execute!
           sqlite
           ["SELECT * FROM alert_rules
             WHERE enabled = 1
               AND (last_eval_at IS NULL OR ? - last_eval_at >= eval_interval_ms)"
            now]
           {:builder-fn rs/as-unqualified-lower-maps})
         (mapv -parse-row))))

(defn update-eval-result!
  "Update evaluation result for a rule.
   Sets state, last_eval_at, last_eval_error, and state_changed_at
   (only if state actually changed)."
  [sqlite id new-state error-msg prev-state]
  (let [now (-now-ms)
        state-changed? (not= new-state prev-state)]
    (if state-changed?
      (jdbc/execute!
        sqlite
        ["UPDATE alert_rules
          SET state = ?, last_eval_at = ?, last_eval_error = ?,
              state_changed_at = ?, updated_at = ?
          WHERE id = ?"
         (clojure.core/name new-state) now error-msg now now id])
      (jdbc/execute!
        sqlite
        ["UPDATE alert_rules
          SET state = ?, last_eval_at = ?, last_eval_error = ?,
              updated_at = ?
          WHERE id = ?"
         (clojure.core/name new-state) now error-msg now id]))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])
  (def sqlite (:db/sqlite system))

  ;; Create a rule
  (create! sqlite "test-rule-1"
           {:name "High error rate"
            :description "Alert when error count > 100"
            :query_mode "events"
            :query {:filter {:field "error" :op "=" :value true}
                    :aggregations [{:id "A" :field "*" :function "count"}]
                    :having {:ref "A" :op ">" :value 100}
                    :visualization {:type "table"}}
            :eval_window_ms 300000
            :eval_interval_ms 60000})

  ;; List all
  (list-all sqlite)

  ;; Get by ID
  (get-by-id sqlite "test-rule-1")

  ;; Get due rules
  (get-enabled-due sqlite)

  ;; Delete
  (delete! sqlite "test-rule-1")

  (ig/halt-key! :db/sqlite sqlite)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

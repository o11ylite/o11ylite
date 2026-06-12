;; ---------------------------------------------------------
;; o11ylite.alert-rule.instance-store
;;
;; SQLite CRUD for the alert_instances table.
;;
;; One row per (rule_id, fingerprint). Labels and last_value are stored
;; as JSON text. Timestamps are epoch milliseconds. The empty fingerprint
;; "" is the degenerate single-instance case for rules without group-by.
;; ---------------------------------------------------------

(ns o11ylite.alert-rule.instance-store
  (:require
    [clojure.string :as str]
    [jsonista.core :as json]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -now-ms
  []
  (System/currentTimeMillis))

(defn- -parse-row
  "Thaw JSON columns on a raw instance row."
  [row]
  (when row
    (cond-> row
      (:labels row) (update :labels #(json/read-value % json/keyword-keys-object-mapper))
      (:last_value row) (update :last_value #(when % (json/read-value % json/keyword-keys-object-mapper))))))

;; ---------------------------------------------------------
;; Public API

(defn list-by-rule
  "All instances for a rule, parsed. Ordered by state then first_seen."
  [sqlite rule-id]
  (->> (jdbc/execute!
         sqlite
         ["SELECT * FROM alert_instances WHERE rule_id = ? ORDER BY state, first_seen" rule-id]
         {:builder-fn rs/as-unqualified-lower-maps})
       (mapv -parse-row)))

(defn list-by-rule-state
  "Instances for a rule in a given state."
  [sqlite rule-id state]
  (->> (jdbc/execute!
         sqlite
         ["SELECT * FROM alert_instances WHERE rule_id = ? AND state = ? ORDER BY first_seen"
          rule-id (name state)]
         {:builder-fn rs/as-unqualified-lower-maps})
       (mapv -parse-row)))

(defn upsert!
  "Insert or update an instance. On conflict (rule_id, fingerprint),
   updates the mutable columns. first_seen is preserved across updates."
  [sqlite {:keys [rule_id fingerprint labels state first_seen last_seen
                  started_at resolved_at last_value]}]
  (jdbc/execute!
    sqlite
    ["INSERT INTO alert_instances
      (rule_id, fingerprint, labels, state, first_seen, last_seen,
       started_at, resolved_at, last_value)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (rule_id, fingerprint) DO UPDATE SET
        labels = excluded.labels,
        state = excluded.state,
        last_seen = excluded.last_seen,
        started_at = excluded.started_at,
        resolved_at = excluded.resolved_at,
        last_value = excluded.last_value"
     rule_id fingerprint
     (json/write-value-as-string (or labels {}))
     (name state)
     (or first_seen (-now-ms))
     (or last_seen (-now-ms))
     started_at resolved_at
     (when last_value (json/write-value-as-string last_value))]))

(defn delete!
  "Delete a single instance by (rule_id, fingerprint)."
  [sqlite rule-id fingerprint]
  (jdbc/execute!
    sqlite
    ["DELETE FROM alert_instances WHERE rule_id = ? AND fingerprint = ?"
     rule-id fingerprint]))

(defn delete-fingerprints!
  "Delete a set of instances on a rule by fingerprint. No-op on empty."
  [sqlite rule-id fingerprints]
  (when (seq fingerprints)
    (let [placeholders (str/join ", " (repeat (count fingerprints) "?"))]
      (jdbc/execute!
        sqlite
        (into [(str "DELETE FROM alert_instances WHERE rule_id = ? AND fingerprint IN ("
                    placeholders ")")
               rule-id]
              fingerprints)))))

(defn count-by-rule
  "Number of instances for a rule (any state)."
  [sqlite rule-id]
  (-> (jdbc/execute-one!
        sqlite
        ["SELECT COUNT(*) AS n FROM alert_instances WHERE rule_id = ?" rule-id]
        {:builder-fn rs/as-unqualified-lower-maps})
      :n))

(defn delete-all-for-rule!
  "Delete every instance for a rule. Used when a rule update changes its
   mode, so stale instances from the old semantics don't linger."
  [sqlite rule-id]
  (jdbc/execute! sqlite ["DELETE FROM alert_instances WHERE rule_id = ?" rule-id]))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])
  (def sqlite (:db/sqlite system))

  (upsert! sqlite {:rule_id "r1" :fingerprint "" :labels {} :state :firing
                   :first_seen 1 :last_seen 1 :started_at 1})
  (list-by-rule sqlite "r1")
  (delete! sqlite "r1" "")

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

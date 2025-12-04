;; ---------------------------------------------------------
;; o11ylite.ducklake.init
;;
;; DuckLake database initialization
;; Creates the events table partitioned by day
;; ---------------------------------------------------------

(ns o11ylite.ducklake.init
  (:require
   [com.brunobonacci.mulog :as mulog]
   [next.jdbc :as jdbc]
   [next.jdbc.date-time]))

;; ---------------------------------------------------------
;; Schema

(def ^:private create-events-table-sql
  "CREATE TABLE IF NOT EXISTS ducklake.events (
     -- Core identity
     service VARCHAR NOT NULL,
     timestamp TIMESTAMP_NS NOT NULL,

     -- Trace context
     trace_id VARCHAR,
     span_id VARCHAR,
     parent_span_id VARCHAR,

     -- Event identity
     name VARCHAR,

     -- Signal type: 'span', 'span_event', 'log'
     \"meta.signal_type\" VARCHAR NOT NULL,

     -- Span-specific fields (NULL for logs)
     \"span.kind\" VARCHAR,
     \"span.status_code\" VARCHAR,
     \"span.status_message\" VARCHAR,
     \"span.start_time\" TIMESTAMP_NS,
     \"span.end_time\" TIMESTAMP_NS,
     \"span.duration_ns\" BIGINT,

     -- Log-specific fields (NULL for spans)
     \"log.severity\" VARCHAR,
     \"log.body\" VARCHAR,

     -- Instrumentation scope
     \"scope.name\" VARCHAR,
     \"scope.version\" VARCHAR,

     -- Metadata
     \"meta.observed_time\" TIMESTAMP_NS NOT NULL
   )")

(def ^:private set-events-partition-sql
  "ALTER TABLE ducklake.events SET PARTITIONED BY (day(timestamp))")

;; ---------------------------------------------------------
;; Public API

(defn init-ducklake!
  "Initialize DuckLake database schema.
   Creates the events table if it doesn't exist, partitioned by day."
  [duckdb-ds]
  (mulog/log ::init-ducklake-starting)
  (jdbc/execute! duckdb-ds [create-events-table-sql])
  (jdbc/execute! duckdb-ds [set-events-partition-sql])
  (mulog/log ::init-ducklake-completed))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Test DuckLake initialization
  (require '[integrant.core :as ig]
           '[next.jdbc :as jdbc]
           '[next.jdbc.date-time])

  ;; Start DuckDB pool
  (def ds
    (ig/init-key :db/duckdb {:data-path "./.tmp"}))

  ;; Initialize schema
  (init-ducklake! ds)

  ;; Check table exists
  (jdbc/execute! ds ["SHOW TABLES"])

  ;; Describe table
  (jdbc/execute! ds ["DESCRIBE events"])

  ;; Insert test event
  (jdbc/execute! ds
                 ["INSERT INTO ducklake.events (service, timestamp, \"meta.signal_type\", \"meta.observed_time\")
                   VALUES (?, ?, ?, ?)"
                  "test-service"
                  (java.time.Instant/now)
                  "log"
                  (java.time.Instant/now)])

  ;; Query events
  (jdbc/execute! ds ["SELECT * FROM ducklake.events"])

  ;; Cleanup
  (ig/halt-key! :db/duckdb ds)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

;; ---------------------------------------------------------
;; o11ylite.store.init
;;
;; DuckLake database initialization
;; Creates the events table partitioned by day
;; ---------------------------------------------------------

(ns o11ylite.store.init
  (:require
    [com.brunobonacci.mulog :as mulog]
    [next.jdbc :as jdbc]
    [next.jdbc.date-time]))

;; ---------------------------------------------------------
;; Schema

(def ^:private create-events-table-sql
  "CREATE TABLE IF NOT EXISTS o11ylite.events (
     -- Snowflake-style ID for pagination (generated at ingest time)
     id BIGINT NOT NULL,

     -- Core identity
     service VARCHAR NOT NULL,
     timestamp TIMESTAMP_NS NOT NULL,

     -- Trace context
     trace_id VARCHAR,
     span_id VARCHAR,
     parent_span_id VARCHAR,

     -- Event identity
     name VARCHAR,

     -- Derived fields
     error BOOLEAN NOT NULL,

     -- Signal type: 'span', 'span_event', 'log'
     \"meta.signal_type\" VARCHAR NOT NULL,

     -- Span-specific fields (NULL for logs)
     \"span.kind\" VARCHAR,
     \"span.status_code\" VARCHAR,
     \"span.status_message\" VARCHAR,
     \"span.start_time\" TIMESTAMP_NS,
     \"span.end_time\" TIMESTAMP_NS,
     \"span.duration_ms\" FLOAT,

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
  "ALTER TABLE o11ylite.events SET PARTITIONED BY (year(timestamp), month(timestamp), day(timestamp), service)")

(def ^:private create-metrics-table-sql
  "CREATE TABLE IF NOT EXISTS o11ylite.metrics (
     -- Core identity
     name VARCHAR NOT NULL,
     service VARCHAR NOT NULL,
     timestamp TIMESTAMP NOT NULL,

     -- Gauge/Sum value (0 for histograms, ignored)
     value DOUBLE NOT NULL DEFAULT 0,

     -- Histogram columns (NULL for gauge/sum)
     \"hist.counts\" BIGINT[],
     \"hist.count\" BIGINT,
     \"hist.sum\" DOUBLE,
     \"hist.min\" DOUBLE,
     \"hist.max\" DOUBLE,

     -- Instrumentation scope
     \"scope.name\" VARCHAR,
     \"scope.version\" VARCHAR,

     -- Metadata
     \"meta.observed_time\" TIMESTAMP NOT NULL
   )")

(def ^:private set-metrics-partition-sql
  "ALTER TABLE o11ylite.metrics SET PARTITIONED BY (year(timestamp), month(timestamp), day(timestamp), name, service)")

;; ---------------------------------------------------------
;; Public API

(defn init-store!
  "Initialize DuckLake database schema.
   Creates the events and metrics tables if they don't exist, partitioned by day."
  [duckdb-ds]
  (mulog/log ::init-store-starting)
  (jdbc/execute! duckdb-ds [create-events-table-sql])
  (jdbc/execute! duckdb-ds [set-events-partition-sql])
  (jdbc/execute! duckdb-ds [create-metrics-table-sql])
  (jdbc/execute! duckdb-ds [set-metrics-partition-sql])
  (mulog/log ::init-store-completed))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Test DuckLake initialization
  (require '[next.jdbc :as jdbc]
           '[next.jdbc.date-time]
           '[integrant.repl.state :refer [system]])

  (def ds (:db/duckdb system))

  ;; Check table exists
  (jdbc/execute! ds ["SHOW TABLES"])

  ;; Describe table
  (jdbc/execute! ds ["DESCRIBE events"])

  ;; Insert test event
  (jdbc/execute! ds
                 ["INSERT INTO events (service, timestamp, \"meta.signal_type\", \"meta.observed_time\")
                   VALUES (?, ?, ?, ?)"
                  "test-service"
                  (java.time.Instant/now)
                  "log"
                  (java.time.Instant/now)])

  ;; Query events
  (jdbc/execute! ds ["SELECT * FROM o11ylite.events"])

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

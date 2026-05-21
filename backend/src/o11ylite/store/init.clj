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

(def ^:private set-events-sorted-sql
  "ALTER TABLE o11ylite.events SET SORTED BY (timestamp DESC)")

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

(defn- -set-metrics-partition-sql
  "Build the ALTER TABLE partition SQL using DuckLake's native bucket() transform.
   bucket(N, name) distributes rows into N buckets via Murmur3 hash on metric name."
  [num-buckets]
  (format "ALTER TABLE o11ylite.metrics SET PARTITIONED BY (year(timestamp), month(timestamp), day(timestamp), bucket(%d, name))"
          num-buckets))

(def ^:private set-metrics-sorted-sql
  "ALTER TABLE o11ylite.metrics SET SORTED BY (timestamp DESC)")

;; ---------------------------------------------------------
;; Public API

(defn init-store!
  "Initialize DuckLake database schema.
   Creates the events and metrics tables if they don't exist.
   Events are partitioned by day + service.
   Metrics are partitioned by day + bucket(N, name) where N comes from core config.

   Each table's DDL goes through its own writer pool to keep the writer
   contract (one connection per table) consistent. At startup no batchers
   are running yet, so there's no contention."
  [writer-events writer-metrics {:keys [metrics-partition-buckets]}]
  (mulog/log ::init-store-starting :o11ylite.store_init.metrics_partition_buckets metrics-partition-buckets)
  (jdbc/execute! writer-events [create-events-table-sql])
  (jdbc/execute! writer-events [set-events-partition-sql])
  (jdbc/execute! writer-events [set-events-sorted-sql])
  (jdbc/execute! writer-metrics [create-metrics-table-sql])
  (jdbc/execute! writer-metrics [(-set-metrics-partition-sql metrics-partition-buckets)])
  (jdbc/execute! writer-metrics [set-metrics-sorted-sql])
  (mulog/log ::init-store-completed))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Test DuckLake initialization
  (require '[next.jdbc :as jdbc]
           '[next.jdbc.date-time]
           '[integrant.repl.state :refer [system]])

  (def ds (:db/duckdb-reader system))

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

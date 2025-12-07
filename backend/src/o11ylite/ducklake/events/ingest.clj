;; ---------------------------------------------------------
;; o11ylite.ducklake.events.ingest
;;
;; Event ingestion: validation and storage for observability events
;; (spans, span-events, logs).
;;
;; Flow:
;;   gRPC handler
;;       │
;;       v
;;   ingest-events! (validate, extract fields with types, submit to batcher)
;;       │
;;       v
;;   batcher accumulates {:events [...] :fields {name {:type t} ...}}
;;       │
;;       v (periodic flush)
;;   persist-batch! (diff fields vs cache, ALTER TABLE, INSERT, refresh cache)
;; ---------------------------------------------------------

(ns o11ylite.ducklake.events.ingest
  (:require
   [clojure.core.async :as a]
   [com.brunobonacci.mulog :as mulog]
   [o11ylite.components.event-metadata :as event-metadata]
   [o11ylite.ducklake.schema :as schema]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -extract-fields
  "Extract all fields from a collection of events with inferred types.
   Returns a map of field-name -> {:type normalized-type}.

   When the same field appears with different types across events,
   the last occurrence wins (merge behavior)."
  [events]
  (->> events
       (mapcat (fn [event]
                 (map (fn [[k v]]
                        [(name k) {:type (schema/infer-type v)}])
                      event)))
       (into {})))

(defn- -validate-events
  "Validate events against the schema.
   Returns {:valid? true} or {:valid? false :errors [...]}

   TODO: Implement actual validation:
   - Required fields: service, timestamp, meta.signal_type, meta.observed_time
   - Type checking based on event-metadata cache"
  [_event-metadata _events]
  ;; TODO: Implement validation
  {:valid? true})

(defn- -compute-schema-diff
  "Compare batch fields against current event-metadata cache.
   Returns a map of new-field-name -> {:type ...} for fields not in cache.
   Returns nil if no new fields.

   The field types come from the batch's inferred types."
  [event-metadata fields]
  (let [known-fields (event-metadata/get-fields event-metadata)
        new-fields (reduce-kv (fn [acc field-name field-meta]
                                (if (contains? known-fields field-name)
                                  acc
                                  (assoc acc field-name field-meta)))
                              {}
                              fields)]
    (when (seq new-fields)
      new-fields)))

;; ---------------------------------------------------------
;; Public API

(defn ingest-events!
  "Ingest events into the observability store.

   Called by gRPC handlers to submit events. Validates events, extracts fields
   with inferred types, then submits events + fields to batcher. Blocks until
   the batch is flushed to storage.

   Arguments:
     event-metadata - The event metadata cache component (for validation)
     batcher        - The ingest batcher component
     events         - Collection of event maps to ingest

   Returns:
     true if all events were persisted successfully
     false if flush failed (caller should handle retry/logging)

   Note: Uses batcher's ingest channel directly to avoid cyclic dependency."
  [event-metadata batcher events]
  (let [validation (-validate-events event-metadata events)]
    (if-not (:valid? validation)
      (do
        (mulog/log ::validation-failed :errors (:errors validation))
        false)
      (let [fields (-extract-fields events)
            done (promise)]
        (a/>!! (:ingest-ch batcher) {:events events
                                     :fields fields
                                     :done done})
        @done))))

(defn persist-batch!
  "Persist a batch of events to DuckLake.

   Called by the ingest batcher during flush. Handles:
     1. Schema diff - compare batch fields against event-metadata cache
     2. Schema evolution - ALTER TABLE ADD COLUMN for any new fields
     3. Bulk insert - INSERT all events into the events table
     4. Cache refresh - update event-metadata if schema changed

   Arguments:
     duckdb         - DuckDB datasource
     event-metadata - Event metadata cache (for diff and refresh)
     events         - Collection of event maps to insert
     fields         - Map of field-name -> {:type ...} for all fields in batch

   Returns:
     true on success

   Throws:
     Exception on failure (batcher will catch and notify callers)

   Retry Strategy (TODO):
     If schema changed between diff and INSERT (concurrent batch added columns),
     the INSERT may fail. In this case:
     1. Refresh event-metadata cache
     2. Recompute diff
     3. Retry ALTER TABLE + INSERT
     This handles race conditions when multiple batches add columns concurrently."
  [duckdb event-metadata events fields]
  (let [new-fields (-compute-schema-diff event-metadata fields)]
    ;; Step 1: Schema evolution (if needed)
    (when new-fields
      (mulog/log ::schema-evolution :new-fields new-fields)
      (schema/add-fields! duckdb new-fields)
      (event-metadata/refresh! event-metadata))

    ;; Step 2: Bulk INSERT
    ;; TODO: Implement bulk INSERT
    (mulog/log ::persist-batch :event-count (count events))

    true))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example event structure
  {:service "my-service"
   :timestamp #inst "2024-01-01T00:00:00Z"
   :trace_id "abc123"
   :span_id "def456"
   :name "HTTP GET /api/users"
   :meta.signal_type "span"
   :span.kind "server"
   :span.status_code "ok"
   :span.duration_ns 1234567
   :meta.observed_time #inst "2024-01-01T00:00:01Z"
   ;; Dynamic attributes - will create new columns
   :http.method "GET"
   :http.status_code 200}

  ;; Test field extraction (uses schema/infer-type internally)
  (-extract-fields [{:service "test" :count 42 :active true}])
  ;; => {"service" {:type :string}
  ;;     "count" {:type :integer}
  ;;     "active" {:type :boolean}}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

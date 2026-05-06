;; ---------------------------------------------------------
;; o11ylite.components.events-schema-cache
;;
;; DuckDB schema cache for the `events` table.
;;
;; Caches the column -> type map (e.g. :service -> {:type :string},
;; :span.duration_ms -> {:type :float}) fetched from DuckDB's
;; information_schema. Used during event cleanse / schema evolution
;; in the ingest hot path to decide which columns exist, whether a
;; new field needs ADD COLUMN, and how to coerce values.
;;
;; The cache is eagerly initialized at startup from DuckDB and can
;; be refreshed asynchronously (e.g. after schema evolution or after
;; dropping fields via the Data Management UI).
;;
;; Not to be confused with:
;;   - `o11ylite.store.metrics.metadata` — per-metric definitions
;;     (description, unit, type, attribute set). Answers "what is this
;;     metric?". Keyed by metric name.
;;   - `o11ylite.store.telemetry-catalog` — service ↔ metric and
;;     service ↔ event-field ownership + liveness. Answers "who emits
;;     what and when did we last see it?". Keyed by service × thing.
;; ---------------------------------------------------------

(ns o11ylite.components.events-schema-cache
  (:require
    [integrant.core :as ig]
    [com.brunobonacci.mulog :as mulog]
    [o11ylite.store.schema :as schema]
    [o11ylite.util.telemetry :as telemetry]))

;; ---------------------------------------------------------
;; Public API

(defn get-fields
  "Get the current cached field metadata.
   Returns a map of keyword -> {:type normalized-type}."
  [events-schema]
  @(:state events-schema))

(defn get-field
  "Get metadata for a specific field.
   Returns {:type normalized-type} or nil if field doesn't exist."
  [events-schema field-name]
  (get @(:state events-schema) field-name))

(defn refresh!
  "Trigger an async refresh of the field metadata cache.
   Returns a promise that will be delivered with:
     - {:ok true :fields <field-map>} on success
     - {:ok false :error <exception>} on failure

   The cache is only updated on successful refresh."
  [events-schema]
  ((:refresh! events-schema)))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -make-refresh-fn
  "Create a refresh function that closes over duckdb and state."
  [duckdb state]
  (fn []
    (let [p (promise)]
      (future
        (try
          (let [fields (schema/fetch-event-fields duckdb)]
            (reset! state fields)
            (mulog/log ::events-schema-refreshed :o11ylite.events_schema_cache.field_count (count fields))
            (deliver p {:ok true :fields fields}))
          (catch Exception e
            (telemetry/report-error! ::events-schema-refresh-failed e)
            (deliver p {:ok false :error e}))))
      p)))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :cache/events-schema
  [_ {:keys [duckdb]}]
  (mulog/log ::events-schema-starting)
  (let [state (atom {})
        fields (schema/fetch-event-fields duckdb)]
    (reset! state fields)
    (mulog/log ::events-schema-started :o11ylite.events_schema_cache.field_count (count fields))
    {:state state
     :refresh! (-make-refresh-fn duckdb state)}))

(defmethod ig/halt-key! :cache/events-schema
  [_ _]
  ;; Nothing to clean up
  nil)

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Test events schema cache manually
  (require '[integrant.repl.state :refer [system]])

  (def esc (:cache/events-schema system))

  ;; Get all fields (now with normalized types)
  (get-fields esc)
  ;; => {:service {:type :string}
  ;;     :timestamp {:type :instant}
  ;;     :span.duration_ms {:type :float}
  ;;     ...}

  ;; Get specific field
  (get-field esc :service)           ;; => {:type :string}
  (get-field esc :timestamp)         ;; => {:type :instant}
  (get-field esc :span.duration_ms)  ;; => {:type :float}

  ;; Async refresh
  @(refresh! esc)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

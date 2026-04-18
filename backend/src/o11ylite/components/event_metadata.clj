;; ---------------------------------------------------------
;; o11ylite.components.event-metadata
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
;;
;; This component is the DuckDB schema cache for events only. A future
;; rename (`events.schema-cache`) would make that clearer but would
;; touch many call sites; see the telemetry-catalog plan for details.
;; ---------------------------------------------------------

(ns o11ylite.components.event-metadata
  (:require
    [integrant.core :as ig]
    [com.brunobonacci.mulog :as mulog]
    [o11ylite.store.schema :as schema]))

;; ---------------------------------------------------------
;; Public API

(defn get-fields
  "Get the current cached field metadata.
   Returns a map of keyword -> {:type normalized-type}."
  [event-metadata]
  @(:state event-metadata))

(defn get-field
  "Get metadata for a specific field.
   Returns {:type normalized-type} or nil if field doesn't exist."
  [event-metadata field-name]
  (get @(:state event-metadata) field-name))

(defn refresh!
  "Trigger an async refresh of the field metadata cache.
   Returns a promise that will be delivered with:
     - {:ok true :fields <field-map>} on success
     - {:ok false :error <exception>} on failure
   
   The cache is only updated on successful refresh."
  [event-metadata]
  ((:refresh! event-metadata)))

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
            (mulog/log ::event-metadata-refreshed :field-count (count fields))
            (deliver p {:ok true :fields fields}))
          (catch Exception e
            (mulog/log ::event-metadata-refresh-failed :error (.getMessage e))
            (deliver p {:ok false :error e}))))
      p)))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :cache/event-metadata
  [_ {:keys [duckdb]}]
  (mulog/log ::event-metadata-starting)
  (let [state (atom {})
        fields (schema/fetch-event-fields duckdb)]
    (reset! state fields)
    (mulog/log ::event-metadata-started :field-count (count fields))
    {:state state
     :refresh! (-make-refresh-fn duckdb state)}))

(defmethod ig/halt-key! :cache/event-metadata
  [_ _]
  ;; Nothing to clean up
  nil)

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Test event metadata manually
  (require '[integrant.repl.state :refer [system]])

  (def em (:cache/event-metadata system))

  ;; Get all fields (now with normalized types)
  (get-fields em)
  ;; => {:service {:type :string}
  ;;     :timestamp {:type :instant}
   ;;     :span.duration_ms {:type :float}
  ;;     ...}

  ;; Get specific field
  (get-field em :service)           ;; => {:type :string}
  (get-field em :timestamp)         ;; => {:type :instant}
  (get-field em :span.duration_ms)  ;; => {:type :float}

  ;; Async refresh
  @(refresh! em)
  (ig/halt-key! :db/duckdb duckdb-ds)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

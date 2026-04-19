;; ---------------------------------------------------------
;; o11ylite.store.telemetry-catalog-gc
;;
;; Garbage collection for stale metric metadata, event fields, and
;; services.
;;
;; The telemetry catalog tracks which services emit which metrics and
;; event fields (`service_metrics`, `service_event_fields`) and the
;; set of services ever seen (`service_metadata`). Three reclamations:
;;
;;   1. Metrics      -> DELETE row from metrics_metadata when no service
;;                      has emitted the metric for `stale-days`. Paired
;;                      with a catalog-row purge (service_metrics).
;;   2. Event fields -> DROP COLUMN from o11ylite.events (DuckLake
;;                      metadata-only) when no service has emitted the
;;                      field for `stale-days`. Refreshes the in-memory
;;                      :cache/event-metadata. Pairs with a catalog-row
;;                      purge (service_event_fields).
;;   3. Services     -> DELETE row from service_metadata when the service
;;                      itself has been silent for `stale-days`, and
;;                      cascade-delete ALL its rows from service_metrics
;;                      + service_event_fields (even those for signals
;;                      still emitted by other services — they're just
;;                      dead weight now).
;;
;; Metric/field GC uses an aggregate query across services (a metric is
;; only stale once `MAX(last_seen_at) < threshold`, i.e. no service is
;; still emitting it). Service GC runs last, sweeping up what remains.
;;
;; Unlike the manual delete path in routes/data-management, this GC does
;; NOT block the dropped fields/metrics. If a service later resumes
;; emitting the same signal, schema evolution will re-add the column /
;; metrics_metadata row on the next ingest, and the next sweep will
;; re-register the service in service_metadata.
;;
;; The staleness threshold is `data-retention-days` from app-config,
;; passed in by the scheduler registry. The conceptual tie-in: once we
;; no longer retain any telemetry from a service that emitted signal X,
;; X is reclaimable. No separate knob to keep in sync.
;; ---------------------------------------------------------

(ns o11ylite.store.telemetry-catalog-gc
  (:require
    [com.brunobonacci.mulog :as mulog]
    [o11ylite.components.event-metadata :as event-metadata]
    [o11ylite.store.metrics.metadata :as metrics-metadata]
    [o11ylite.store.schema :as schema]
    [o11ylite.store.services :as services]
    [o11ylite.store.telemetry-catalog :as catalog]
    [steffan-westcott.clj-otel.api.trace.span :as span]))

;; ---------------------------------------------------------
;; Private helpers

(defn- -now-ms
  []
  (System/currentTimeMillis))

(defn- -days->ms
  [days]
  (* days 24 60 60 1000))

(defn- -gc-stale-metrics!
  "Find metrics whose every emitter is stale, delete them from
   metrics_metadata, and remove the backing service_metrics rows."
  [{:keys [sqlite]} threshold-ms]
  (let [stale (catalog/get-stale-metrics sqlite threshold-ms)]
    (span/with-span!
      [::gc-stale-metrics {:count (count stale)}]
      (when (seq stale)
        (metrics-metadata/delete-metrics! sqlite stale)
        (catalog/delete-metrics! sqlite stale)
        (mulog/log ::stale-metrics-reclaimed :count (count stale) :names stale))
      stale)))

(defn- -gc-stale-event-fields!
  "Find event fields whose every emitter is stale, drop the DuckDB
   columns, remove the catalog rows, and refresh the event-metadata
   cache so the next ingest sees the current schema."
  [{:keys [sqlite duckdb event-metadata]} threshold-ms]
  (let [stale (catalog/get-stale-event-fields sqlite threshold-ms)]
    (span/with-span!
      [::gc-stale-event-fields {:count (count stale)}]
      (when (seq stale)
        (schema/drop-event-fields! duckdb stale)
        (catalog/delete-event-fields! sqlite stale)
        @(event-metadata/refresh! event-metadata)
        (mulog/log ::stale-event-fields-reclaimed
                   :count (count stale)
                   :names stale))
      stale)))

(defn- -gc-stale-services!
  "Find services silent for longer than the threshold, delete their
   service_metadata rows, and cascade-delete every row they own in
   service_metrics and service_event_fields. Run last so the metric /
   event-field passes see the full catalog first."
  [{:keys [sqlite]} threshold-ms]
  (let [stale (services/get-stale-services sqlite threshold-ms)]
    (span/with-span!
      [::gc-stale-services {:count (count stale)}]
      (when (seq stale)
        (services/delete-services! sqlite stale)
        (mulog/log ::stale-services-reclaimed :count (count stale) :names stale))
      stale)))

;; ---------------------------------------------------------
;; Public API

(defn run-gc!
  "Reclaim metrics, event fields, and services that haven't been seen
   for at least `stale-days`.

   `deps` must contain :sqlite, :duckdb, :event-metadata.

   Returns a map {:metrics <names> :event-fields <names> :services <names>}
   describing what was reclaimed. Safe to call with a dataset where
   nothing is stale — short-circuits to empty collections.

   Order: metrics → event-fields → services. Running service GC last
   means the aggregate metric/field queries see the full catalog and
   can correctly decide what's still live; the service pass then
   cascade-cleans leftover per-service catalog rows."
  [deps stale-days]
  (span/with-span!
    [::run-gc {:stale-days stale-days}]
    (let [threshold-ms (- (-now-ms) (-days->ms stale-days))
          metrics (-gc-stale-metrics! deps threshold-ms)
          event-fields (-gc-stale-event-fields! deps threshold-ms)
          svcs (-gc-stale-services! deps threshold-ms)]
      (mulog/log ::gc-finished
                 :stale-days stale-days
                 :metrics-reclaimed (count metrics)
                 :event-fields-reclaimed (count event-fields)
                 :services-reclaimed (count svcs))
      {:metrics metrics
       :event-fields event-fields
       :services svcs})))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])

  (def deps
    {:sqlite (:db/sqlite system)
     :duckdb (:db/duckdb system)
     :event-metadata (:cache/event-metadata system)})

  ;; Dry-run style: inspect candidates without deleting
  (require '[o11ylite.store.telemetry-catalog :as catalog])
  (let [threshold (- (System/currentTimeMillis) (* 30 24 60 60 1000))]
    {:metrics (catalog/get-stale-metrics (:sqlite deps) threshold)
     :event-fields (catalog/get-stale-event-fields (:sqlite deps) threshold)})

  ;; Actually reclaim
  (run-gc! deps 30)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

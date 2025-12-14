;; ---------------------------------------------------------
;; o11ylite.store.metrics.ingest
;;
;; Metrics ingestion: validation and storage for time-series metrics.
;;
;; Metrics have fundamentally different characteristics than events:
;;   - Different schema (metric name, value)
;;   - May require different storage patterns (downsampling, rollups)
;;   - Different query patterns
;;
;; This module will mirror the events/ingest.clj structure:
;;   - ingest-metrics! : validation + submit to batcher
;;   - insert-batch!   : bulk INSERT to metrics table
;;
;; Note: Metrics may need their own batcher or shared batcher with
;; type-aware flush logic. TBD based on requirements.
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.ingest)

;; TODO: Implement metrics ingestion

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example metric structure (OTLP-style)
  {:name "http.server.duration"
   :description "HTTP server request duration"
   :unit "ms"
   :type :histogram  ; :gauge, :counter, :histogram, :summary
   :data-points [{:timestamp #inst "2024-01-01T00:00:00Z"
                  :attributes {"service.name" "api"
                               "http.method" "GET"
                               "http.status_code" "200"}
                  :value 42.5}]}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

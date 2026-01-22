;; ---------------------------------------------------------
;; o11ylite.store.batcher
;;
;; Shared batcher utilities for submitting payloads to batchers.
;; Used by both event and metric ingestion paths.
;; ---------------------------------------------------------

(ns o11ylite.store.batcher
  (:require
   [clojure.core.async :as a]
   [steffan-westcott.clj-otel.api.trace.span :as span]))

;; ---------------------------------------------------------
;; Public API

(defn ->batcher!
  "Submit a payload to a batcher. Blocks until flushed to storage.

   Arguments:
     batcher - Batcher component (must have :ingest-ch)
     payload - Map to submit (structure depends on batcher type)

   Returns:
     true if payload was persisted successfully
     false if flush failed (caller should handle retry/logging)

   This is a generic function used by signal-specific ingest functions
   (e.g., events.ingest/ingest-events!, metrics.ingest/ingest-metrics!)
   after they've done validation and field extraction."
  [batcher payload]
  (span/add-event! ::->bacher)
  (let [done (promise)]
    (a/>!! (:ingest-ch batcher) (assoc payload :done done))
    @done))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example usage from events/ingest.clj:
  ;; (batcher/->batcher! batcher {:events events :fields fields})

  ;; Example usage from metrics/ingest.clj:
  ;; (batcher/->batcher! batcher {:data-points data-points})

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

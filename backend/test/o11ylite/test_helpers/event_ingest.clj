;; ---------------------------------------------------------
;; o11ylite.test-helpers.event-ingest
;;
;; Event ingestion helpers for integration tests.
;; Provides random event generation and direct ingestion via batcher.
;; ---------------------------------------------------------

(ns o11ylite.test-helpers.event-ingest
  (:require
    [o11ylite.store.events.ingest :as events.ingest])
  (:import
    [java.time Instant]
    [java.util UUID]))

;; ---------------------------------------------------------
;; Random Data Generators

(def ^:private service-names
  ["api-gateway" "user-service" "order-service" "payment-service" "inventory-service"])

(def ^:private span-names
  ["HTTP GET /api/users" "HTTP POST /api/orders" "DB query" "cache lookup" "auth check"])

(def ^:private http-methods
  ["GET" "POST" "PUT" "DELETE" "PATCH"])

(defn- -random-hex
  "Generate a random hex string of given length."
  [len]
  (let [chars "0123456789abcdef"]
    (apply str (repeatedly len #(rand-nth chars)))))

(defn- -random-trace-id
  []
  (-random-hex 32))

(defn- -random-span-id
  []
  (-random-hex 16))

(defn- -random-timestamp
  "Generate a random timestamp within the last hour."
  []
  (let [now (System/currentTimeMillis)
        one-hour-ms (* 60 60 1000)
        offset (rand-int one-hour-ms)]
    (Instant/ofEpochMilli (- now offset))))

;; ---------------------------------------------------------
;; Public API

(defn make-random-event
  "Generate a random event map with realistic field values.
   
   Optional overrides can be provided to set specific fields.
   
   Example:
     (make-random-event)
     (make-random-event {:service \"my-service\" :name \"custom-span\"})"
  ([] (make-random-event {}))
  ([overrides]
   (merge {:service (rand-nth service-names)
           :timestamp (-random-timestamp)
           :meta.signal_type :span
           :meta.observed_time (Instant/now)
           :name (rand-nth span-names)
           :trace_id (-random-trace-id)
           :span_id (-random-span-id)
           :span.kind :server
           :span.status_code :ok
           :span.duration_ms (+ 0.1 (rand 100.0))
           :attr.http.method (rand-nth http-methods)
           :attr.http.status_code (rand-nth [200 201 204 400 404 500])
           :attr.request.id (str (UUID/randomUUID))}
          overrides)))

(defn make-random-events
  "Generate n random events.
   
   Optional overrides are applied to all events.
   
   Example:
     (make-random-events 10)
     (make-random-events 5 {:service \"test-service\"})"
  ([n] (make-random-events n {}))
  ([n overrides]
   (repeatedly n #(make-random-event overrides))))

(defn ingest-events!
  "Ingest events directly via the batcher, bypassing gRPC.
   Blocks until events are persisted to storage.

   Arguments:
     event-metadata - The event metadata cache component
     blocked-fields - The blocked-fields cache component
     batcher        - The ingest batcher component
     id-generator   - The ID generator component
     events         - Collection of event maps to ingest

   Returns:
     true if all events were persisted successfully

   Example:
     (ingest-events! (event-metadata) (blocked-fields) (batcher) (id-generator) (make-random-events 10))"
  [event-metadata blocked-fields batcher id-generator events]
  (events.ingest/ingest-events! event-metadata blocked-fields batcher id-generator events))

(defn ingest-sample-events!
  "Generate and ingest n random events. Returns the ingested events.

   Arguments:
     event-metadata - The event metadata cache component
     blocked-fields - The blocked-fields cache component
     batcher        - The ingest batcher component
     id-generator   - The ID generator component
     n              - Number of events to generate and ingest

   Optional:
     overrides      - Map of field overrides for all events

   Returns:
     Vector of the ingested event maps (for verification)

   Example:
      (ingest-sample-events! (event-metadata) (blocked-fields) (batcher) (id-gen) 10)
      (ingest-sample-events! (event-metadata) (blocked-fields) (batcher) (id-gen) 5 {:service \"test-svc\"})"
  ([event-metadata blocked-fields batcher id-generator n]
   (ingest-sample-events! event-metadata blocked-fields batcher id-generator n {}))
  ([event-metadata blocked-fields batcher id-generator n overrides]
   (let [events (vec (make-random-events n overrides))]
     (ingest-events! event-metadata blocked-fields batcher id-generator events)
     events)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[o11ylite.test-helpers :as h])

  ;; Generate random events
  (make-random-event)
  (make-random-events 3)
  (make-random-events 2 {:service "my-service"})

  ;; In a test with h/*system* bound:
  ;; (ingest-sample-events! (:cache/event-metadata h/*system*)
  ;;                        (:ingest/event-batcher h/*system*)
  ;;                        10)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

;; ---------------------------------------------------------
;; o11ylite.integration.telemetry-catalog-test
;;
;; End-to-end: ingest events/metrics → batcher flushes → catalog buffer
;; sweep writes SQLite rows → assert on service_metrics,
;; service_event_fields, and service_metadata.last_seen_at.
;;
;; One deftest with multiple testing blocks to amortize with-system
;; startup cost.
;; ---------------------------------------------------------

(ns o11ylite.integration.telemetry-catalog-test
  (:require
    [clojure.set :as set]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.components.telemetry-catalog-buffer :as catalog-buffer]
    [o11ylite.store.services :as services]
    [o11ylite.store.telemetry-catalog :as catalog]
    [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helpers

(defn- sqlite
  []
  (:db/sqlite h/*system*))

(defn- catalog-buf
  []
  (:discovery/telemetry-catalog-buffer h/*system*))

(defn- flush-batcher-and-sweep!
  "Wait for the batcher's async flush to land data in DuckDB, then force a
   catalog sweep so SQLite reflects the run's activity.

   The batchers flush every 100ms in tests; we give them 300ms of slack to
   land. Then the track-* put!s have been queued into the catalog buffer,
   so a synchronous sweep! finalizes the SQLite writes."
  []
  (Thread/sleep 300)
  (catalog-buffer/sweep! (catalog-buf)))

(defn- service-names-in
  "Given (services/get-services) output, return the set of service names.
   Filters out the self-instrumented 'o11ylite' service so assertions are
   independent of the server's own OTel agent activity."
  [rows]
  (->> rows
       (map :name)
       (remove #{"o11ylite"})
       set))

;; ---------------------------------------------------------
;; Tests

(deftest catalog-buffer-sweep-test
  ;; Baseline: nothing tracked from tests yet, but the self-instrumentation
  ;; of the running system may have tracked "o11ylite". We filter that out
  ;; in our assertions.
  (testing "ingesting events populates service_event_fields and service_metadata"
    (h/ingest-sample-events! 5 {:service "svc-events-a"})
    (h/ingest-sample-events! 3 {:service "svc-events-b"})
    (flush-batcher-and-sweep!)

    (let [svc-a-fields (set (catalog/get-service-event-fields (sqlite) "svc-events-a"))
          svc-b-fields (set (catalog/get-service-event-fields (sqlite) "svc-events-b"))
          observed-services (service-names-in (services/get-services (sqlite)))]
      (is (contains? observed-services "svc-events-a"))
      (is (contains? observed-services "svc-events-b"))
      ;; Core fields every event carries
      (is (set/subset? #{"service" "timestamp" "trace_id" "name"} svc-a-fields)
          (str "svc-events-a missing core fields, got: " svc-a-fields))
      (is (set/subset? #{"service" "timestamp" "trace_id" "name"} svc-b-fields))
      ;; service_metadata last_seen_at got bumped
      (let [rows (services/get-services (sqlite))
            svc-a-row (first (filter #(= "svc-events-a" (:name %)) rows))]
        (is (some? (:last_seen_at svc-a-row))
            "svc-events-a should have a last_seen_at"))))

  (testing "ingesting metrics populates service_metrics"
    (h/ingest-sample-metrics! 4 {:service "svc-metrics-a" :name "cpu.utilization"})
    (h/ingest-sample-metrics! 2 {:service "svc-metrics-b" :name "http.request.duration"})
    (flush-batcher-and-sweep!)

    (let [svc-a-metrics (set (catalog/get-service-metrics (sqlite) "svc-metrics-a"))
          svc-b-metrics (set (catalog/get-service-metrics (sqlite) "svc-metrics-b"))
          observed-services (service-names-in (services/get-services (sqlite)))]
      (is (contains? observed-services "svc-metrics-a"))
      (is (contains? observed-services "svc-metrics-b"))
      (is (contains? svc-a-metrics "cpu.utilization"))
      (is (contains? svc-b-metrics "http.request.duration"))))

  (testing "get-metric-services reverse lookup works"
    (let [services-emitting-cpu (set (catalog/get-metric-services
                                       (sqlite) "cpu.utilization"))]
      (is (contains? services-emitting-cpu "svc-metrics-a"))))

  (testing "sweep is idempotent: re-sweep without new ingest is a no-op write"
    (let [metrics-before (catalog/get-all-service-metrics (sqlite))
          fields-before (catalog/get-all-service-event-fields (sqlite))]
      (catalog-buffer/sweep! (catalog-buf))
      (is (= metrics-before (catalog/get-all-service-metrics (sqlite))))
      (is (= fields-before (catalog/get-all-service-event-fields (sqlite))))))

  (testing "re-ingesting the same service bumps last_seen_at without duplicating rows"
    (let [before (catalog/get-all-service-metrics (sqlite))
          svc-a-row-before (first (filter #(= "svc-metrics-a" (:name %))
                                          (services/get-services (sqlite))))
          _ (Thread/sleep 5) ; ensure epoch-ms moves forward
          _ (h/ingest-sample-metrics! 1 {:service "svc-metrics-a" :name "cpu.utilization"})
          _ (flush-batcher-and-sweep!)
          after (catalog/get-all-service-metrics (sqlite))
          svc-a-row-after (first (filter #(= "svc-metrics-a" (:name %))
                                         (services/get-services (sqlite))))]
      (is (= before after) "No new (service, metric_name) pairs expected")
      (is (>= (:last_seen_at svc-a-row-after)
              (:last_seen_at svc-a-row-before))
          "service_metadata.last_seen_at should advance"))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.telemetry-catalog-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

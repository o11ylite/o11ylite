;; ---------------------------------------------------------
;; o11ylite.integration.scheduled-jobs.telemetry-catalog-gc-test
;;
;; Integration tests for the telemetry-catalog GC scheduled job.
;; One deftest registers via the scheduler and asserts it runs; the
;; others exercise run-gc! directly with seeded stale data so we can
;; assert on the actual reclamation behavior.
;; ---------------------------------------------------------

(ns o11ylite.integration.scheduled-jobs.telemetry-catalog-gc-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.components.events-schema-cache :as events-schema-cache]
    [o11ylite.components.scheduler :as scheduler]
    [o11ylite.store.metrics.metadata :as metrics-metadata]
    [o11ylite.store.schema :as schema]
    [next.jdbc :as jdbc]
    [o11ylite.store.services :as services]
    [o11ylite.store.telemetry-catalog :as catalog]
    [o11ylite.store.telemetry-catalog-gc :as catalog-gc]
    [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helpers

(defn- sqlite
  []
  (:db/sqlite h/*system*))
(defn- duckdb
  []
  (:db/duckdb-reader h/*system*))
(defn- events-schema
  []
  (:cache/events-schema h/*system*))

(defn- gc-deps
  []
  {:sqlite (sqlite)
   :duckdb-writer-events (:db/duckdb-writer-events h/*system*)
   :events-schema (events-schema)})

(defn- get-gc-job-status
  []
  (->> (scheduler/get-job-status (sqlite))
       (filter #(= "telemetry-catalog-garbage-collection" (:job_name %)))
       first))

(defn- seed-catalog-metric!
  [service metric-name last-seen-at]
  (catalog/upsert-service-metrics!
    (sqlite)
    [{:service service :metric-name metric-name :last-seen-at last-seen-at}]))

(defn- seed-catalog-event-field!
  [service field last-seen-at]
  (catalog/upsert-service-event-fields!
    (sqlite)
    [{:service service :field field :last-seen-at last-seen-at}]))

;; ---------------------------------------------------------
;; Tests

(deftest gc-job-registered-test
  (testing "Scheduler registers the telemetry-catalog-gc job on startup"
    (let [job (get-gc-job-status)]
      (is (some? job) "Job should be registered")
      (is (= "telemetry-catalog-garbage-collection" (:job_name job)))
      (is (pos? (:interval_ms job)) "Interval should be positive")
      (is (= 1 (:enabled job)) "Job should be enabled")))

  (testing "Scheduler triggers the GC job and records success"
    ;; Scheduler is configured with 1-minute interval and 100ms tick;
    ;; the job runs immediately because last_run_at is NULL. Poll
    ;; rather than fixed-sleep — the tick + GC + SQLite write can take
    ;; longer than a tight budget under load.
    (let [job (h/wait-until #(let [j (get-gc-job-status)]
                               (when (some? (:last_run_at j)) j))
                            {:label "telemetry-catalog-gc first run"})]
      (is (some? (:last_run_at job)) "Job should have run")
      (is (some? (:last_success_at job)) "Job should have succeeded")
      (is (nil? (:last_error job)) "Job should have no error"))))

(deftest run-gc-reclaims-stale-metrics-test
  (testing "run-gc! deletes metrics_metadata + service_metrics rows when
            no service has emitted the metric for stale-days"
    (let [now (System/currentTimeMillis)
          ancient (- now (* 60 24 60 60 1000)) ; 60 days ago
          fresh (- now (* 60 1000))]           ; 1 minute ago

      ;; Seed: "dead.metric" is only emitted by stale services; "live.metric"
      ;; has a fresh emitter so it must survive.
      (metrics-metadata/upsert-metrics!
        (sqlite)
        {"dead.metric" {:metric_type :gauge :unit "1"}
         "live.metric" {:metric_type :gauge :unit "1"}})
      (seed-catalog-metric! "svc-gone-a" "dead.metric" ancient)
      (seed-catalog-metric! "svc-gone-b" "dead.metric" ancient)
      (seed-catalog-metric! "svc-live"  "live.metric" fresh)
      (seed-catalog-metric! "svc-gone-c" "live.metric" ancient) ; not all stale

      (let [result (catalog-gc/run-gc! (gc-deps) 30)]
        (is (contains? (set (:metrics result)) "dead.metric"))
        (is (not (contains? (set (:metrics result)) "live.metric"))))

      (testing "metrics_metadata only retains live metrics"
        (let [remaining (set (metrics-metadata/list-metric-names (sqlite)))]
          (is (contains? remaining "live.metric"))
          (is (not (contains? remaining "dead.metric")))))

      (testing "service_metrics rows for reclaimed metric are gone"
        (is (empty? (catalog/get-metric-services (sqlite) "dead.metric")))
        (is (seq (catalog/get-metric-services (sqlite) "live.metric")))))))

(deftest run-gc-reclaims-stale-event-fields-test
  (testing "run-gc! drops events column + service_event_fields row when
            no service has emitted the field for stale-days"
    (let [now (System/currentTimeMillis)
          ancient (- now (* 60 24 60 60 1000))
          fresh (- now (* 60 1000))
          dead-field "attr.gc.dead"
          live-field "attr.gc.live"]

      ;; Add both columns to DuckDB so we can watch one get dropped.
      (schema/add-event-fields! (duckdb)
                                {(keyword dead-field) {:type :string}
                                 (keyword live-field) {:type :string}})
      @(events-schema-cache/refresh! (events-schema))

      (seed-catalog-event-field! "svc-gone" dead-field ancient)
      (seed-catalog-event-field! "svc-live" live-field fresh)

      (let [result (catalog-gc/run-gc! (gc-deps) 30)]
        (is (contains? (set (:event-fields result)) dead-field))
        (is (not (contains? (set (:event-fields result)) live-field))))

      (testing "events table no longer has the dead column"
        (let [columns (set (map name (keys (events-schema-cache/get-fields (events-schema)))))]
          (is (contains? columns live-field))
          (is (not (contains? columns dead-field)))))

      (testing "service_event_fields row for the dead field is gone"
        (is (empty? (catalog/get-service-event-fields (sqlite) "svc-gone")))
        (is (seq (catalog/get-service-event-fields (sqlite) "svc-live")))))))

(deftest run-gc-reclaims-stale-services-test
  (testing "run-gc! deletes service_metadata rows and cascade-deletes
            all service_metrics / service_event_fields rows for services
            whose last_seen_at predates the threshold"
    (let [now (System/currentTimeMillis)
          ancient (- now (* 60 24 60 60 1000))
          fresh (- now (* 60 1000))]

      ;; svc-ghost: stale everywhere. Its catalog rows point at fields /
      ;; metrics that ARE still live via svc-alive, so aggregate GC won't
      ;; reclaim them. Service GC must cascade-delete those rows anyway.
      (services/upsert-services! (sqlite) ["svc-ghost"] ancient)
      (seed-catalog-metric! "svc-ghost" "shared.metric" ancient)
      (seed-catalog-event-field! "svc-ghost" "attr.shared.field" ancient)

      ;; svc-alive: fresh emitter of the same signals — they must survive.
      (services/upsert-services! (sqlite) ["svc-alive"] fresh)
      (seed-catalog-metric! "svc-alive" "shared.metric" fresh)
      (seed-catalog-event-field! "svc-alive" "attr.shared.field" fresh)

      (let [result (catalog-gc/run-gc! (gc-deps) 30)
            reclaimed (set (:services result))]
        (is (contains? reclaimed "svc-ghost"))
        (is (not (contains? reclaimed "svc-alive"))))

      (testing "service_metadata no longer lists the reclaimed service"
        (let [remaining (set (map :name (services/get-services (sqlite))))]
          (is (not (contains? remaining "svc-ghost")))
          (is (contains? remaining "svc-alive"))))

      (testing "cascaded catalog rows for the reclaimed service are gone"
        (is (empty? (catalog/get-service-metrics (sqlite) "svc-ghost")))
        (is (empty? (catalog/get-service-event-fields (sqlite) "svc-ghost")))
        ;; The shared signals survive because svc-alive is fresh.
        (is (contains? (set (catalog/get-metric-services (sqlite) "shared.metric"))
                       "svc-alive"))
        (is (not (contains? (set (catalog/get-metric-services (sqlite) "shared.metric"))
                            "svc-ghost")))))))

(deftest run-gc-reclaims-services-with-null-last-seen-test
  (testing "services with NULL last_seen_at are treated as stale"
    ;; Simulates the post-migration case: a row that existed before the
    ;; last_seen_at column was added and hasn't been re-observed since.
    ;; We forge the state by inserting a service_metadata row directly.
    (jdbc/execute!
      (sqlite)
      ["INSERT INTO service_metadata (service, first_seen_at, updated_at, last_seen_at)
        VALUES (?, ?, ?, NULL)"
       "svc-pre-migration" 0 0])

    (let [result (catalog-gc/run-gc! (gc-deps) 30)]
      (is (contains? (set (:services result)) "svc-pre-migration")))

    (is (not (contains? (set (map :name (services/get-services (sqlite))))
                        "svc-pre-migration")))))

(deftest run-gc-handles-orphaned-catalog-rows-test
  (testing "run-gc! tolerates stale event-field catalog rows whose DuckDB
            column does not exist (e.g. manually dropped or from a partial
            prior GC run) and cleans up the catalog row anyway"
    (let [now (System/currentTimeMillis)
          ancient (- now (* 60 24 60 60 1000))
          orphan-field "attr.gc.orphan"]
      ;; Seed a catalog row for a field whose column we deliberately
      ;; never add to DuckDB. This mirrors the production scenario where
      ;; attr.names was in service_event_fields but not in the events table.
      (seed-catalog-event-field! "svc-ghost" orphan-field ancient)
      ;; Also add a real column + catalog row for a live field to verify
      ;; the GC still does real work in the same pass.
      (let [real-field "attr.gc.real"]
        (schema/add-event-fields! (duckdb)
                                  {(keyword real-field) {:type :string}})
        @(events-schema-cache/refresh! (events-schema))
        (seed-catalog-event-field! "svc-ghost" real-field ancient)

        (let [result (catalog-gc/run-gc! (gc-deps) 30)
              reclaimed (set (:event-fields result))]
          (is (contains? reclaimed orphan-field)
              "Orphaned field should be reported as reclaimed")
          (is (contains? reclaimed real-field)
              "Real field should also be reclaimed")
          (is (= 2 (count (:event-fields result)))))

        (testing "both catalog rows are gone"
          (is (empty? (catalog/get-service-event-fields (sqlite) "svc-ghost"))))))))

(deftest run-gc-is-a-noop-when-nothing-is-stale-test
  (testing "run-gc! short-circuits gracefully when there are no stale rows"
    (let [result (catalog-gc/run-gc! (gc-deps) 30)]
      (is (= [] (:metrics result)))
      (is (= [] (:event-fields result)))
      (is (= [] (:services result))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.scheduled-jobs.telemetry-catalog-gc-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

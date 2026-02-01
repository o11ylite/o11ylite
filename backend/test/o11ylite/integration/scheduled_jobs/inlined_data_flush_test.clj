;; ---------------------------------------------------------
;; o11ylite.integration.scheduled-jobs.inlined-data-flush-test
;;
;; Integration tests for the inlined-data-flush scheduled job.
;; Tests that the scheduler correctly triggers the flush job
;; and records its execution status.
;; ---------------------------------------------------------

(ns o11ylite.integration.scheduled-jobs.inlined-data-flush-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [next.jdbc :as jdbc]
    [o11ylite.components.scheduler :as scheduler]
    [o11ylite.store.ducklake :as ducklake]
    [o11ylite.store.events.ingest :as events.ingest]
    [o11ylite.test-helpers :as h])
  (:import
    [java.time Instant]))

;; Start scheduler and event-metadata for these tests
;; Note: We use persist-batch! directly instead of the batcher to avoid a
;; known DuckLake concurrency issue where INSERT + flush_inlined_data can
;; cause data duplication when running concurrently.
;; See: https://github.com/duckdb/ducklake/issues/650
(use-fixtures :each (h/with-partial-system [:scheduler/executor :cache/event-metadata]))

;; ---------------------------------------------------------
;; Helpers

(defn- sqlite
  []
  (:db/sqlite h/*system*))
(defn- duckdb
  []
  (:db/duckdb h/*system*))
(defn- event-metadata
  []
  (:cache/event-metadata h/*system*))

(def ^:private test-id-counter (atom 0))

(defn- ingest-small-batch!
  "Ingest a small batch of events directly (will be inlined due to DATA_INLINING_ROW_LIMIT)."
  [service-name n]
  (let [now (Instant/now)
        events (for [i (range n)]
                 {:id (swap! test-id-counter inc)
                  :service service-name
                  :timestamp now
                  :error false
                  :meta.signal_type :span
                  :meta.observed_time now
                  :name (str "test-span-" i)})
        fields {:id {:type :integer}
                :service {:type :string}
                :timestamp {:type :instant}
                :error {:type :boolean}
                :meta.signal_type {:type :string}
                :meta.observed_time {:type :instant}
                :name {:type :string}}]
    (events.ingest/persist-batch! (duckdb) (event-metadata) events fields)))

(defn- count-events
  "Count total events in the events table."
  []
  (-> (jdbc/execute-one! (duckdb) ["SELECT COUNT(*) AS cnt FROM events"])
      :cnt))

(defn- get-flush-job-status
  []
  (->> (scheduler/get-job-status (sqlite))
       (filter #(= "inlined-data-flush" (:job_name %)))
       first))

;; ---------------------------------------------------------
;; Tests

(deftest flush-job-registered-test
  (testing "Scheduler registers the inlined-data-flush job on startup"
    (let [job (get-flush-job-status)]
      (is (some? job) "Job should be registered")
      (is (= "inlined-data-flush" (:job_name job)))
      (is (pos? (:interval_ms job)) "Interval should be positive")
      (is (= 1 (:enabled job)) "Job should be enabled"))))

(deftest flush-job-runs-periodically-test
  (testing "Scheduler triggers the flush job and records success"
    ;; Ingest some events (will be inlined)
    (ingest-small-batch! "api-gateway" 3)
    (ingest-small-batch! "payment-service" 2)

    ;; Wait for scheduler to trigger job (test uses 100ms tick/interval)
    (Thread/sleep 250)

    ;; Events should still be queryable after flush
    (is (= 5 (count-events)))

    ;; Job should have recorded success
    (let [job (get-flush-job-status)]
      (is (some? (:last_run_at job)) "Job should have run")
      (is (some? (:last_success_at job)) "Job should have succeeded")
      (is (nil? (:last_error job)) "Job should have no error"))))

(deftest flush-job-handles-empty-data-test
  (testing "Flush job succeeds with no inlined data"
    ;; Wait for scheduler to trigger job with no data
    (Thread/sleep 250)

    ;; Job should still succeed
    (let [job (get-flush-job-status)]
      (is (some? (:last_success_at job)) "Job should have succeeded")
      (is (nil? (:last_error job)) "Job should have no error"))))

(deftest manual-flush-test
  (testing "Manual flush via ducklake/flush-inlined-data! works"
    (ingest-small-batch! "test-service" 5)
    (is (= 5 (count-events)))

    ;; Manual flush should not error
    (ducklake/flush-inlined-data! (duckdb))

    ;; Data should still be queryable
    (is (= 5 (count-events)))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.scheduled-jobs.inlined-data-flush-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

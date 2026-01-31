;; ---------------------------------------------------------
;; o11ylite.integration.scheduled-jobs.daily-maintenance-test
;;
;; Integration tests for the daily-maintenance scheduled job.
;; Tests that the scheduler correctly triggers the maintenance job
;; (data retention + checkpoint) and records its execution status.
;; ---------------------------------------------------------

(ns o11ylite.integration.scheduled-jobs.daily-maintenance-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.components.scheduler :as scheduler]
   [o11ylite.store.ducklake :as ducklake]
   [o11ylite.test-helpers :as h :refer [ingest-sample-events! ingest-sample-metrics!]]))

;; Start scheduler and ingest components for these tests
(use-fixtures :each (h/with-partial-system
                      (into [:scheduler/executor] h/ingest-components)))

;; ---------------------------------------------------------
;; Helpers

(defn- sqlite [] (:db/sqlite h/*system*))
(defn- duckdb [] (:db/duckdb h/*system*))

(defn- get-maintenance-job-status []
  (->> (scheduler/get-job-status (sqlite))
       (filter #(= "daily-maintenance" (:job_name %)))
       first))

;; ---------------------------------------------------------
;; Tests

(deftest maintenance-job-registered-test
  (testing "Scheduler registers the daily-maintenance job on startup"
    (let [job (get-maintenance-job-status)]
      (is (some? job) "Job should be registered")
      (is (= "daily-maintenance" (:job_name job)))
      (is (pos? (:interval_ms job)) "Interval should be positive")
      (is (= 1 (:enabled job)) "Job should be enabled"))))

(deftest maintenance-job-runs-periodically-test
  (testing "Scheduler triggers the maintenance job and records success"
    ;; Wait for scheduler to trigger job (test uses 100ms tick/interval)
    (Thread/sleep 250)

    ;; Job should have recorded success
    (let [job (get-maintenance-job-status)]
      (is (some? (:last_run_at job)) "Job should have run")
      (is (some? (:last_success_at job)) "Job should have succeeded")
      (is (nil? (:last_error job)) "Job should have no error"))))

(deftest manual-delete-old-data-test
  (testing "Manual data retention via ducklake/delete-old-data! works"
    ;; Insert data so DELETE actually evaluates the WHERE clause type casts
    (ingest-sample-events! 1)
    (ingest-sample-metrics! 1)
    ;; Should not throw, returns JDBC result (array)
    (is (ducklake/delete-old-data! (duckdb) 30))))

(deftest manual-checkpoint-test
  (testing "Manual checkpoint via ducklake/run-checkpoint! works"
    ;; Should not error (even with nothing to checkpoint), returns JDBC result
    (is (ducklake/run-checkpoint! (duckdb)))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.scheduled-jobs.daily-maintenance-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

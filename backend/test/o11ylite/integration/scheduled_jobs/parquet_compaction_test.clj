;; ---------------------------------------------------------
;; o11ylite.integration.scheduled-jobs.parquet-compaction-test
;;
;; Integration tests for the parquet-compaction scheduled job.
;; Tests that the scheduler correctly triggers the compaction job
;; and records its execution status.
;; ---------------------------------------------------------

(ns o11ylite.integration.scheduled-jobs.parquet-compaction-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.components.scheduler :as scheduler]
   [o11ylite.store.ducklake :as ducklake]
   [o11ylite.test-helpers :as h]))

;; Start scheduler for these tests
(use-fixtures :each (h/with-partial-system [:scheduler/executor]))

;; ---------------------------------------------------------
;; Helpers

(defn- sqlite [] (:db/sqlite h/*system*))
(defn- duckdb [] (:db/duckdb h/*system*))

(defn- get-compaction-job-status []
  (->> (scheduler/get-job-status (sqlite))
       (filter #(= "parquet-compaction" (:job_name %)))
       first))

;; ---------------------------------------------------------
;; Tests

(deftest compaction-job-registered-test
  (testing "Scheduler registers the parquet-compaction job on startup"
    (let [job (get-compaction-job-status)]
      (is (some? job) "Job should be registered")
      (is (= "parquet-compaction" (:job_name job)))
      (is (pos? (:interval_ms job)) "Interval should be positive")
      (is (= 1 (:enabled job)) "Job should be enabled"))))

(deftest compaction-job-runs-periodically-test
  (testing "Scheduler triggers the compaction job and records success"
    ;; Wait for scheduler to trigger job (test uses 100ms tick/interval)
    (Thread/sleep 250)

    ;; Job should have recorded success
    (let [job (get-compaction-job-status)]
      (is (some? (:last_run_at job)) "Job should have run")
      (is (some? (:last_success_at job)) "Job should have succeeded")
      (is (nil? (:last_error job)) "Job should have no error"))))

(deftest manual-compaction-test
  (testing "Manual compaction via ducklake/merge-adjacent-files! works"
    ;; Manual compaction should not error (even with no files to compact), returns JDBC result
    (is (ducklake/merge-adjacent-files! (duckdb)))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.scheduled-jobs.parquet-compaction-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

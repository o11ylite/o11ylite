;; ---------------------------------------------------------
;; o11ylite.integration.components.scheduler-test
;;
;; Integration tests for the scheduler component.
;; Uses a mock job registry to test scheduler mechanics
;; independently of any real job implementation.
;; ---------------------------------------------------------

(ns o11ylite.integration.components.scheduler-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [integrant.core :as ig]
    [o11ylite.components.scheduler :as scheduler]
    [o11ylite.test-helpers :as h]))

;; ---------------------------------------------------------
;; Test State

(def ^:private test-job-calls (atom []))
(def ^:private test-job-should-fail (atom false))

(defn- reset-test-state!
  []
  (reset! test-job-calls [])
  (reset! test-job-should-fail false))

;; ---------------------------------------------------------
;; Test Registry Builder
;;
;; Creates a test registry with mock jobs. No with-redefs needed
;; since we pass the registry directly to the scheduler.

(defn- make-test-registry
  []
  {:test-job
   {:interval-ms 100
    :description "Test job for scheduler testing"
    :handler (fn []
               (swap! test-job-calls conj {:time (System/currentTimeMillis)})
               (when @test-job-should-fail
                 (throw (ex-info "Test job failed" {}))))}})

;; ---------------------------------------------------------
;; Fixture Setup

(def ^:dynamic *scheduler* nil)

(defn with-test-scheduler
  [f]
  (let [sys (h/start-partial-system! [:storage/init])
        sqlite (:db/sqlite sys)
        test-registry (make-test-registry)
        sched (ig/init-key :scheduler/executor
                           {:sqlite sqlite
                            :registry test-registry
                            :tick-interval-ms 50})]
    (try
      (reset-test-state!)
      (binding [h/*system* sys
                *scheduler* sched]
        (f))
      (finally
        (ig/halt-key! :scheduler/executor sched)
        (h/stop-system! sys)))))

(use-fixtures :each with-test-scheduler)

;; ---------------------------------------------------------
;; Helpers

(defn- sqlite
  []
  (:db/sqlite h/*system*))

(defn- get-test-job-status
  []
  (->> (scheduler/get-job-status (sqlite))
       (filter #(= "test-job" (:job_name %)))
       first))

;; ---------------------------------------------------------
;; Tests

(deftest scheduler-registers-job-on-startup-test
  (testing "Scheduler registers jobs from registry on startup"
    (let [job (get-test-job-status)]
      (is (some? job) "Job should be registered")
      (is (= "test-job" (:job_name job)))
      (is (= 100 (:interval_ms job)) "Interval should match registry config")
      (is (= 1 (:enabled job)) "Job should be enabled by default"))))

(deftest scheduler-runs-due-jobs-test
  (testing "Scheduler executes jobs when they become due"
    ;; Wait for at least one tick + job interval
    (Thread/sleep 200)

    ;; Job should have been called
    (is (pos? (count @test-job-calls)) "Job should have been called at least once")))

(deftest scheduler-records-success-test
  (testing "Scheduler records success after job completes"
    ;; Wait for job to run
    (Thread/sleep 200)

    (let [job (get-test-job-status)]
      (is (some? (:last_run_at job)) "last_run_at should be set")
      (is (some? (:last_success_at job)) "last_success_at should be set")
      (is (nil? (:last_error job)) "last_error should be nil on success"))))

(deftest scheduler-records-failure-test
  (testing "Scheduler records failure when job throws"
    ;; Make the job fail
    (reset! test-job-should-fail true)

    ;; Wait for job to run and fail
    (Thread/sleep 200)

    (let [job (get-test-job-status)]
      (is (some? (:last_run_at job)) "last_run_at should be set even on failure")
      (is (some? (:last_error job)) "last_error should contain error message"))))

(deftest scheduler-prevents-concurrent-execution-test
  (testing "Scheduler prevents same job from running concurrently"
    ;; Create a slow job that takes longer than tick interval
    (let [slow-job-running (atom false)
          slow-job-concurrent-calls (atom 0)
          slow-job-registry
          {:slow-job
           {:interval-ms 10
            :description "Slow job for concurrency testing"
            :handler (fn []
                       (when @slow-job-running
                         (swap! slow-job-concurrent-calls inc))
                       (reset! slow-job-running true)
                       (Thread/sleep 150)
                       (reset! slow-job-running false))}}]

      ;; Stop current scheduler and start new one with slow job
      (ig/halt-key! :scheduler/executor *scheduler*)

      (let [sched (ig/init-key :scheduler/executor
                               {:sqlite (sqlite)
                                :registry slow-job-registry
                                :tick-interval-ms 30})]
        (try
          ;; Wait long enough for multiple ticks during job execution
          (Thread/sleep 300)

          ;; Should never have had concurrent calls
          (is (zero? @slow-job-concurrent-calls)
              "Job should not run concurrently with itself")
          (finally
            (ig/halt-key! :scheduler/executor sched)))))))

(deftest get-all-jobs-returns-job-status-test
  (testing "get-job-status returns all registered jobs with their state"
    ;; Wait for job to run
    (Thread/sleep 200)

    (let [jobs (scheduler/get-job-status (sqlite))]
      (is (seq jobs) "Should return at least one job")
      (is (every? #(contains? % :job_name) jobs))
      (is (every? #(contains? % :interval_ms) jobs))
      (is (every? #(contains? % :last_run_at) jobs))
      (is (every? #(contains? % :enabled) jobs)))))

(deftest scheduler-deletes-orphaned-jobs-test
  (testing "Scheduler deletes jobs that are no longer in registry"
    ;; Stop current scheduler
    (ig/halt-key! :scheduler/executor *scheduler*)

    ;; Start scheduler with a different job, simulating removal of test-job
    (let [new-registry {:new-job {:interval-ms 100
                                  :description "Replacement job"
                                  :handler (fn [] nil)}}
          sched (ig/init-key :scheduler/executor
                             {:sqlite (sqlite)
                              :registry new-registry
                              :tick-interval-ms 50})]
      (try
        (let [jobs (scheduler/get-job-status (sqlite))
              job-names (set (map :job_name jobs))]
          (is (contains? job-names "new-job") "New job should be registered")
          (is (not (contains? job-names "test-job")) "Old job should be deleted"))
        (finally
          (ig/halt-key! :scheduler/executor sched))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.components.scheduler-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

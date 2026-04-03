;; ---------------------------------------------------------
;; o11ylite.integration.scheduled-jobs.parquet-compaction-test
;;
;; Integration tests for tiered parquet compaction.
;; Tests that the scheduler correctly registers and runs the compaction job,
;; and that tiered compaction works directly.
;; ---------------------------------------------------------

(ns o11ylite.integration.scheduled-jobs.parquet-compaction-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.components.scheduler :as scheduler]
    [o11ylite.kv :as kv]
    [o11ylite.store.ducklake :as ducklake]
    [o11ylite.test-helpers :as h])
  (:import
    [java.time Instant]
    [java.time.temporal ChronoUnit]))

;; Start scheduler for these tests
(use-fixtures :each (h/with-partial-system [:scheduler/executor]))

;; ---------------------------------------------------------
;; Helpers

(defn- sqlite
  []
  (:db/sqlite h/*system*))
(defn- duckdb
  []
  (:db/duckdb h/*system*))

(defn- get-compaction-job-status
  []
  (->> (scheduler/get-job-status (sqlite))
       (filter #(= "parquet-compaction" (:job_name %)))
       first))

(defn- tier-last-run
  [tier-name]
  (kv/get-value (sqlite) (ducklake/tier-kv-key tier-name)))

(def ^:private all-tiers-due
  "Tier intervals of 0 minutes — all tiers are always due."
  {:compaction-small-interval-minutes  0
   :compaction-medium-interval-minutes 0
   :compaction-large-interval-minutes  0})

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

    (let [job (get-compaction-job-status)]
      (is (some? (:last_run_at job)) "Job should have run")
      (is (some? (:last_success_at job)) "Job should have succeeded")
      (is (nil? (:last_error job)) "Job should have no error"))))

(deftest tiered-compaction-records-last-run-test
  (testing "run-tiered-compaction! records last-run timestamps for all tiers"
    (doseq [{:keys [tier-name]} ducklake/compaction-tiers]
      (is (nil? (tier-last-run tier-name))
          (str tier-name " should have no last-run before first compaction")))

    (ducklake/run-tiered-compaction! (duckdb) (sqlite) 5 all-tiers-due)

    (doseq [{:keys [tier-name]} ducklake/compaction-tiers]
      (let [last-run (tier-last-run tier-name)]
        (is (some? last-run)
            (str tier-name " should have a last-run timestamp"))
        (is (instance? Instant last-run)
            (str tier-name " last-run should be an Instant"))))))

(deftest tiered-compaction-skips-tiers-not-due-test
  (testing "Tiers with recent last-run are skipped"
    ;; Run once to seed all timestamps
    (ducklake/run-tiered-compaction! (duckdb) (sqlite) 5 all-tiers-due)

    (let [small-after-first  (tier-last-run :small)
          medium-after-first (tier-last-run :medium)
          large-after-first  (tier-last-run :large)]

      ;; Run again with long intervals — no tier should be due
      (ducklake/run-tiered-compaction! (duckdb) (sqlite) 5
                                       {:compaction-small-interval-minutes  9999
                                        :compaction-medium-interval-minutes 9999
                                        :compaction-large-interval-minutes  9999})

      (testing "Last-run timestamps should be unchanged when tiers are skipped"
        (is (= small-after-first  (tier-last-run :small)))
        (is (= medium-after-first (tier-last-run :medium)))
        (is (= large-after-first  (tier-last-run :large)))))))

(deftest tiered-compaction-respects-per-tier-intervals-test
  (testing "Only the tier whose interval has elapsed runs"
    ;; Seed: set small to old timestamp, medium and large to now
    (let [old-timestamp   (.minus (Instant/now) 10 ChronoUnit/MINUTES)
          _               (kv/set-value! (sqlite) (ducklake/tier-kv-key :small) old-timestamp)
          _               (kv/set-value! (sqlite) (ducklake/tier-kv-key :medium) (Instant/now))
          _               (kv/set-value! (sqlite) (ducklake/tier-kv-key :large) (Instant/now))
          medium-before   (tier-last-run :medium)
          large-before    (tier-last-run :large)]

      ;; Small interval is 5 min (elapsed), medium/large are 60 min (not elapsed)
      (ducklake/run-tiered-compaction! (duckdb) (sqlite) 5
                                       {:compaction-small-interval-minutes  5
                                        :compaction-medium-interval-minutes 60
                                        :compaction-large-interval-minutes  60})

      (testing "Small tier should have a fresh timestamp"
        (is (not= old-timestamp (tier-last-run :small))))
      (testing "Medium tier should be unchanged"
        (is (= medium-before (tier-last-run :medium))))
      (testing "Large tier should be unchanged"
        (is (= large-before (tier-last-run :large)))))))

(deftest manual-merge-adjacent-files-test
  (testing "Bounded merge returns 0 when nothing to compact"
    (is (zero? (ducklake/merge-adjacent-files!
                 (duckdb)
                 {:max-compacted-files 5
                  :target-file-size    "5MB"
                  :max-file-size       1048576}))))

  (testing "Unbounded merge (no max-compacted-files) returns 0 when nothing to compact"
    (is (zero? (ducklake/merge-adjacent-files!
                 (duckdb)
                 {:target-file-size "5MB"
                  :max-file-size    1048576})))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.scheduled-jobs.parquet-compaction-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

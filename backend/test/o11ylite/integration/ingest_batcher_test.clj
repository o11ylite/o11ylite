;; ---------------------------------------------------------
;; o11ylite.integration.ingest-batcher-test
;;
;; Integration tests for the ingest batcher component.
;; ---------------------------------------------------------

(ns o11ylite.integration.ingest-batcher-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [integrant.core :as ig]
   [o11ylite.components.ingest-batcher :as batcher]))

;; ---------------------------------------------------------
;; Tests

(deftest batcher-ingest-blocks-until-flush-test
  (testing "Ingest blocks until periodic flush occurs"
    (let [b (ig/init-key :ingest/batcher {:flush-interval-ms 100})
          started (atom false)
          result (promise)]
      (try
        ;; Start ingest in background - it should block
        (future
          (reset! started true)
          (deliver result (batcher/ingest! b {:type :span :name "test"})))
        ;; Wait for future to start
        (Thread/sleep 20)
        (is @started "Future should have started")
        (is (not (realized? result)) "Ingest should still be blocking")
        ;; Wait for periodic flush
        (Thread/sleep 150)
        (is (realized? result) "Ingest should complete after flush")
        (is (true? @result) "Ingest should return true on success")
        (finally
          (ig/halt-key! :ingest/batcher b))))))

(deftest batcher-concurrent-ingest-all-block-test
  (testing "Multiple concurrent ingests all block until flush"
    (let [b (ig/init-key :ingest/batcher {:flush-interval-ms 100})
          n 10
          results (atom [])]
      (try
        ;; Start n ingests concurrently
        (let [futures (doall
                       (for [i (range n)]
                         (future
                           (let [r (batcher/ingest! b {:id i})]
                             (swap! results conj r)
                             r))))]
          ;; None should be done yet
          (Thread/sleep 20)
          (is (< (count @results) n) "Not all should complete before flush")
          ;; Wait for flush
          (Thread/sleep 150)
          ;; All should be done now
          (doseq [f futures] @f)
          (is (= n (count @results)) "All should complete after flush")
          (is (every? true? @results) "All should return true"))
        (finally
          (ig/halt-key! :ingest/batcher b))))))

(deftest batcher-stop-flushes-and-unblocks-test
  (testing "Stopping batcher flushes remaining events and unblocks callers"
    (let [b (ig/init-key :ingest/batcher {:flush-interval-ms 60000})
          result (promise)]
      ;; Start ingest - will block for 60s normally
      (future
        (deliver result (batcher/ingest! b {:type :span :name "test"})))
      ;; Give it time to block
      (Thread/sleep 50)
      (is (not (realized? result)) "Should still be blocking")
      ;; Stop should flush and unblock
      (ig/halt-key! :ingest/batcher b)
      ;; Should complete now
      (is (deref result 1000 :timeout) "Should unblock after stop")
      (is (true? @result) "Should return true"))))

(deftest batcher-stop-idempotent-test
  (testing "Stopping batcher multiple times is safe"
    (let [b (ig/init-key :ingest/batcher {:flush-interval-ms 60000})]
      (batcher/stop! b)
      (batcher/stop! b)
      (batcher/stop! b)
      (is true "Multiple stops should not throw"))))

(deftest batcher-batch-accumulates-before-flush-test
  (testing "Events accumulate in batch before flush"
    (let [b (ig/init-key :ingest/batcher {:flush-interval-ms 60000})]
      (try
        ;; Start several ingests
        (dotimes [i 5]
          (future (batcher/ingest! b {:id i})))
        ;; Give time for events to reach the batch
        (Thread/sleep 100)
        ;; Batch should have events
        (is (= 5 (count @(:batch b))) "Batch should have 5 events")
        (finally
          (ig/halt-key! :ingest/batcher b))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.ingest-batcher-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

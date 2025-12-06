;; ---------------------------------------------------------
;; o11ylite.integration.ingest-batcher-test
;;
;; Integration tests for the ingest batcher component.
;; ---------------------------------------------------------

(ns o11ylite.integration.ingest-batcher-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.test-helpers :as h]
   [o11ylite.components.ingest-batcher :as batcher]
   [o11ylite.ducklake.events.ingest :as events.ingest]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helpers

(defn- batcher-component []
  (:ingest/batcher h/*system*))

(defn- make-payload
  "Create an ingest payload with events and optional fields map.
   Fields is a map of field-name -> {:type type-keyword}."
  ([events] (make-payload events nil))
  ([events fields]
   {:events events
    :fields fields}))

;; ---------------------------------------------------------
;; Tests

(deftest batcher-ingest-returns-true-test
  (testing "Ingest returns true after flush"
    (let [b (batcher-component)
          ;; Ingest blocks until flush, then returns
          result (batcher/ingest! b (make-payload [{:service "test" :name "span-1"}]))]
      (is (true? result) "Ingest should return true on success"))))

(deftest batcher-ingest-multiple-events-test
  (testing "Ingest accepts multiple events in one call"
    (let [b (batcher-component)
          events [{:service "test" :name "span-1"}
                  {:service "test" :name "span-2"}
                  {:service "test" :name "span-3"}]
          result (batcher/ingest! b (make-payload events))]
      (is (true? result) "Ingest should return true"))))

(deftest batcher-ingest-with-fields-test
  (testing "Ingest accepts events with fields map"
    (let [b (batcher-component)
          result (batcher/ingest! b (make-payload
                                     [{:service "test" :custom.field "value"}]
                                     {"service" {:type :string}
                                      "custom.field" {:type :string}}))]
      (is (true? result) "Ingest with fields should return true"))))

(deftest batcher-concurrent-ingest-test
  (testing "Multiple concurrent ingests all succeed"
    (let [b (batcher-component)
          n 10
          ;; Start n ingests concurrently, collect results
          futures (doall
                   (for [i (range n)]
                     (future (batcher/ingest! b (make-payload [{:id i}])))))
          ;; Wait for all and collect results
          results (mapv deref futures)]
      (is (= n (count results)) "All should complete")
      (is (every? true? results) "All should return true"))))

(deftest batcher-stop-idempotent-test
  (testing "Stopping batcher multiple times is safe"
    (let [b (batcher-component)]
      ;; First stop happens via fixture cleanup
      ;; These should be safe no-ops
      (batcher/stop! b)
      (batcher/stop! b)
      (is true "Multiple stops should not throw"))))

(deftest batcher-stop-flushes-pending-test
  (testing "Stop flushes pending events and unblocks callers"
    (let [b (batcher-component)
          result (promise)]
      ;; Start ingest in background
      (future
        (deliver result (batcher/ingest! b (make-payload [{:service "test"}]))))
      ;; Give it time to start blocking
      (Thread/sleep 20)
      ;; Stop should flush and unblock
      (batcher/stop! b)
      ;; Result should be delivered
      (is (true? (deref result 1000 false)) "Should return true after stop"))))

(deftest batcher-batches-multiple-ingests-test
  (testing "Multiple ingests are batched into a single persist-batch! call"
    (let [b (batcher-component)
          persist-calls (atom [])
          n 5]
      ;; Mock persist-batch! to capture calls
      (with-redefs [events.ingest/persist-batch!
                    (fn [_duckdb _event-metadata events fields]
                      (swap! persist-calls conj {:event-count (count events)
                                                 :field-count (count fields)})
                      true)]
        ;; Start n ingests concurrently - they should all be batched together
        (let [futures (doall
                       (for [i (range n)]
                         (future (batcher/ingest! b (make-payload [{:id i}]
                                                                  {"id" {:type :integer}})))))]
          ;; Wait for all to complete
          (doseq [f futures] @f)
          ;; Should have been batched into a single persist-batch! call
          ;; (or possibly 2 if timing causes a flush mid-accumulation)
          (is (<= (count @persist-calls) 2)
              "Should batch multiple ingests into few persist calls")
          ;; Total events across all calls should equal n
          (is (= n (reduce + (map :event-count @persist-calls)))
              "All events should be persisted"))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.ingest-batcher-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

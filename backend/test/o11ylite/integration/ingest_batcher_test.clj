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
   [o11ylite.ducklake.events.ingest :as events.ingest])
  (:import
   [java.time Instant]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helpers

(defn- batcher-component []
  (:ingest/batcher h/*system*))

(def ^:private base-fields
  "Fields for the minimal valid event structure."
  {:service {:type :string}
   :timestamp {:type :instant}
   :meta.signal_type {:type :string}
   :meta.observed_time {:type :instant}
   :name {:type :string}})

(defn- make-event
  "Create a minimal valid event with required fields."
  ([] (make-event {}))
  ([overrides]
   (merge {:service "test-service"
           :timestamp (Instant/now)
           :meta.signal_type :span
           :meta.observed_time (Instant/now)}
          overrides)))

(defn- make-payload
  "Create an ingest payload with events and fields map.
   Uses base-fields by default, merged with any extra fields."
  ([events] (make-payload events {}))
  ([events extra-fields]
   {:events events
    :fields (merge base-fields extra-fields)}))

;; ---------------------------------------------------------
;; Tests

(deftest batcher-ingest-returns-true-test
  (testing "Ingest returns true after flush"
    (let [b (batcher-component)
          result (batcher/ingest! b (make-payload [(make-event {:name "span-1"})]))]
      (is (true? result) "Ingest should return true on success"))))

(deftest batcher-ingest-multiple-events-test
  (testing "Ingest accepts multiple events in one call"
    (let [b (batcher-component)
          events [(make-event {:name "span-1"})
                  (make-event {:name "span-2"})
                  (make-event {:name "span-3"})]
          result (batcher/ingest! b (make-payload events))]
      (is (true? result) "Ingest should return true"))))

(deftest batcher-ingest-with-fields-test
  (testing "Ingest accepts events with fields map"
    (let [b (batcher-component)
          result (batcher/ingest! b (make-payload
                                     [(make-event {:attr.custom.field "value"})]
                                     {:attr.custom.field {:type :string}}))]
      (is (true? result) "Ingest with fields should return true"))))

(deftest batcher-concurrent-ingest-test
  (testing "Multiple concurrent ingests all succeed"
    (let [b (batcher-component)
          n 10
          futures (doall
                   (for [i (range n)]
                     (future (batcher/ingest! b (make-payload [(make-event {:name (str "span-" i)})])))))
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
        (deliver result (batcher/ingest! b (make-payload [(make-event)]))))
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
      ;; Mock persist-batch! to capture call counts (batching behavior test)
      (with-redefs [events.ingest/persist-batch!
                    (fn [_duckdb _event-metadata events fields]
                      (swap! persist-calls conj {:event-count (count events)
                                                 :field-count (count fields)})
                      true)]
        (let [futures (doall
                       (for [i (range n)]
                         (future (batcher/ingest! b (make-payload [(make-event {:name (str "span-" i)})])))))]
          (doseq [f futures] @f)
          ;; Should have been batched into few persist-batch! calls
          (is (<= (count @persist-calls) 2)
              "Should batch multiple ingests into few persist calls")
          (is (= n (reduce + (map :event-count @persist-calls)))
              "All events should be persisted"))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.ingest-batcher-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

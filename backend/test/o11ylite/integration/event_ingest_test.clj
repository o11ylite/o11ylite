;; ---------------------------------------------------------
;; o11ylite.integration.event-ingest-test
;;
;; Integration tests for event ingestion into DuckLake.
;; ---------------------------------------------------------

(ns o11ylite.integration.event-ingest-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [next.jdbc :as jdbc]
   [o11ylite.test-helpers :as h]
   [o11ylite.store.events.ingest :as events.ingest])
  (:import
   [java.time Instant]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helpers

(defn- duckdb [] (:db/duckdb h/*system*))
(defn- event-metadata [] (:cache/event-metadata h/*system*))
(defn- event-batcher [] (:ingest/event-batcher h/*system*))
(defn- id-generator [] (:id/generator h/*system*))

(defn- query-events
  "Query all events from DuckLake, ordered by name."
  []
  (jdbc/execute! (duckdb) ["SELECT * FROM o11ylite.events ORDER BY name"]))

(def ^:private test-id-counter (atom 0))

(defn- make-event
  "Create a valid event with required fields."
  [overrides]
  (merge {:id (swap! test-id-counter inc)
          :service "test-service"
          :timestamp (Instant/parse "2024-01-15T10:30:00Z")
          :error false
          :meta.signal_type :span
          :meta.observed_time (Instant/parse "2024-01-15T10:30:01Z")}
         overrides))

;; ---------------------------------------------------------
;; Tests

(deftest persist-batch-inserts-events-test
  (testing "Events are inserted with correct field values"
    (let [events [(make-event {:name "span-1" :trace_id "abc123"})
                  (make-event {:name "span-2" :trace_id "def456"})]
          fields {:id {:type :integer}
                  :service {:type :string}
                  :timestamp {:type :instant}
                  :error {:type :boolean}
                  :meta.signal_type {:type :string}
                  :meta.observed_time {:type :instant}
                  :name {:type :string}
                  :trace_id {:type :string}}]
      (events.ingest/persist-batch! (duckdb) (event-metadata) events fields)
      (let [rows (query-events)]
        (is (= 2 (count rows)))
        (is (= "span-1" (:name (first rows))))
        (is (= "span-2" (:name (second rows))))
        (is (= "abc123" (:trace_id (first rows))))
        (is (= "test-service" (:service (first rows))))))))

(deftest persist-batch-dynamic-fields-test
  (testing "Events with arbitrary dynamic fields are inserted correctly"
    (let [random-suffix (rand-int 100000)
          custom-field (keyword (str "attr.custom.field_" random-suffix))
          events [(make-event {:name "dynamic-span"
                               custom-field "dynamic-value"})]
          fields {:id {:type :integer}
                  :service {:type :string}
                  :timestamp {:type :instant}
                  :error {:type :boolean}
                  :meta.signal_type {:type :string}
                  :meta.observed_time {:type :instant}
                  :name {:type :string}
                  custom-field {:type :string}}]
      (events.ingest/persist-batch! (duckdb) (event-metadata) events fields)
      (let [rows (query-events)
            row (first rows)]
        (is (= 1 (count rows)))
        (is (= "dynamic-value" (get row custom-field)))))))

(deftest ingest-events-happy-path-test
  (testing "ingest-events! validates, extracts fields, and persists via batcher"
    (let [events [(make-event {:name "ingested-span-1"
                               :trace_id "trace-001"
                               :attr.http.method "GET"})
                  (make-event {:name "ingested-span-2"
                               :trace_id "trace-002"
                               :attr.http.status_code 200})]
          {:keys [success rejected-count]} (events.ingest/ingest-events! (event-metadata) (event-batcher) (id-generator) events)]
      (is (true? success) "ingest-events! should return success true")
      (is (= 0 rejected-count) "No events should be rejected")
      (let [rows (query-events)]
        (is (= 2 (count rows)))
        (is (= "ingested-span-1" (:name (first rows))))
        (is (= "ingested-span-2" (:name (second rows))))
        (is (= "trace-001" (:trace_id (first rows))))
        (is (= "GET" (:attr.http.method (first rows))))
        (is (= 200 (:attr.http.status_code (second rows))))))))

(deftest ingest-sample-events-helper-test
  (testing "ingest-sample-events! generates and persists random events"
    (let [n 5
          events (h/ingest-sample-events! n)
          rows (query-events)]
      (is (= n (count events)) "Should return the generated events")
      (is (= n (count rows)) "Should persist all events")
      (is (every? :service events) "Generated events have service")
      (is (every? :trace_id events) "Generated events have trace_id")
      (is (every? :name rows) "Persisted events have name"))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.event-ingest-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

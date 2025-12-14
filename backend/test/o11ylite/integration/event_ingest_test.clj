;; ---------------------------------------------------------
;; o11ylite.integration.event-ingest-test
;;
;; Integration tests for event ingestion into DuckLake.
;; Focuses on persist-batch! INSERT behavior.
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

(defn- query-events
  "Query all events from DuckLake, ordered by name."
  []
  (jdbc/execute! (duckdb) ["SELECT * FROM o11ylite.events ORDER BY name"]))

(defn- make-event
  "Create a valid event with required fields."
  [overrides]
  (merge {:service "test-service"
          :timestamp (Instant/parse "2024-01-15T10:30:00Z")
          :meta.signal_type :span
          :meta.observed_time (Instant/parse "2024-01-15T10:30:01Z")}
         overrides))

;; ---------------------------------------------------------
;; Tests

(deftest persist-batch-inserts-events-test
  (testing "Events are inserted with correct field values"
    (let [events [(make-event {:name "span-1" :trace_id "abc123"})
                  (make-event {:name "span-2" :trace_id "def456"})]
          fields {:service {:type :string}
                  :timestamp {:type :instant}
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
          fields {:service {:type :string}
                  :timestamp {:type :instant}
                  :meta.signal_type {:type :string}
                  :meta.observed_time {:type :instant}
                  :name {:type :string}
                  custom-field {:type :string}}]
      (events.ingest/persist-batch! (duckdb) (event-metadata) events fields)
      (let [rows (query-events)
            row (first rows)]
        (is (= 1 (count rows)))
        (is (= "dynamic-value" (get row custom-field)))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.event-ingest-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

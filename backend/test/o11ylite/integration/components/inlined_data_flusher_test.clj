;; ---------------------------------------------------------
;; o11ylite.integration.components.inlined-data-flusher-test
;;
;; Integration tests for inlined data flusher component.
;; ---------------------------------------------------------

(ns o11ylite.integration.components.inlined-data-flusher-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [next.jdbc :as jdbc]
   [o11ylite.store.ducklake :as ducklake]
   [o11ylite.store.events.ingest :as events.ingest]
   [o11ylite.test-helpers :as h])
  (:import
   [java.time Instant]))

;; Only start components needed for inlined data flusher
;; Note: We use persist-batch! directly instead of the batcher to avoid a
;; known DuckLake concurrency issue where INSERT + flush_inlined_data can
;; cause data duplication when running concurrently.
;; See: https://github.com/duckdb/ducklake/issues/650
(use-fixtures :each (h/with-partial-system [:ingest/inlined-data-flusher :cache/event-metadata]))

;; ---------------------------------------------------------
;; Helpers

(defn- duckdb [] (:db/duckdb h/*system*))
(defn- event-metadata [] (:cache/event-metadata h/*system*))

(defn- ingest-small-batch!
  "Ingest a small batch of events directly (will be inlined due to DATA_INLINING_ROW_LIMIT)."
  [service-name n]
  (let [now (Instant/now)
        events (for [i (range n)]
                 {:service service-name
                  :timestamp now
                  :meta.signal_type :span
                  :meta.observed_time now
                  :name (str "test-span-" i)})
        fields {:service {:type :string}
                :timestamp {:type :instant}
                :meta.signal_type {:type :string}
                :meta.observed_time {:type :instant}
                :name {:type :string}}]
    (events.ingest/persist-batch! (duckdb) (event-metadata) events fields)))

(defn- count-events
  "Count total events in the events table."
  []
  (-> (jdbc/execute-one! (duckdb) ["SELECT COUNT(*) AS cnt FROM events"])
      :cnt))

;; ---------------------------------------------------------
;; Tests

(deftest flush-inlined-data-manual-test
  (testing "Manual flush of inlined data succeeds"
    ;; Ingest a small batch (will be inlined)
    (ingest-small-batch! "test-service" 5)

    ;; Verify events were ingested
    (is (= 5 (count-events)))

    ;; Flush inlined data - should not error
    (ducklake/flush-inlined-data! (duckdb))

    ;; Events should still be queryable after flush
    (is (= 5 (count-events)))))

(deftest flush-inlined-data-periodic-test
  (testing "Periodic flush component runs without error"
    ;; Ingest some events
    (ingest-small-batch! "api-gateway" 3)
    (ingest-small-batch! "payment-service" 2)

    ;; Wait for background flush (test system uses 100ms interval)
    (Thread/sleep 200)

    ;; Events should still be queryable
    (is (= 5 (count-events)))))

(deftest flush-inlined-data-empty-test
  (testing "Flush succeeds even with no inlined data"
    ;; Flush with no data - should not error
    (is (nil? (ducklake/flush-inlined-data! (duckdb))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.components.inlined-data-flusher-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

;; ---------------------------------------------------------
;; o11ylite.integration.event-metadata-test
;;
;; Integration tests for the event metadata cache component.
;; ---------------------------------------------------------

(ns o11ylite.integration.event-metadata-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.test-helpers :as h]
   [o11ylite.components.event-metadata :as event-metadata]))

;; Only start the event metadata component and its dependencies
(use-fixtures :each (h/with-partial-system [:cache/event-metadata]))

;; ---------------------------------------------------------
;; Helper to get event metadata component from system

(defn- event-metadata-component []
  (:cache/event-metadata h/*system*))

;; ---------------------------------------------------------
;; Tests

(deftest event-metadata-initialized-test
  (testing "Event metadata is populated after system start"
    (let [em (event-metadata-component)
          fields (event-metadata/get-fields em)]
      (is (map? fields))
      (is (pos? (count fields)) "Should have fields from events table"))))

(deftest event-metadata-has-core-fields-test
  (testing "Event metadata contains expected core fields"
    (let [em (event-metadata-component)]
      ;; Core identity fields
      (is (some? (event-metadata/get-field em :service)))
      (is (some? (event-metadata/get-field em :timestamp)))
      ;; Trace context
      (is (some? (event-metadata/get-field em :trace_id)))
      (is (some? (event-metadata/get-field em :span_id)))
      ;; Signal type
      (is (some? (event-metadata/get-field em :meta.signal_type))))))

(deftest event-metadata-field-types-test
  (testing "Event metadata has normalized application-level types"
    (let [em (event-metadata-component)]
      (is (= :string (:type (event-metadata/get-field em :service))))
      (is (= :instant (:type (event-metadata/get-field em :timestamp))))
      (is (= :integer (:type (event-metadata/get-field em :span.duration_ns)))))))

(deftest event-metadata-refresh-async-test
  (testing "Async refresh returns promise with fields"
    (let [em (event-metadata-component)
          p (event-metadata/refresh! em)
          result (deref p 5000 {:ok false :error "timeout"})]
      (is (:ok result))
      (is (map? (:fields result)))
      (is (pos? (count (:fields result)))))))

(deftest event-metadata-get-nonexistent-field-test
  (testing "Getting nonexistent field returns nil"
    (let [em (event-metadata-component)]
      (is (nil? (event-metadata/get-field em :nonexistent_field_xyz))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.event-metadata-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

;; ---------------------------------------------------------
;; o11ylite.integration.components.events-schema-cache-test
;;
;; Integration tests for the events schema cache component.
;; ---------------------------------------------------------

(ns o11ylite.integration.components.events-schema-cache-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.test-helpers :as h]
    [o11ylite.components.events-schema-cache :as events-schema-cache]))

;; Only start the events schema cache component and its dependencies
(use-fixtures :each (h/with-partial-system [:cache/events-schema]))

;; ---------------------------------------------------------
;; Helper to get the events schema cache component from system

(defn- events-schema-component
  []
  (:cache/events-schema h/*system*))

;; ---------------------------------------------------------
;; Tests

(deftest events-schema-initialized-test
  (testing "Events schema is populated after system start"
    (let [esc (events-schema-component)
          fields (events-schema-cache/get-fields esc)]
      (is (map? fields))
      (is (pos? (count fields)) "Should have fields from events table"))))

(deftest events-schema-has-core-fields-test
  (testing "Events schema contains expected core fields"
    (let [esc (events-schema-component)]
      ;; Core identity fields
      (is (some? (events-schema-cache/get-field esc :service)))
      (is (some? (events-schema-cache/get-field esc :timestamp)))
      ;; Trace context
      (is (some? (events-schema-cache/get-field esc :trace_id)))
      (is (some? (events-schema-cache/get-field esc :span_id)))
      ;; Signal type
      (is (some? (events-schema-cache/get-field esc :meta.signal_type))))))

(deftest events-schema-field-types-test
  (testing "Events schema has normalized application-level types"
    (let [esc (events-schema-component)]
      (is (= :string (:type (events-schema-cache/get-field esc :service))))
      (is (= :instant (:type (events-schema-cache/get-field esc :timestamp))))
      (is (= :float (:type (events-schema-cache/get-field esc :span.duration_ms)))))))

(deftest events-schema-refresh-async-test
  (testing "Async refresh returns promise with fields"
    (let [esc (events-schema-component)
          p (events-schema-cache/refresh! esc)
          result (deref p 5000 {:ok false :error "timeout"})]
      (is (:ok result))
      (is (map? (:fields result)))
      (is (pos? (count (:fields result)))))))

(deftest events-schema-get-nonexistent-field-test
  (testing "Getting nonexistent field returns nil"
    (let [esc (events-schema-component)]
      (is (nil? (events-schema-cache/get-field esc :nonexistent_field_xyz))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.components.events-schema-cache-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

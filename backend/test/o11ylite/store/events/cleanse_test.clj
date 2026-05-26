;; ---------------------------------------------------------
;; o11ylite.store.events.cleanse-test
;;
;; Unit tests for event field cleansing.
;; ---------------------------------------------------------

(ns o11ylite.store.events.cleanse-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [o11ylite.store.events.cleanse :as cleanse]
    [o11ylite.store.schema :as schema]))

;; ---------------------------------------------------------
;; Tests

(deftest cleanse-events-test
  (testing "Fields with type conflicts are skipped, event is kept"
    (with-redefs [schema/fetch-event-fields
                  (constantly {:service {:type :string}
                               :attr.count {:type :integer}})]
      (let [events [{:service "test-service"
                     :attr.count "not-a-number"  ; type conflict: string vs integer
                     :attr.new-field "value"}]
            {:keys [events skipped-field-count]} (cleanse/cleanse-events :mock-duckdb #{} events)]
        (is (= 1 (count events)))
        (is (= "test-service" (:service (first events))))
        (is (nil? (:attr.count (first events))) "Conflicting field should be removed")
        (is (= "value" (:attr.new-field (first events))) "New fields pass through")
        (is (= 1 skipped-field-count)))))

  (testing "Fields with matching types pass through"
    (with-redefs [schema/fetch-event-fields
                  (constantly {:service {:type :string}
                               :attr.count {:type :integer}
                               :attr.active {:type :boolean}})]
      (let [events [{:service "test-service"
                     :attr.count 42          ; matches :integer
                     :attr.active true}]     ; matches :boolean
            {:keys [events skipped-field-count]} (cleanse/cleanse-events :mock-duckdb #{} events)]
        (is (= 1 (count events)))
        (is (= "test-service" (:service (first events))))
        (is (= 42 (:attr.count (first events))))
        (is (= true (:attr.active (first events))))
        (is (= 0 skipped-field-count)))))

  (testing "Blocked fields are stripped before type-conflict check"
    (with-redefs [schema/fetch-event-fields
                  (constantly {:service {:type :string}
                               :attr.http.method {:type :string}})]
      (let [events [{:service "test-service"
                     :attr.http.method "GET"
                     :attr.new-field "value"}]
            blocked #{"attr.http.method"}
            {:keys [events skipped-field-count]} (cleanse/cleanse-events :mock-duckdb blocked events)]
        (is (= 1 (count events)))
        (is (= "test-service" (:service (first events))))
        (is (nil? (:attr.http.method (first events))) "Blocked field should be removed")
        (is (= "value" (:attr.new-field (first events))) "Non-blocked fields pass through")
        (is (= 1 skipped-field-count))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.store.events.cleanse-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

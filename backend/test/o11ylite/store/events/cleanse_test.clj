;; ---------------------------------------------------------
;; o11ylite.store.events.cleanse-test
;;
;; Unit tests for event field cleansing.
;; ---------------------------------------------------------

(ns o11ylite.store.events.cleanse-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [o11ylite.components.event-metadata :as event-metadata]
    [o11ylite.store.events.cleanse :as cleanse]))

;; ---------------------------------------------------------
;; Tests

(deftest cleanse-events-test
  (testing "Fields with type conflicts are skipped, event is kept"
    (with-redefs [event-metadata/get-fields
                  (constantly {:service {:type :string}
                               :attr.count {:type :integer}})]
      (let [events [{:service "test-service"
                     :attr.count "not-a-number"  ; type conflict: string vs integer
                     :attr.new-field "value"}]
            {:keys [events skipped-field-count]} (cleanse/cleanse-events :mock-metadata events)]
        (is (= 1 (count events)))
        (is (= "test-service" (:service (first events))))
        (is (nil? (:attr.count (first events))) "Conflicting field should be removed")
        (is (= "value" (:attr.new-field (first events))) "New fields pass through")
        (is (= 1 skipped-field-count)))))

  (testing "Fields with matching types pass through"
    (with-redefs [event-metadata/get-fields
                  (constantly {:service {:type :string}
                               :attr.count {:type :integer}
                               :attr.active {:type :boolean}})]
      (let [events [{:service "test-service"
                     :attr.count 42          ; matches :integer
                     :attr.active true}]     ; matches :boolean
            {:keys [events skipped-field-count]} (cleanse/cleanse-events :mock-metadata events)]
        (is (= 1 (count events)))
        (is (= "test-service" (:service (first events))))
        (is (= 42 (:attr.count (first events))))
        (is (= true (:attr.active (first events))))
        (is (= 0 skipped-field-count))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.store.events.cleanse-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

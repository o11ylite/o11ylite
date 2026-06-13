;; ---------------------------------------------------------
;; o11ylite.alert-rule.fingerprint-test
;;
;; Canonicalization is the contract: the same group must hash
;; identically across evaluations, and distinct groups must not collide.
;; ---------------------------------------------------------

(ns o11ylite.alert-rule.fingerprint-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [o11ylite.alert-rule.fingerprint :as fp]))

(deftest empty-fingerprint-test
  (testing "no group-by -> empty fingerprint"
    (is (= "" (fp/fingerprint {})))
    (is (= "" (fp/fingerprint nil)))))

(deftest order-independence-test
  (testing "key order does not affect the fingerprint"
    (is (= (fp/fingerprint {:service "api" :region "us"})
           (fp/fingerprint {:region "us" :service "api"})))
    (is (= (fp/fingerprint {:a "1" :b "2" :c "3"})
           (fp/fingerprint {:c "3" :a "1" :b "2"})))))

(deftest key-type-normalization-test
  (testing "keyword and string column names hash the same"
    (is (= (fp/fingerprint {:service "api"})
           (fp/fingerprint {"service" "api"})))))

(deftest numeric-stability-test
  (testing "integral values agree regardless of numeric type"
    (is (= (fp/fingerprint {:code 1})
           (fp/fingerprint {:code 1.0})))
    (is (= (fp/fingerprint {:code (long 200)})
           (fp/fingerprint {:code (double 200.0)}))))
  (testing "non-integral values are distinct from their truncation"
    (is (not= (fp/fingerprint {:ratio 1.5})
              (fp/fingerprint {:ratio 1})))))

(deftest null-handling-test
  (testing "nil is distinct from empty string and from absence"
    (is (not= (fp/fingerprint {:host nil})
              (fp/fingerprint {:host ""})))
    (is (not= (fp/fingerprint {:host nil})
              (fp/fingerprint {})))))

(deftest distinct-groups-test
  (testing "different values produce different fingerprints"
    (is (not= (fp/fingerprint {:service "api"})
              (fp/fingerprint {:service "web"}))))
  (testing "value/key boundary cannot be forged by concatenation"
    ;; {:ab "c"} must not collide with {:a "bc"}
    (is (not= (fp/fingerprint {:ab "c"})
              (fp/fingerprint {:a "bc"})))))

(deftest boolean-and-shape-test
  (testing "booleans canonicalize to true/false strings, stably"
    (is (= (fp/fingerprint {:ok true})
           (fp/fingerprint {:ok true})))
    (is (not= (fp/fingerprint {:ok true})
              (fp/fingerprint {:ok false}))))
  (testing "fingerprint is a 64-char lowercase hex SHA-256"
    (let [f (fp/fingerprint {:a 1})]
      (is (= 64 (count f)))
      (is (re-matches #"[0-9a-f]{64}" f)))))

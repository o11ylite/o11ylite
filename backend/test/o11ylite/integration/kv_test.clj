;; ---------------------------------------------------------
;; o11ylite.integration.kv-test
;;
;; Integration tests for key-value store.
;; ---------------------------------------------------------

(ns o11ylite.integration.kv-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.test-helpers :as h]
    [o11ylite.kv :as kv]))

;; Only start storage (creates kv table) and sqlite
(use-fixtures :each (h/with-partial-system [:storage/init]))

;; ---------------------------------------------------------
;; Helpers

(defn- sqlite
  []
  (get-in h/*system* [:db/sqlite]))

;; ---------------------------------------------------------
;; Tests

(deftest get-set-value-test
  (testing "get-value returns nil for non-existent key"
    (is (nil? (kv/get-value (sqlite) "non-existent"))))

  (testing "set-value! and get-value round-trip with map"
    (kv/set-value! (sqlite) "test-map" {:foo "bar" :count 42})
    (is (= {:foo "bar" :count 42} (kv/get-value (sqlite) "test-map"))))

  (testing "set-value! and get-value round-trip with vector"
    (kv/set-value! (sqlite) "test-vec" [1 2 3 "four"])
    (is (= [1 2 3 "four"] (kv/get-value (sqlite) "test-vec"))))

  (testing "set-value! and get-value round-trip with string"
    (kv/set-value! (sqlite) "test-str" "hello world")
    (is (= "hello world" (kv/get-value (sqlite) "test-str"))))

  (testing "set-value! and get-value round-trip with number"
    (kv/set-value! (sqlite) "test-num" 12345)
    (is (= 12345 (kv/get-value (sqlite) "test-num")))))

(deftest upsert-test
  (testing "set-value! updates existing key"
    (kv/set-value! (sqlite) "upsert-key" {:version 1})
    (is (= {:version 1} (kv/get-value (sqlite) "upsert-key")))

    (kv/set-value! (sqlite) "upsert-key" {:version 2})
    (is (= {:version 2} (kv/get-value (sqlite) "upsert-key")))))

(deftest delete-value-test
  (testing "delete-value! removes existing key"
    (kv/set-value! (sqlite) "delete-me" {:temp true})
    (is (some? (kv/get-value (sqlite) "delete-me")))

    (kv/delete-value! (sqlite) "delete-me")
    (is (nil? (kv/get-value (sqlite) "delete-me"))))

  (testing "delete-value! is no-op for non-existent key"
    (kv/delete-value! (sqlite) "never-existed")
    (is (nil? (kv/get-value (sqlite) "never-existed")))))

(deftest complex-data-test
  (testing "handles nested data structures"
    (let [complex {:users [{:id 1 :name "Alice"}
                           {:id 2 :name "Bob"}]
                   :metadata {:created-at 1702000000000
                              :tags #{"a" "b" "c"}}}]
      (kv/set-value! (sqlite) "complex" complex)
      (is (= complex (kv/get-value (sqlite) "complex"))))))

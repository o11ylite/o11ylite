;; ---------------------------------------------------------
;; o11ylite.integration.components.api-key-cache-test
;;
;; Integration tests for the API key cache component.
;; ---------------------------------------------------------

(ns o11ylite.integration.components.api-key-cache-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.api-key :as api-key]
    [o11ylite.api-key.cache :as cache]
    [o11ylite.api-key.crypto :as crypto]
    [o11ylite.api-key.store :as store]
    [o11ylite.components.api-key-cache :as api-key-cache]
    [o11ylite.test-helpers :as h]))

;; Only start the api-key-cache component and its dependencies
(use-fixtures :each (h/with-partial-system [:auth/api-key-cache]))

;; ---------------------------------------------------------
;; Helpers

(defn- component
  []
  (:auth/api-key-cache h/*system*))
(defn- sqlite
  []
  (:db/sqlite h/*system*))

;; ---------------------------------------------------------
;; Tests

(deftest cache-starts-empty-test
  (testing "Cache starts empty when no keys in DB"
    (is (false? (api-key-cache/any-keys? (component))))))

(deftest cache-reflects-created-key-test
  (testing "Cache contains key after create + refresh"
    (let [key-data (crypto/generate-key)]
      (api-key/create! (sqlite) {:id "k1"
                                 :name "Test Key"
                                 :prefix (:prefix key-data)
                                 :key-hash (:key-hash key-data)
                                 :scope "ingest"})
      (api-key-cache/refresh! (component))

      (is (true? (api-key-cache/any-keys? (component))))
      ;; Validate via request to check lookup works
      (let [request {:headers {"authorization" (str "Bearer " (:key key-data))}}
            entry (api-key-cache/validate-request (component) request)]
        (is (some? entry))
        (is (= "k1" (:id entry)))
        (is (= "Test Key" (:name entry)))
        (is (= "ingest" (:scope entry)))))))

(deftest cache-reflects-deleted-key-test
  (testing "Cache removes key after delete + refresh"
    (let [key-data (crypto/generate-key)]
      (api-key/create! (sqlite) {:id "k2"
                                 :name "Ephemeral"
                                 :prefix (:prefix key-data)
                                 :key-hash (:key-hash key-data)
                                 :scope "read"})
      (api-key-cache/refresh! (component))
      (is (some? (api-key-cache/validate-token (component) (:key key-data))))

      (api-key/delete! (sqlite) "k2")
      (api-key-cache/refresh! (component))
      (is (nil? (api-key-cache/validate-token (component) (:key key-data)))))))

(deftest validate-request-returns-key-info-test
  (testing "validate-request resolves a valid Bearer token"
    (let [key-data (crypto/generate-key)]
      (api-key/create! (sqlite) {:id "k3"
                                 :name "Validator"
                                 :prefix (:prefix key-data)
                                 :key-hash (:key-hash key-data)
                                 :scope "write"})
      (api-key-cache/refresh! (component))

      (let [request {:headers {"authorization" (str "Bearer " (:key key-data))}}
            result (api-key-cache/validate-request (component) request)]
        (is (some? result))
        (is (= "k3" (:id result)))
        (is (= "write" (:scope result)))))))

(deftest validate-request-rejects-invalid-token-test
  (testing "validate-request returns nil for invalid token"
    (let [key-data (crypto/generate-key)]
      (api-key/create! (sqlite) {:id "k4"
                                 :name "Valid"
                                 :prefix (:prefix key-data)
                                 :key-hash (:key-hash key-data)
                                 :scope "admin"})
      (api-key-cache/refresh! (component))

      (let [request {:headers {"authorization" "Bearer o11y_bogus_token"}}
            result (api-key-cache/validate-request (component) request)]
        (is (nil? result))))))

(deftest validate-request-ignores-missing-header-test
  (testing "validate-request returns nil when no Authorization header"
    (let [request {:headers {}}
          result (api-key-cache/validate-request (component) request)]
      (is (nil? result)))))

(deftest last-used-throttle-test
  (testing "last_used_at is only written once within the throttle interval"
    (let [key-data (crypto/generate-key)]
      (api-key/create! (sqlite) {:id "k-throttle"
                                 :name "Throttle Test"
                                 :prefix (:prefix key-data)
                                 :key-hash (:key-hash key-data)
                                 :scope "ingest"})
      (api-key-cache/refresh! (component))

      ;; First validate — should trigger DB write (last_used_at was nil/0)
      (api-key-cache/validate-token (component) (:key key-data))
      ;; Allow the async future to complete
      (Thread/sleep 100)
      (let [row-after-first (store/get-by-id (sqlite) "k-throttle")]
        (is (some? (:last_used_at row-after-first))
            "First validation should write last_used_at")

        ;; Second validate immediately — should NOT update DB
        (api-key-cache/validate-token (component) (:key key-data))
        (Thread/sleep 100)
        (let [row-after-second (store/get-by-id (sqlite) "k-throttle")]
          (is (= (:last_used_at row-after-first) (:last_used_at row-after-second))
              "Rapid second validation should not update last_used_at")))))

  (testing "cache-level touch throttle returns true only when stale"
    (let [cache-atom (atom {"hash1" {:id "t1" :name "Test" :scope "ingest"
                                     :last-used-at 0}})
          now 120000]
      (is (true? (cache/touch-last-used! cache-atom "hash1" now))
          "Should return true when last-used-at is stale")
      (is (= now (get-in @cache-atom ["hash1" :last-used-at])))
      (is (false? (cache/touch-last-used! cache-atom "hash1" (+ now 1000)))
          "Should return false within throttle interval")
      (is (true? (cache/touch-last-used! cache-atom "hash1" (+ now 61000)))
          "Should return true after throttle interval expires"))))

;; ---------------------------------------------------------
;; o11ylite.integration.components.service-discovery-test
;;
;; Integration tests for service discovery component.
;; ---------------------------------------------------------

(ns o11ylite.integration.components.service-discovery-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.store.services :as services]
   [o11ylite.test-helpers :as h]))

;; Only start components needed for service discovery
(use-fixtures :each (h/with-partial-system [:discovery/services :cache/event-metadata :ingest/event-batcher]))

;; ---------------------------------------------------------
;; Helpers

(defn- sqlite [] (:db/sqlite h/*system*))
(defn- event-metadata [] (:cache/event-metadata h/*system*))
(defn- event-batcher [] (:ingest/event-batcher h/*system*))

;; ---------------------------------------------------------
;; Tests

(deftest service-discovery-test
  (testing "Service discovery component discovers services from telemetry"
    ;; Ingest events for two services with recent timestamps
    ;; (service discovery scan window is ~30s, so we need recent events)
    (let [now (java.time.Instant/now)]
      (h/ingest-sample-events! (event-metadata) (event-batcher) 1 {:service "api-gateway" :timestamp now})
      (h/ingest-sample-events! (event-metadata) (event-batcher) 1 {:service "payment-service" :timestamp now}))

    ;; Wait for background discovery (test system uses 100ms interval)
    (Thread/sleep 200)

    ;; Services should be registered
    (let [registered (services/get-services (sqlite))]
      (is (= 2 (count registered)))
      (is (= #{"api-gateway" "payment-service"}
             (set (map :name registered))))
      (is (every? :first_seen_at registered)))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.service-discovery-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

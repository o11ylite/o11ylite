;; ---------------------------------------------------------
;; o11ylite.integration.service-discovery-test
;;
;; Integration tests for service discovery component.
;; ---------------------------------------------------------

(ns o11ylite.integration.service-discovery-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.store.events.ingest :as events.ingest]
   [o11ylite.store.services :as services]
   [o11ylite.test-helpers :as h])
  (:import
   [java.time Instant]))

;; Only start components needed for service discovery
(use-fixtures :each (h/with-partial-system [:discovery/services :cache/event-metadata]))

;; ---------------------------------------------------------
;; Helpers

(defn- sqlite [] (:db/sqlite h/*system*))
(defn- duckdb [] (:db/duckdb h/*system*))
(defn- event-metadata [] (:cache/event-metadata h/*system*))

(defn- ingest-event!
  "Ingest a single event for a service."
  [service-name]
  (let [now (Instant/now)
        events [{:service service-name
                 :timestamp now
                 :meta.signal_type :span
                 :meta.observed_time now
                 :name "test-span"}]
        fields {:service {:type :string}
                :timestamp {:type :instant}
                :meta.signal_type {:type :string}
                :meta.observed_time {:type :instant}
                :name {:type :string}}]
    (events.ingest/persist-batch! (duckdb) (event-metadata) events fields)))

;; ---------------------------------------------------------
;; Tests

(deftest service-discovery-test
  (testing "Service discovery component discovers services from telemetry"
    ;; Ingest events for two services
    (ingest-event! "api-gateway")
    (ingest-event! "payment-service")

    ;; Wait for background discovery (test system uses 100ms interval)
    (Thread/sleep 200)

    ;; Services should be registered
    (let [registered (services/get-services (sqlite))]
      (is (= 2 (count registered)))
      (is (= #{"api-gateway" "payment-service"}
             (set (map :service registered))))
      (is (every? :first_seen_at registered)))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.service-discovery-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

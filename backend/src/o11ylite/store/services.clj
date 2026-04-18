;; ---------------------------------------------------------
;; o11ylite.store.services
;;
;; Service registry storage operations.
;; Services are discovered from telemetry and persist independently.
;; ---------------------------------------------------------

(ns o11ylite.store.services
  (:require
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -now-ms
  []
  (System/currentTimeMillis))

;; ---------------------------------------------------------
;; Public API

(defn scan-services
  "Scan DuckDB for distinct services in the scan window.
   Returns a list of service names."
  [duckdb scan-window-ms]
  (let [cutoff-ms (- (-now-ms) scan-window-ms)]
    (->> (jdbc/execute!
           duckdb
           ["SELECT DISTINCT service
            FROM o11ylite.events
            WHERE timestamp > (epoch_ms(?::BIGINT))::TIMESTAMP_NS"
            cutoff-ms]
           {:builder-fn rs/as-unqualified-lower-maps})
         (map :service))))

(defn register-services!
  "Register newly discovered services. Ignores already known services."
  [sqlite services]
  (let [now (-now-ms)]
    (jdbc/with-transaction [tx sqlite]
                           (doseq [service services]
                             (jdbc/execute!
                               tx
                               ["INSERT OR IGNORE INTO service_metadata (service, first_seen_at, updated_at)
           VALUES (?, ?, ?)"
                                service
                                now
                                now])))))

(defn get-services
  "Get all registered services.
   Returns a list of {:name, :first_seen_at, :updated_at, :last_seen_at}."
  [sqlite]
  (jdbc/execute!
    sqlite
    ["SELECT service AS name, first_seen_at, updated_at, last_seen_at
     FROM service_metadata
     ORDER BY service"]
    {:builder-fn rs/as-unqualified-lower-maps}))

(defn upsert-services!
  "Insert any services not yet in service_metadata, bumping last_seen_at for
   all supplied services (new and existing). `services` is a collection of
   service-name strings. Called from the telemetry-catalog buffer's sweep."
  [sqlite services now-ms]
  (when (seq services)
    (jdbc/with-transaction [tx sqlite]
                           (doseq [service services]
                             (jdbc/execute!
                               tx
                               ["INSERT INTO service_metadata (service, first_seen_at, updated_at, last_seen_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(service) DO UPDATE SET last_seen_at = excluded.last_seen_at"
                                service now-ms now-ms now-ms])))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])

  (def sqlite (:db/sqlite system))
  (def duckdb (:db/duckdb system))

  ;; Scan for services in last 5 minutes
  (def discovered (scan-services duckdb (* 5 60 1000)))
  ;; => ["api-gateway" "payment-service" ...]

  ;; Register new services
  (register-services! sqlite discovered)

  ;; Get all known services
  (get-services sqlite)
  ;; => [{:name "api-gateway" :first_seen_at 1702000000000 :updated_at 1702000000000 :last_seen_at 1702000100000} ...]

  ;; Bulk upsert + bump last_seen_at (called from telemetry-catalog sweep)
  (upsert-services! sqlite ["svc-a" "svc-b"] (System/currentTimeMillis))

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

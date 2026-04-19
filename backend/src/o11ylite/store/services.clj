;; ---------------------------------------------------------
;; o11ylite.store.services
;;
;; Service registry storage operations.
;; The service_metadata table is populated by the telemetry-catalog
;; buffer's periodic sweep (`upsert-services!`), not by scanning.
;; ---------------------------------------------------------

(ns o11ylite.store.services
  (:require
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [o11ylite.util.sql :as sql]))

;; ---------------------------------------------------------
;; Public API

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

(defn get-stale-services
  "Return the names of services whose `last_seen_at` is older than
   `threshold-ms` (epoch ms). Returned as a vector of strings, sorted.
   Candidates for deletion from service_metadata + cascade cleanup of
   service_metrics and service_event_fields.

   NULL `last_seen_at` is treated as stale: the column was added after
   initial schema creation, so a row with NULL here means the catalog
   sweep has never observed the service since the migration. In normal
   operation every live service gets its `last_seen_at` bumped on every
   sweep, so NULL is a reliable 'never seen again' signal."
  [sqlite threshold-ms]
  (->> (jdbc/execute!
         sqlite
         ["SELECT service
          FROM service_metadata
          WHERE last_seen_at IS NULL OR last_seen_at < ?
          ORDER BY service"
          threshold-ms]
         {:builder-fn rs/as-unqualified-lower-maps})
       (mapv :service)))

(defn delete-services!
  "Delete the named services from service_metadata AND cascade-remove all
   their rows from service_metrics and service_event_fields. Called by
   the telemetry-catalog GC. Single transaction."
  [sqlite service-names]
  (when (seq service-names)
    (let [placeholders (sql/in-placeholders (count service-names))
          params (vec service-names)]
      (jdbc/with-transaction [tx sqlite]
                             (jdbc/execute! tx (into [(str "DELETE FROM service_metrics WHERE service IN ("
                                                           placeholders ")")]
                                                     params))
                             (jdbc/execute! tx (into [(str "DELETE FROM service_event_fields WHERE service IN ("
                                                           placeholders ")")]
                                                     params))
                             (jdbc/execute! tx (into [(str "DELETE FROM service_metadata WHERE service IN ("
                                                           placeholders ")")]
                                                     params))))))

(defn get-services-with-counts
  "Get all registered services joined with per-service metric and event-field
   counts from the telemetry catalog. Services without catalog entries show
   zero counts (COALESCE).

   Returns a list of {:name :last_seen_at :metric_count :event_field_count}."
  [sqlite]
  (jdbc/execute!
    sqlite
    ["SELECT sm.service AS name,
             sm.last_seen_at,
             COALESCE(mc.c, 0) AS metric_count,
             COALESCE(fc.c, 0) AS event_field_count
     FROM service_metadata sm
     LEFT JOIN (SELECT service, COUNT(*) AS c
                FROM service_metrics
                GROUP BY service) mc ON mc.service = sm.service
     LEFT JOIN (SELECT service, COUNT(*) AS c
                FROM service_event_fields
                GROUP BY service) fc ON fc.service = sm.service
     ORDER BY sm.service"]
    {:builder-fn rs/as-unqualified-lower-maps}))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])

  (def sqlite (:db/sqlite system))

  ;; Get all known services
  (get-services sqlite)
  ;; => [{:name "api-gateway" :first_seen_at 1702000000000 :updated_at 1702000000000 :last_seen_at 1702000100000} ...]

  ;; Bulk upsert + bump last_seen_at (called from telemetry-catalog sweep)
  (upsert-services! sqlite ["svc-a" "svc-b"] (System/currentTimeMillis))

  ;; Service GC candidates — nothing seen for retention-days
  (get-stale-services sqlite (- (System/currentTimeMillis) (* 30 24 60 60 1000)))

  ;; Reclaim: cascades through service_metrics + service_event_fields too
  (delete-services! sqlite ["svc-gone"])

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

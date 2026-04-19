;; ---------------------------------------------------------
;; o11ylite.store.telemetry-catalog
;;
;; SQLite read/write operations for the telemetry catalog: service ↔ metric
;; and service ↔ event-field ownership + liveness.
;;
;; This store is the persistent backing for the telemetry-catalog-buffer
;; component. The buffer component owns the hot-path accumulation and the
;; periodic sweep; this namespace is the thin SQLite layer the sweep calls
;; into.
;;
;; Not to be confused with:
;;   - o11ylite.store.services        — CRUD on service_metadata itself
;;   - o11ylite.store.metrics.metadata — per-metric definitions (what a
;;                                       metric IS, keyed by metric name)
;;   - :cache/event-metadata           — DuckDB column schema cache for
;;                                       the events table
;;
;; This namespace owns the "WHO emits WHAT and WHEN last seen" view.
;; ---------------------------------------------------------

(ns o11ylite.store.telemetry-catalog
  (:require
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [o11ylite.util.sql :as sql]))

;; ---------------------------------------------------------
;; Reads

(defn get-all-service-metrics
  "Load the full (service, metric_name) set from SQLite.
   Returns a set of [service metric-name] tuples. Used to hydrate the
   in-memory cache at startup."
  [sqlite]
  (->> (jdbc/execute! sqlite
                      ["SELECT service, metric_name FROM service_metrics"]
                      {:builder-fn rs/as-unqualified-lower-maps})
       (map (juxt :service :metric_name))
       (into #{})))

(defn get-all-service-event-fields
  "Load the full {service -> #{field ...}} map from SQLite. Used to
   hydrate the in-memory cache at startup."
  [sqlite]
  (->> (jdbc/execute! sqlite
                      ["SELECT service, field FROM service_event_fields"]
                      {:builder-fn rs/as-unqualified-lower-maps})
       (reduce (fn [acc {:keys [service field]}]
                 (update acc service (fnil conj #{}) field))
               {})))

(defn get-service-metrics
  "Get the metric names emitted by a specific service.
   Returns a sorted vector of strings."
  [sqlite service]
  (->> (jdbc/execute! sqlite
                      ["SELECT metric_name FROM service_metrics
                        WHERE service = ? ORDER BY metric_name"
                       service]
                      {:builder-fn rs/as-unqualified-lower-maps})
       (mapv :metric_name)))

(defn get-service-event-fields
  "Get the event fields emitted by a specific service.
   Returns a sorted vector of strings."
  [sqlite service]
  (->> (jdbc/execute! sqlite
                      ["SELECT field FROM service_event_fields
                        WHERE service = ? ORDER BY field"
                       service]
                      {:builder-fn rs/as-unqualified-lower-maps})
       (mapv :field)))

(defn get-metric-services
  "Get the services emitting a specific metric.
   Returns a sorted vector of service names."
  [sqlite metric-name]
  (->> (jdbc/execute! sqlite
                      ["SELECT service FROM service_metrics
                        WHERE metric_name = ? ORDER BY service"
                       metric-name]
                      {:builder-fn rs/as-unqualified-lower-maps})
       (mapv :service)))

;; ---------------------------------------------------------
;; Writes
;;
;; All writes are called from the telemetry-catalog-buffer's sweep thread
;; (single writer), so no concurrency concerns within the process.

(defn upsert-service-metrics!
  "Upsert (service, metric_name, last_seen_at) rows. On conflict, bumps
   last_seen_at only. Called during sweep for all observed pairs (both new
   and existing) so stale pairs can be identified later by their older
   last_seen_at."
  [sqlite entries]
  (when (seq entries)
    (jdbc/with-transaction [tx sqlite]
                           (doseq [{:keys [service metric-name last-seen-at]} entries]
                             (jdbc/execute!
                               tx
                               ["INSERT INTO service_metrics (service, metric_name, last_seen_at)
            VALUES (?, ?, ?)
            ON CONFLICT(service, metric_name) DO UPDATE SET
              last_seen_at = excluded.last_seen_at"
                                service metric-name last-seen-at])))))

(defn upsert-service-event-fields!
  "Upsert (service, field, last_seen_at) rows. Same semantics as
   upsert-service-metrics!."
  [sqlite entries]
  (when (seq entries)
    (jdbc/with-transaction [tx sqlite]
                           (doseq [{:keys [service field last-seen-at]} entries]
                             (jdbc/execute!
                               tx
                               ["INSERT INTO service_event_fields (service, field, last_seen_at)
            VALUES (?, ?, ?)
            ON CONFLICT(service, field) DO UPDATE SET
              last_seen_at = excluded.last_seen_at"
                                service field last-seen-at])))))

;; ---------------------------------------------------------
;; GC foundation (unused for now, exposed for future GC work)
;;
;; GC operates on fields and metrics, not on (service, field) or
;; (service, metric) pairs. A field is reclaimable only when NO service
;; has emitted it recently; same for a metric. These queries aggregate
;; across services and return the field/metric names whose most-recent
;; emitter is older than the threshold.

(defn get-stale-metrics
  "Return metric names whose most-recent emitter across all services has
   `last_seen_at` older than `threshold-ms` (epoch ms). These metrics are
   candidates for deletion from metrics_metadata."
  [sqlite threshold-ms]
  (->> (jdbc/execute! sqlite
                      ["SELECT metric_name
                        FROM service_metrics
                        GROUP BY metric_name
                        HAVING MAX(last_seen_at) < ?"
                       threshold-ms]
                      {:builder-fn rs/as-unqualified-lower-maps})
       (mapv :metric_name)))

(defn get-stale-event-fields
  "Return event field names whose most-recent emitter across all services
   has `last_seen_at` older than `threshold-ms` (epoch ms). These fields
   are candidates for column drop from the events table."
  [sqlite threshold-ms]
  (->> (jdbc/execute! sqlite
                      ["SELECT field
                        FROM service_event_fields
                        GROUP BY field
                        HAVING MAX(last_seen_at) < ?"
                       threshold-ms]
                      {:builder-fn rs/as-unqualified-lower-maps})
       (mapv :field)))

(defn delete-metrics!
  "Delete rows from service_metrics matching any of the given metric
   names. Used by the GC job after the backing metrics_metadata rows
   are gone, but generic enough to call wherever catalog rows need to
   be removed by metric name."
  [sqlite metric-names]
  (when (seq metric-names)
    (let [stmt (str "DELETE FROM service_metrics WHERE metric_name IN ("
                    (sql/in-placeholders (count metric-names)) ")")]
      (jdbc/execute! sqlite (into [stmt] metric-names)))))

(defn delete-event-fields!
  "Delete rows from service_event_fields matching any of the given field
   names. Used by the GC job after the backing DuckDB columns are
   dropped, but generic enough to call wherever catalog rows need to be
   removed by field name."
  [sqlite field-names]
  (when (seq field-names)
    (let [stmt (str "DELETE FROM service_event_fields WHERE field IN ("
                    (sql/in-placeholders (count field-names)) ")")]
      (jdbc/execute! sqlite (into [stmt] field-names)))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])
  (def sqlite (:db/sqlite system))

  (get-all-service-metrics sqlite)
  (get-all-service-event-fields sqlite)

  (upsert-service-metrics! sqlite
                           [{:service "svc-a"
                             :metric-name "cpu.utilization"
                             :last-seen-at (System/currentTimeMillis)}])

  (upsert-service-event-fields! sqlite
                                [{:service "svc-a"
                                  :field "attr.http.method"
                                  :last-seen-at (System/currentTimeMillis)}])

  (get-service-metrics sqlite "svc-a")
  (get-service-event-fields sqlite "svc-a")
  (get-metric-services sqlite "cpu.utilization")

  ;; GC candidates — fields/metrics where every emitter is stale
  (get-stale-metrics sqlite (- (System/currentTimeMillis) (* 7 24 60 60 1000)))
  (get-stale-event-fields sqlite (- (System/currentTimeMillis) (* 7 24 60 60 1000)))

  ;; Remove catalog rows after the backing metric/column is gone
  (delete-metrics! sqlite ["dead.metric"])
  (delete-event-fields! sqlite ["attr.dead.field"])

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

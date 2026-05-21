;; ---------------------------------------------------------
;; o11ylite.components.ducklake-metrics
;;
;; Publishes DuckLake parquet-file stats as OTel gauge metrics.
;;
;; Registers two async gauges, each keyed by table and file type:
;;   o11ylite.ducklake.files{table=..., type=data|delete}
;;   o11ylite.ducklake.bytes{table=..., type=data|delete}
;;
;; Both are sourced from a single `ducklake_table_info('o11ylite')` query,
;; which returns per-table file counts and sizes for both data files and
;; delete files. The query hits the DuckLake catalog metadata (SQLite),
;; not object storage, so it is cheap enough to run on every OTel export
;; tick without caching.
;;
;; The `type` dimension follows the same pattern as `o11ylite.duckdb.memory`:
;; consumers who want the total should aggregate with `sum(...) without (type)`
;; rather than `avg`.
;; ---------------------------------------------------------

(ns o11ylite.components.ducklake-metrics
  (:require
    [integrant.core :as ig]
    [com.brunobonacci.mulog :as mulog]
    [next.jdbc :as jdbc]
    [o11ylite.util.telemetry :as telemetry]
    [steffan-westcott.clj-otel.api.metrics.instrument :as instrument]))

;; ---------------------------------------------------------
;; Private Helpers

(def ^:private -catalog "o11ylite")

(defn- -fetch-table-info
  "Query ducklake_table_info() and return a seq of per-table rows.
   Returns an empty seq on failure so the callback never throws into the
   OTel SDK."
  [duckdb]
  (try
    (jdbc/execute! duckdb
                   [(str "SELECT table_name, file_count, file_size_bytes,"
                         " delete_file_count, delete_file_size_bytes"
                         " FROM ducklake_table_info('" -catalog "')")])
    (catch Exception e
      (telemetry/report-error! ::ducklake-table-info-query-error e)
      [])))

(defn- -rows->measurements
  "Flatten ducklake_table_info rows into measurements for the given value key.
   Each row produces two measurements: one for data files and one for delete files."
  [rows data-key delete-key]
  (into []
        (mapcat (fn [row]
                  (let [table (:table_name row)]
                    [{:value (get row data-key 0)
                      :attributes {:o11ylite.ducklake.table table
                                   :o11ylite.ducklake.file.type "data"}}
                     {:value (get row delete-key 0)
                      :attributes {:o11ylite.ducklake.table table
                                   :o11ylite.ducklake.file.type "delete"}}])))
        rows))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :metrics/ducklake
  [_ {:keys [duckdb]}]
  (let [files-gauge (instrument/instrument
                      {:name "o11ylite.ducklake.files"
                       :instrument-type :gauge
                       :measurement-type :long
                       :unit "{file}"
                       :description "DuckLake parquet file count by table and file type"}
                      (fn []
                        (-rows->measurements (-fetch-table-info duckdb)
                                             :file_count
                                             :delete_file_count)))
        bytes-gauge (instrument/instrument
                      {:name "o11ylite.ducklake.bytes"
                       :instrument-type :gauge
                       :measurement-type :long
                       :unit "By"
                       :description "DuckLake parquet byte size by table and file type"}
                      (fn []
                        (-rows->measurements (-fetch-table-info duckdb)
                                             :file_size_bytes
                                             :delete_file_size_bytes)))]
    (mulog/log ::ducklake-metrics-started)
    {:files-gauge files-gauge
     :bytes-gauge bytes-gauge}))

(defmethod ig/halt-key! :metrics/ducklake
  [_ {:keys [files-gauge bytes-gauge]}]
  (.close ^java.lang.AutoCloseable files-gauge)
  (.close ^java.lang.AutoCloseable bytes-gauge)
  (mulog/log ::ducklake-metrics-stopped))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.core :as ig]
           '[integrant.repl.state :as state])

  ;; Start manually
  (def gauges
    (ig/init-key :metrics/ducklake {:duckdb (:db/duckdb-reader state/system)}))

  ;; Inspect what the callback would emit
  (-rows->measurements (-fetch-table-info (:db/duckdb-reader state/system))
                       :file_count
                       :delete_file_count)

  ;; Stop
  (ig/halt-key! :metrics/ducklake gauges)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

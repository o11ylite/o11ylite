;; ---------------------------------------------------------
;; o11ylite.components.storage-init
;;
;; Storage initialization component
;; Detects installation state and runs appropriate migrations
;; ---------------------------------------------------------

(ns o11ylite.components.storage-init
  (:require
    [integrant.core :as ig]
    [com.brunobonacci.mulog :as mulog]
    [migratus.core :as migratus]
    [o11ylite.store.init :as store]))

;; ---------------------------------------------------------
;; Constants

(def migrations-table
  "Name of the migrations tracking table in SQLite."
  "_o11ylite_migrations")

(def migrations-dir
  "Directory containing migration files (under resources/)."
  "migrations/")

;; ---------------------------------------------------------
;; Private Helpers

(defn- -migratus-config
  "Build migratus configuration using the SQLite datasource."
  [sqlite-ds]
  {:store :database
   :migration-dir migrations-dir
   :migration-table-name migrations-table
   :db {:datasource sqlite-ds}})

(defn- -run-migrations
  "Run all pending SQLite migrations."
  [sqlite-ds]
  (mulog/log ::run-migrations-starting)
  (let [config (-migratus-config sqlite-ds)]
    (migratus/migrate config)
    (mulog/log ::run-migrations-completed)))

(defn- -init-duckdb
  "Initialize DuckDB schema (idempotent)."
  [writer-events writer-metrics core-config]
  (store/init-store! writer-events writer-metrics core-config))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :storage/init
  [_ {:keys [sqlite duckdb-writer-events duckdb-writer-metrics core-config]}]
  (mulog/log ::storage-init-starting)
  (-run-migrations sqlite)
  (-init-duckdb duckdb-writer-events duckdb-writer-metrics core-config)
  (mulog/log ::storage-init-completed)
  nil)

(defmethod ig/halt-key! :storage/init
  [_ _]
  ;; Nothing to clean up
  nil)

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Test storage init manually
  (require '[next.jdbc :as jdbc]
           '[migratus.core :as migratus]
           '[integrant.repl.state :refer [system]])

  (def sqlite-ds (:db/sqlite system))
  (def duckdb-ds (:db/duckdb-reader system))

  ;; Test migratus config
  (def config (-migratus-config sqlite-ds))

  ;; Run migrations
  (migratus/migrate config)

  ;; Check pending migrations
  (migratus/pending-list config)

  ;; Check SQLite tables
  (jdbc/execute! sqlite-ds ["SELECT name FROM sqlite_master WHERE type='table'"])

  ;; Check DuckDB events table
  (jdbc/execute! duckdb-ds ["SHOW TABLES"])
  (jdbc/execute! duckdb-ds ["DESCRIBE o11ylite.events"])
  (ig/halt-key! :db/sqlite sqlite-ds)
  (ig/halt-key! :db/duckdb-reader duckdb-ds)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

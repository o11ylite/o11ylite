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
   [next.jdbc :as jdbc]))

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

(defn- migratus-config
  "Build migratus configuration using the SQLite datasource."
  [sqlite-ds]
  {:store :database
   :migration-dir migrations-dir
   :init-script "init.sql"
   :init-in-transaction? true
   :migration-table-name migrations-table
   :db {:datasource sqlite-ds}})

(defn- migrations-table-exists?
  "Check if the migrations table exists in SQLite."
  [sqlite-ds]
  (let [result (jdbc/execute-one! sqlite-ds
                 ["SELECT name FROM sqlite_master
                   WHERE type='table' AND name=?"
                  migrations-table])]
    (some? result)))

(defn- init-storage
  "Initialize storage for a fresh installation.
   Runs migratus/init to execute init.sql, then runs all migrations."
  [sqlite-ds _duckdb-ds]
  (mulog/log ::init-storage-starting)
  (let [config (migratus-config sqlite-ds)]
    (migratus/init config)
    (mulog/log ::migratus-init-completed)
    (migratus/migrate config)
    (mulog/log ::init-storage-completed)))

(defn- pickup-migration
  "Run pending migrations for an existing installation."
  [sqlite-ds _duckdb-ds]
  (mulog/log ::pickup-migration-starting)
  (let [config (migratus-config sqlite-ds)]
    (migratus/migrate config)
    (mulog/log ::pickup-migration-completed)))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :storage/init
  [_ {:keys [sqlite duckdb]}]
  (mulog/log ::storage-init-starting)
  (let [fresh? (not (migrations-table-exists? sqlite))]
    (if fresh?
      (do
        (mulog/log ::fresh-installation-detected)
        (init-storage sqlite duckdb))
      (do
        (mulog/log ::existing-installation-detected)
        (pickup-migration sqlite duckdb)))
    (mulog/log ::storage-init-completed :fresh? fresh?)
    {:fresh? fresh?}))

(defmethod ig/halt-key! :storage/init
  [_ _]
  ;; Nothing to clean up
  nil)

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Test storage init manually
  (require '[integrant.core :as ig]
           '[next.jdbc :as jdbc]
           '[migratus.core :as migratus])

  ;; Start dependencies first
  (def sqlite-ds
    (ig/init-key :db/sqlite {:data-path "./.tmp"}))

  (def duckdb-ds
    (ig/init-key :db/duckdb {:data-path "./.tmp"}))

  ;; Check if migrations table exists
  (migrations-table-exists? sqlite-ds)

  ;; Test migratus config
  (def config (migratus-config sqlite-ds))

  ;; Initialize (runs init.sql)
  (migratus/init config)

  ;; Run migrations
  (migratus/migrate config)

  ;; Check pending migrations
  (migratus/pending-list config)

  ;; Start storage init component
  (def storage
    (ig/init-key :storage/init {:sqlite sqlite-ds :duckdb duckdb-ds}))

  ;; Cleanup
  (ig/halt-key! :storage/init storage)
  (ig/halt-key! :db/sqlite sqlite-ds)
  (ig/halt-key! :db/duckdb duckdb-ds)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

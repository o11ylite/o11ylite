;; ---------------------------------------------------------
;; o11ylite.components.duckdb-pool
;;
;; DuckDB connection pool component using HikariCP and next.jdbc
;; Each connection initializes DuckLake extension via connectionInitSql
;; ---------------------------------------------------------

(ns o11ylite.components.duckdb-pool
  (:require
   [integrant.core :as ig]
   [com.brunobonacci.mulog :as mulog]
   [next.jdbc :as jdbc]
   [next.jdbc.connection :as connection])
  (:import
   [com.zaxxer.hikari HikariDataSource]
   [java.io File]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- ensure-data-dir!
  "Ensure the data directory exists, creating it if necessary."
  [data-path]
  (let [dir (File. data-path)]
    (when-not (.exists dir)
      (.mkdirs dir)
      (mulog/log ::data-dir-created :path data-path))))

(defn- ducklake-path
  "Construct the DuckLake database file path."
  [data-path]
  (str data-path "/o11ylite.ducklake"))

(defn- connection-init-sql
  "Build SQL that runs on each new connection to initialize DuckLake.
   Installs/loads the extension and attaches the DuckLake database."
  [ducklake-file]
  (str "INSTALL ducklake; "
       "LOAD ducklake; "
       "ATTACH 'ducklake:" ducklake-file "' AS ducklake; "
       "USE ducklake;"))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :db/duckdb
  [_ {:keys [data-path]}]
  (mulog/log ::duckdb-pool-starting :data-path data-path)
  (ensure-data-dir! data-path)
  (let [ducklake-file (ducklake-path data-path)
        init-sql (connection-init-sql ducklake-file)
        datasource (connection/->pool HikariDataSource
                                      {:jdbcUrl "jdbc:duckdb:"
                                       :maximumPoolSize 10
                                       :minimumIdle 2
                                       :connectionTimeout 30000
                                       :idleTimeout 600000
                                       :maxLifetime 1800000
                                       :poolName "duckdb-pool"
                                       :connectionInitSql init-sql
                                       :connectionTestQuery "SELECT 1"})]
    (try
      ;; Validate connection pool and init SQL by getting a connection
      (.close (jdbc/get-connection datasource))
      (mulog/log ::duckdb-pool-started
                 :data-path data-path
                 :ducklake-file ducklake-file)
      datasource
      (catch Exception e
        (.close datasource)
        (throw e)))))

(defmethod ig/halt-key! :db/duckdb
  [_ ^HikariDataSource datasource]
  (mulog/log ::duckdb-pool-stopping)
  (.close datasource)
  (mulog/log ::duckdb-pool-stopped))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Test DuckDB pool manually
  (require '[integrant.core :as ig]
           '[next.jdbc :as jdbc])

  ;; Start the pool
  (def ds
    (ig/init-key :db/duckdb {:data-path "./.tmp"}))

  ;; Run queries using next.jdbc - DuckLake is already attached and active
  (jdbc/execute! ds ["SELECT 42 AS answer"])
  ;; => [{:answer 42}]

  (jdbc/execute-one! ds ["SELECT 42 AS answer"])
  ;; => {:answer 42}

  ;; Check current database (should be ducklake)
  (jdbc/execute! ds ["SELECT current_database()"])

  ;; Check DuckLake is attached
  (jdbc/execute! ds ["SHOW DATABASES"])

  ;; Concurrent writes to different tables work
  (do
    (future (jdbc/execute! ds ["SELECT 1"]))
    (future (jdbc/execute! ds ["SELECT 2"])))

  ;; Use transactions
  (jdbc/with-transaction [tx ds]
    (jdbc/execute! tx ["SELECT 1"])
    (jdbc/execute! tx ["SELECT 2"]))

  ;; Stop the pool
  (ig/halt-key! :db/duckdb ds)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

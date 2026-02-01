;; ---------------------------------------------------------
;; o11ylite.components.sqlite-pool
;;
;; SQLite connection pool component using HikariCP and next.jdbc
;; Configured with WAL mode and best practices for concurrency
;; ---------------------------------------------------------

(ns o11ylite.components.sqlite-pool
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

(defn- sqlite-path
  "Construct the SQLite database file path."
  [data-path]
  (str data-path "/o11ylite.sqlite"))

;; SQLite best practices for concurrent access:
;; - WAL mode: allows concurrent reads during writes
;; - busy_timeout: wait up to 5s for locks instead of failing immediately
;; - synchronous=NORMAL: good balance of safety and performance with WAL
;; - cache_size: negative value = KB, use 64MB cache
;; - foreign_keys: enforce referential integrity
;; - temp_store=MEMORY: keep temp tables in memory
(def ^:private connection-init-sql
  "PRAGMA journal_mode=WAL;
   PRAGMA busy_timeout=5000;
   PRAGMA synchronous=NORMAL;
   PRAGMA cache_size=-65536;
   PRAGMA foreign_keys=ON;
   PRAGMA temp_store=MEMORY;")

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :db/sqlite
  [_ {:keys [core-config]}]
  (let [data-path (:data-path core-config)]
    (mulog/log ::sqlite-pool-starting :data-path data-path)
    (ensure-data-dir! data-path)
    (let [db-file (sqlite-path data-path)
          jdbc-url (str "jdbc:sqlite:" db-file)
          datasource (connection/->pool HikariDataSource
                                        {:jdbcUrl jdbc-url
                                         :maximumPoolSize 10
                                         :minimumIdle 2
                                         :connectionTimeout 30000
                                         :idleTimeout 600000
                                         :maxLifetime 1800000
                                         :poolName "sqlite-pool"
                                         :connectionInitSql connection-init-sql
                                         :connectionTestQuery "SELECT 1"})]
      (try
        ;; Validate connection pool and init SQL by getting a connection
        (.close (jdbc/get-connection datasource))
        (mulog/log ::sqlite-pool-started
                   :data-path data-path
                   :db-file db-file)
        datasource
        (catch Exception e
          (.close datasource)
          (throw e))))))

(defmethod ig/halt-key! :db/sqlite
  [_ ^HikariDataSource datasource]
  (mulog/log ::sqlite-pool-stopping)
  (.close datasource)
  (mulog/log ::sqlite-pool-stopped))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Test SQLite pool manually
  (require '[integrant.core :as ig]
           '[next.jdbc :as jdbc])

  ;; Start the pool
  (def ds
    (ig/init-key :db/sqlite {:data-path "./.tmp"}))

  ;; Run queries using next.jdbc
  (jdbc/execute! ds ["SELECT 42 AS answer"])
  ;; => [{:answer 42}]

  (jdbc/execute-one! ds ["SELECT 42 AS answer"])
  ;; => {:answer 42}

  ;; Check WAL mode is enabled
  (jdbc/execute! ds ["PRAGMA journal_mode"])
  ;; => [{:journal_mode "wal"}]

  ;; Check other pragmas
  (jdbc/execute! ds ["PRAGMA foreign_keys"])
  (jdbc/execute! ds ["PRAGMA synchronous"])
  (jdbc/execute! ds ["PRAGMA busy_timeout"])

  ;; Create a test table
  (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS test (id INTEGER PRIMARY KEY, name TEXT)"])
  (jdbc/execute! ds ["INSERT INTO test (name) VALUES (?)" "hello"])
  (jdbc/execute! ds ["SELECT * FROM test"])

  ;; Use transactions
  (jdbc/with-transaction [tx ds]
    (jdbc/execute! tx ["INSERT INTO test (name) VALUES (?)" "world"])
    (jdbc/execute! tx ["SELECT * FROM test"]))

  ;; Stop the pool
  (ig/halt-key! :db/sqlite ds)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

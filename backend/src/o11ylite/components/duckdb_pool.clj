;; ---------------------------------------------------------
;; o11ylite.components.duckdb-pool
;;
;; DuckDB connection pool component using HikariCP
;;
;; Why duplicate() instead of standard JDBC connections?
;; -----------------------------------------------------
;; DuckDB is an in-process database where each JDBC connection creates an
;; independent database instance. Unlike client-server databases (PostgreSQL,
;; MySQL), DuckDB connections don't share state - each "jdbc:duckdb:" connection
;; is isolated with its own catalog, attached databases, and loaded extensions.
;;
;; This causes problems for connection pooling:
;; 1. Each pooled connection would need to re-run INSTALL/LOAD/ATTACH on checkout
;; 2. DuckLake's ATTACH fails if another connection already attached the same file
;; 3. Extensions and USE database state don't persist across connections
;;
;; DuckDB's duplicate() solves this by creating lightweight connection handles
;; that share the same underlying database instance. All duplicated connections:
;; - Share the same catalog and attached databases
;; - Share loaded extensions
;; - Can run concurrent queries against the same data
;; - Support proper transaction isolation
;;
;; Architecture:
;; - One "root" connection initializes DuckLake (INSTALL, LOAD, ATTACH, USE)
;; - HikariCP pools duplicate() handles from this root connection
;; - All pooled connections inherit the root's DuckLake configuration
;; - Closing the pool closes both HikariCP and the root connection
;;
;; See: https://duckdb.org/docs/stable/clients/java#configuring-connections
;; ---------------------------------------------------------

(ns o11ylite.components.duckdb-pool
  (:require
   [integrant.core :as ig]
   [com.brunobonacci.mulog :as mulog])
  (:import
   [org.duckdb DuckDBConnection]
   [com.zaxxer.hikari HikariConfig HikariDataSource]
   [javax.sql DataSource]
   [java.io File Closeable]))

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

(defn- init-root-connection!
  "Create the root DuckDB connection with DuckLake attached.
   This connection is used as the basis for duplicate() calls.

   Note: USE ducklake is run here but won't carry over to duplicate() connections
   since USE is session-level state. We add connectionInitSql to HikariCP to run
   USE on each pooled connection checkout."
  [ducklake-file]
  (let [conn (java.sql.DriverManager/getConnection "jdbc:duckdb:")]
    (with-open [stmt (.createStatement conn)]
      (.execute stmt "INSTALL ducklake")
      (.execute stmt "LOAD ducklake")
      (.execute stmt (str "ATTACH 'ducklake:" ducklake-file "' AS ducklake")))
    (mulog/log ::root-connection-initialized :ducklake-file ducklake-file)
    conn))

(defn- duplicating-datasource
  "A DataSource that returns duplicate() connections from a root DuckDB connection.

   Each duplicate() call creates a new connection handle that shares the root's
   database instance, catalog, extensions, and attached databases. This is much
   cheaper than creating new JDBC connections and avoids DuckLake re-attachment."
  [^DuckDBConnection root-conn]
  (reify DataSource
    (getConnection [_]
      (.duplicate root-conn))
    (getConnection [_ _user _pass]
      (.duplicate root-conn))
    (getLogWriter [_] nil)
    (setLogWriter [_ _])
    (setLoginTimeout [_ _])
    (getLoginTimeout [_] 0)
    (getParentLogger [_]
      (throw (java.sql.SQLFeatureNotSupportedException.)))
    (^boolean isWrapperFor [_ ^Class _c] false)
    (unwrap [_ _c] (throw (java.sql.SQLException. "Not a wrapper")))))

(defn- create-pool-datasource
  "Create a HikariCP-pooled datasource backed by DuckDB duplicate() connections."
  [{:keys [data-path pool-size]
    :or {pool-size 10}}]
  (let [ducklake-file (ducklake-path data-path)
        root-conn (init-root-connection! ducklake-file)
        ;; USE is session-level state that doesn't carry over from root to
        ;; duplicate() connections, so we run it on each connection checkout
        config (doto (HikariConfig.)
                 (.setDataSource (duplicating-datasource root-conn))
                 (.setMaximumPoolSize pool-size)
                 (.setMinimumIdle 2)
                 (.setPoolName "duckdb-ducklake-pool")
                 (.setConnectionTestQuery "SELECT 1")
                 (.setConnectionInitSql "USE ducklake"))
        hikari-ds (HikariDataSource. config)]
    ;; Return a wrapper that closes both HikariCP and root connection
    (reify
      DataSource
      (getConnection [_] (.getConnection hikari-ds))
      (getConnection [_ u p] (.getConnection hikari-ds u p))
      (getLogWriter [_] (.getLogWriter hikari-ds))
      (setLogWriter [_ w] (.setLogWriter hikari-ds w))
      (setLoginTimeout [_ t] (.setLoginTimeout hikari-ds t))
      (getLoginTimeout [_] (.getLoginTimeout hikari-ds))
      (getParentLogger [_] (.getParentLogger hikari-ds))
      (^boolean isWrapperFor [_ ^Class c] (.isWrapperFor hikari-ds c))
      (unwrap [_ c] (.unwrap hikari-ds c))

      Closeable
      (close [_]
        (mulog/log ::duckdb-pool-closing)
        (.close hikari-ds)
        (.close root-conn)
        (mulog/log ::duckdb-pool-closed)))))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :db/duckdb
  [_ {:keys [data-path]}]
  (mulog/log ::duckdb-pool-starting :data-path data-path)
  (ensure-data-dir! data-path)
  (let [datasource (create-pool-datasource {:data-path data-path})]
    ;; Validate pool by getting and closing a connection
    (.close (.getConnection datasource))
    (mulog/log ::duckdb-pool-started :data-path data-path)
    datasource))

(defmethod ig/halt-key! :db/duckdb
  [_ datasource]
  (mulog/log ::duckdb-pool-stopping)
  (.close ^Closeable datasource)
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

  ;; Test concurrent queries
  (let [results (doall
                 (pmap (fn [i]
                         (jdbc/execute-one! ds ["SELECT ? AS n" i]))
                       (range 10)))]
    (map :n results))

  ;; Use transactions
  (jdbc/with-transaction [tx ds]
    (jdbc/execute! tx ["SELECT 1"])
    (jdbc/execute! tx ["SELECT 2"]))

  ;; Stop the pool - can restart cleanly after this
  (ig/halt-key! :db/duckdb ds)

  ;; Restart should work without conflict
  (def ds2
    (ig/init-key :db/duckdb {:data-path "./.tmp"}))

  (jdbc/execute! ds2 ["SELECT current_database()"])

  (ig/halt-key! :db/duckdb ds2)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

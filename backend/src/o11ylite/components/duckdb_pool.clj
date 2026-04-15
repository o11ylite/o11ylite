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
    [com.brunobonacci.mulog :as mulog]
    [next.jdbc :as jdbc]
    [o11ylite.store.jdbc-types :as jdbc-types])
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

(defn- -build-attach-sql
  "Build the DuckLake ATTACH SQL with appropriate options.

   Always includes AUTOMATIC_MIGRATION because DuckLake is pre-1.0 and its catalog
   schema can change between extension versions. Without this flag, a DuckDB upgrade
   that bundles a newer DuckLake extension would refuse to attach an older catalog.

   DATA_INLINING_ROW_LIMIT is only included when data-inlining-row-limit > 0 (opt-in).
   When 0, DuckLake writes all inserts directly to Parquet files. See core_config.clj
   for the rationale behind disabling data inlining by default."
  [ducklake-file data-inlining-row-limit]
  (if (pos? data-inlining-row-limit)
    (format "ATTACH 'ducklake:%s' AS o11ylite (DATA_INLINING_ROW_LIMIT %s, AUTOMATIC_MIGRATION)"
            ducklake-file data-inlining-row-limit)
    (format "ATTACH 'ducklake:%s' AS o11ylite (DATA_INLINING_ROW_LIMIT 0, AUTOMATIC_MIGRATION)"
            ducklake-file)))

(defn- -temp-directory
  "Build the DuckDB temp directory path under the data directory.

   DuckDB in-memory databases default temp_directory to '.tmp' relative to cwd.
   In production, s6-overlay sets cwd to /run/service (root-owned), so the
   default location is not writable by the o11ylite process. Explicitly placing
   temp files under the data directory avoids this."
  [data-path]
  (str data-path "/.tmp"))

(defn- -system-memory-bytes
  "Total physical memory in bytes, via the JMX OperatingSystemMXBean."
  []
  (.getTotalMemorySize (java.lang.management.ManagementFactory/getOperatingSystemMXBean)))

(defn- -memory-limit-bytes
  "Compute the DuckDB memory_limit in bytes from a percentage of system RAM.
   Returns nil when pct is 0 (meaning: don't set a limit, use DuckDB default)."
  [pct]
  (when (pos? pct)
    (quot (* (-system-memory-bytes) (min pct 100)) 100)))

(defn- init-root-connection!
  "Create the root DuckDB connection with DuckLake attached.
   This connection is used as the basis for duplicate() calls.

   Note: USE o11ylite won't carry over to duplicate() connections since USE is
   session-level state. We add connectionInitSql to HikariCP to run USE on each
   pooled connection checkout."
  [data-path ducklake-file data-inlining-row-limit memory-limit-pct]
  (let [conn (java.sql.DriverManager/getConnection "jdbc:duckdb:")
        attach-sql (-build-attach-sql ducklake-file data-inlining-row-limit)
        temp-dir (-temp-directory data-path)
        mem-bytes (-memory-limit-bytes memory-limit-pct)]
    (jdbc/execute! conn [(str "SET temp_directory = '" temp-dir "'")])
    (when mem-bytes
      (jdbc/execute! conn [(format "SET memory_limit = '%d bytes'" mem-bytes)]))
    ;; Disable the external file cache (enabled by default in DuckDB 1.5+).
    ;; This cache keeps decompressed Parquet pages in memory after reads, but
    ;; it ignores memory_limit, has no eviction policy, and grows monotonically
    ;; on write-heavy workloads -- likely a DuckDB bug. Each DuckLake INSERT
    ;; writes a new Parquet file whose pages get cached but rarely re-read,
    ;; causing unbounded native memory growth that led to OOM kills in prod.
    ;; Disabling this reduced RSS by ~80% in load testing (3.1 GB -> 1.0 GB
    ;; over 500K events).
    (jdbc/execute! conn ["SET enable_external_file_cache = false"])
    (jdbc/execute! conn ["INSTALL ducklake"])
    (jdbc/execute! conn ["LOAD ducklake"])
    (jdbc/execute! conn [attach-sql])
    ;; Set zstd compression for Parquet files written by DuckLake.
    ;; Default is Snappy.
    ;; These are persisted in the DuckLake metadata table, so they only need
    ;; to be set once per catalog. Re-setting is idempotent and ensures the
    ;; desired compression even after a DuckLake migration or fresh attach.
    (jdbc/execute! conn ["CALL o11ylite.set_option('parquet_compression', 'zstd')"])
    (jdbc/execute! conn ["CALL o11ylite.set_option('parquet_compression_level', '3')"])
    ;; Disable sorting on INSERT for ingestion throughput. Tables are configured
    ;; with SET SORTED BY in store/init, so compaction and inlined-data flushes
    ;; still sort data into optimal Parquet layout for time-range queries.
    (jdbc/execute! conn ["CALL o11ylite.set_option('sort_on_insert', 'false')"])
    (mulog/log ::root-connection-initialized
               :ducklake-file ducklake-file
               :data-inlining-row-limit data-inlining-row-limit
               :memory-limit-pct memory-limit-pct
               :memory-limit-bytes mem-bytes
               :temp-directory temp-dir)
    conn))

(defn- duplicating-datasource
  "A DataSource that returns duplicate() connections from a root DuckDB connection.

   Each duplicate() call creates a new connection handle that shares the root's
   database instance, catalog, extensions, and attached databases. This is much
   cheaper than creating new JDBC connections and avoids DuckLake re-attachment."
  [^DuckDBConnection root-conn]
  (reify DataSource
    (getConnection
      [_]
      (.duplicate root-conn))

    (getConnection
      [_ _user _pass]
      (.duplicate root-conn))

    (getLogWriter [_] nil)

    (setLogWriter [_ _])

    (setLoginTimeout [_ _])

    (getLoginTimeout [_] 0)

    (getParentLogger
      [_]
      (throw (java.sql.SQLFeatureNotSupportedException.)))

    (^boolean isWrapperFor [_ ^Class _c] false)

    (unwrap [_ _c] (throw (java.sql.SQLException. "Not a wrapper")))))

(defn- create-pool-datasource
  "Create a HikariCP-pooled datasource backed by DuckDB duplicate() connections."
  [{:keys [data-path data-inlining-row-limit memory-limit-pct pool-size]
    :or {data-inlining-row-limit 0 memory-limit-pct 0 pool-size 10}}]
  (let [ducklake-file (ducklake-path data-path)
        root-conn (init-root-connection! data-path ducklake-file data-inlining-row-limit memory-limit-pct)
        ;; USE is session-level state that doesn't carry over from root to
        ;; duplicate() connections, so we run it on each connection checkout
        config (doto (HikariConfig.)
                 (.setDataSource (duplicating-datasource root-conn))
                 (.setMaximumPoolSize pool-size)
                 (.setMinimumIdle 2)
                 (.setPoolName "duckdb-pool")
                 (.setConnectionTestQuery "SELECT 1")
                 (.setConnectionInitSql "USE o11ylite"))
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

      (close
        [_]
        (mulog/log ::duckdb-pool-closing)
        (.close hikari-ds)
        (.close root-conn)
        (mulog/log ::duckdb-pool-closed)))))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :db/duckdb
  [_ {:keys [core-config]}]
  (let [data-path (:data-path core-config)
        data-inlining-row-limit (:data-inlining-row-limit core-config 0)
        memory-limit-pct (:duckdb-memory-limit-pct core-config 0)]
    (mulog/log ::duckdb-pool-starting
               :data-path data-path
               :data-inlining-row-limit data-inlining-row-limit
               :memory-limit-pct memory-limit-pct)
    (ensure-data-dir! data-path)
    (let [datasource (create-pool-datasource {:data-path data-path
                                              :data-inlining-row-limit data-inlining-row-limit
                                              :memory-limit-pct memory-limit-pct})]
      ;; Validate pool by getting and closing a connection
      (.close (.getConnection datasource))
      (mulog/log ::duckdb-pool-started :data-path data-path)
      ;; Wrap with default options for automatic Timestamp -> epoch-ms conversion.
      ;; Note: jdbc/with-options returns a wrapper that applies these options to
      ;; all next.jdbc operations. However, raw Java calls like .getConnection
      ;; bypass the wrapper. Use jdbc/with-transaction+options for transactions.
      (jdbc/with-options datasource
                         {:builder-fn jdbc-types/as-unqualified-maps}))))

(defmethod ig/halt-key! :db/duckdb
  [_ datasource]
  (mulog/log ::duckdb-pool-stopping)
  ;; Unwrap the with-options wrapper to get the underlying Closeable datasource
  (.close ^Closeable (jdbc/get-datasource datasource))
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

  ;; Check current database (should be o11ylite)
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

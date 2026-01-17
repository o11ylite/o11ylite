;; ---------------------------------------------------------
;; o11ylite.store.ducklake
;;
;; DuckLake-specific maintenance functions.
;; ---------------------------------------------------------

(ns o11ylite.store.ducklake
  (:require
   [com.brunobonacci.mulog :as mulog]
   [next.jdbc :as jdbc]))

;; ---------------------------------------------------------
;; Public API

(defn flush-inlined-data!
  "Flush all inlined data in DuckLake to Parquet files.

   DuckLake stores small writes directly in the metadata catalog (data inlining).
   This function flushes that inlined data to Parquet files in the data path.

   Note: Uses with-transaction to ensure the flush operation runs in its own
   transaction context, avoiding DuckLake's 'scanning after transaction ended' error.

   See: https://ducklake.select/docs/stable/duckdb/advanced_features/data_inlining"
  [duckdb-ds]
  (mulog/log ::flush-inlined-data-starting)
  (jdbc/with-transaction [tx duckdb-ds]
    (jdbc/execute! tx ["CALL ducklake_flush_inlined_data('o11ylite')"]))
  (mulog/log ::flush-inlined-data-completed))

(defn merge-adjacent-files!
  "Merge small Parquet files into larger ones for better query performance.

   Each insert to DuckLake writes data to a new Parquet file. This function
   compacts adjacent small files into larger ones without expiring snapshots,
   preserving time travel and data change feed functionality.

   See: https://ducklake.select/docs/stable/duckdb/maintenance/merge_adjacent_files"
  [duckdb-ds]
  (mulog/log ::merge-adjacent-files-starting)
  (jdbc/with-transaction [tx duckdb-ds]
    (jdbc/execute! tx ["CALL ducklake_merge_adjacent_files('o11ylite')"]))
  (mulog/log ::merge-adjacent-files-completed))

(defn delete-old-data!
  "Delete events and metrics older than retention-days."
  [duckdb-ds retention-days]
  (mulog/log ::delete-old-data-starting :retention-days retention-days)
  (jdbc/with-transaction [tx duckdb-ds]
    ;; events.timestamp is TIMESTAMP_NS, metrics.timestamp is TIMESTAMP
    ;; NOW() returns TIMESTAMP WITH TIME ZONE, so cast to TIMESTAMP first, then to target type
    (jdbc/execute! tx [(format "DELETE FROM o11ylite.events WHERE timestamp < ((NOW() - INTERVAL '%d days')::TIMESTAMP)::TIMESTAMP_NS" retention-days)])
    (jdbc/execute! tx [(format "DELETE FROM o11ylite.metrics WHERE timestamp < (NOW() - INTERVAL '%d days')::TIMESTAMP" retention-days)]))
  (mulog/log ::delete-old-data-completed))

(defn run-checkpoint!
  "Run DuckLake maintenance operations for comprehensive cleanup.

   Executes the key maintenance operations with aggressive 1-day cleanup
   since observability data is append-only and time travel has minimal value.

   Operations performed:
   - Expire old snapshots (1-day threshold)
   - Rewrite data files with deletions to reclaim storage
   - Clean up old/orphaned files (1-day threshold)

   Note: Does not call ducklake_flush_inlined_data or ducklake_merge_adjacent_files
   since those already run via their own scheduled jobs (every 15 min and 1 hour).

   Note: DuckLake documents a CHECKPOINT command that runs all maintenance
   operations, but it's not available in the DuckDB extension API. We call
   individual ducklake_* functions instead.

   See: https://ducklake.select/docs/stable/duckdb/maintenance/checkpoint"
  [duckdb-ds]
  (mulog/log ::checkpoint-starting)
  ;; Each operation runs in its own transaction to avoid DuckLake scanning issues
  (jdbc/with-transaction [tx duckdb-ds]
    (jdbc/execute! tx ["CALL ducklake_expire_snapshots('o11ylite', older_than := NOW() - INTERVAL '1 day')"]))
  (jdbc/with-transaction [tx duckdb-ds]
    (jdbc/execute! tx ["CALL ducklake_rewrite_data_files('o11ylite')"]))
  (jdbc/with-transaction [tx duckdb-ds]
    (jdbc/execute! tx ["CALL ducklake_cleanup_old_files('o11ylite', older_than := NOW() - INTERVAL '1 day')"]))
  (jdbc/with-transaction [tx duckdb-ds]
    (jdbc/execute! tx ["CALL ducklake_delete_orphaned_files('o11ylite')"]))
  (mulog/log ::checkpoint-completed))

;; ---------------------------------------------------------
;; Rich Comment
(comment
  ;; Requires dev system running via (user/go)
  (require '[integrant.repl.state :refer [system]])
  (def duckdb (:db/duckdb system))

  ;; Flush inlined data to Parquet files
  (flush-inlined-data! duckdb)

  ;; Merge adjacent files (compaction)
  (merge-adjacent-files! duckdb)

  ;; Delete old data (retention)
  (delete-old-data! duckdb 30)

  ;; Run checkpoint (comprehensive maintenance)
  (run-checkpoint! duckdb)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

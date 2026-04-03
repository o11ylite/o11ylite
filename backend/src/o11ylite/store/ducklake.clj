;; ---------------------------------------------------------
;; o11ylite.store.ducklake
;;
;; DuckLake-specific maintenance functions.
;; ---------------------------------------------------------

(ns o11ylite.store.ducklake
  (:require
    [com.brunobonacci.mulog :as mulog]
    [next.jdbc :as jdbc]
    [o11ylite.kv :as kv]
    [steffan-westcott.clj-otel.api.trace.span :as span])
  (:import
    [java.time Instant]))

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
  (span/with-span! [::flush-inlined-data]
                   (jdbc/with-transaction [tx duckdb-ds]
                                          (jdbc/execute! tx ["CALL ducklake_flush_inlined_data('o11ylite')"]))))

(defn- -set-target-file-size!
  "Set the DuckLake target_file_size for the next merge operation."
  [tx target-size]
  (jdbc/execute! tx [(format "CALL ducklake_set_option('o11ylite', 'target_file_size', '%s')" target-size)]))

(defn- -merge-batch!
  "Run one bounded merge call. Returns the number of output files created."
  [tx max-compacted-files min-file-size max-file-size]
  (let [params (cond-> (format "max_compacted_files => %d" max-compacted-files)
                 min-file-size (str (format ", min_file_size => %d" min-file-size))
                 max-file-size (str (format ", max_file_size => %d" max-file-size)))
        sql    (format "CALL ducklake_merge_adjacent_files('o11ylite', %s)" params)
        rows   (jdbc/execute! tx [sql])]
    (count rows)))

(defn- -merge-loop!
  "Repeatedly merge in bounded batches until a batch produces fewer output
   files than max-compacted-files, meaning the backlog is drained."
  [duckdb-ds max-compacted-files min-file-size max-file-size]
  (loop [total-files 0]
    (let [n (jdbc/with-transaction [tx duckdb-ds]
                                   (-merge-batch! tx max-compacted-files min-file-size max-file-size))]
      (if (< n max-compacted-files)
        (+ total-files n)
        (recur (+ total-files n))))))

(defn merge-adjacent-files!
  "Merge small Parquet files into larger ones for better query performance.

   Uses max_compacted_files to bound memory per operation and loops until
   the backlog is drained. Optionally filters files by size range.

   Options:
     :max-compacted-files - max output files per batch (required)
     :target-file-size    - target output size, e.g. \"5MB\" (required)
     :min-file-size       - exclude files smaller than this (bytes, optional)
     :max-file-size       - exclude files at or larger than this (bytes, optional)

   See: https://ducklake.select/docs/stable/duckdb/maintenance/merge_adjacent_files"
  [duckdb-ds {:keys [max-compacted-files target-file-size min-file-size max-file-size]}]
  (span/with-span! [::merge-adjacent-files {:max-compacted-files max-compacted-files
                                            :target-file-size    target-file-size
                                            :min-file-size       min-file-size
                                            :max-file-size       max-file-size}]
                   ;; Set target file size before merging (catalog-level setting)
                   (jdbc/with-transaction [tx duckdb-ds]
                                          (-set-target-file-size! tx target-file-size))
                   (let [total (-merge-loop! duckdb-ds max-compacted-files min-file-size max-file-size)]
                     (when (pos? total)
                       (mulog/log ::merge-adjacent-files-completed :files-created total))
                     total)))

;; ---------------------------------------------------------
;; Tiered Compaction
;;
;; target_file_size is a catalog-level setting (persisted in ducklake_metadata),
;; so tiers must run sequentially to avoid races. A single scheduled job calls
;; run-tiered-compaction! which iterates all tiers, skipping any whose
;; per-tier interval hasn't elapsed yet. Last-run timestamps are persisted
;; in the KV store so cadence survives restarts.
;;
;; Tier boundaries:
;;   Small:  files < 1MB    → ~5MB targets   (handles streaming ingestion)
;;   Medium: files 1-10MB   → ~32MB targets   (consolidates small-tier output)
;;   Large:  files ≥10MB    → ~256MB targets  (final compaction)

(def compaction-tiers
  "Tiered compaction definitions, run sequentially from smallest to largest.
   :interval-key maps to an app-config setting (minutes).
   See: https://ducklake.select/docs/stable/duckdb/maintenance/merge_adjacent_files"
  [{:tier-name     :small
    :interval-key  :compaction-small-interval-minutes
    :target-file-size "5MB"
    :max-file-size    1048576}
   {:tier-name     :medium
    :interval-key  :compaction-medium-interval-minutes
    :target-file-size "32MB"
    :min-file-size    1048576
    :max-file-size    10485760}
   {:tier-name     :large
    :interval-key  :compaction-large-interval-minutes
    :target-file-size "256MB"
    :min-file-size    10485760}])

(defn tier-kv-key
  "KV store key for a tier's last-run timestamp."
  [tier-name]
  (str "compaction-last-run:" (name tier-name)))

(defn- -tier-due?
  "Check if a tier's interval has elapsed since its last run."
  [sqlite tier-name interval-ms]
  (let [last-run (kv/get-value sqlite (tier-kv-key tier-name))]
    (or (nil? last-run)
        (>= (- (inst-ms (Instant/now)) (inst-ms last-run))
            interval-ms))))

(defn- -record-tier-run!
  "Record the current time as a tier's last-run timestamp."
  [sqlite tier-name]
  (kv/set-value! sqlite (tier-kv-key tier-name) (Instant/now)))

(defn run-tiered-compaction!
  "Run due compaction tiers sequentially with bounded memory per batch.

   Each tier checks whether its interval has elapsed (via KV store), and
   skips if not due. Due tiers set target_file_size (a catalog-level setting),
   then merge files in that size range. Sequential execution avoids races
   on the shared target_file_size setting.

   tier-intervals is a map of interval-key to interval in minutes,
   e.g. {:compaction-small-interval-minutes 5, ...}"
  [duckdb-ds sqlite max-compacted-files tier-intervals]
  (span/with-span! [::run-tiered-compaction {:max-compacted-files max-compacted-files}]
                   (doseq [{:keys [tier-name interval-key] :as tier} compaction-tiers]
                     (let [interval-ms (* (get tier-intervals interval-key 60) 60000)]
                       (when (-tier-due? sqlite tier-name interval-ms)
                         (merge-adjacent-files! duckdb-ds
                                                (assoc (select-keys tier [:target-file-size :min-file-size :max-file-size])
                                                       :max-compacted-files max-compacted-files))
                         (-record-tier-run! sqlite tier-name))))))

(defn delete-old-data!
  "Delete events and metrics older than retention-days."
  [duckdb-ds retention-days]
  (span/with-span! [::delete-old-data {:retention-days retention-days}]
                   (jdbc/with-transaction [tx duckdb-ds]
                                          ;; events.timestamp is TIMESTAMP_NS, metrics.timestamp is TIMESTAMP
                                          ;; NOW() returns TIMESTAMP WITH TIME ZONE, so cast to TIMESTAMP first, then to target type
                                          (jdbc/execute! tx [(format "DELETE FROM o11ylite.events WHERE timestamp < ((NOW() - INTERVAL '%d days')::TIMESTAMP)::TIMESTAMP_NS" retention-days)])
                                          (jdbc/execute! tx [(format "DELETE FROM o11ylite.metrics WHERE timestamp < (NOW() - INTERVAL '%d days')::TIMESTAMP" retention-days)]))))

(defn run-checkpoint!
  "Run DuckLake maintenance operations for comprehensive cleanup.

   Executes the key maintenance operations with aggressive 1-day cleanup
   since observability data is append-only and time travel has minimal value.

   Operations performed:
   - Expire old snapshots (1-day threshold)
   - Rewrite data files with deletions to reclaim storage
   - Clean up old/orphaned files (1-day threshold)

   Note: Does not call ducklake_flush_inlined_data or ducklake_merge_adjacent_files
   since those already run via their own scheduled jobs.

   Note: DuckLake documents a CHECKPOINT command that runs all maintenance
   operations, but it's not available in the DuckDB extension API. We call
   individual ducklake_* functions instead.

   See: https://ducklake.select/docs/stable/duckdb/maintenance/checkpoint"
  [duckdb-ds]
  (span/with-span! [::run-checkpoint]
                   ;; Each operation runs in its own transaction to avoid DuckLake scanning issues
                   (jdbc/with-transaction [tx duckdb-ds]
                                          (jdbc/execute! tx ["CALL ducklake_expire_snapshots('o11ylite', older_than := NOW() - INTERVAL '1 day')"]))
                   (jdbc/with-transaction [tx duckdb-ds]
                                          (jdbc/execute! tx ["CALL ducklake_rewrite_data_files('o11ylite')"]))
                   (jdbc/with-transaction [tx duckdb-ds]
                                          (jdbc/execute! tx ["CALL ducklake_cleanup_old_files('o11ylite', older_than := NOW() - INTERVAL '1 day')"]))
                   (jdbc/with-transaction [tx duckdb-ds]
                                          (jdbc/execute! tx ["CALL ducklake_delete_orphaned_files('o11ylite')"]))))

;; ---------------------------------------------------------
;; Rich Comment
(comment
  ;; Requires dev system running via (user/go)
  (require '[integrant.repl.state :refer [system]])
  (def duckdb (:db/duckdb system))

  ;; Flush inlined data to Parquet files
  (flush-inlined-data! duckdb)

  (def sqlite (:db/sqlite system))

  ;; Run tiered compaction (skips tiers not yet due)
  (run-tiered-compaction! duckdb sqlite 10
                          {:compaction-small-interval-minutes 5
                           :compaction-medium-interval-minutes 15
                           :compaction-large-interval-minutes 60})

  ;; Or run a single tier manually
  (merge-adjacent-files! duckdb {:max-compacted-files 5
                                 :target-file-size "5MB"
                                 :max-file-size 1048576})

  ;; Delete old data (retention)
  (delete-old-data! duckdb 30)

  ;; Run checkpoint (comprehensive maintenance)
  (run-checkpoint! duckdb)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

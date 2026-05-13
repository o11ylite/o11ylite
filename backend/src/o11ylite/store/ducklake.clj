;; ---------------------------------------------------------
;; o11ylite.store.ducklake
;;
;; DuckLake-specific maintenance functions.
;; ---------------------------------------------------------

(ns o11ylite.store.ducklake
  (:require
    [clojure.string :as str]
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

(defn- -build-merge-sql
  "Build the ducklake_merge_adjacent_files SQL with optional parameters."
  [{:keys [max-compacted-files min-file-size max-file-size]}]
  (let [params (cond-> []
                 max-compacted-files (conj (format "max_compacted_files => %d" max-compacted-files))
                 min-file-size       (conj (format "min_file_size => %d" min-file-size))
                 max-file-size       (conj (format "max_file_size => %d" max-file-size)))]
    (if (seq params)
      (format "CALL ducklake_merge_adjacent_files('o11ylite', %s)" (str/join ", " params))
      "CALL ducklake_merge_adjacent_files('o11ylite')")))

(defn- -merge-once!
  "Run a single merge call. Returns a map of :files-created and :files-processed."
  [duckdb-ds opts]
  (let [sql (-build-merge-sql opts)
        rows (jdbc/with-transaction [tx duckdb-ds]
               (jdbc/execute! tx [sql]))]
    {:files-created (count rows)
     :files-processed (reduce + 0 (map :files_processed rows))}))

(defn- -merge-loop!
  "Repeatedly merge in bounded batches until a batch produces fewer output
   files than max-compacted-files, meaning the backlog is drained."
  [duckdb-ds {:keys [max-compacted-files] :as opts}]
  (loop [total-created 0 total-processed 0]
    (let [{:keys [files-created files-processed]} (-merge-once! duckdb-ds opts)]
      (if (< files-created max-compacted-files)
        {:files-created   (+ total-created files-created)
         :files-processed (+ total-processed files-processed)}
        (recur (+ total-created files-created)
               (+ total-processed files-processed))))))

(defn merge-adjacent-files!
  "Merge small Parquet files into larger ones for better query performance.

   Two modes depending on whether :max-compacted-files is provided:

   - Without :max-compacted-files — runs a single unbounded merge call.
     DuckLake merges all eligible files in one pass. Use for the small tier
     where file count is bounded by ingestion rate × compaction interval.

   - With :max-compacted-files — loops in bounded batches until the backlog
     is drained. Use for medium/large tiers where output file count is
     inherently bounded.

   Options:
     :target-file-size    - target output size, e.g. \"5MB\" (required)
     :max-compacted-files - max output files per batch (optional)
     :min-file-size       - exclude files smaller than this (bytes, optional)
     :max-file-size       - exclude files at or larger than this (bytes, optional)

   See: https://ducklake.select/docs/stable/duckdb/maintenance/merge_adjacent_files"
  [duckdb-ds {:keys [max-compacted-files target-file-size tier-name] :as opts}]
  (span/with-span! [::merge-adjacent-files {:o11ylite.ducklake.compaction.max_compacted_files (:max-compacted-files opts)
                                            :o11ylite.ducklake.compaction.target_file_size    (:target-file-size opts)
                                            :o11ylite.ducklake.compaction.min_file_size       (:min-file-size opts)
                                            :o11ylite.ducklake.compaction.max_file_size       (:max-file-size opts)}]
    ;; Set target file size before merging (catalog-level setting)
    (jdbc/with-transaction [tx duckdb-ds]
      (-set-target-file-size! tx target-file-size))
    (let [{:keys [files-created files-processed]} (if max-compacted-files
                                                    (-merge-loop! duckdb-ds opts)
                                                    (-merge-once! duckdb-ds opts))]
      (when tier-name
        (span/add-span-data! {:attributes {:o11ylite.ducklake.compaction.tier_name       (name tier-name)
                                           :o11ylite.ducklake.compaction.files_created   files-created
                                           :o11ylite.ducklake.compaction.files_processed files-processed}}))
      {:files-created files-created :files-processed files-processed})))

;; ---------------------------------------------------------
;; Tiered Compaction
;;
;; ## Why tiers?
;;
;; Streaming ingestion produces many tiny parquet files (one per flush per
;; table). DuckLake's ducklake_merge_adjacent_files merges them, but its
;; memory cost is proportional to the number of files it scans — not the
;; amount of data it outputs. Tested with 333K files totaling 2.5 GB, a
;; single merge call consumed 30 GB of RAM because DuckLake loads all
;; matching file metadata into memory at bind time.
;;
;; Tiered compaction keeps the file count low at every level:
;;   Small:  files < 1MB      → ~5MB targets   (runs every 5 min)
;;   Medium: files 1-10MB     → ~32MB targets   (runs every 15 min)
;;   Large:  files 10-128MB   → ~256MB targets  (runs every 60 min)
;;
;; ## Why the small tier is unbounded (no max_compacted_files, no loop)
;;
;; The small tier must drain all new files every run. If it falls behind,
;; files accumulate and the catalog scan becomes the bottleneck — the exact
;; death spiral we hit in production. A single unbounded call lets DuckLake
;; plan one pass over all eligible files. At steady state, the file count
;; per run is bounded by (ingestion rate × compaction interval), typically
;; a few hundred files — well within DuckLake's capacity.
;;
;; ## Why medium/large tiers loop with max_compacted_files
;;
;; These tiers operate on the output of previous tiers, so the file
;; count is inherently small. max_compacted_files bounds the merge/write
;; memory per call (it does not help with the catalog scan — see above),
;; and the loop drains any remaining backlog.
;;
;; ## Sequential execution
;;
;; target_file_size is a catalog-level setting (persisted in
;; ducklake_metadata), so tiers must run sequentially to avoid races.
;; Per-tier cadence is tracked in the KV store so it survives restarts.
;;
;; ## max_file_size < target_file_size
;;
;; Every tier sets max_file_size below its target so that output files
;; from one call are excluded from subsequent calls.

(def compaction-tiers
  "Tiered compaction definitions, run sequentially from smallest to largest.
   :interval-key maps to an app-config setting (minutes).
   :loop? true means the tier uses max-compacted-files and loops until drained.
   See: https://ducklake.select/docs/stable/duckdb/maintenance/merge_adjacent_files"
  [{:tier-name     :small
    :interval-key  :compaction-small-interval-minutes
    :target-file-size "5MB"
    :max-file-size    1048576
    :loop?            false}
   {:tier-name     :medium
    :interval-key  :compaction-medium-interval-minutes
    :target-file-size "32MB"
    :min-file-size    1048576
    :max-file-size    10485760
    :loop?            true}
   {:tier-name     :large
    :interval-key  :compaction-large-interval-minutes
    :target-file-size "256MB"
    :min-file-size    10485760
    :max-file-size    134217728       ; 128MB — below target to avoid re-merging outputs
    :loop?            true}])

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

(defn- -tier-merge-opts
  "Build merge options for a tier. Looping tiers include max-compacted-files;
   non-looping tiers omit it for a single unbounded call."
  [{:keys [target-file-size min-file-size max-file-size loop? tier-name]} max-compacted-files]
  (cond-> {:target-file-size target-file-size
           :min-file-size    min-file-size
           :max-file-size    max-file-size
           :tier-name        tier-name}
    loop? (assoc :max-compacted-files max-compacted-files)))

(defn run-tiered-compaction!
  "Run due compaction tiers sequentially.

   The small tier runs a single unbounded merge (no max_compacted_files) to
   compact all new small files in one pass. Medium and large tiers use
   max_compacted_files and loop until their backlogs are drained.

   Each tier checks whether its interval has elapsed (via KV store) and
   skips if not due. Tiers run sequentially because target_file_size is a
   catalog-level setting that must not be mutated concurrently.

   tier-intervals is a map of interval-key to interval in minutes,
   e.g. {:compaction-small-interval-minutes 5, ...}"
  [duckdb-ds sqlite max-compacted-files tier-intervals]
  (span/with-span! [::run-tiered-compaction {:o11ylite.ducklake.compaction.max_compacted_files max-compacted-files}]
    (doseq [{:keys [tier-name interval-key] :as tier} compaction-tiers]
      (let [interval-ms (* (get tier-intervals interval-key 60) 60000)]
        (when (-tier-due? sqlite tier-name interval-ms)
          (merge-adjacent-files! duckdb-ds (-tier-merge-opts tier max-compacted-files))
          (-record-tier-run! sqlite tier-name))))))

(defn- -delete-table-older-than!
  "Delete rows from `table` whose `timestamp` column is older than
   retention-days. The threshold is cast to `ts-type` to match the
   column's type."
  [duckdb-ds table retention-days ts-type]
  (jdbc/with-transaction [tx duckdb-ds]
    (jdbc/execute! tx [(format "DELETE FROM o11ylite.%s WHERE timestamp < ((NOW() - INTERVAL '%d days')::TIMESTAMP)::%s"
                               table retention-days ts-type)])))

(defn delete-old-data!
  "Delete events and metrics older than retention-days.

   Each table is deleted in its own transaction. Combining both into one
   transaction widened the conflict window against concurrent DuckLake
   catalog mutations (compaction, snapshot cleanup), which surfaced as
   'cannot rollback - no transaction is active' once the server-side
   transaction was aborted under load."
  [duckdb-ds retention-days]
  (span/with-span! [::delete-old-data {:o11ylite.ducklake.retention_days retention-days}]
    ;; NOW() is TIMESTAMP WITH TIME ZONE; cast to each column's type.
    (-delete-table-older-than! duckdb-ds "events"  retention-days "TIMESTAMP_NS")
    (-delete-table-older-than! duckdb-ds "metrics" retention-days "TIMESTAMP")
    true))

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

(defn run-snapshot-cleanup!
  "Expire old snapshots and remove superseded files on a shorter cadence
   than daily maintenance. Keeps the DuckLake catalog lean between daily
   runs, preventing unbounded metadata growth."
  [duckdb-ds]
  (span/with-span! [::run-snapshot-cleanup]
    (jdbc/with-transaction [tx duckdb-ds]
      (jdbc/execute! tx ["CALL ducklake_expire_snapshots('o11ylite', older_than := NOW() - INTERVAL '1 hour')"]))
    (jdbc/with-transaction [tx duckdb-ds]
      (jdbc/execute! tx ["CALL ducklake_cleanup_old_files('o11ylite', older_than := NOW() - INTERVAL '1 hour')"]))
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

  ;; Single unbounded merge (small tier style — no loop, no max_compacted_files)
  (merge-adjacent-files! duckdb {:target-file-size "5MB"
                                 :max-file-size 1048576})

  ;; Bounded looping merge (medium/large tier style)
  (merge-adjacent-files! duckdb {:max-compacted-files 10
                                 :target-file-size "32MB"
                                 :min-file-size 1048576
                                 :max-file-size 10485760})

  ;; Delete old data (retention)
  (delete-old-data! duckdb 30)

  ;; Run checkpoint (comprehensive maintenance)
  (run-checkpoint! duckdb)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

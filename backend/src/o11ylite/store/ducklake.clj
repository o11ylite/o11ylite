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

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

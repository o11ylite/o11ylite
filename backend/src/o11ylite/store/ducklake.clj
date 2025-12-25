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

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.core :as ig])

  ;; Start DuckDB pool
  (def ds
    (ig/init-key :db/duckdb {:data-path "./.tmp"}))

  ;; Flush inlined data to Parquet files
  (flush-inlined-data! ds)

  ;; Cleanup
  (ig/halt-key! :db/duckdb ds)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

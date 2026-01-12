;; ---------------------------------------------------------
;; o11ylite.store.query-util
;;
;; Shared query utilities for events and metrics queries.
;; Provides time bucketing, alignment, and common query helpers.
;; ---------------------------------------------------------

(ns o11ylite.store.query-util)

;; ---------------------------------------------------------
;; Time Bucket Selection

(def bucket-sizes-ms
  "Allowed bucket sizes in milliseconds, ascending order.
   These are 'nice' intervals that align naturally to time boundaries."
  [1000        ; 1s
   5000        ; 5s
   10000       ; 10s
   20000       ; 20s
   30000       ; 30s
   60000       ; 1m
   120000      ; 2m
   300000      ; 5m
   600000      ; 10m
   1200000     ; 20m
   1800000     ; 30m
   3600000     ; 1h
   7200000     ; 2h
   14400000    ; 4h
   21600000    ; 6h
   43200000    ; 12h
   86400000])  ; 1d

(defn select-bucket-ms
  "Select the smallest 'nice' bucket size that yields ~100 buckets for the given range.
   Returns a bucket size from bucket-sizes-ms that produces approximately 100 buckets."
  [range-ms]
  (let [target-buckets 100
        ideal-bucket (quot range-ms target-buckets)]
    (or (first (filter #(>= % ideal-bucket) bucket-sizes-ms))
        (last bucket-sizes-ms))))

(defn align-to-bucket
  "Align a timestamp (in milliseconds) down to the nearest bucket boundary.
   Returns the aligned timestamp in milliseconds."
  [epoch-ms bucket-ms]
  (- epoch-ms (mod epoch-ms bucket-ms)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Bucket size selection examples
  (select-bucket-ms 3600000)    ;; 1 hour => 60000 (1 min buckets, ~60 buckets)
  (select-bucket-ms 300000)     ;; 5 minutes => 5000 (5s buckets, ~60 buckets)
  (select-bucket-ms 86400000)   ;; 1 day => 1200000 (20 min buckets, ~72 buckets)
  (select-bucket-ms 604800000)  ;; 1 week => 7200000 (2 hour buckets, ~84 buckets)

  ;; Bucket alignment examples
  (align-to-bucket 1702000000123 60000)  ;; => 1702000000000 (aligned to minute)
  (align-to-bucket 1702000045000 60000)  ;; => 1702000000000 (aligned to minute)
  (align-to-bucket 1702000060000 60000)  ;; => 1702000060000 (already aligned)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

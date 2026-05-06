;; ---------------------------------------------------------
;; o11ylite.components.scheduler
;;
;; Central scheduler for periodic background jobs.
;;
;; Architecture:
;; - :scheduler/registry - component that provides job definitions
;; - :scheduler/executor - component that executes jobs on schedule
;;
;; Features:
;; - Survives restarts (job state in DB)
;; - In-memory lock prevents concurrent execution of same job
;; - Parallel execution of different jobs
;; - Records success/failure for system status visibility
;;
;; Limitations:
;; - On shutdown, running jobs complete in background (not awaited)
;; ---------------------------------------------------------

(ns o11ylite.components.scheduler
  (:require
    [integrant.core :as ig]
    [com.brunobonacci.mulog :as mulog]
    [next.jdbc :as jdbc]
    [o11ylite.components.app-config :as app-config]
    [o11ylite.store.scheduler :as store]
    [o11ylite.alert-rule :as alert-rule]
    [o11ylite.store.ducklake :as ducklake]
    [o11ylite.store.telemetry-catalog-gc :as catalog-gc]
    [o11ylite.util.telemetry :as telemetry]
    [o11ylite.util.ticker :as ticker]))

;; ---------------------------------------------------------
;; Configuration

(def ^:private default-tick-interval-ms
  "Default interval for checking due jobs (30 seconds)."
  30000)

;; ---------------------------------------------------------
;; In-Memory Lock
;;
;; Prevents concurrent execution of the same job.
;; If a job takes longer than the tick interval, subsequent
;; ticks will skip it until it completes.
;;
;; The running-jobs atom is created per scheduler instance
;; and passed through the execution functions.

(defn- -try-acquire-lock!
  "Try to acquire lock for job. Returns true if acquired, false if already running."
  [running-jobs job-name]
  (let [[old-jobs _] (swap-vals! running-jobs conj job-name)]
    (not (contains? old-jobs job-name))))

(defn- -release-lock!
  "Release lock for job."
  [running-jobs job-name]
  (swap! running-jobs disj job-name))

;; ---------------------------------------------------------
;; Job Execution

(defn- -run-job!
  "Execute a single job. Handles locking, execution, and result recording."
  [running-jobs sqlite job-name handler]
  (if-not (-try-acquire-lock! running-jobs job-name)
    (mulog/log ::job-skipped-already-running :o11ylite.scheduler.job_name job-name)
    (future
      (try
        (mulog/log ::job-starting :o11ylite.scheduler.job_name job-name)
        (handler)
        (store/record-success! sqlite job-name)
        (mulog/log ::job-succeeded :o11ylite.scheduler.job_name job-name)
        (catch Exception e
          (store/record-failure! sqlite job-name (.getMessage e))
          (telemetry/report-error! ::job-failed e :o11ylite.scheduler.job_name job-name))
        (finally
          (-release-lock! running-jobs job-name))))))

(defn- -process-due-jobs!
  "Check for due jobs and run them in parallel."
  [running-jobs sqlite registry]
  (let [due-jobs (store/get-due-jobs sqlite)]
    (doseq [{:keys [job_name]} due-jobs]
      (let [job-key (keyword job_name)
            handler (get-in registry [job-key :handler])]
        (if handler
          (-run-job! running-jobs sqlite job-key handler)
          (mulog/log ::job-handler-not-found :o11ylite.scheduler.job_name job-key))))))

(defn- -start-scheduler-loop!
  "Start the scheduler ticker loop."
  [running-jobs sqlite registry tick-interval-ms]
  (let [t (ticker/ticker tick-interval-ms)]
    (future
      (loop []
        (when (ticker/tick! t)
          (try
            (-process-due-jobs! running-jobs sqlite registry)
            (catch Exception e
              (telemetry/report-error! ::scheduler-tick-failed e)))
          (recur))))
    t))

(defn- -sync-jobs!
  "Sync jobs from registry to DB.
   Upserts all registered jobs and deletes orphaned jobs."
  [sqlite registry]
  (doseq [[job-key job-def] registry]
    (let [interval (:interval-ms job-def)]
      (store/upsert-job! sqlite job-key interval)
      (mulog/log ::job-registered :o11ylite.scheduler.job_name job-key :o11ylite.scheduler.interval_ms interval)))
  (let [job-names (mapv name (keys registry))
        result (store/delete-unrecognized-jobs! sqlite job-names)
        deleted-count (or (::jdbc/update-count (first result)) 0)]
    (when (pos? deleted-count)
      (mulog/log ::orphaned-jobs-deleted :o11ylite.scheduler.deleted_count deleted-count))))

;; ---------------------------------------------------------
;; Registry Component
;;
;; Provides job definitions with their handlers.
;; Handlers are closures that capture their dependencies.
;; Intervals are configured in minutes via system.edn (converted to ms here).

(def ^:private minutes->ms
  "Convert minutes to milliseconds."
  (partial * 60000))

(defmethod ig/init-key :scheduler/registry
  [_ {:keys [core-config duckdb sqlite events-schema app-config]}]
  (mulog/log ::registry-initializing)
  (let [data-inlining-row-limit (:data-inlining-row-limit core-config 0)
        inlined-data-flush-interval-minutes (app-config/get-setting-value app-config :inlined-data-flush-interval-minutes)
        compaction-max-files (app-config/get-setting-value app-config :compaction-max-files-per-batch)
        compaction-small-interval (app-config/get-setting-value app-config :compaction-small-interval-minutes)
        tier-intervals {:compaction-small-interval-minutes  compaction-small-interval
                        :compaction-medium-interval-minutes (app-config/get-setting-value app-config :compaction-medium-interval-minutes)
                        :compaction-large-interval-minutes  (app-config/get-setting-value app-config :compaction-large-interval-minutes)}
        snapshot-cleanup-interval-minutes (app-config/get-setting-value app-config :snapshot-cleanup-interval-minutes)
        daily-maintenance-interval-minutes (app-config/get-setting-value app-config :daily-maintenance-interval-minutes)
        data-retention-days (app-config/get-setting-value app-config :data-retention-days)
        catalog-gc-interval-minutes (app-config/get-setting-value app-config :telemetry-catalog-gc-interval-minutes)
        catalog-gc-deps {:sqlite sqlite :duckdb duckdb :events-schema events-schema}
        webhook-url (app-config/get-setting-value app-config :webhook-url)]
    (cond->
      {;; Tiered compaction: runs small → medium → large tiers sequentially.
       ;; Small tier runs a single unbounded merge to compact all new small
       ;; files. Medium/large tiers use max_compacted_files and loop until
       ;; drained. Per-tier cadence is tracked in the KV store; the scheduler
       ;; fires at the smallest tier's interval and each tier decides
       ;; independently whether it's due to run.
       ;;
       ;; See: https://ducklake.select/docs/stable/duckdb/maintenance/merge_adjacent_files
       :parquet-compaction
       {:interval-ms (minutes->ms compaction-small-interval)
        :description "Tiered compaction of small Parquet files"
        :handler (fn [] (ducklake/run-tiered-compaction! duckdb sqlite compaction-max-files tier-intervals))}

       ;; Snapshot cleanup: expire old snapshots and remove superseded file
       ;; entries on a shorter cadence than daily maintenance.  Keeps the
       ;; DuckLake catalog lean between daily runs, preventing unbounded
       ;; metadata growth that can contribute to memory pressure.
       :snapshot-cleanup
       {:interval-ms (minutes->ms snapshot-cleanup-interval-minutes)
        :description "Expire old snapshots and clean up superseded files"
        :handler (fn [] (ducklake/run-snapshot-cleanup! duckdb))}

       :daily-maintenance
       {:interval-ms (minutes->ms daily-maintenance-interval-minutes)
        :description "Daily data retention and DuckLake maintenance"
        :handler (fn []
                   (ducklake/delete-old-data! duckdb data-retention-days)
                   (ducklake/run-checkpoint! duckdb))}

       ;; Telemetry catalog GC: reclaim metrics_metadata rows, events
       ;; columns, and service_metadata rows whose last emitter has been
       ;; silent for longer than the data retention window — once no data
       ;; survives from a service that emitted signal X, X is reclaimable.
       ;; See o11ylite.store.telemetry-catalog-gc.
       :telemetry-catalog-garbage-collection
       {:interval-ms (minutes->ms catalog-gc-interval-minutes)
        :description "Reclaim unused metrics, event fields, and services"
        :handler (fn [] (catalog-gc/run-gc! catalog-gc-deps data-retention-days))}

       :alert-evaluation
       {:interval-ms 30000
        :description "Evaluate due alert rules and send webhook notifications"
        :handler (fn []
                   (alert-rule/run-evaluation-cycle!
                     duckdb sqlite events-schema webhook-url))}}

      ;; Only register the inlined-data-flush job when data inlining is enabled.
      ;; When DATA_INLINING_ROW_LIMIT is 0, no data is ever inlined so flushing
      ;; would be a no-op. Skipping the job avoids unnecessary scheduler overhead
      ;; and makes it visible in the job status page that inlining is off.
      (pos? data-inlining-row-limit)
      (assoc :inlined-data-flush
             {:interval-ms (minutes->ms inlined-data-flush-interval-minutes)
              :description "Flush DuckLake inlined data to Parquet"
              :handler (fn [] (ducklake/flush-inlined-data! duckdb))}))))

;; ---------------------------------------------------------
;; Scheduler Component

(defmethod ig/init-key :scheduler/executor
  [_ {:keys [sqlite registry tick-interval-ms]
      :or {tick-interval-ms default-tick-interval-ms}}]
  (mulog/log ::scheduler-starting)

  ;; Sync jobs: upsert registered jobs, delete orphaned jobs
  (-sync-jobs! sqlite registry)

  ;; Start scheduler loop
  (let [running-jobs (atom #{})
        ticker (-start-scheduler-loop! running-jobs sqlite registry tick-interval-ms)]
    (mulog/log ::scheduler-started :o11ylite.scheduler.tick_interval_ms tick-interval-ms)
    {:ticker ticker
     :running-jobs running-jobs}))

(defmethod ig/halt-key! :scheduler/executor
  [_ {:keys [ticker]}]
  (mulog/log ::scheduler-stopping)
  (when ticker
    (ticker/stop! ticker))
  ;; Note: Running jobs will complete in background.
  ;; This is acceptable because jobs are idempotent and
  ;; update last_run_at after completion.
  (mulog/log ::scheduler-stopped))

;; ---------------------------------------------------------
;; Public API (for testing and status page)

(defn get-job-status
  "Get status of all scheduled jobs."
  [sqlite]
  (store/get-all-jobs sqlite))

(defn trigger-job!
  "Manually trigger a job in the background.
   Returns :triggered if the job was started, :already-running if locked."
  [executor sqlite registry job-key]
  (let [running-jobs (:running-jobs executor)
        handler      (get-in registry [job-key :handler])]
    (when handler
      (if-not (-try-acquire-lock! running-jobs job-key)
        :already-running
        (do (future
              (try
                (mulog/log ::job-manual-trigger :o11ylite.scheduler.job_name job-key)
                (handler)
                (store/record-success! sqlite job-key)
                (mulog/log ::job-succeeded :o11ylite.scheduler.job_name job-key)
                (catch Exception e
                  (store/record-failure! sqlite job-key (.getMessage e))
                  (telemetry/report-error! ::job-failed e :o11ylite.scheduler.job_name job-key))
                (finally
                  (-release-lock! running-jobs job-key))))
            :triggered)))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])

  (def sqlite (:db/sqlite system))
  (def scheduler (:scheduler/executor system))

  ;; Check job status
  (get-job-status sqlite)

  ;; Check running jobs
  @(:running-jobs scheduler)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

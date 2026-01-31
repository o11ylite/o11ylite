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
   [o11ylite.store.ducklake :as ducklake]
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
    (mulog/log ::job-skipped-already-running :job job-name)
    (future
      (try
        (mulog/log ::job-starting :job job-name)
        (handler)
        (store/record-success! sqlite job-name)
        (mulog/log ::job-succeeded :job job-name)
        (catch Exception e
          (let [error-msg (.getMessage e)]
            (store/record-failure! sqlite job-name error-msg)
            (mulog/log ::job-failed :job job-name :error error-msg)))
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
          (mulog/log ::job-handler-not-found :job job-key))))))

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
              (mulog/log ::scheduler-tick-failed :error (.getMessage e))))
          (recur))))
    t))

(defn- -sync-jobs!
  "Sync jobs from registry to DB.
   Upserts all registered jobs and deletes orphaned jobs."
  [sqlite registry]
  (doseq [[job-key job-def] registry]
    (let [interval (:interval-ms job-def)]
      (store/upsert-job! sqlite job-key interval)
      (mulog/log ::job-registered :job job-key :interval-ms interval)))
  (let [job-names (mapv name (keys registry))
        result (store/delete-unrecognized-jobs! sqlite job-names)
        deleted-count (or (::jdbc/update-count (first result)) 0)]
    (when (pos? deleted-count)
      (mulog/log ::orphaned-jobs-deleted :count deleted-count))))

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
  [_ {:keys [duckdb app-config]}]
  (mulog/log ::registry-initializing)
  (let [inlined-data-flush-interval-minutes (app-config/get-setting-value app-config :inlined-data-flush-interval-minutes)
        parquet-compaction-interval-minutes (app-config/get-setting-value app-config :parquet-compaction-interval-minutes)
        daily-maintenance-interval-minutes (app-config/get-setting-value app-config :daily-maintenance-interval-minutes)
        data-retention-days (app-config/get-setting-value app-config :data-retention-days)]
    {:inlined-data-flush
     {:interval-ms (minutes->ms inlined-data-flush-interval-minutes)
      :description "Flush DuckLake inlined data to Parquet"
      :handler (fn [] (ducklake/flush-inlined-data! duckdb))}

     :parquet-compaction
     {:interval-ms (minutes->ms parquet-compaction-interval-minutes)
      :description "Merge small Parquet files for better query performance"
      :handler (fn [] (ducklake/merge-adjacent-files! duckdb))}

     :daily-maintenance
     {:interval-ms (minutes->ms daily-maintenance-interval-minutes)
      :description "Daily data retention and DuckLake maintenance"
      :handler (fn []
                 (ducklake/delete-old-data! duckdb data-retention-days)
                 (ducklake/run-checkpoint! duckdb))}}))

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
    (mulog/log ::scheduler-started :tick-interval-ms tick-interval-ms)
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

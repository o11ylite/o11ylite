;; ---------------------------------------------------------
;; o11ylite.store.scheduler
;;
;; Database operations for scheduled jobs.
;; Jobs are defined in code and persisted to SQLite for
;; restart survival and visibility.
;; ---------------------------------------------------------

(ns o11ylite.store.scheduler
  (:require
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [o11ylite.util.sql :as sql]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -now-ms
  []
  (System/currentTimeMillis))

;; ---------------------------------------------------------
;; Public API

(defn upsert-job!
  "Insert or update a job definition.
   Called on startup to sync code-defined jobs with DB."
  [sqlite job-name interval-ms]
  (let [now (-now-ms)]
    (jdbc/execute!
      sqlite
      ["INSERT INTO scheduled_jobs (job_name, interval_ms, created_at, updated_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT(job_name) DO UPDATE SET
         interval_ms = excluded.interval_ms,
         updated_at = excluded.updated_at"
       (name job-name)
       interval-ms
       now
       now])))

(defn get-due-jobs
  "Get all enabled jobs that are due to run.
   A job is due if:
   - last_run_at is NULL (never run), or
   - now - last_run_at >= interval_ms"
  [sqlite]
  (let [now (-now-ms)]
    (jdbc/execute!
      sqlite
      ["SELECT job_name, interval_ms, last_run_at, last_success_at, last_error
       FROM scheduled_jobs
       WHERE enabled = 1
         AND (last_run_at IS NULL OR ? - last_run_at >= interval_ms)"
       now]
      {:builder-fn rs/as-unqualified-lower-maps})))

(defn record-success!
  "Record a successful job execution.
   Updates last_run_at, last_success_at, and clears last_error."
  [sqlite job-name]
  (let [now (-now-ms)]
    (jdbc/execute!
      sqlite
      ["UPDATE scheduled_jobs
       SET last_run_at = ?,
           last_success_at = ?,
           last_error = NULL,
           updated_at = ?
       WHERE job_name = ?"
       now now now (name job-name)])))

(defn record-failure!
  "Record a failed job execution.
   Updates last_run_at and sets last_error."
  [sqlite job-name error-msg]
  (let [now (-now-ms)]
    (jdbc/execute!
      sqlite
      ["UPDATE scheduled_jobs
       SET last_run_at = ?,
           last_error = ?,
           updated_at = ?
       WHERE job_name = ?"
       now error-msg now (name job-name)])))

(defn get-all-jobs
  "Get all jobs with their current state.
   For system status page."
  [sqlite]
  (jdbc/execute!
    sqlite
    ["SELECT job_name, interval_ms, last_run_at, last_success_at, last_error, enabled, created_at, updated_at
     FROM scheduled_jobs
     ORDER BY job_name"]
    {:builder-fn rs/as-unqualified-lower-maps}))

(defn delete-unrecognized-jobs!
  "Delete jobs from DB that are not in the provided set of job names.
   Called on startup to clean up orphaned jobs after code changes."
  [sqlite job-names]
  (when (seq job-names)
    (let [stmt (str "DELETE FROM scheduled_jobs WHERE job_name NOT IN ("
                    (sql/in-placeholders (count job-names)) ")")]
      (jdbc/execute! sqlite (into [stmt] job-names)))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.core :as ig])

  ;; Start SQLite
  (def sqlite (ig/init-key :db/sqlite {:data-path "./.tmp"}))

  ;; Upsert a job
  (upsert-job! sqlite :test-job 60000)

  ;; Get due jobs
  (get-due-jobs sqlite)

  ;; Record success
  (record-success! sqlite :test-job)

  ;; Record failure
  (record-failure! sqlite :test-job "Something went wrong")

  ;; Get all jobs
  (get-all-jobs sqlite)

  ;; Delete unrecognized jobs (keeps only test-job)
  (delete-unrecognized-jobs! sqlite ["test-job"])

  ;; Cleanup
  (ig/halt-key! :db/sqlite sqlite)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

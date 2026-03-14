;; ---------------------------------------------------------
;; o11ylite.routes.scheduled-jobs
;;
;; Inertia page showing scheduled background jobs
;; and their current state. Supports manual triggering.
;; ---------------------------------------------------------

(ns o11ylite.routes.scheduled-jobs
  (:require
    [o11ylite.auth.middleware :as auth-mw]
    [o11ylite.components.scheduler :as scheduler]
    [o11ylite.util.response :as response]
    [ring.util.response :as rr]))

;; ---------------------------------------------------------
;; Helpers

(defn- -enrich-job
  "Merge registry metadata (description) into a DB job row.
   Computes next_run_at from last_run_at + interval_ms."
  [registry {:keys [job_name interval_ms last_run_at] :as job}]
  (let [reg-entry (get registry (keyword job_name))
        next-run  (when last_run_at
                    (+ last_run_at interval_ms))]
    (assoc job
           :description (or (:description reg-entry) "")
           :next_run_at next-run)))

;; ---------------------------------------------------------
;; Handlers

(defn- -make-list-handler
  "GET /system/jobs - List all scheduled jobs with status."
  [{:keys [sqlite scheduler-registry]}]
  (fn [_request]
    (let [jobs     (scheduler/get-job-status sqlite)
          enriched (mapv (partial -enrich-job scheduler-registry) jobs)]
      (response/inertia "ScheduledJobs" {:jobs enriched}))))

(defn- -make-trigger-handler
  "POST /system/jobs/:name/trigger - Manually trigger a job in the background."
  [{:keys [sqlite scheduler-registry scheduler-executor]}]
  (fn [request]
    (let [job-name (get-in request [:path-params :name])
          job-key  (keyword job-name)
          result   (scheduler/trigger-job! scheduler-executor sqlite scheduler-registry job-key)
          message  (case result
                     :triggered       (str job-name " triggered")
                     :already-running (str job-name " is already running")
                     (str job-name " not found"))]
      (-> (rr/redirect "/system/jobs" :see-other)
          (assoc :flash {:message message})))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "Scheduled jobs status routes. Requires admin scope when auth is active."
  [{:keys [auth-config] :as opts}]
  (let [middleware (when-not (:open-mode? auth-config)
                     [(auth-mw/make-wrap-require-scope "admin")])]
    ["/system/jobs" {:middleware middleware}
     ["" {:get {:handler (-make-list-handler opts)}}]
     ["/:name/trigger" {:post {:handler (-make-trigger-handler opts)}}]]))

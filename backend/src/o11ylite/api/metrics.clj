;; ---------------------------------------------------------
;; o11ylite.api.metrics
;;
;; Metrics metadata API endpoints.
;; Provides access to metric definitions for the frontend picker UI.
;;
;; Endpoints:
;;   GET /api/metrics       - List all metrics (lightweight summary)
;;   GET /api/metrics/:name - Get detailed metadata for a specific metric
;; ---------------------------------------------------------

(ns o11ylite.api.metrics
  (:require
    [o11ylite.store.metrics.metadata :as metadata]
    [o11ylite.util.response :as response]))

;; ---------------------------------------------------------
;; Handlers

(defn- -list-handler
  "List all metrics with lightweight summary.
   Returns [{:name :metric_type :unit} ...]."
  [sqlite]
  (fn [_request]
    (response/json 200 (metadata/list-metrics-summary sqlite))))

(defn- -detail-handler
  "Get detailed metadata for a specific metric.
   Returns full metadata or 404 if not found."
  [sqlite]
  (fn [request]
    (let [metric-name (get-in request [:path-params :name])
          metric (metadata/get-metric sqlite metric-name)]
      (if metric
        (response/json 200 (update metric :attributes #(some-> % sort vec)))
        (response/json 404 {:error "metric_not_found"
                            :name metric-name})))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "Metrics API routes.
   
   Arguments:
     opts - Map with :sqlite component"
  [{:keys [sqlite]}]
  [["/metrics"
    ["" {:get {:handler (-list-handler sqlite)}}]
    ["/:name" {:get {:handler (-detail-handler sqlite)}}]]])

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example: List all metrics
  ;; GET /api/metrics
  ;; => [{:name "cpu.utilization" :metric_type :gauge :unit "%"}
  ;;     {:name "http.server.duration" :metric_type :histogram :unit "ms"}
  ;;     ...]

  ;; Example: Get specific metric
  ;; GET /api/metrics/http.server.duration
  ;; => {:name "http.server.duration"
  ;;     :description "Duration of HTTP server requests"
  ;;     :unit "ms"
  ;;     :metric_type :histogram
  ;;     :temporality :delta
  ;;     :attributes ["http.method" "http.route" "http.status_code"]
  ;;     :hist_boundaries [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2.5 5 10]}

  ;; Example: Metric not found
  ;; GET /api/metrics/nonexistent
  ;; => {:error "metric_not_found" :name "nonexistent"}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

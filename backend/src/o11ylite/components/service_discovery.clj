;; ---------------------------------------------------------
;; o11ylite.components.service-discovery
;;
;; Service discovery with background scanning.
;; Periodically scans DuckDB for new services and registers them.
;; ---------------------------------------------------------

(ns o11ylite.components.service-discovery
  (:require
   [integrant.core :as ig]
   [com.brunobonacci.mulog :as mulog]
   [o11ylite.store.services :as services]
   [o11ylite.util.ticker :as ticker]))

;; ---------------------------------------------------------
;; Configuration

(def ^:private default-scan-interval-ms
  "Default scan interval (5 minutes)."
  (* 5 60 1000))

(def ^:private scan-buffer-ms
  "Extra buffer added to scan interval for scan window.
   Ensures we don't miss events at the boundary."
  (* 30 1000))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -discover!
  "Discover and register new services from recent telemetry."
  [sqlite duckdb scan-window-ms]
  (let [discovered (services/scan-services duckdb scan-window-ms)]
    (when (seq discovered)
      (services/register-services! sqlite discovered))
    (mulog/log ::services-discovered :count (count discovered))))

(defn- -start-discovery-loop
  "Start background discovery loop using ticker."
  [sqlite duckdb scan-interval-ms]
  (let [scan-window-ms (+ scan-interval-ms scan-buffer-ms)
        t (ticker/ticker scan-interval-ms)]
    (future
      (loop []
        (when (ticker/tick! t)
          (try
            (-discover! sqlite duckdb scan-window-ms)
            (catch Exception e
              (mulog/log ::service-discovery-failed :error (.getMessage e))))
          (recur))))
    t))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :discovery/services
  [_ {:keys [sqlite duckdb scan-interval-ms]
      :or {scan-interval-ms default-scan-interval-ms}}]
  (mulog/log ::service-discovery-starting :scan-interval-ms scan-interval-ms)
  (let [scan-window-ms (+ scan-interval-ms scan-buffer-ms)]
    ;; Initial discovery
    (-discover! sqlite duckdb scan-window-ms)
    ;; Start background loop
    (let [ticker (-start-discovery-loop sqlite duckdb scan-interval-ms)]
      (mulog/log ::service-discovery-started)
      {:ticker ticker})))

(defmethod ig/halt-key! :discovery/services
  [_ {:keys [ticker]}]
  (when ticker
    (ticker/stop! ticker))
  (mulog/log ::service-discovery-stopped))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[o11ylite.store.services :as services])
  (require '[integrant.repl.state :refer [system]])

  (def sqlite (:db/sqlite system))
  (def duckdb (:db/duckdb system))

  ;; Get services via store/services
  (services/get-services sqlite)
  ;; => [{:service "api-gateway" :first_seen_at 1702000000000 :updated_at 1702000000000} ...]

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

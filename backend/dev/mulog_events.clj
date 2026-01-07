;; ---------------------------------------------------------
;; Mulog Dev Initialization
;;
;; Initializes mulog with dev profile for REPL workflow.
;; Uses console (pretty) + OpenTelemetry publishers.
;; ---------------------------------------------------------

(ns mulog-events
  (:require
   [o11ylite.mulog :as mulog]))

;; ---------------------------------------------------------
;; Initialize mulog for dev profile
;; - console (pretty) for immediate feedback
;; - OpenTelemetry for dogfooding (with 5s delay for server startup)

(def publisher
  "Mulog publisher for REPL workflow.
   Call (publisher) to stop."
  (mulog/init! :dev))

(defn stop
  "Stop mulog publisher"
  []
  (publisher))
;; ---------------------------------------------------------

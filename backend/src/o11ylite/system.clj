;; ---------------------------------------------------------
;; o11ylite.system
;;
;; Integrant system configuration and lifecycle
;; ---------------------------------------------------------

(ns o11ylite.system
  (:require
    [aero.core :as aero]
    [clojure.java.io :as io]
    [integrant.core :as ig]
    [com.brunobonacci.mulog :as mulog]
    ;; Load component namespaces for ig/init-key methods
    [o11ylite.components.core-config]
    [o11ylite.components.app-config]
    [o11ylite.components.duckdb-pool]
    [o11ylite.components.sqlite-pool]
    [o11ylite.components.id-gen]
    [o11ylite.components.storage-init]
    [o11ylite.components.blocked-fields]
    [o11ylite.components.event-metadata]
    [o11ylite.components.service-discovery]
    [o11ylite.components.telemetry-catalog-buffer]
    [o11ylite.components.event-batcher]
    [o11ylite.components.metric-batcher]
    [o11ylite.components.scheduler]
    [o11ylite.components.otel-grpc-server]
    [o11ylite.components.inertia]
    [o11ylite.components.auth-config]
    [o11ylite.components.api-key-cache]
    [o11ylite.components.duckdb-metrics]
    [o11ylite.components.router]
    [o11ylite.components.web-server])
  (:import
    [java.util.concurrent Executors]))

;; Make futures use virtual threads
;; How I know? Reading the source code found future use the same executor with agent
(set-agent-send-executor! (Executors/newVirtualThreadPerTaskExecutor))
(set-agent-send-off-executor! (Executors/newVirtualThreadPerTaskExecutor))

;; ---------------------------------------------------------
;; Configuration

(defmethod aero/reader 'ig/ref
  [_ _ value]
  (ig/ref value))

(defn read-config
  "Read the system configuration from system.edn.
   Note: All configuration values are now defined in core-config and
   app-config components, not in this file."
  []
  (aero/read-config (io/resource "system.edn")))

;; ---------------------------------------------------------
;; System Lifecycle

(defn start
  "Start the system."
  []
  (let [config (read-config)]
    (mulog/log ::system-starting)
    (ig/init config)))

(defn stop
  "Stop the running system."
  [system]
  (mulog/log ::system-stopping)
  (ig/halt! system))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (def sys (start))
  (stop sys)

  (read-config)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

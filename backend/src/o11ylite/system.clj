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
   [o11ylite.components.duckdb-pool]
   [o11ylite.components.otel-grpc-server]
   [o11ylite.components.inertia]
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
  "Read and parse the system configuration file using Aero."
  ([] (read-config :default))
  ([profile]
   (aero/read-config (io/resource "system.edn") {:profile profile})))

;; ---------------------------------------------------------
;; System Lifecycle

(defn start
  "Start the system with the given profile."
  ([] (start :default))
  ([profile]
   (let [config (read-config profile)]
     (mulog/log ::system-starting :profile profile)
     (ig/init config))))

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

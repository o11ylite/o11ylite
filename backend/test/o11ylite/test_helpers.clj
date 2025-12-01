;; ---------------------------------------------------------
;; o11ylite.test-helpers
;;
;; Shared test utilities for integration tests.
;; Re-exports helpers from sub-namespaces for convenience.
;; ---------------------------------------------------------

(ns o11ylite.test-helpers
  (:require
   [integrant.core :as ig]
   [o11ylite.system :as system]
   [o11ylite.test-helpers.http :as http]
   [o11ylite.test-helpers.otlp :as otlp]))

;; ---------------------------------------------------------
;; Test Configuration

(def test-http-port http/test-port)
(def test-grpc-port otlp/test-port)

;; ---------------------------------------------------------
;; System Lifecycle

(def ^:dynamic *system*
  "Dynamic var holding the running test system"
  nil)

(defn start-test-system!
  "Start the system with test configuration."
  []
  (let [config (-> (system/read-config :dev)
                   (assoc-in [:server/web :port] test-http-port)
                   (assoc-in [:server/web :host] "127.0.0.1")
                   (assoc-in [:server/otel-grpc :port] test-grpc-port))]
    (ig/init config)))

(defn stop-test-system!
  "Stop the test system."
  [system]
  (ig/halt! system))

(defn with-system
  "Test fixture that starts/stops the system for each test.

   Usage:
   (use-fixtures :each test-helpers/with-system)"
  [f]
  (let [sys (start-test-system!)]
    (try
      (binding [*system* sys]
        (f))
      (finally
        (stop-test-system! sys)))))

;; ---------------------------------------------------------
;; Re-exports: HTTP helpers

(def url http/url)
(def get-request http/get-request)
(def get-json http/get-json)
(def inertia-headers http/inertia-headers)
(def inertia-request http/inertia-request)
(def inertia-json-request http/inertia-json-request)
(def status http/status)
(def header http/header)
(def body http/body)
(def content-type http/content-type)
(def json-response? http/json-response?)
(def html-response? http/html-response?)

;; ---------------------------------------------------------
;; Re-exports: OTLP helpers

(def build-span otlp/build-span)
(def build-trace-request otlp/build-trace-request)
(def export-traces! otlp/export-traces!)

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
   [o11ylite.test-helpers.event-ingest :as event-ingest]
   [o11ylite.test-helpers.http :as http]
   [o11ylite.test-helpers.otlp :as otlp])
  (:import
   [java.io File]))

;; ---------------------------------------------------------
;; Test Configuration

(def test-http-port http/test-port)
(def test-grpc-port otlp/test-port)

;; ---------------------------------------------------------
;; System Lifecycle

(def ^:dynamic *system*
  "Dynamic var holding the running test system"
  nil)

(defn- create-temp-data-path
  "Create a unique temporary directory for test data."
  []
  (let [dir (File/createTempFile "o11ylite-test-" "")]
    (.delete dir)
    (.mkdirs dir)
    (.getAbsolutePath dir)))

(defn test-config
  "Build test configuration with test-specific overrides."
  []
  (let [temp-path (create-temp-data-path)]
    (-> (system/read-config :dev)
        (assoc-in [:server/web :port] test-http-port)
        (assoc-in [:server/web :host] "127.0.0.1")
        (assoc-in [:server/otel-grpc :port] test-grpc-port)
        (assoc-in [:db/duckdb :data-path] temp-path)
        (assoc-in [:db/sqlite :data-path] temp-path)
        ;; Fast flush interval for tests (100ms)
        (assoc-in [:ingest/batcher :flush-interval-ms] 100)
        ;; Fast service discovery for tests (100ms)
        (assoc-in [:discovery/services :scan-interval-ms] 100)
        ;; Fast inlined data flusher for tests (100ms)
        (assoc-in [:ingest/inlined-data-flusher :flush-interval-ms] 100))))

(defn start-system!
  "Start the full system with test configuration."
  []
  (ig/init (test-config)))

(defn start-partial-system!
  "Start only specified components (and their dependencies).
   
   Example:
     (start-partial-system! [:discovery/services])
     ;; Starts :discovery/services, :db/sqlite, :db/duckdb, :storage/init"
  [keys]
  (ig/init (test-config) keys))

(defn stop-system!
  "Stop the test system."
  [system]
  (ig/halt! system))

(defn with-system
  "Test fixture that starts/stops the full system for each test.

   Usage:
   (use-fixtures :each h/with-system)"
  [f]
  (let [sys (start-system!)]
    (try
      (binding [*system* sys]
        (f))
      (finally
        (stop-system! sys)))))

(defn with-partial-system
  "Create a test fixture that starts only specified components.
   
   Usage:
   (use-fixtures :each (h/with-partial-system [:discovery/services]))"
  [keys]
  (fn [f]
    (let [sys (start-partial-system! keys)]
      (try
        (binding [*system* sys]
          (f))
        (finally
          (stop-system! sys))))))

;; ---------------------------------------------------------
;; Re-exports: HTTP helpers

(def url http/url)
(def get-request http/get-request)
(def get-json http/get-json)
(def post-json http/post-json)
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

(def build-log-record otlp/build-log-record)
(def build-logs-request otlp/build-logs-request)
(def export-logs! otlp/export-logs!)

;; ---------------------------------------------------------
;; Re-exports: Event ingest helpers

(def make-random-event event-ingest/make-random-event)
(def make-random-events event-ingest/make-random-events)
(def ingest-events! event-ingest/ingest-events!)
(def ingest-sample-events! event-ingest/ingest-sample-events!)

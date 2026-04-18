;; ---------------------------------------------------------
;; o11ylite.test-helpers
;;
;; Shared test utilities for integration tests.
;; Re-exports helpers from sub-namespaces for convenience.
;; ---------------------------------------------------------

(ns o11ylite.test-helpers
  (:require
    [integrant.core :as ig]
    [jsonista.core :as json]
    [o11ylite.system :as system]
    [o11ylite.test-helpers.event-ingest :as event-ingest]
    [o11ylite.test-helpers.fake-oidc :as fake-oidc]
    [o11ylite.test-helpers.http :as http]
    [o11ylite.test-helpers.metric-ingest :as metric-ingest]
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

(defn- base-test-config
  "Build base test configuration with test-specific overrides."
  []
  (let [temp-path (create-temp-data-path)]
    (-> (system/read-config)
        (assoc-in [:config/core :data-path] temp-path)
        (assoc-in [:config/core :host] "127.0.0.1")
        (assoc-in [:config/core :web-port] test-http-port)
        (assoc-in [:config/core :otel-grpc-port] test-grpc-port)
        (assoc-in [:config/core :dev?] true)
        ;; Fast flush interval for tests (100ms)
        (assoc-in [:config/app :ingest-flush-interval-ms] 100)
        (assoc-in [:config/app :metric-flush-interval-ms] 100)
        ;; Fast scheduler intervals for tests (in minutes for job intervals)
        (assoc-in [:config/app :inlined-data-flush-interval-minutes] 1)
        (assoc-in [:config/app :compaction-small-interval-minutes] 1)
        (assoc-in [:config/app :compaction-medium-interval-minutes] 1)
        (assoc-in [:config/app :compaction-large-interval-minutes] 1)
        (assoc-in [:config/app :daily-maintenance-interval-minutes] 1)
        ;; Fast scheduler tick for tests (100ms)
        (assoc-in [:scheduler/executor :tick-interval-ms] 100))))

(defn test-config
  "Build test configuration for full system tests.

   The scheduler runs normally. Data inlining is disabled by default
   (DATA_INLINING_ROW_LIMIT=0), so the flush_inlined_data job that triggered
   the DuckLake concurrency bug (duckdb/ducklake#650) is never registered."
  []
  (base-test-config))

(defn start-system!
  "Start the full system with test configuration."
  []
  (ig/init (test-config)))

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

(defn start-partial-system!
  "Start only specified components (and their dependencies).
   Optional config-overrides map is deep-merged into the base test config,
   keyed by integrant component (e.g. {:config/core {:data-inlining-row-limit 1000}}).
   
   Example:
     (start-partial-system! [:discovery/telemetry-catalog-buffer])
     ;; Starts :discovery/telemetry-catalog-buffer and its dependencies"
  ([keys] (start-partial-system! keys {}))
  ([keys config-overrides]
   (let [config (reduce-kv (fn [cfg k overrides]
                             (update cfg k merge overrides))
                           (base-test-config)
                           config-overrides)]
     (ig/init config keys))))

(defn with-partial-system
  "Create a test fixture that starts only specified components.
   Optional config-overrides map is deep-merged into the base test config,
   keyed by integrant component (e.g. {:config/core {:data-inlining-row-limit 1000}}).
   
   Usage:
   (use-fixtures :each (h/with-partial-system [:discovery/telemetry-catalog-buffer]))
   (use-fixtures :each (h/with-partial-system [:scheduler/executor]
                                              {:config/core {:data-inlining-row-limit 1000}}))"
  ([keys] (with-partial-system keys {}))
  ([keys config-overrides]
   (fn [f]
     (let [sys (start-partial-system! keys config-overrides)]
       (try
         (binding [*system* sys]
           (f))
         (finally
           (stop-system! sys)))))))

;; ---------------------------------------------------------
;; OIDC Test Support

(defn oidc-test-config
  "Build test configuration with OIDC enabled, pointing at a fake IdP."
  [idp-base-url]
  (-> (test-config)
      (assoc-in [:config/core :oidc-issuer-url] idp-base-url)
      (assoc-in [:config/core :oidc-client-id] "test-client")))

(defn with-oidc-system
  "Test fixture that starts a fake OIDC IdP and system with OIDC enabled.
   The fake IdP must be running before the system starts (OIDC discovery
   happens during ig/init-key :auth/config).

   Usage:
   (use-fixtures :each h/with-oidc-system)"
  [f]
  (let [idp (fake-oidc/start-server)]
    (try
      (let [sys (ig/init (oidc-test-config (:base-url idp)))]
        (try
          (binding [*system* sys]
            (f))
          (finally
            (stop-system! sys))))
      (finally
        (fake-oidc/stop-server idp)))))

;; ---------------------------------------------------------
;; Component Groups
;; Centralized lists for use in with-partial-system fixtures.

(def event-ingest-components
  "Components required for event ingestion."
  [:cache/event-metadata :cache/blocked-fields :ingest/event-batcher :id/generator
   :discovery/telemetry-catalog-buffer])

(def metric-ingest-components
  "Components required for metric ingestion."
  [:cache/blocked-fields :ingest/metric-batcher :norm/metric-temporality
   :discovery/telemetry-catalog-buffer])

(def ingest-components
  "All components required for event and metric ingestion."
  (into event-ingest-components metric-ingest-components))

;; ---------------------------------------------------------
;; JSON Utilities

(defn ->json
  "Convert Clojure data to JSON string."
  [data]
  (json/write-value-as-string data))

;; ---------------------------------------------------------
;; Re-exports: HTTP helpers

(def url http/url)
(def get-request http/get-request)
(def get-json http/get-json)
(def post http/post)
(def post-json http/post-json)
(def inertia-headers http/inertia-headers)
(def inertia-request http/inertia-request)
(def inertia-json-request http/inertia-json-request)
(def put-json http/put-json)
(def delete-request http/delete-request)
(def csrf-session http/csrf-session)
(def csrf-headers http/csrf-headers)
(def post-mutation http/post-mutation)
(def put-mutation http/put-mutation)
(def delete-mutation http/delete-mutation)
(def status http/status)
(def header http/header)
(def body http/body)
(def content-type http/content-type)
(def json-response? http/json-response?)
(def html-response? http/html-response?)

(def no-redirect-get http/no-redirect-get)
(def no-redirect-post http/no-redirect-post)
(def extract-session-cookie http/extract-session-cookie)

;; ---------------------------------------------------------
;; Re-exports: OTLP helpers

(def build-span otlp/build-span)
(def build-trace-request otlp/build-trace-request)
(def export-traces! otlp/export-traces!)

(def build-log-record otlp/build-log-record)
(def build-logs-request otlp/build-logs-request)
(def export-logs! otlp/export-logs!)

(def build-gauge-metric otlp/build-gauge-metric)
(def build-sum-metric otlp/build-sum-metric)
(def build-histogram-metric otlp/build-histogram-metric)
(def build-metrics-request otlp/build-metrics-request)
(def export-metrics! otlp/export-metrics!)

;; ---------------------------------------------------------
;; Re-exports: Event ingest helpers

(def make-random-event event-ingest/make-random-event)
(def make-random-events event-ingest/make-random-events)
(def ingest-events! event-ingest/ingest-events!)

(defn ingest-sample-events!
  "Ingest n random events using components from *system*."
  ([n] (ingest-sample-events! n {}))
  ([n overrides]
   (event-ingest/ingest-sample-events!
     (:cache/event-metadata *system*)
     (:cache/blocked-fields *system*)
     (:ingest/event-batcher *system*)
     (:id/generator *system*)
     n
     overrides)))

;; ---------------------------------------------------------
;; Re-exports: Metric ingest helpers

(def make-random-metric-data-point metric-ingest/make-random-metric-data-point)
(def make-random-metric-data-points metric-ingest/make-random-metric-data-points)
(def make-metrics-metadata metric-ingest/make-metrics-metadata)
(def ingest-metrics! metric-ingest/ingest-metrics!)

(defn ingest-sample-metrics!
  "Ingest n random metrics using components from *system*."
  ([n] (ingest-sample-metrics! n {}))
  ([n overrides]
   (metric-ingest/ingest-sample-metrics!
     (:ingest/metric-batcher *system*)
     (:cache/blocked-fields *system*)
     (:db/sqlite *system*)
     (:norm/metric-temporality *system*)
     n
     overrides)))

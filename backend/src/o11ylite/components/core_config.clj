;; ---------------------------------------------------------
;; o11ylite.components.core-config
;;
;; Core configuration component for static, environment-based settings.
;; These values are read-only and cannot be changed at runtime.
;; ---------------------------------------------------------

(ns o11ylite.components.core-config
  (:require
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mulog]
    [integrant.core :as ig]))

;; ---------------------------------------------------------
;; Core Configuration Definitions
;;
;; Each entry defines: key, env var, default, parser.
;; This is the single source of truth for all core settings.
;; ---------------------------------------------------------

(def ^:private core-config-defs
  "Configuration definitions: key, env var, default, parser."
  [{:key         :data-path
    :env-var     "O11YLITE_DATA_PATH"
    :default     "./.tmp"
    :parser      identity
    :description "Directory for DuckDB and SQLite data files."}

   {:key         :node-id
    :env-var     "O11YLITE_NODE_ID"
    :default     0
    :parser      #(Long/parseLong %)
    :description "Node ID for distributed ID generation (Snowflake-style)."}

   {:key         :host
    :env-var     "O11YLITE_WEB_HOST"
    :default     "0.0.0.0"
    :parser      identity
    :description "Host interface the web server binds to."}

   {:key         :web-port
    :env-var     "O11YLITE_WEB_PORT"
    :default     3000
    :parser      #(Long/parseLong %)
    :description "HTTP port for the web server."}

   {:key         :otel-grpc-port
    :env-var     "O11YLITE_OTEL_GRPC_PORT"
    :default     4317
    :parser      #(Long/parseLong %)
    :description "gRPC port for OpenTelemetry data ingestion."}

   {:key         :asset-base-url
    :env-var     "O11YLITE_ASSET_BASE_URL"
    :default     "/frontend"
    :parser      identity
    :description "Base URL path for serving static frontend assets."}

   {:key         :frontend-manifest-path
    :env-var     "O11YLITE_FRONTEND_MANIFEST_PATH"
    :default     ".vite/manifest.json"
    :parser      identity
    :description "Path to the Vite manifest file (production only)."}

   {:key         :frontend-entry-point
    :env-var     "O11YLITE_FRONTEND_ENTRY_POINT"
    :default     "src/main.tsx"
    :parser      identity
    :description "Frontend entry point matching vite.config.ts input."}

   {:key         :dev?
    :env-var     "O11YLITE_DEV"
    :default     false
    :parser      #(= "true" %)
    :description "Development mode: enables Vite dev server integration."}

   {:key         :runtime-app-config?
    :env-var     "O11YLITE_ENABLE_RUNTIME_APP_CONFIG"
    :default     false
    :parser      #(= "true" %)
    :description "Allow runtime configuration changes via the KV store."}

   {:key         :oidc-issuer-url
    :env-var     "O11YLITE_OIDC_ISSUER_URL"
    :default     nil
    :parser      identity
    :description "OIDC provider issuer URL. Enables authentication when set."}

   {:key         :oidc-client-id
    :env-var     "O11YLITE_OIDC_CLIENT_ID"
    :default     nil
    :parser      identity
    :description "OIDC client identifier."}

   {:key          :oidc-client-secret
    :env-var      "O11YLITE_OIDC_CLIENT_SECRET"
    :default      nil
    :parser       identity
    :description  "OIDC client secret for token exchange."
    :credential?  true}

   {:key          :session-secret
    :env-var      "O11YLITE_SESSION_SECRET"
    :default      nil
    :parser       identity
    :description  "Encryption key for session cookies (16-byte hex)."
    :credential?  true}

   {:key         :duckdb-memory-limit-pct
    :env-var     "O11YLITE_DUCKDB_MEMORY_LIMIT_PCT"
    :default     0
    :parser      #(Long/parseLong %)
    :description "DuckDB memory limit as percentage of system RAM (1-100). 0 = unlimited (DuckDB default: ~80%)."}

   ;; DuckLake data inlining row limit (opt-in, default 0 = disabled).
   ;;
   ;; Data inlining stores small writes directly in the metadata catalog (SQLite)
   ;; instead of creating individual Parquet files. This avoids tiny Parquet files
   ;; from trickle inserts. However, we disable it by default because:
   ;;
   ;; 1. DuckLake's data inlining is not yet stable (DuckLake is pre-1.0).
   ;; 2. OTLP collectors/exporters already batch aggressively (typically 512-8192
   ;;    spans per batch), so our ingestion pipeline rarely produces single-row
   ;;    inserts that would benefit from inlining.
   ;; 3. The merge_adjacent_files maintenance job already compacts small Parquet
   ;;    files, providing a stable alternative for the small-file problem.
   ;;
   ;; WARNING: Do NOT set this to a high value (e.g. DuckLake's old default of
   ;; 100,000). Our ingestion pipeline does bulk INSERT INTO ... SELECT from a
   ;; staging table, pushing 1K-50K rows per statement. When the inlining limit
   ;; is higher than the batch size, those entire batches get inlined into the
   ;; SQLite metadata catalog instead of going to Parquet, causing significant
   ;; overhead that caps throughput at ~1k rows/s. If you opt in, keep the limit
   ;; low (e.g. 1000) so only genuinely small trickle inserts get inlined while
   ;; normal batched ingestion bypasses inlining entirely.
   ;;
   ;; Set to a positive integer (e.g., 1000) to opt in. Inserts with fewer rows
   ;; than this limit will be inlined; larger inserts go directly to Parquet.
   ;; When enabled, a scheduled flush job periodically materializes inlined data.
   {:key         :data-inlining-row-limit
    :env-var     "O11YLITE_DATA_INLINING_ROW_LIMIT"
    :default     0
    :parser      #(Long/parseLong %)
    :description "DuckLake data inlining row limit (0 = disabled). Keep low if enabled."}])

;; ---------------------------------------------------------
;; Generated Configuration Maps
;; ---------------------------------------------------------

(def ^:private default-core-config
  "Default values as a map, generated from core-config-defs."
  (into {} (map (juxt :key :default)) core-config-defs))

;; ---------------------------------------------------------
;; Private Helpers
;; ---------------------------------------------------------

(defn- -load-from-env
  "Load configuration from environment variables based on definitions.
   Returns a map of config keys to parsed values.
   Keys with nil/blank env vars are excluded from the result."
  []
  (into {} (keep (fn [{:keys [key env-var parser]}]
                   (when-let [raw-value (System/getenv env-var)]
                     (when-not (str/blank? raw-value)
                       (try
                         [key (parser raw-value)]
                         (catch Exception _
                           nil)))))
                 core-config-defs)))

;; ---------------------------------------------------------
;; Component Lifecycle
;; ---------------------------------------------------------

(defmethod ig/init-key :config/core
  [_ config-map]
  (let [env-config (-load-from-env)
        ;; Precedence: config-map overrides (for tests) > env vars > defaults
        config (-> default-core-config
                   (merge env-config)
                   (merge config-map))]
    (mulog/log ::core-config-loaded
               :data-path (:data-path config)
               :host (:host config)
               :web-port (:web-port config)
               :otel-grpc-port (:otel-grpc-port config)
               :dev? (:dev? config))
    config))

(defmethod ig/halt-key! :config/core
  [_ _]
  (mulog/log ::core-config-stopped))

;; ---------------------------------------------------------
;; Documentation Helper
;; ---------------------------------------------------------

(def ^:private list-config-keys
  [:key :env-var :default :description :credential?])

(defn list-config
  "Returns a list of all core configuration options with their
defaults, environment variable names, and descriptions."
  []
  (mapv #(select-keys % list-config-keys) core-config-defs))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; View all configuration options
  ;; (list-config)
  ;; => [{:key :data-path, :env-var "DATA_PATH", :default "./.tmp", ...}]
  ;;
  ;; View defaults map
  ;; default-core-config
  ;; => {:data-path "./.tmp", :node-id 0, ...}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

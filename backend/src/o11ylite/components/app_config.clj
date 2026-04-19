;; ---------------------------------------------------------
;; o11ylite.components.app-config
;;
;; App configuration component for dynamic, runtime-configurable settings.
;; Settings are resolved at query time with precedence: KV store → env var → default
;; ---------------------------------------------------------

(ns o11ylite.components.app-config
  (:require
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mulog]
    [integrant.core :as ig]
    [o11ylite.kv :as kv]))

;; ---------------------------------------------------------
;; App Configuration Definitions
;;
;; Each entry defines: key, env var, default, parser.
;; This is the single source of truth for all app settings.
;; ---------------------------------------------------------

(def ^:private app-config-defs
  "Configuration definitions: key, env var, default, parser."
  [{:key         :ingest-flush-interval-ms
    :env-var     "O11YLITE_INGEST_FLUSH_INTERVAL_MS"
    :default     1000
    :parser      #(Long/parseLong %)
    :description "Interval between event batch flushes to storage (ms)."}

   {:key         :metric-normalizer-ttl-ms
    :env-var     "O11YLITE_METRIC_NORMALIZER_TTL_MS"
    :default     1800000
    :parser      #(Long/parseLong %)
    :description "TTL for metric normalizer series state (ms)."}

   {:key         :metric-normalizer-cleanup-ms
    :env-var     "O11YLITE_METRIC_NORMALIZER_CLEANUP_MS"
    :default     60000
    :parser      #(Long/parseLong %)
    :description "Cleanup interval for expired metric normalizer entries (ms)."}

   {:key         :metric-flush-interval-ms
    :env-var     "O11YLITE_METRIC_FLUSH_INTERVAL_MS"
    :default     1000
    :parser      #(Long/parseLong %)
    :description "Interval between metric batch flushes to storage (ms)."}

   {:key         :inlined-data-flush-interval-minutes
    :env-var     "O11YLITE_INLINED_DATA_FLUSH_INTERVAL_MINUTES"
    :default     15
    :parser      #(Long/parseLong %)
    :description "How often inlined data is flushed to Parquet files (min)."}

   {:key         :compaction-max-files-per-batch
    :env-var     "O11YLITE_COMPACTION_MAX_FILES_PER_BATCH"
    :default     10
    :parser      #(Long/parseLong %)
    :description "Max output files per compaction batch for medium/large tiers."}

   {:key         :compaction-small-interval-minutes
    :env-var     "O11YLITE_COMPACTION_SMALL_INTERVAL_MINUTES"
    :default     5
    :parser      #(Long/parseLong %)
    :description "How often to compact small files (<1MB) into ~5MB targets (min)."}

   {:key         :compaction-medium-interval-minutes
    :env-var     "O11YLITE_COMPACTION_MEDIUM_INTERVAL_MINUTES"
    :default     15
    :parser      #(Long/parseLong %)
    :description "How often to compact medium files (1-10MB) into ~32MB targets (min)."}

   {:key         :compaction-large-interval-minutes
    :env-var     "O11YLITE_COMPACTION_LARGE_INTERVAL_MINUTES"
    :default     60
    :parser      #(Long/parseLong %)
    :description "How often to compact large files (10–128MB) into ~256MB targets (min)."}

   {:key         :snapshot-cleanup-interval-minutes
    :env-var     "O11YLITE_SNAPSHOT_CLEANUP_INTERVAL_MINUTES"
    :default     30
    :parser      #(Long/parseLong %)
    :description "How often to expire old snapshots and clean up superseded files (min)."}

   {:key         :daily-maintenance-interval-minutes
    :env-var     "O11YLITE_DAILY_MAINTENANCE_INTERVAL_MINUTES"
    :default     1440
    :parser      #(Long/parseLong %)
    :description "Interval for daily maintenance tasks like retention cleanup (min)."}

   {:key         :data-retention-days
    :env-var     "O11YLITE_DATA_RETENTION_DAYS"
    :default     30
    :parser      #(Long/parseLong %)
    :description "Number of days to retain trace and metric data."}

   {:key         :telemetry-catalog-gc-interval-minutes
    :env-var     "O11YLITE_TELEMETRY_CATALOG_GC_INTERVAL_MINUTES"
    :default     1440
    :parser      #(Long/parseLong %)
    :description "Interval for telemetry catalog GC (drops unused event columns and metric metadata) (min)."}

   {:key         :webhook-url
    :env-var     "O11YLITE_WEBHOOK_URL"
    :default     nil
    :parser      identity
    :description "Webhook URL for alert notifications (Alertmanager-compatible)."}])

;; ---------------------------------------------------------
;; Generated Configuration Maps
;; ---------------------------------------------------------

(def ^:private default-settings
  "Default values as a map, generated from app-config-defs."
  (into {} (map (juxt :key :default)) app-config-defs))

;; ---------------------------------------------------------
;; Private Helpers
;; ---------------------------------------------------------

(defn- -load-env-values
  "Load configuration values from environment variables based on definitions.
   Returns a map of setting keys to parsed values.
   Keys with nil/blank env vars are excluded from the result."
  []
  (into {} (keep (fn [{:keys [key env-var parser]}]
                   (when-let [raw-value (System/getenv env-var)]
                     (when-not (str/blank? raw-value)
                       (try
                         [key (parser raw-value)]
                         (catch Exception _
                           nil)))))
                 app-config-defs)))

(defn- -resolve-setting
  "Resolve a setting at runtime with precedence: KV (if enabled) → env → default.
   Returns a map with :value and :source."
  [sqlite env-values runtime-app-config? setting-key]
  (let [env-value (get env-values setting-key)
        default-value (get default-settings setting-key)]
    (if runtime-app-config?
      ;; KV override enabled: check KV store first
      (if-let [kv-value (kv/get-value sqlite setting-key)]
        {:value kv-value :source :kv}
        (if env-value
          {:value env-value :source :env}
          {:value default-value :source :default}))
      ;; KV override disabled: skip KV, go env → default
      (if env-value
        {:value env-value :source :env}
        {:value default-value :source :default}))))

;; ---------------------------------------------------------
;; Component Lifecycle
;; ---------------------------------------------------------

(defmethod ig/init-key :config/app
  [_ {:keys [sqlite core-config] :as config-map}]
  (let [env-values (-load-env-values)
        ;; Extract test overrides (keys that exist in default-settings)
        test-overrides (select-keys config-map (keys default-settings))
        ;; Precedence: test overrides > env vars
        merged-values (merge env-values test-overrides)
        ;; Check if runtime config override is enabled
        runtime-app-config? (get core-config :runtime-app-config? false)]
    (mulog/log ::app-config-loaded
               :runtime-app-config? runtime-app-config?)
    {:sqlite sqlite
     :env-values merged-values
     :runtime-app-config? runtime-app-config?}))

(defmethod ig/halt-key! :config/app
  [_ _]
  (mulog/log ::app-config-stopped))

;; ---------------------------------------------------------
;; Public API
;; ---------------------------------------------------------

(defn get-setting
  "Get a configuration setting with metadata.
   Queries KV store at runtime (if enabled). Returns {:value <value> :source <:kv|:env|:default>}."
  [app-config setting-key]
  (let [{:keys [sqlite env-values runtime-app-config?]} app-config]
    (-resolve-setting sqlite env-values runtime-app-config? setting-key)))

(defn get-setting-value
  "Get just the value of a configuration setting.
     Queries KV store at runtime (if enabled)."
  [app-config setting-key]
  (:value (get-setting app-config setting-key)))

;; ---------------------------------------------------------
;; Documentation Helper
;; ---------------------------------------------------------

(def ^:private list-config-keys
  [:key :env-var :default :description])

(defn list-config
  "Returns a list of all app configuration options with their
  defaults, environment variable names, and descriptions."
  []
  (mapv #(select-keys % list-config-keys) app-config-defs))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example usage:
  ;; (get-setting app-config :data-retention-days)
  ;; => {:value 30 :source :kv}
  ;;
  ;; (get-setting-value app-config :data-retention-days)
  ;; => 30

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

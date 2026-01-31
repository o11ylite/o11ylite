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
  [
   ;; Service discovery scan interval in milliseconds
   {:key :service-discovery-interval-ms :env-var "SERVICE_DISCOVERY_INTERVAL_MS" :default 300000 :parser #(Long/parseLong %)}

   ;; Ingest flush interval in milliseconds
   {:key :ingest-flush-interval-ms :env-var "INGEST_FLUSH_INTERVAL_MS" :default 1000 :parser #(Long/parseLong %)}

   ;; TTL for metric normalizer series state in milliseconds
   {:key :metric-normalizer-ttl-ms :env-var "METRIC_NORMALIZER_TTL_MS" :default 1800000 :parser #(Long/parseLong %)}

   ;; Cleanup interval for metric normalizer in milliseconds
   {:key :metric-normalizer-cleanup-ms :env-var "METRIC_NORMALIZER_CLEANUP_MS" :default 60000 :parser #(Long/parseLong %)}

   ;; Metric flush interval in milliseconds
   {:key :metric-flush-interval-ms :env-var "METRIC_FLUSH_INTERVAL_MS" :default 1000 :parser #(Long/parseLong %)}

   ;; Interval for flushing inlined data to Parquet in minutes
   {:key :inlined-data-flush-interval-minutes :env-var "INLINED_DATA_FLUSH_INTERVAL_MINUTES" :default 15 :parser #(Long/parseLong %)}

   ;; Interval for Parquet compaction in minutes
   {:key :parquet-compaction-interval-minutes :env-var "PARQUET_COMPACTION_INTERVAL_MINUTES" :default 60 :parser #(Long/parseLong %)}

   ;; Interval for daily maintenance tasks in minutes
   {:key :daily-maintenance-interval-minutes :env-var "DAILY_MAINTENANCE_INTERVAL_MINUTES" :default 1440 :parser #(Long/parseLong %)}

   ;; Data retention period in days
   {:key :data-retention-days :env-var "DATA_RETENTION_DAYS" :default 30 :parser #(Long/parseLong %)}])

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
  "Resolve a setting at runtime with precedence: KV → env → default.
   Returns a map with :value and :source."
  [sqlite env-values setting-key]
  (let [kv-key setting-key
        env-value (get env-values setting-key)
        default-value (get default-settings setting-key)]
    (if-let [kv-value (kv/get-value sqlite kv-key)]
      {:value kv-value :source :kv}
      (if env-value
        {:value env-value :source :env}
        {:value default-value :source :default}))))

;; ---------------------------------------------------------
;; Component Lifecycle
;; ---------------------------------------------------------

(defmethod ig/init-key :config/app
  [_ {:keys [sqlite] :as config-map}]
  (let [env-values (-load-env-values)
        ;; Extract test overrides (keys that exist in default-settings)
        test-overrides (select-keys config-map (keys default-settings))
        ;; Precedence: test overrides > env vars
        merged-values (merge env-values test-overrides)]
    (mulog/log ::app-config-loaded)
    {:sqlite sqlite
     :env-values merged-values}))

(defmethod ig/halt-key! :config/app
  [_ _]
  (mulog/log ::app-config-stopped))

;; ---------------------------------------------------------
;; Public API
;; ---------------------------------------------------------

(defn get-setting
  "Get a configuration setting with metadata.
   Queries KV store at runtime. Returns {:value <value> :source <:kv|:env|:default>}."
  [app-config setting-key]
  (let [{:keys [sqlite env-values]} app-config]
    (-resolve-setting sqlite env-values setting-key)))

(defn get-setting-value
  "Get just the value of a configuration setting.
    Queries KV store at runtime."
  [app-config setting-key]
  (:value (get-setting app-config setting-key)))

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

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
  [;; Path to data directory for DuckLake and SQLite files
   {:key :data-path :env-var "O11YLITE_DATA_PATH" :default "./.tmp" :parser identity}

   ;; Node ID for distributed ID generation (Snowflake-style)
   {:key :node-id :env-var "O11YLITE_NODE_ID" :default 0 :parser #(Long/parseLong %)}

   ;; Host interface to bind web server (0.0.0.0 for all interfaces)
   {:key :host :env-var "O11YLITE_WEB_HOST" :default "0.0.0.0" :parser identity}

   ;; HTTP web server port
   {:key :web-port :env-var "O11YLITE_WEB_PORT" :default 3000 :parser #(Long/parseLong %)}

   ;; OTLP/gRPC ingestion port for OpenTelemetry data
   {:key :otel-grpc-port :env-var "O11YLITE_OTEL_GRPC_PORT" :default 4317 :parser #(Long/parseLong %)}

   ;; Base URL for static assets (e.g., '/frontend' or CDN URL)
   {:key :asset-base-url :env-var "O11YLITE_ASSET_BASE_URL" :default "/frontend" :parser identity}

   ;; Path to Vite manifest relative to resources/ (prod only)
   {:key :frontend-manifest-path :env-var "O11YLITE_FRONTEND_MANIFEST_PATH" :default ".vite/manifest.json" :parser identity}

   ;; Entry point for frontend, must match vite.config.ts rollupOptions.input
   {:key :frontend-entry-point :env-var "O11YLITE_FRONTEND_ENTRY_POINT" :default "src/main.tsx" :parser identity}

   ;; Development mode flag - enables Vite dev server integration
   {:key :dev? :env-var "O11YLITE_DEV" :default false :parser #(= "true" %)}

   ;; Enable runtime app configuration via KV store
   {:key :runtime-app-config? :env-var "O11YLITE_ENABLE_RUNTIME_APP_CONFIG" :default false :parser #(= "true" %)}])

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

(defn list-config
  "Returns a list of all core configuration options with their
defaults and environment variable names. Useful for documentation."
  []
  (mapv (fn [{:keys [key env-var default]}]
          {:key key
           :env-var env-var
           :default default})
        core-config-defs))

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

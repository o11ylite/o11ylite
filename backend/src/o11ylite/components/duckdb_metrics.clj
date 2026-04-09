;; ---------------------------------------------------------
;; o11ylite.components.duckdb-metrics
;;
;; Publishes DuckDB memory usage as OTel gauge metrics.
;;
;; Registers a single async gauge `o11ylite.duckdb.memory` with a `tag`
;; attribute for each duckdb_memory() category (e.g., base_table,
;; external_file_cache, art_index). This follows the same dimensional
;; pattern as jvm.memory.used{type=...}.
;;
;; The OTel SDK polls the callback on its export interval (~60s).
;; Results are cached briefly so the callback is cheap even if polled
;; more frequently than expected.
;;
;; This component exists because DuckDB's native memory usage is
;; invisible to JVM metrics and was the root cause of OOM kills
;; in production (the external_file_cache grew unbounded).
;; ---------------------------------------------------------

(ns o11ylite.components.duckdb-metrics
  (:require
    [clojure.string :as str]
    [integrant.core :as ig]
    [com.brunobonacci.mulog :as mulog]
    [next.jdbc :as jdbc]
    [steffan-westcott.clj-otel.api.metrics.instrument :as instrument])
  (:import
    [io.opentelemetry.api.common AttributeKey]))

;; ---------------------------------------------------------
;; Private Helpers

;; Use AttributeKey directly to bypass clj-otel's camel-snake-kebab
;; normalization which mangles "o11ylite" → "o_11ylite".
(def ^:private -memory-type-key
  (AttributeKey/stringKey "o11ylite.duckdb.memory.type"))

(defn- -fetch-memory
  "Query duckdb_memory() and return a seq of {:tag \"...\" :bytes N} maps."
  [duckdb]
  (try
    (mapv (fn [row]
            {:tag (str/lower-case (:tag row))
             :bytes (:memory_usage_bytes row)})
          (jdbc/execute! duckdb ["FROM duckdb_memory()"]))
    (catch Exception e
      (mulog/log ::duckdb-memory-query-error :error (.getMessage e))
      [])))

(defn- -make-cached-fetcher
  "Return a fn that caches duckdb_memory() results for `ttl-ms`."
  [duckdb ttl-ms]
  (let [cache (atom {:ts 0 :data []})]
    (fn []
      (let [{:keys [ts data]} @cache
            now (System/currentTimeMillis)]
        (if (< (- now ts) ttl-ms)
          data
          (let [fresh (-fetch-memory duckdb)]
            (reset! cache {:ts now :data fresh})
            fresh))))))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :metrics/duckdb
  [_ {:keys [duckdb]}]
  (let [cached-fetch (-make-cached-fetcher duckdb 5000)
        gauge (instrument/instrument
                {:name "o11ylite.duckdb.memory"
                 :instrument-type :gauge
                 :measurement-type :long
                 :unit "By"
                 :description "DuckDB memory usage by category"}
                (fn []
                  (mapv (fn [{:keys [tag bytes]}]
                          {:value bytes
                           :attributes {-memory-type-key tag}})
                        (cached-fetch))))]
    (mulog/log ::duckdb-metrics-started)
    gauge))

(defmethod ig/halt-key! :metrics/duckdb
  [_ gauge]
  (.close ^java.lang.AutoCloseable gauge)
  (mulog/log ::duckdb-metrics-stopped))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.core :as ig]
           '[integrant.repl.state :as state])

  ;; Start manually
  (def gauge
    (ig/init-key :metrics/duckdb {:duckdb (:db/duckdb state/system)}))

  ;; Stop
  (ig/halt-key! :metrics/duckdb gauge)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

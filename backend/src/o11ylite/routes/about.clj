;; ---------------------------------------------------------
;; o11ylite.routes.system-about
;;
;; Inertia page showing system information: versions, runtime, hardware.
;; ---------------------------------------------------------

(ns o11ylite.routes.about
  (:require
    [clojure.string :as str]
    [next.jdbc :as jdbc]
    [o11ylite.auth.middleware :as auth-mw]
    [o11ylite.util.response :as response]
    [o11ylite.version :as version])
  (:import
    [java.lang.management ManagementFactory]))

;; ---------------------------------------------------------
;; Helpers

(defn- -duckdb-version
  "Query DuckDB library version."
  [duckdb]
  (try
    (:library_version (jdbc/execute-one! duckdb ["SELECT library_version FROM pragma_version()"]))
    (catch Exception _
      "unknown")))

(defn- -ducklake-version
  "Query DuckLake extension version."
  [duckdb]
  (try
    (:extension_version
      (jdbc/execute-one! duckdb
                         ["SELECT extension_version FROM duckdb_extensions() WHERE extension_name = 'ducklake'"]))
    (catch Exception _
      "unknown")))

(defn- -sqlite-version
  "Query SQLite version."
  [sqlite]
  (try
    (:sqlite_version (jdbc/execute-one! sqlite ["SELECT sqlite_version() AS sqlite_version"]))
    (catch Exception _
      "unknown")))

(defn- -java-info
  "Collect Java runtime information."
  []
  (let [rt (Runtime/getRuntime)]
    {:version          (System/getProperty "java.version")
     :vendor           (System/getProperty "java.vendor")
     :home             (System/getProperty "java.home")
     :available_cpus   (.availableProcessors rt)
     :heap_max_mb      (quot (.maxMemory rt) (* 1024 1024))
     :heap_used_mb     (quot (- (.totalMemory rt) (.freeMemory rt)) (* 1024 1024))}))

(defn- -os-info
  "Collect operating system information."
  []
  {:name    (System/getProperty "os.name")
   :version (System/getProperty "os.version")
   :arch    (System/getProperty "os.arch")})

(defn- -uptime-minutes
  "Process uptime in minutes."
  []
  (let [uptime-ms (.getUptime (ManagementFactory/getRuntimeMXBean))]
    (quot uptime-ms 60000)))

(defn- -format-uptime
  "Format uptime as a human-readable string."
  [minutes]
  (let [days    (quot minutes 1440)
        hours   (rem (quot minutes 60) 24)
        minutes (rem minutes 60)]
    (str days "d " hours "h " minutes "m")))

(defn- -format-bytes
  "Format bytes as a human-readable string."
  [^long n]
  (cond
    (>= n 1073741824) (format "%.1f GB" (/ (double n) 1073741824))
    (>= n 1048576)    (format "%.1f MB" (/ (double n) 1048576))
    (>= n 1024)       (format "%.1f KB" (/ (double n) 1024))
    :else             (str n " B")))

(defn- -format-number
  "Format a number with commas."
  [^long n]
  (.format (java.text.NumberFormat/getNumberInstance java.util.Locale/US) n))

(defn- -event-count
  "Get total event count."
  [duckdb]
  (try
    (:cnt (jdbc/execute-one! duckdb ["SELECT COUNT(*) AS cnt FROM events"]))
    (catch Exception _ 0)))

(defn- -metric-count
  "Get total metric datapoint count."
  [duckdb]
  (try
    (:cnt (jdbc/execute-one! duckdb ["SELECT COUNT(*) AS cnt FROM metrics"]))
    (catch Exception _ 0)))

(defn- -ducklake-table-info
  "Query ducklake_table_info for per-table file and size stats."
  [duckdb]
  (try
    (jdbc/execute! duckdb
                   ["SELECT table_name, file_count, file_size_bytes,
               delete_file_count, delete_file_size_bytes
        FROM ducklake_table_info('o11ylite')"])
    (catch Exception _ [])))

;; ---------------------------------------------------------
;; Handlers

(defn- -make-page-handler
  "GET /system/about — Render the system info page."
  [{:keys [duckdb sqlite]}]
  (fn [_request]
    (let [uptime-min (-uptime-minutes)
          table-rows (-ducklake-table-info duckdb)
          events-count (-event-count duckdb)
          metrics-count (-metric-count duckdb)
          ;; Aggregate file stats across all tables
          total-files (reduce + 0 (map :file_count table-rows))
          total-delete-files (reduce + 0 (map :delete_file_count table-rows))
          total-bytes (reduce + 0 (map :file_size_bytes table-rows))
          total-delete-bytes (reduce + 0 (map :delete_file_size_bytes table-rows))]
      (response/inertia "About"
                        {:o11ylite_version version/current
                         :duckdb_version   (-duckdb-version duckdb)
                         :ducklake_version (-ducklake-version duckdb)
                         :sqlite_version   (-sqlite-version sqlite)
                         :java             (-java-info)
                         :os               (-os-info)
                         :uptime_minutes   uptime-min
                         :uptime_display   (-format-uptime uptime-min)
                         :events_count     events-count
                         :events_count_fmt (-format-number events-count)
                         :metrics_count    metrics-count
                         :metrics_count_fmt (-format-number metrics-count)
                         :parquet_files    total-files
                         :parquet_delete_files total-delete-files
                         :parquet_data_size (-format-bytes total-bytes)
                         :parquet_data_bytes total-bytes
                         :parquet_delete_size (-format-bytes total-delete-bytes)
                         :parquet_delete_bytes total-delete-bytes}))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "System about page route. Requires admin scope when auth is active."
  [{:keys [auth-config duckdb sqlite] :as opts}]
  (let [middleware (when-not (:open-mode? auth-config)
                     [(auth-mw/make-wrap-require-scope "admin")])]
    ["/system/about" {:middleware middleware}
     ["" {:get {:handler (-make-page-handler opts)}}]]))

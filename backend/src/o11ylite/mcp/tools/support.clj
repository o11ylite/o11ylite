;; ---------------------------------------------------------
;; o11ylite.mcp.tools.support
;;
;; Shared helpers for MCP tool handlers: argument validation that
;; reports errors the model can act on, and time range resolution.
;;
;; Models are unreliable at epoch-millisecond arithmetic, so tools
;; accept relative ranges ({"last": "1h"}) and ISO-8601 timestamps in
;; addition to epoch milliseconds, and convert here.
;; ---------------------------------------------------------

(ns o11ylite.mcp.tools.support
  (:require
    [clojure.string :as str]
    [malli.core :as m]
    [malli.error :as me]
    [o11ylite.mcp.protocol :as protocol])
  (:import
    [java.time Instant OffsetDateTime]
    [java.time.format DateTimeParseException]))

;; ---------------------------------------------------------
;; Argument validation

(defn validate!
  "Validate tool arguments against a malli schema. Throws a tool error
   with humanized messages so the model can correct the call."
  [schema args]
  (when-not (m/validate schema args)
    (throw (protocol/tool-error "Invalid arguments"
                                (me/humanize (m/explain schema args))))))

(defn query-error!
  "Throw a tool error for a query engine validation result ({:error ...})."
  [validation-error]
  (throw (protocol/tool-error "Invalid query" (:error validation-error))))

(defn drop-nil-values
  "Remove nil-valued keys from a map. Event rows carry every column ever
   seen across all services; most are null for any given row."
  [m]
  (into {} (remove (comp nil? val)) m))

(defn epoch-ms->iso
  "Render epoch milliseconds (possibly fractional) as an ISO-8601 string.
   Models read ISO timestamps far more reliably than 1.79E12."
  [ms]
  (if (number? ms)
    (str (Instant/ofEpochMilli (long ms)))
    ms))

(defn time-range->iso
  "Render a resolved {:start :end} time range for tool output."
  [{:keys [start end]}]
  {:start (epoch-ms->iso start) :end (epoch-ms->iso end)})

(defn filter-by-search
  "Case-insensitive substring filter over :name, capped at limit.
   Returns {:items :total :truncated}."
  [items search limit]
  (let [needle (some-> search str/lower-case)
        matched (cond->> items
                  (not (str/blank? needle))
                  (filter #(str/includes? (str/lower-case (str (:name %))) needle)))
        total (count matched)]
    {:items (vec (take limit matched))
     :total total
     :truncated (> total limit)}))

;; ---------------------------------------------------------
;; Time ranges

(def ^:private unit-ms
  {"s" 1000
   "m" (* 60 1000)
   "h" (* 60 60 1000)
   "d" (* 24 60 60 1000)
   "w" (* 7 24 60 60 1000)})

(def ^:private max-range-ms (* 90 24 60 60 1000))

(def time-range-args-schema
  "Malli schema for the time_range tool argument."
  [:map {:closed true}
   [:last {:optional true} [:re #"^\d{1,6}[smhdw]$"]]
   [:start {:optional true} [:or :int :string]]
   [:end {:optional true} [:or :int :string]]])

(def time-range-json-schema
  "JSON Schema for the time_range tool argument."
  {:type "object"
   :description (str "Time window to query. Either {\"last\": \"15m\"} (units s, m, h, d, w) "
                     "or {\"start\": ..., \"end\": ...} with ISO-8601 timestamps "
                     "(e.g. \"2026-01-15T10:00:00Z\") or epoch milliseconds. "
                     "end defaults to now.")
   :properties {:last {:type "string" :pattern "^\\d{1,6}[smhdw]$"}
                :start {:type ["string" "integer"]}
                :end {:type ["string" "integer"]}}
   :additionalProperties false})

(defn- -parse-instant
  "Parse epoch ms or an ISO-8601 timestamp to epoch ms."
  [field value]
  (if (integer? value)
    value
    (try
      (.toEpochMilli (Instant/parse value))
      (catch DateTimeParseException _
        (try
          (.toEpochMilli (.toInstant (OffsetDateTime/parse value)))
          (catch DateTimeParseException _
            (throw (protocol/tool-error
                     (str "time_range." field " must be an ISO-8601 timestamp with a zone "
                          "(e.g. 2026-01-15T10:00:00Z) or epoch milliseconds")))))))))

(defn resolve-time-range
  "Resolve a time_range argument to {:start :end} epoch ms.
   default-last applies when time_range is absent."
  [time-range default-last now-ms]
  (validate! [:maybe time-range-args-schema] time-range)
  (let [{last-span :last :keys [start end]} (or time-range {:last default-last})]
    (cond
      (and last-span (or start end))
      (throw (protocol/tool-error "time_range takes either last or start/end, not both"))

      last-span
      (let [[_ n unit] (re-matches #"^(\d{1,6})([smhdw])$" last-span)
            span (* (parse-long n) (unit-ms unit))]
        (when (or (zero? span) (> span max-range-ms))
          (throw (protocol/tool-error "time_range.last must be between 1s and 90d")))
        {:start (- now-ms span) :end now-ms})

      (nil? start)
      (throw (protocol/tool-error "time_range needs last or start"))

      :else
      (let [start-ms (-parse-instant "start" start)
            end-ms (if (some? end) (-parse-instant "end" end) now-ms)]
        (when-not (< start-ms end-ms)
          (throw (protocol/tool-error "time_range.start must be before time_range.end")))
        {:start start-ms :end end-ms}))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (resolve-time-range {:last "15m"} "1h" (System/currentTimeMillis))
  (resolve-time-range {:start "2026-01-15T10:00:00Z"} "1h" (System/currentTimeMillis))
  (resolve-time-range nil "1h" (System/currentTimeMillis))

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

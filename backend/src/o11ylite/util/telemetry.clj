;; ---------------------------------------------------------
;; o11ylite.util.telemetry
;;
;; Helpers for reporting telemetry from application code.
;; Centralizes the log-and-span-exception pattern so call sites
;; don't have to remember to do both.
;; ---------------------------------------------------------

(ns o11ylite.util.telemetry
  (:require
    [com.brunobonacci.mulog :as mulog]
    [steffan-westcott.clj-otel.api.attributes :as otel-attrs]
    [steffan-westcott.clj-otel.api.trace.span :as span]))

;; ---------------------------------------------------------
;; OTel attribute name override
;;
;; clj-otel's default `qualified-name` runs every span/metric attribute
;; key through `csk/->snake_case_string`, which inserts an underscore
;; at digit boundaries — turning "o11ylite" into "o_11ylite" and
;; mangling our entire attribute namespace. Our keys are already in
;; OTel-conformant shape (dotted, snake_case tails), so we override
;; the function to preserve them verbatim.
;;
;; Affects span attributes (span/with-span!, span/add-span-data!) and
;; metric attributes (instrument callbacks). Mulog OTel publisher has
;; its own conversion path and is not affected.

(defn- preserve-attribute-name
  "Return the attribute key as a dotted OTel-conformant string,
   preserving the verbatim keyword/symbol name without snake_case
   normalization. Namespaced keywords are joined with a dot so the
   result stays within the OTel-recommended character set (Latin
   alphabet, digits, underscore, dot)."
  ^String [x]
  (cond
    (string? x)        x
    (instance? clojure.lang.Named x)
    (if-let [ns (namespace x)]
      (str ns "." (name x))
      (name x))
    :else (str x)))

(defn install-attribute-name-override!
  "Install the attribute-name override on clj-otel. Call once at
   system boot before any spans/metrics are emitted."
  []
  (otel-attrs/set-attribute-name-fn! preserve-attribute-name))

;; ---------------------------------------------------------
;; Public API

(defmacro report-error!
  "Report an exception via mulog AND attach it to the current OTel span.

   Use in `catch` blocks instead of calling `mulog/log` + manual span
   bookkeeping. If no span is active, the span call is a no-op.

   Arguments:
     event-name - namespaced keyword (the mulog event-name)
     ex         - the Throwable being reported
     kvs        - extra mulog kv attributes (must follow AGENTS.md
                  observability conventions: namespaced keyword keys)

   Side effects:
   - Emits a mulog event with OTel-semconv log attrs
     `:exception.type` / `:exception.message`. The stacktrace is
     intentionally not logged — it lives on the span exception event
     (full fidelity) and would bloat console-json log lines if dumped
     into the event map.
   - Calls `span/add-exception!` to record the exception event on the
     current span (with stacktrace) and set its status to :error.

   This is a macro so mulog records the *caller's* namespace, not
   o11ylite.util.telemetry."
  [event-name ex & kvs]
  `(let [ex# ~ex]
     (mulog/log ~event-name
                :exception.type (-> ex# class .getName)
                :exception.message (.getMessage ^Throwable ex#)
                ~@kvs)
     (span/add-exception! ex#)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Replace this:
  ;;   (catch Exception e
  ;;     (mulog/log ::flush-error :o11ylite.event_batcher.error (.getMessage e)))
  ;;
  ;; With this:
  ;;   (catch Exception e
  ;;     (telemetry/report-error! ::flush-error e))

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

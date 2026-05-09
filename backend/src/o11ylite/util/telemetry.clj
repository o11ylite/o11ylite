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
    [steffan-westcott.clj-otel.api.trace.span :as span]))

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

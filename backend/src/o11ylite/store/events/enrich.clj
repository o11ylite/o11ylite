;; ---------------------------------------------------------
;; o11ylite.store.events.enrich
;;
;; Computes derived fields for events during ingestion.
;; Currently handles the `error` boolean field.
;; ---------------------------------------------------------

(ns o11ylite.store.events.enrich)

;; ---------------------------------------------------------
;; Error Detection
;;
;; Computes a boolean `error` field based on signal type:
;;   - Span: status_code = :error
;;   - Log: severity in #{:error :fatal} OR exception.* attributes present
;;   - Span event: name = "exception" OR exception.* attributes present

(def ^:private error-severities
  "Log severities that indicate an error."
  #{:error :fatal})

(defn- -span-error?
  "Returns true if span has error status."
  [event]
  (= :error (:span.status_code event)))

(defn- -log-error?
  "Returns true if log indicates an error.
   Checks severity level and exception attributes."
  [event]
  (or (contains? error-severities (:log.severity event))
      (some? (:exception.type event))
      (some? (:exception.message event))))

(defn- -span-event-error?
  "Returns true if span event indicates an error.
   Exception span events have name='exception' or exception.* attributes."
  [event]
  (or (= "exception" (:name event))
      (some? (:exception.type event))))

(defn- -compute-error
  "Compute error field for a single event."
  [event]
  (case (:meta.signal_type event)
    :span (-span-error? event)
    :log (-log-error? event)
    :span_event (-span-event-error? event)
    false))

;; ---------------------------------------------------------
;; Public API

(defn enrich-event
  "Enrich a single event with derived fields.
   Currently adds :error boolean field."
  [event]
  (assoc event :error (-compute-error event)))

(defn enrich-events
  "Enrich a collection of events with derived fields."
  [events]
  (map enrich-event events))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Span with error status
  (enrich-event {:meta.signal_type :span
                 :span.status_code :error
                 :name "GET /api"})
  ;; => {:meta.signal_type :span, :span.status_code :error, :name "GET /api", :error true}

  ;; Span with ok status
  (enrich-event {:meta.signal_type :span
                 :span.status_code :ok
                 :name "GET /api"})
  ;; => {:meta.signal_type :span, :span.status_code :ok, :name "GET /api", :error false}

  ;; Log with error severity
  (enrich-event {:meta.signal_type :log
                 :log.severity :error
                 :log.body "Something went wrong"})
  ;; => {..., :error true}

  ;; Log with info severity but exception attributes
  (enrich-event {:meta.signal_type :log
                 :log.severity :info
                 :exception.type "java.lang.NullPointerException"})
  ;; => {..., :error true}

  ;; Span event for exception
  (enrich-event {:meta.signal_type :span_event
                 :name "exception"
                 :exception.type "RuntimeError"})
  ;; => {..., :error true}

  ;; Regular span event
  (enrich-event {:meta.signal_type :span_event
                 :name "user.click"})
  ;; => {..., :error false}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

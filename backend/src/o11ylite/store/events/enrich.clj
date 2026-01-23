;; ---------------------------------------------------------
;; o11ylite.store.events.enrich
;;
;; Computes derived fields for events during ingestion.
;; Handles:
;;   - `error` boolean field (based on signal type and status)
;;   - `id` Snowflake-style ID (for pagination)
;; ---------------------------------------------------------

(ns o11ylite.store.events.enrich
  (:require
   [o11ylite.components.id-gen :as id-gen]))

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
   Adds :error boolean field. Does NOT add :id (use enrich-events for that)."
  [event]
  (assoc event :error (-compute-error event)))

(defn enrich-events
  "Enrich a collection of events with derived fields.
   Adds :error boolean and :id (Snowflake-style) to each event."
  [id-generator events]
  (let [ids (id-gen/next-ids! id-generator (count events))]
    (mapv (fn [event id]
            (-> event
                (assoc :error (-compute-error event))
                (assoc :id id)))
          events
          ids)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.core :as ig])

  ;; Create ID generator for testing
  (def gen (ig/init-key :id/generator {:node-id 0}))

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

  ;; Enrich multiple events with IDs
  (enrich-events gen
                 [{:meta.signal_type :span :span.status_code :ok :name "span-1"}
                  {:meta.signal_type :span :span.status_code :error :name "span-2"}])
  ;; => ({:meta.signal_type :span, :span.status_code :ok, :name "span-1", :error false, :id 12345...}
  ;;     {:meta.signal_type :span, :span.status_code :error, :name "span-2", :error true, :id 12346...})

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

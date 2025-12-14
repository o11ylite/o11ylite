;; ---------------------------------------------------------
;; o11ylite.components.event-metadata
;;
;; Event metadata cache component.
;; Caches field metadata (name, type) from the events table.
;; Initialized after storage init, supports async refresh.
;; ---------------------------------------------------------

(ns o11ylite.components.event-metadata
  (:require
   [integrant.core :as ig]
   [com.brunobonacci.mulog :as mulog]
   [o11ylite.store.schema :as schema]))

;; ---------------------------------------------------------
;; Public API

(defn get-fields
  "Get the current cached field metadata.
   Returns a map of keyword -> {:type normalized-type}."
  [event-metadata]
  @(:state event-metadata))

(defn get-field
  "Get metadata for a specific field.
   Returns {:type normalized-type} or nil if field doesn't exist."
  [event-metadata field-name]
  (get @(:state event-metadata) field-name))

(defn refresh!
  "Trigger an async refresh of the field metadata cache.
   Returns a promise that will be delivered with:
     - {:ok true :fields <field-map>} on success
     - {:ok false :error <exception>} on failure
   
   The cache is only updated on successful refresh."
  [event-metadata]
  ((:refresh! event-metadata)))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -make-refresh-fn
  "Create a refresh function that closes over duckdb and state."
  [duckdb state]
  (fn []
    (let [p (promise)]
      (future
        (try
          (let [fields (schema/fetch-event-fields duckdb)]
            (reset! state fields)
            (mulog/log ::event-metadata-refreshed :field-count (count fields))
            (deliver p {:ok true :fields fields}))
          (catch Exception e
            (mulog/log ::event-metadata-refresh-failed :error (.getMessage e))
            (deliver p {:ok false :error e}))))
      p)))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :cache/event-metadata
  [_ {:keys [duckdb]}]
  (mulog/log ::event-metadata-starting)
  (let [state (atom {})
        fields (schema/fetch-event-fields duckdb)]
    (reset! state fields)
    (mulog/log ::event-metadata-started :field-count (count fields))
    {:state state
     :refresh! (-make-refresh-fn duckdb state)}))

(defmethod ig/halt-key! :cache/event-metadata
  [_ _]
  ;; Nothing to clean up
  nil)

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Test event metadata manually
  (require '[integrant.core :as ig])

  ;; Start dependencies
  (def duckdb-ds
    (ig/init-key :db/duckdb {:data-path "./.tmp"}))

  (def sqlite-ds
    (ig/init-key :db/sqlite {:data-path "./.tmp"}))

  ;; Initialize storage (creates events table)
  (def storage
    (ig/init-key :storage/init {:sqlite sqlite-ds :duckdb duckdb-ds}))

  ;; Start event metadata component
  (def em
    (ig/init-key :cache/event-metadata {:duckdb duckdb-ds}))

  ;; Get all fields (now with normalized types)
  (get-fields em)
  ;; => {:service {:type :string}
  ;;     :timestamp {:type :instant}
  ;;     :span.duration_ns {:type :integer}
  ;;     ...}

  ;; Get specific field
  (get-field em :service)           ;; => {:type :string}
  (get-field em :timestamp)         ;; => {:type :instant}
  (get-field em :span.duration_ns)  ;; => {:type :integer}

  ;; Async refresh
  @(refresh! em)

  ;; Cleanup
  (ig/halt-key! :cache/event-metadata em)
  (ig/halt-key! :storage/init storage)
  (ig/halt-key! :db/sqlite sqlite-ds)
  (ig/halt-key! :db/duckdb duckdb-ds)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

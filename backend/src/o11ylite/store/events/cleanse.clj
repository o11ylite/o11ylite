;; ---------------------------------------------------------
;; o11ylite.store.events.cleanse
;;
;; Event field cleansing: sanitize events by removing fields that
;; violate schema constraints.
;;
;; Cleansing rules:
;;   1. Type conflicts - skip fields where incoming type differs from
;;      existing schema type
;;   2. Field limit - skip new fields when event exceeds max-fields-per-event
;;
;; Cleansing does NOT reject entire events; it only removes problematic
;; fields while preserving the event. For event-level rejection (e.g.,
;; missing required fields), see future validation logic.
;; ---------------------------------------------------------

(ns o11ylite.store.events.cleanse
  (:require
    [com.brunobonacci.mulog :as mulog]
    [o11ylite.components.event-metadata :as event-metadata]
    [o11ylite.store.schema :as schema]))

;; ---------------------------------------------------------
;; Constants

(def ^:private max-fields-per-event
  "Maximum number of fields allowed per event.
   Events exceeding this limit will have new fields (not in schema) skipped."
  200)

;; ---------------------------------------------------------
;; Private Helpers

(defn- -field-type-conflict?
  "Check if incoming value type conflicts with existing field type.
   Returns true if conflict, false if OK (compatible or new field)."
  [existing-type incoming-value]
  (when existing-type
    (let [incoming-type (schema/infer-type incoming-value)]
      ;; Allow nil values (they're always valid) and same types
      (and (some? incoming-value)
           (not= incoming-type existing-type)))))

(defn- -cleanse-event
  "Cleanse a single event by removing fields that violate schema constraints.
   
   Skips fields with:
   - Type conflicts (incoming type differs from schema type)
   - Field limit exceeded (new field when event has >200 fields)
   
   Returns {:event cleansed-event :skipped-fields [...]}."
  [known-fields event]
  (let [field-count (count event)]
    (reduce-kv
      (fn [acc field-key field-value]
        (let [kw-key (keyword field-key)
              existing-meta (get known-fields kw-key)
              existing-type (:type existing-meta)
              is-new-field? (nil? existing-meta)
              has-type-conflict? (-field-type-conflict? existing-type field-value)
              exceeds-limit? (and is-new-field?
                                  (> field-count max-fields-per-event))]
          (cond
            ;; Type conflict - skip field
            has-type-conflict?
            (update acc :skipped-fields conj
                    {:field kw-key
                     :reason :type-conflict
                     :existing-type existing-type
                     :incoming-type (schema/infer-type field-value)})

            ;; New field but over limit - skip field
            exceeds-limit?
            (update acc :skipped-fields conj
                    {:field kw-key
                     :reason :field-limit-exceeded})

            ;; OK - keep field
            :else
            (update acc :event assoc kw-key field-value))))
      {:event {} :skipped-fields []}
      event)))

;; ---------------------------------------------------------
;; Public API

(defn cleanse-events
  "Cleanse all events by removing fields that violate schema constraints.
   
   For each event:
   - Skips fields with type conflicts (incoming type differs from schema)
   - Skips new fields if event exceeds 200 field limit
   - Logs skipped fields for debugging
   
   Arguments:
     event-metadata - The event metadata cache component
     events         - Collection of event maps to cleanse
   
   Returns:
     {:events [...] :skipped-field-count N}"
  [event-metadata events]
  (let [known-fields (event-metadata/get-fields event-metadata)]
    (reduce
      (fn [acc event]
        (let [{:keys [event skipped-fields]} (-cleanse-event known-fields event)]
          ;; Log skipped fields if any
          (when (seq skipped-fields)
            (mulog/log ::fields-skipped
                       :skipped-count (count skipped-fields)
                       :fields (mapv :field skipped-fields)
                       :reasons (mapv :reason skipped-fields)))
          (-> acc
              (update :events conj event)
              (update :skipped-field-count + (count skipped-fields)))))
      {:events [] :skipped-field-count 0}
      events)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example: cleanse event with type conflict
  ;; If schema has :attr.count as :integer, but incoming has string value
  (let [known-fields {:attr.count {:type :integer}
                      :service {:type :string}}
        event {:service "test"
               :attr.count "not-a-number"}]  ; type conflict!
    (-cleanse-event known-fields event))
  ;; => {:event {:service "test"}
  ;;     :skipped-fields [{:field :attr.count
  ;;                       :reason :type-conflict
  ;;                       :existing-type :integer
  ;;                       :incoming-type :string}]}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

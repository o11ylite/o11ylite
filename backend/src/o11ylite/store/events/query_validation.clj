;; ---------------------------------------------------------
;; o11ylite.store.events.query-validation
;;
;; Metadata-aware validation for events queries.
;; Validates filter operators against field types and rejects
;; references to fields that don't exist in the events schema.
;;
;; Mirrors the events/metrics split: query-schema is pure malli +
;; cross-field structural checks; query-validation needs runtime
;; state (the events-table field metadata map).
;; ---------------------------------------------------------

(ns o11ylite.store.events.query-validation
  (:require
    [clojure.string :as str]))

;; ---------------------------------------------------------
;; Type-Aware Filter Validation

(def valid-ops-by-type
  "Valid filter operators for each field type."
  {:string    #{"=" "!=" "contains" "exists" "starts-with"}
   :integer   #{"=" "!=" ">" "<" ">=" "<=" "exists"}
   :float     #{"=" "!=" ">" "<" ">=" "<=" "exists"}
   :boolean   #{"=" "!=" "exists"}
   :instant   #{"=" "!=" ">" "<" ">=" "<=" "exists"}})

(defn- -validate-filter-op-for-type
  "Validate that an operator is valid for the given field type.
   Returns nil if valid, error map if invalid."
  [field-op field-type field-name]
  (let [valid-ops (get valid-ops-by-type field-type)]
    (if-not (contains? valid-ops field-op)
      {:error (format "operator '%s' is not valid for %s field '%s'. Valid operators: %s"
                      field-op field-type field-name (str/join ", " (sort valid-ops)))}
      nil)))

(defn- -validate-filter-expr-with-metadata
  "Recursively validate filter expression operators against field types.
   Returns nil if valid, error map if invalid.
   Unknown fields are skipped (allows querying before data exists)."
  [events-schema filter-expr]
  (cond
    ;; Compound AND
    (:and filter-expr)
    (some #(-validate-filter-expr-with-metadata events-schema %)
          (:and filter-expr))

    ;; Compound OR
    (:or filter-expr)
    (some #(-validate-filter-expr-with-metadata events-schema %)
          (:or filter-expr))

    ;; Simple filter
    :else
    (when-let [field-meta (get events-schema (keyword (:field filter-expr)))]
      (-validate-filter-op-for-type (:op filter-expr)
                                    (:type field-meta)
                                    (:field filter-expr)))))

(defn validate-filter-ops-with-metadata
  "Validate all filter operators are valid for their field types.
   Returns nil if valid, {:error ...} if invalid.
   Unknown fields are skipped (allows querying before data exists).
   Having uses ref-based numeric comparisons (no field-type check needed)."
  [events-schema {:keys [filter]}]
  (when filter
    (-validate-filter-expr-with-metadata events-schema filter)))

;; ---------------------------------------------------------
;; Field-Existence Validation
;;
;; A saved query (notebook, alert rule, shared URL) can outlive the fields
;; it references — operator-deleted via /system/data-management, GC'd by
;; the telemetry catalog, or simply typo'd. The same condition arises when
;; a user types a field name that has never been observed.
;;
;; Either way, if such a field reaches the SQL builder it produces a
;; DuckDB binder error (HTTP 500). We catch it here instead: a query that
;; names an unknown field is rejected with a 400 explaining exactly which
;; field doesn't exist, so the UI can render a clean error.

(defn- -known-field?
  "Returns true if `field-name` is a column we recognize in the events
   schema cache. Built-in columns (timestamp, service, ...) and attribute
   columns (attr.*) are all populated by the cache."
  [events-schema field-name]
  (contains? events-schema (keyword field-name)))

(defn- -collect-filter-fields
  "Walk a filter expression and return a sequence of every field name it
   references."
  [filter-expr]
  (cond
    (:and filter-expr) (mapcat -collect-filter-fields (:and filter-expr))
    (:or filter-expr) (mapcat -collect-filter-fields (:or filter-expr))
    :else [(:field filter-expr)]))

(defn- -collect-referenced-fields
  "Return the seq of field names a query references that need to resolve
   to real columns at SQL time. Skips aggregation '*' (count(*)),
   aggregation-id refs in having, and displayed_fields (UI-only)."
  [{:keys [filter aggregations group_by visualization]}]
  (let [agg-fields (->> aggregations
                        (map :field)
                        (remove #(= "*" %)))
        sort-field (when-let [sort (and (= "table" (:type visualization))
                                        (:sort visualization))]
                     (:field sort))]
    (cond-> (concat (when filter (-collect-filter-fields filter))
                    agg-fields
                    group_by)
      sort-field (concat [sort-field]))))

(defn validate-fields-exist
  "Reject queries that reference fields not present in the events schema.
   Returns nil if every referenced field exists, or {:error ...} naming
   the first unknown field. Caller is the HTTP layer, which turns this
   into a 400."
  [events-schema query]
  (when-let [unknown (->> (-collect-referenced-fields query)
                          (remove #(-known-field? events-schema %))
                          first)]
    {:error (format "Field '%s' does not exist" unknown)}))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Type-aware filter validation
  (validate-filter-ops-with-metadata
    {:service {:type :string}}
    {:filter {:field "service" :op ">" :value "api"}})
  ;; => {:error "operator '>' is not valid for :string field 'service'. ..."}

  ;; Field-existence validation
  (validate-fields-exist
    {:service {:type :string}}
    {:filter {:field "attr.foo" :op "=" :value "x"}
     :visualization {:type "table"}})
  ;; => {:error "Field 'attr.foo' does not exist"}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

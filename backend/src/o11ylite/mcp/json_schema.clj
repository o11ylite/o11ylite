;; ---------------------------------------------------------
;; o11ylite.mcp.json-schema
;;
;; Builds MCP tool inputSchema documents. Query sub-schemas (filters,
;; aggregations, having, metric definitions) are generated from the
;; malli schemas the query engine validates against, so tool schemas
;; cannot drift from the API. The top-level shape and descriptions are
;; hand-written for the model's benefit.
;;
;; malli.json-schema emits recursive schemas with local
;; `definitions` + `#/definitions/...` refs. Embedded under a property,
;; those refs would no longer resolve from the document root, so all
;; definitions are hoisted into a root `$defs` (JSON Schema 2020-12).
;; ---------------------------------------------------------

(ns o11ylite.mcp.json-schema
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [malli.json-schema :as mjs]))

;; ---------------------------------------------------------
;; Public API

(defn from-malli
  "Convert a malli schema to JSON Schema, optionally with a description.
   May carry local :definitions; object-schema hoists them."
  ([schema] (from-malli schema nil))
  ([schema description]
   (cond-> (mjs/transform schema)
     description (assoc :description description))))

(defn- -hoist-definitions
  "Move every nested :definitions map into one map and rewrite refs."
  [schema]
  (let [defs (atom {})
        stripped (walk/postwalk
                   (fn [x]
                     (if (and (map? x) (contains? x :definitions))
                       (do (swap! defs merge (:definitions x))
                           (dissoc x :definitions))
                       x))
                   schema)
        rewrite (fn [x]
                  (walk/postwalk
                    (fn [v]
                      (if (and (string? v) (str/starts-with? v "#/definitions/"))
                        (str "#/$defs/" (subs v (count "#/definitions/")))
                        v))
                    x))]
    [(rewrite stripped) (rewrite @defs)]))

(defn object-schema
  "Build a closed object schema for a tool's inputSchema.
   properties: map of property keyword -> JSON Schema map.
   required:   vector of property names (keywords or strings)."
  ([properties] (object-schema properties []))
  ([properties required]
   (let [[props defs] (-hoist-definitions properties)]
     (cond-> {:type "object"
              :properties props
              :additionalProperties false}
       (seq required) (assoc :required (mapv name required))
       (seq defs) (assoc :$defs defs)))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[o11ylite.store.events.query-schema :as es])
  (object-schema {:filter (from-malli es/filter-expr "Row filter")})

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

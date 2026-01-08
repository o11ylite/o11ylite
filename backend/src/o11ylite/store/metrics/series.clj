;; ---------------------------------------------------------
;; o11ylite.store.metrics.series
;;
;; Series key generation for metric data points.
;;
;; A series key uniquely identifies a time series within a metric,
;; composed of the metric name and sorted attribute key-value pairs.
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.series
  (:require
   [clojure.string :as str]))

;; ---------------------------------------------------------
;; Series Key Generation

(defn- -sorted-attrs-string
  "Convert attributes map to a sorted, deterministic string.
   Only includes attr.* keys, strips the prefix for the key part."
  [data-point]
  (->> data-point
       (filter (fn [[k _]] (and (keyword? k)
                                (.startsWith (name k) "attr."))))
       (map (fn [[k v]] [(subs (name k) 5) (str v)]))
       (sort-by first)
       (map (fn [[k v]] (str k "=" v)))
       (str/join ",")))

(defn series-key
  "Generate a unique series key from a data point.
   Format: metric-name|attr1=val1,attr2=val2,...
   
   The series key uniquely identifies a time series within a metric.
   Two data points with the same series key belong to the same time series."
  [data-point]
  (str (:name data-point) "|" (-sorted-attrs-string data-point)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (series-key {:name "http.requests"
               :value 100
               :attr.method "GET"
               :attr.status "200"})
  ;; => "http.requests|method=GET,status=200"

  ;; Attributes are sorted alphabetically
  (series-key {:name "cpu"
               :attr.host "server-1"
               :attr.core "0"})
  ;; => "cpu|core=0,host=server-1"

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

;; ---------------------------------------------------------
;; o11ylite.api-key.schema
;;
;; Malli schemas for API key validation.
;; ---------------------------------------------------------

(ns o11ylite.api-key.schema
  (:require
    [malli.core :as m]
    [malli.error :as me]))

;; ---------------------------------------------------------
;; Schema

(def api-key-create
  "Schema for API key creation requests."
  [:map {:closed true}
   [:name [:string {:min 1, :max 255}]]
   [:scope [:enum "ingest" "read" "write" "admin"]]])

;; ---------------------------------------------------------
;; Validation

(defn validate
  "Validate API key creation params.
   Returns nil if valid, or {:error ...} if invalid."
  [params]
  (when-not (m/validate api-key-create params)
    {:error (me/humanize (m/explain api-key-create params))}))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (validate {:name "Production Ingest" :scope "ingest"})
  ;; => nil

  (validate {:name "" :scope "ingest"})
  ;; => {:error {:name ["should be at least 1 characters"]}}

  (validate {:name "Test" :scope "superadmin"})
  ;; => {:error {:scope [...]}}

  (validate {})
  ;; => {:error {:name ["missing required key"] :scope ["missing required key"]}}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

;; ---------------------------------------------------------
;; o11ylite.alert-rule
;;
;; Facade for alert rule operations.
;; Re-exports store functions and evaluation orchestration.
;;
;; Namespace structure:
;; - alert-rule.store: Database CRUD operations
;; - alert-rule.eval: Rule evaluation engine and cycle orchestration
;; - alert-rule.notify: Webhook notification dispatch
;; ---------------------------------------------------------

(ns o11ylite.alert-rule
  (:require
    [o11ylite.alert-rule.eval :as eval]
    [o11ylite.alert-rule.instance-store :as instance-store]
    [o11ylite.alert-rule.store :as store]))

;; ---------------------------------------------------------
;; Re-exports from store

(def create! store/create!)
(def update! store/update!)
(def delete! store/delete!)
(def get-by-id store/get-by-id)
(def list-all store/list-all)

;; ---------------------------------------------------------
;; Re-exports from instance-store

(def list-instances instance-store/list-by-rule)
(def dismiss-instances! instance-store/delete-fingerprints!)

;; ---------------------------------------------------------
;; Re-exports from eval

(def run-evaluation-cycle! eval/run-evaluation-cycle!)

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])

  (do
    (require '[integrant.repl.state :refer [system]])
    (def sqlite (:db/sqlite system))
    (def duckdb (:db/duckdb-reader system))

    ;; List all rules
    (def r (list-all sqlite))
    (eval/evaluate-rule! duckdb sqlite (nth r 2)))

  ;; Run evaluation cycle (with no webhook URL)
  (run-evaluation-cycle! duckdb sqlite nil)

  ;; Evaluate a single rule (via eval namespace)
  ;; (eval/evaluate-rule! duckdb sqlite sample-rule)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

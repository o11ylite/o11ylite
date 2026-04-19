;; ---------------------------------------------------------
;; o11ylite.notebook
;;
;; Facade for notebook operations.
;; Re-exports store functions.
;;
;; Namespace structure:
;; - notebook.store: Database CRUD operations
;; - notebook.schema: Malli validation schemas
;; ---------------------------------------------------------

(ns o11ylite.notebook
  (:require
    [o11ylite.notebook.store :as store]))

;; ---------------------------------------------------------
;; Re-exports from store — Notebooks

(def create-notebook! store/create-notebook!)
(def update-notebook! store/update-notebook!)
(def delete-notebook! store/delete-notebook!)
(def get-notebook-by-id store/get-notebook-by-id)
(def list-notebooks store/list-notebooks)
(def touch-notebook! store/touch-notebook!)

;; ---------------------------------------------------------
;; Re-exports from store — Cells

(def create-cell! store/create-cell!)
(def update-cell! store/update-cell!)
(def delete-cell! store/delete-cell!)
(def move-cell! store/move-cell!)

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])
  (def sqlite (:db/sqlite system))

  (list-notebooks sqlite)
  (get-notebook-by-id sqlite "some-id")

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

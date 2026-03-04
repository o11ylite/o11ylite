;; ---------------------------------------------------------
;; o11ylite.auth.scope
;;
;; Scope hierarchy and checking for API key and OIDC auth.
;; Four scopes: ingest, read, write, admin.
;; write includes ingest + read; admin includes everything.
;; ---------------------------------------------------------

(ns o11ylite.auth.scope)

;; ---------------------------------------------------------
;; Scope Hierarchy

(def scope-hierarchy
  "Maps a principal's scope to the set of scopes it satisfies."
  {"admin"  #{"admin" "write" "read" "ingest"}
   "write"  #{"write" "read" "ingest"}
   "read"   #{"read"}
   "ingest" #{"ingest"}})

(def valid-scopes
  "Set of all valid scope strings."
  #{"ingest" "read" "write" "admin"})

;; ---------------------------------------------------------
;; Public API

(defn has-scope?
  "Returns true if principal-scope satisfies the required-scope."
  [principal-scope required-scope]
  (contains? (scope-hierarchy principal-scope) required-scope))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (has-scope? "admin" "ingest")  ;; => true
  (has-scope? "admin" "admin")   ;; => true
  (has-scope? "write" "admin")   ;; => false
  (has-scope? "ingest" "read")   ;; => false
  (has-scope? "read" "ingest")   ;; => false
  (has-scope? "write" "ingest")  ;; => true
  (has-scope? "write" "read")    ;; => true

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

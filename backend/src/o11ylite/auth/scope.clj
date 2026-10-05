;; ---------------------------------------------------------
;; o11ylite.auth.scope
;;
;; Scope hierarchy and checking for API key and OIDC auth.
;; Four scopes: ingest, read, write, admin.
;; write includes ingest + read; admin includes everything.
;; ---------------------------------------------------------

(ns o11ylite.auth.scope
  (:require
    [clojure.string :as str]))

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

(def ^:private -scopes-by-breadth
  "Scopes from narrowest to broadest; used to pick the smallest grant."
  ["read" "ingest" "write" "admin"])

(defn resolve-requested
  "Resolve an OAuth `scope` parameter (space-separated, RFC 6749 §3.3)
   to the single narrowest scope that satisfies every requested scope.
   Returns nil when the parameter contains an unknown scope.

   (resolve-requested \"read write\") => \"write\""
  [scope-param]
  (let [requested (set (remove str/blank? (str/split (or scope-param "") #"\s+")))]
    (when (and (seq requested) (every? valid-scopes requested))
      (first (filter (fn [candidate] (every? #(has-scope? candidate %) requested))
                     -scopes-by-breadth)))))

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

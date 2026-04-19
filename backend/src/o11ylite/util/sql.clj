;; ---------------------------------------------------------
;; o11ylite.util.sql
;;
;; Small SQL string helpers shared across stores. SQLite doesn't
;; support array parameters, so callers that need an IN (?, ?, ...)
;; clause must build the placeholder list themselves from the number
;; of parameters.
;; ---------------------------------------------------------

(ns o11ylite.util.sql
  (:require
    [clojure.string :as str]))

(defn in-placeholders
  "Return a `?, ?, ...` string with `n` placeholders, suitable for
   splicing into a SQL IN clause. Caller is responsible for also
   supplying `n` parameters, in order, to the JDBC call.

   Example:
     (let [names [\"a\" \"b\" \"c\"]
           sql (str \"DELETE FROM t WHERE name IN (\"
                    (in-placeholders (count names)) \")\")]
       (jdbc/execute! ds (into [sql] names)))"
  [n]
  (str/join ", " (repeat n "?")))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (in-placeholders 0) ;; => ""
  (in-placeholders 1) ;; => "?"
  (in-placeholders 3) ;; => "?, ?, ?"

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

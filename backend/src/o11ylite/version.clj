;; ---------------------------------------------------------
;; o11ylite.version
;;
;; Application version resolution.
;; Reads version.txt from the classpath (baked in at build time).
;; Falls back to "dev" when running from source (REPL / dev).
;; ---------------------------------------------------------

(ns o11ylite.version
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]))

;; ---------------------------------------------------------
;; Version Resolution

(def current
  "The current application version.
   Resolved once at load time: version.txt on classpath, or \"dev\"."
  (or (some-> (io/resource "version.txt") slurp str/trim)
      "dev"))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  current
  ;; => "dev" (when running from source)
  ;; => "0.5.0" (when running from uberjar built with VERSION=0.5.0)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

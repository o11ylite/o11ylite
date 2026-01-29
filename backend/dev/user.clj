(ns user
  "Tools for REPL Driven Development"
  (:require
   ;; REPL Workflow
   [mulog-events]                      ; Event Logging
   [clojure.tools.namespace.repl :as namespace]
   [integrant.repl :as ig-repl]
   [o11ylite.system :as system]))

;; ---------------------------------------------------------
;; Avoid reloading `dev` code
;; - code in `dev` directory should be evaluated if changed to reload into repl
(println
 "Set REPL refresh directories to "
 (namespace/set-refresh-dirs "src" "resources" "test"))
;; ---------------------------------------------------------

;; ---------------------------------------------------------
;; Hotload libraries into running REPL
;; `deps-*` LSP snippets to add dependency forms
(comment
  (require '[clojure.repl.deps :as deps])
  ;; Clojure 1.12.x only
  (deps/add-lib 'org.babashka/http-client)
  (deps/sync-deps :as :test/env))
;; ---------------------------------------------------------

;; ---------------------------------------------------------
;; Namespace refresh
;; `deps-*` LSP snippets to add dependency forms
(comment
  (namespace/refresh) ; refresh all changed namespace
  (namespace/refresh-all)) ; refresh all namespace
;; ---------------------------------------------------------

;; ---------------------------------------------------------
;; Integrant REPL - System Lifecycle Management
;;
;; integrant.repl provides convenient functions to start, stop, and reset
;; the system during development. It maintains the system state internally.

;; Tell integrant.repl how to read your system config
(ig-repl/set-prep! #(system/read-config :dev))

;; Convenience functions - just call these from anywhere!
(defn go
  "Start the dev system"
  []
  (ig-repl/go))

(defn halt
  "Stop the dev system"
  []
  (ig-repl/halt))

(defn reset
  "Reset the dev system (stop, refresh changed namespaces, start)"
  []
  (ig-repl/reset))

(defn reset-all
  "Reset all namespaces and restart the system"
  []
  (ig-repl/reset-all))

(comment
  ;; Quick reference - evaluate any of these:
  (go)                  ; Start the system
  (halt)                ; Stop the system
  (reset)               ; Stop + refresh + start (after code changes)
  (reset-all)           ; Full refresh + restart

  @ig-repl/system)       ; Inspect running system state
;; ---------------------------------------------------------

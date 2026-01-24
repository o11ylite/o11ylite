;; ---------------------------------------------------------
;; REPL workflow development tools
;;
;; Include development tool libraries vai aliases from practicalli/clojure-cli-config
;; Start Rich Terminal UI REPL prompt:
;; `clojure -M:repl/reloaded`
;;
;; Or call clojure jack-in from an editor to start a repl
;; including the `:dev/reloaded` alias
;; - alias included in the Emacs `.dir-locals.el` file
;; ---------------------------------------------------------


(ns user
  "Tools for REPL Driven Development"
  (:require
   ;; REPL Workflow
   [mulog-events]                      ; Event Logging
   [clojure.tools.namespace.repl :as namespace]
   ;; integrant.repl provides convenient functions to start, stop, and reset
   ;; the system during development. It maintains the system state internally.
   [integrant.repl :as ig-repl]
   [o11ylite.system :as system]))

;; ---------------------------------------------------------
;; Help

(println "---------------------------------------------------------")
(println "Loading custom user namespace tools...")
(println "---------------------------------------------------------")

(defn help
  "Print help menu for REPL session"
  []
  (println "---------------------------------------------------------")
  (println "Namesapece Management:")
  (println "(namespace/refresh)            ; refresh all changed namespaces")
  (println "(namespace/refresh-all)        ; refresh all namespaces")
  (println)
  (println "Hotload libraries:             ; Clojure 1.12.x")
  (println "(add-lib 'library-name)")
  (println "(add-libs '{domain/library-name {:mvn/version \"v1.2.3\"}})")
  (println "(sync-deps)                    ; load dependencies from deps.edn")
  (println "- deps-* lsp snippets for adding library")
  (println)
  (println "Mulog Publisher:")
  (println "- mulog publisher started by default")
  (println "(mulog-events/stop)            ; stop publishing log events")
  (println)
  (println "(help)                         ; print help text")
  (println "---------------------------------------------------------"))

(help)

;; End of Help
;; ---------------------------------------------------------

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
  (deps/sync-deps :as :test/env)
  #_(add-lib 'library-name)   ; find and add library
  #_(sync-deps)               ; load dependencies in deps.edn (if not yet loaded)
  #_()) ; End of rich comment
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

  @ig-repl/system       ; Inspect running system state

  (mulog-events/stop)   ; stop publishing log events

  #_()) ; End of rich comment
;; ---------------------------------------------------------

;; ---------------------------------------------------------
;; o11ylite.backend
;;
;; Main entry point for the o11ylite backend service
;; ---------------------------------------------------------

(ns o11ylite.backend
  (:gen-class)
  (:require
    [com.brunobonacci.mulog :as mulog]
    [o11ylite.mulog]
    [o11ylite.system :as system]))

;; ---------------------------------------------------------
;; Application

(defn -main
  "Entry point into the application via clojure.main -M

   Set O11YLITE_DEV=true for development mode (uses Vite dev server)."
  [& _args]
  (o11ylite.mulog/init!)
  (mulog/log ::application-startup)

  ;; Start the system
  (let [sys (system/start)]
    ;; Add shutdown hook for graceful shutdown
    (.addShutdownHook
      (Runtime/getRuntime)
      (Thread. ^Runnable (fn []
                           (mulog/log ::shutdown-initiated)
                           (system/stop sys))))
    ;; Keep the main thread alive
    @(promise)))

;; ---------------------------------------------------------

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (-main)

  ;; Start/stop system manually
  (def sys (system/start))
  (system/stop sys)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

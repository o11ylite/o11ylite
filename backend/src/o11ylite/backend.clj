;; ---------------------------------------------------------
;; o11ylite.backend
;;
;; Main entry point for the o11ylite backend service
;; ---------------------------------------------------------

(ns o11ylite.backend
  (:gen-class)
  (:require
   [com.brunobonacci.mulog :as mulog]
   [o11ylite.system :as system]))

;; ---------------------------------------------------------
;; Application

(defn greet
  "Greeting message via Clojure CLI clojure.exec"
  ([] (greet {:team-name "secret engineering"}))
  ([{:keys [team-name]}]
   (str "o11ylite backend service developed by the " team-name " team")))

(defn -main
  "Entry point into the application via clojure.main -M"
  [& _args]
  (mulog/set-global-context!
   {:app-name "o11ylite backend" :version "0.1.0-SNAPSHOT"})
  (mulog/log ::application-startup)
  (println (greet))

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
  (greet)
  (greet {:team-name "Clojure Engineering"})

  ;; Start/stop system manually
  (def sys (system/start))
  (system/stop sys)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

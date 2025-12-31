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

(defn greet
  "Greeting message via Clojure CLI clojure.exec"
  ([] (greet {:team-name "secret engineering"}))
  ([{:keys [team-name]}]
   (str "o11ylite backend service developed by the " team-name " team")))

(defn- dev-mode? []
  (or (some? (System/getProperty "O11YLITE_DEV"))
      (some? (System/getenv "O11YLITE_DEV"))))

(defn -main
  "Entry point into the application via clojure.main -M

   Set O11YLITE_DEV=1 for development mode (uses Vite dev server)."
  [& _args]
  (let [profile (if (dev-mode?) :dev :default)]
    (o11ylite.mulog/init! profile)
    (mulog/log ::application-startup :profile profile)
    (println (greet))

    ;; Start the system
    (let [sys (system/start profile)]
      ;; Add shutdown hook for graceful shutdown
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. ^Runnable (fn []
                            (mulog/log ::shutdown-initiated)
                            (system/stop sys))))
      ;; Keep the main thread alive
      @(promise))))

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

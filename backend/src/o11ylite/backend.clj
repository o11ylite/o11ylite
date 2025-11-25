;; ---------------------------------------------------------
;; o11ylite.backend
;;
;; TODO: Provide a meaningful description of the project
;; ---------------------------------------------------------

(ns o11ylite.backend
  (:gen-class)
  (:require
   [com.brunobonacci.mulog :as mulog]))

;; ---------------------------------------------------------
;; Application

(defn greet
  "Greeting message via Clojure CLI clojure.exec"
  ([] (greet {:team-name "secret engineering"}))
  ([{:keys [team-name]}]
   (str "o11ylite backend service developed by the " team-name " team")))


(defn -main
  "Entry point into the application via clojure.main -M"
  [& args]
  (let [team (first args)]
    (mulog/set-global-context!
     {:app-name "o11ylite backend" :version  "0.1.0-SNAPSHOT"})
    (mulog/log ::application-starup :arguments args)
    (if team
      (println (greet team))
      (println (greet)))))

;; ---------------------------------------------------------


;; ---------------------------------------------------------
;; Rick Comment
(comment

  (-main)
  (-main {:team-name "Clojure Engineering"})

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

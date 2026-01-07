;; ---------------------------------------------------------
;; o11ylite.mulog
;;
;; Mulog configuration and initialization.
;; Sets up publishers and global context based on profile.
;; ---------------------------------------------------------

(ns o11ylite.mulog
  (:require
   [com.brunobonacci.mulog :as mulog]
   ;; Require to register :open-telemetry publisher multimethod
   [com.brunobonacci.mulog.publishers.open-telemetry]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -otel-http-url
  "Build the OTLP HTTP endpoint URL for logs.
   Uses PORT env var (defaults to 3000)."
  []
  (let [port (or (System/getenv "PORT") "3000")]
    (str "http://localhost:" port "/")))

(defn- -dev-publisher
  "Publisher config for dev mode.
   Console (pretty) for immediate feedback + OpenTelemetry for dogfooding."
  []
  {:type :multi
   :publishers
   [{:type :console :pretty? true}
    {:type :open-telemetry
     :url (-otel-http-url)
     :send :logs
     ;; Delay to allow system to start before sending logs
     :publish-delay 5000}]})

(defn- -prod-publisher
  "Publisher config for prod mode.
   Console (JSON) for log collectors."
  []
  {:type :console})

;; ---------------------------------------------------------
;; Public API

(defn init!
  "Initialize mulog publisher and global context.

   Dev mode: console (pretty) + OpenTelemetry for dogfooding.
   Prod mode: console (JSON) for log collectors."
  [profile]
  (mulog/start-publisher!
   (if (= :dev profile)
     (-dev-publisher)
     (-prod-publisher)))
  (mulog/set-global-context!
   {:app-name "o11ylite" :version "0.1.0-SNAPSHOT"}))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Initialize for dev
  (init! :dev)

  ;; Initialize for prod
  (init! :default)

  ;; Test logging
  (mulog/log ::test-event :foo "bar" :count 42)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

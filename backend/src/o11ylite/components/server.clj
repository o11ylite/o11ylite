;; ---------------------------------------------------------
;; o11ylite.components.server
;;
;; Jetty HTTP server component
;; ---------------------------------------------------------

(ns o11ylite.components.server
  (:require
   [integrant.core :as ig]
   [com.brunobonacci.mulog :as mulog]
   [ring.adapter.jetty :as jetty])
  (:import
   [org.eclipse.jetty.server Server]))

(defmethod ig/init-key :server/http
  [_ {:keys [host port handler]}]
  (mulog/log ::server-starting :host host :port port)
  (jetty/run-jetty handler
                   {:host host
                    :port port
                    :join? false}))

(defmethod ig/halt-key! :server/http
  [_ ^Server server]
  (mulog/log ::server-stopping)
  (.stop server))

;; ---------------------------------------------------------
;; o11ylite.components.web-server
;;
;; Jetty web server component
;; ---------------------------------------------------------

(ns o11ylite.components.web-server
  (:require
   [integrant.core :as ig]
   [com.brunobonacci.mulog :as mulog]
   [ring.adapter.jetty :as jetty])
  (:import
   [org.eclipse.jetty.server Server]
   [org.eclipse.jetty.util.thread QueuedThreadPool]))

(defn- virtual-thread-pool
  "Create a QueuedThreadPool that uses virtual threads."
  []
  (doto (QueuedThreadPool.)
    (.setVirtualThreadsExecutor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor))))

(defmethod ig/init-key :server/web
  [_ {:keys [core-config handler]}]
  (let [host (:host core-config)
        port (:web-port core-config)]
    (mulog/log ::web-server-starting :host host :port port)
    (jetty/run-jetty handler
                     {:host host
                      :port port
                      :join? false
                      :thread-pool (virtual-thread-pool)})))

(defmethod ig/halt-key! :server/web
  [_ ^Server server]
  (mulog/log ::web-server-stopping)
  (.stop server))

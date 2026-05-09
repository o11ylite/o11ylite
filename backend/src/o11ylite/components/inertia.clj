;; ---------------------------------------------------------
;; o11ylite.components.inertia
;;
;; Inertia configuration component
;; ---------------------------------------------------------

(ns o11ylite.components.inertia
  (:require
    [integrant.core :as ig]
    [com.brunobonacci.mulog :as mulog]
    [o11ylite.inertia.template :as template]))

(defmethod ig/init-key :inertia/config
  [_ {:keys [core-config]}]
  (let [config (-> core-config
                   (select-keys [:dev? :asset-base-url])
                   (assoc :manifest-path (:frontend-manifest-path core-config)
                          :entry-point (:frontend-entry-point core-config)))
        assets (template/load-assets config)
        version (:version assets)]
    (mulog/log ::inertia-init :o11ylite.dev_mode (:dev? config) :o11ylite.inertia.version version)
    (assoc config
           :template-fn (template/make-template-fn assets)
           :version version)))

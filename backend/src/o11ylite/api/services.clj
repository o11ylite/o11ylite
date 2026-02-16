;; ---------------------------------------------------------
;; o11ylite.api.services
;;
;; Services metadata API endpoint.
;; Provides the list of registered services for frontend pickers.
;;
;; Endpoints:
;;   GET /api/services - List all services
;; ---------------------------------------------------------

(ns o11ylite.api.services
  (:require
    [o11ylite.store.services :as services]
    [o11ylite.util.response :as response]))

;; ---------------------------------------------------------
;; Handlers

(defn- -list-handler
  "List all registered services.
   Returns [{:name :first_seen_at :updated_at} ...]."
  [sqlite]
  (fn [_request]
    (response/json 200 (services/get-services sqlite))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "Services API routes.

   Arguments:
     opts - Map with :sqlite component"
  [{:keys [sqlite]}]
  [["/services"
    ["" {:get {:handler (-list-handler sqlite)}}]]])

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example: List all services
  ;; GET /api/services
  ;; => [{:name "api-gateway" :first_seen_at 1702000000000 :updated_at 1702000000000}
  ;;     {:name "user-service" :first_seen_at 1702000000000 :updated_at 1702000000000}
  ;;     ...]

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

;; ---------------------------------------------------------
;; o11ylite.inertia.middleware
;;
;; Ring middleware for Inertia.js
;; ---------------------------------------------------------

(ns o11ylite.inertia.middleware
  (:require
   [o11ylite.inertia.core :as inertia]))

;; ---------------------------------------------------------
;; Anti-Forgery (CSRF)

(defn wrap-csrf-cookie
  "Set CSRF token in cookie for Inertia.js frontend.
   Inertia reads XSRF-TOKEN cookie and sends it as X-XSRF-TOKEN header.
   
   See: https://inertiajs.com/csrf-protection"
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if-let [token (:anti-forgery-token request)]
        (assoc-in response [:cookies "XSRF-TOKEN"]
                  {:value token
                   :path "/"
                   :same-site :lax})
        response))))

;; ---------------------------------------------------------
;; Shared Data

(defn wrap-inertia-share
  "Add shared data to all Inertia responses.
   
   share-fn receives the request and returns a map of shared props."
  [handler share-fn]
  (fn [request]
    (let [shared-data (share-fn request)]
      (handler (assoc request :inertia-share shared-data)))))

;; ---------------------------------------------------------
;; Main Inertia Middleware

(defn wrap-inertia
  "Main Inertia middleware. Converts handler responses to Inertia format.
   
   - For Inertia XHR requests: returns JSON with page data
   - For initial page load: returns HTML with page data in #app div
   - Handles asset version conflicts (409 response)
   - Handles redirects appropriately
   
   Options:
   - template-fn - Function that takes page-data JSON and returns HTML
   - version     - Asset version string (static, computed at startup)"
  [handler {:keys [template-fn version]}]
  (fn [request]
    ;; Check for version mismatch first (avoids unnecessary handler call)
    (if (inertia/version-mismatch? request version)
      (inertia/version-conflict-response request)
      
      (let [response (handler request)]
        (cond
          ;; Handle redirects
          (inertia/redirect-response? response)
          (inertia/handle-redirect response request)
          
          ;; Check if this is an Inertia response (has :component in body)
          (and (map? (:body response))
               (:component (:body response)))
          (let [page-data (inertia/build-page-data request response version)
                status (:status response 200)]
            (if (inertia/inertia-request? request)
              (inertia/json-response page-data status)
              (inertia/html-response template-fn page-data status)))
          
          ;; Not an Inertia response, pass through
          :else response)))))

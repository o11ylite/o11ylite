;; ---------------------------------------------------------
;; o11ylite.inertia.core
;;
;; Core Inertia.js adapter logic
;; ---------------------------------------------------------

(ns o11ylite.inertia.core
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.string :as str]
   [jsonista.core :as json]
   [ring.util.response :as rr]))

;; ---------------------------------------------------------
;; JSON Encoding (camelCase keys for frontend)

(def ^:private json-mapper
  (json/object-mapper
   {:encode-key-fn (comp csk/->camelCase name)}))

(defn ->json
  "Encode data as JSON with camelCase keys."
  [data]
  (json/write-value-as-string data json-mapper))

;; ---------------------------------------------------------
;; Request Helpers

(defn inertia-request?
  "Check if this is an Inertia request (from frontend)."
  [request]
  (some? (get-in request [:headers "x-inertia"])))

(defn- request-url
  "Get the full request URL including query string."
  [request]
  (str (:uri request)
       (when-let [qs (:query-string request)]
         (str "?" qs))))

(defn- get-header
  "Get a header value from request."
  [request header]
  (get-in request [:headers header]))

;; ---------------------------------------------------------
;; Asset Version

(defn version-mismatch?
  "Check if frontend asset version differs from current version."
  [request current-version]
  (let [method (:request-method request)
        requested-version (get-header request "x-inertia-version")]
    (and (inertia-request? request)
         (= method :get)
         (not= requested-version current-version))))

;; ---------------------------------------------------------
;; Partial Reloads

(defn- apply-partial-data
  "Filter props for partial reload requests."
  [{:keys [component props] :as page-data} request]
  (let [partial-data (get-header request "x-inertia-partial-data")
        partial-component (get-header request "x-inertia-partial-component")]
    (if (and partial-data (= component partial-component))
      (let [only (str/split partial-data #",")]
        (assoc page-data :props (select-keys props (map keyword only))))
      page-data)))

;; ---------------------------------------------------------
;; Props Population

(defn- merge-flash-to-props
  "Merge flash data into props."
  [props request]
  (if-let [flash (:flash request)]
    (update props :flash merge flash)
    props))

(defn- uplift-flash-errors
  "Move flash errors to top-level errors key (Inertia convention)."
  [props]
  (if-let [errors (get-in props [:flash :errors])]
    (update props :errors merge errors)
    props))

(defn- populate-props
  "Add flash data and shared data to props."
  [props request]
  (-> props
      (merge-flash-to-props request)
      (uplift-flash-errors)
      (merge (:inertia-share request))))

;; ---------------------------------------------------------
;; Page Data

(defn build-page-data
  "Build the Inertia page data object."
  [request response asset-version]
  (-> (:body response)
      (update :props populate-props request)
      (apply-partial-data request)
      (assoc :url (request-url request)
             :version asset-version)))

;; ---------------------------------------------------------
;; Response Builders

(defn json-response
  "Create an Inertia JSON response (for XHR requests)."
  [page-data status]
  (-> {:headers {"x-inertia" "true"
                 "vary" "accept"}
       :body (->json page-data)}
      (rr/status status)
      (rr/content-type "application/json")))

(defn html-response
  "Create an Inertia HTML response (for initial page load)."
  [template page-data status]
  (-> (rr/response (template (->json page-data)))
      (rr/status status)
      (rr/content-type "text/html")))

(defn version-conflict-response
  "Create a 409 response to force frontend to reload."
  [request]
  {:status 409
   :headers {"x-inertia-location" (request-url request)}})

;; ---------------------------------------------------------
;; Redirect Handling

(defn redirect-response?
  "Check if response is a redirect."
  [response]
  (contains? #{302 303 307} (:status response)))

(defn handle-redirect
  "Handle redirects for Inertia requests.
   External redirects need special handling via 409."
  [response request]
  (let [location (get-in response [:headers "Location"])]
    (if (and (inertia-request? request)
             location
             (not (str/starts-with? location "/"))
             (not= (get-header request "referer") location))
      ;; External redirect - use 409 to force full page reload
      {:status 409
       :headers {"x-inertia-location" location}}
      response)))

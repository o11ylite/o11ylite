;; ---------------------------------------------------------
;; o11ylite.components.router
;;
;; Reitit router component - assembles all routes
;; ---------------------------------------------------------

(ns o11ylite.components.router
  (:require
    [integrant.core :as ig]
    [com.brunobonacci.mulog :as mulog]
    [ring.middleware.defaults :refer [wrap-defaults site-defaults api-defaults]]
    [reitit.ring :as ring]
    [reitit.ring.middleware.exception :as exception]
    [steffan-westcott.clj-otel.api.trace.span :as span]
    [jsonista.core :as json]
    [o11ylite.util.response :as response]
    [o11ylite.inertia.middleware :as inertia]
    [o11ylite.auth.middleware :as auth-mw]
    [o11ylite.auth.oidc :as oidc]
    [o11ylite.api.events :as api.events]
    [o11ylite.api.health :as api.health]
    [o11ylite.api.metrics :as api.metrics]
    [o11ylite.api.query :as api.query]
    [o11ylite.api.services :as api.services]
    [o11ylite.otel-http :as otel-http]
    [o11ylite.routes.home :as home]
    [o11ylite.routes.explore :as explore]
    [o11ylite.routes.trace :as trace]
    [o11ylite.routes.notebooks :as notebooks]
    [o11ylite.routes.alert-rules :as alert-rules]
    [o11ylite.routes.api-keys :as api-keys]
    [o11ylite.routes.data-management :as data-management]
    [o11ylite.routes.oauth :as oauth]
    [o11ylite.routes.scheduled-jobs :as scheduled-jobs]
    [o11ylite.routes.settings :as settings]
    [o11ylite.routes.about :as about]
    [ring.middleware.session]
    [ring.middleware.session.cookie :as cookie]))

;; ---------------------------------------------------------
;; Middleware Factories

(defn- -json-content-type?
  "Check if request has JSON content type."
  [request]
  (when-let [content-type (get-in request [:headers "content-type"])]
    (.contains content-type "application/json")))

(defn wrap-json-body
  "Parse JSON request body into :body as Clojure data with keyword keys."
  [handler]
  (fn [request]
    (if (and (-json-content-type? request) (:body request))
      (let [body-str (slurp (:body request))
            parsed (when (seq body-str)
                     (json/read-value body-str json/keyword-keys-object-mapper))]
        (handler (assoc request :body parsed)))
      (handler request))))

(defn wrap-api-defaults
  "Wrap handler with api-defaults middleware."
  [handler]
  (-> handler
      wrap-json-body
      (wrap-defaults api-defaults)))

(defn make-wrap-site-defaults
  "Create site-defaults middleware with a stable session key."
  [session-key]
  (fn [handler]
    (wrap-defaults handler
                   (-> site-defaults
                       (assoc-in [:session :store]
                                 (cookie/cookie-store {:key session-key}))
                       (assoc-in [:session :cookie-attrs :same-site] :lax)))))

(defn make-wrap-api-session
  "Create minimal session-reading middleware for API routes.
   Allows API routes to recognize OIDC-authenticated browser sessions
   without full site-defaults overhead."
  [session-key]
  (fn [handler]
    (-> handler
        (ring.middleware.session/wrap-session
          {:store (cookie/cookie-store {:key session-key})
           :cookie-attrs {:same-site :lax}}))))

(defn make-wrap-inertia
  "Create Inertia middleware with config."
  [inertia-config]
  (fn [handler]
    (inertia/wrap-inertia handler inertia-config)))

;; ---------------------------------------------------------
;; Exception Handling

(defn- -api-exception-handler
  "Record exception on current span and return 500 with JSON body."
  [exception _request]
  (span/add-exception! exception)
  (response/json 500 {:error "Internal server error"}))

(defn- -page-exception-handler
  "Record exception on current span and return Inertia error response."
  [exception _request]
  (span/add-exception! exception)
  (response/inertia-error 500))

(def ^:private -api-exception-middleware
  "Exception middleware for API routes - returns JSON responses."
  (exception/create-exception-middleware
    (merge
      exception/default-handlers
      {::exception/default -api-exception-handler})))

(def ^:private -page-exception-middleware
  "Exception middleware for page routes - returns Inertia error page."
  (exception/create-exception-middleware
    (merge
      exception/default-handlers
      {::exception/default -page-exception-handler})))

;; ---------------------------------------------------------
;; Routes

(defn api-routes
  "API routes - no CSRF. Auth via API key header or session cookie.
   In open mode, no auth middleware. Otherwise: session + identity + scope check."
  [{:keys [duckdb-reader sqlite events-schema blocked-fields auth-config api-key-cache]}]
  (let [open-mode? (:open-mode? auth-config)
        wrap-session  (when-not open-mode? (make-wrap-api-session (:session-key auth-config)))
        wrap-identity (when-not open-mode? (auth-mw/make-wrap-identity {:api-key-cache api-key-cache
                                                                        :auth-config auth-config}))
        wrap-scope    (when-not open-mode? (auth-mw/make-wrap-require-scope "read"))]
    ["/api" {:middleware (filterv some? [wrap-api-defaults
                                         wrap-session
                                         wrap-identity
                                         wrap-scope
                                         -api-exception-middleware])}
     (api.events/routes {:events-schema events-schema :blocked-fields blocked-fields})
     (api.metrics/routes {:sqlite sqlite})
     (api.query/routes {:duckdb duckdb-reader :sqlite sqlite :events-schema events-schema})
     (api.services/routes {:sqlite sqlite})]))

(defn otlp-routes
  "OTLP HTTP routes - raw body handling, no JSON parsing middleware.
   These routes handle their own protobuf/JSON parsing.
   Auth: API key required if any keys exist in DB."
  [{:keys [events-schema blocked-fields event-batcher id-generator metric-batcher metric-normalizer sqlite api-key-cache]}]
  (let [wrap-otlp-auth (auth-mw/make-wrap-otlp-auth {:api-key-cache api-key-cache})]
    ["" {:middleware [wrap-otlp-auth
                      -api-exception-middleware]}
     (otel-http/routes {:events-schema events-schema
                        :blocked-fields blocked-fields
                        :event-batcher event-batcher
                        :id-generator id-generator
                        :metric-batcher metric-batcher
                        :metric-normalizer metric-normalizer
                        :sqlite sqlite})]))

(defn- -make-inertia-share-fn
  "Create a function that returns shared Inertia data for each request."
  [auth-config]
  (fn [request]
    {:auth {:user (get-in request [:session :user])
            :oidc_enabled (not (:open-mode? auth-config))}}))

(defn page-routes
  "Page routes - site defaults + Inertia middleware + auth.
   In open mode, no identity middleware (all pages accessible)."
  [{:keys [inertia sqlite duckdb-reader duckdb-writer-events duckdb-writer-metrics
           events-schema blocked-fields id-generator auth-config app-config
           core-config api-key-cache scheduler-registry scheduler-executor]}]
  (let [open-mode? (:open-mode? auth-config)
        wrap-site (make-wrap-site-defaults (:session-key auth-config))
        wrap-identity (when-not open-mode?
                        (auth-mw/make-wrap-identity {:api-key-cache api-key-cache
                                                     :auth-config auth-config}))]
    ["" {:middleware (filterv some?
                              [wrap-site
                               wrap-identity
                               inertia/wrap-csrf-cookie
                               (fn [handler]
                                 (inertia/wrap-inertia-share handler
                                                             (-make-inertia-share-fn auth-config)))
                               (make-wrap-inertia inertia)
                               ;; Inertia sends POST/PUT/DELETE as JSON; site-defaults
                               ;; only parses form-encoded bodies, so we need this.
                               wrap-json-body
                               -page-exception-middleware])}
     ;; Auth routes (outside identity check — handled internally)
     (oidc/routes auth-config)
     ;; OAuth authorize endpoint (needs session for OIDC mode)
     (oauth/authorize-routes {:auth-config auth-config})
     (home/routes {})
     (explore/routes {})
     (trace/routes {})
     (notebooks/routes {:sqlite sqlite})
     (alert-rules/routes {:sqlite sqlite
                          :id-generator id-generator})
     ;; API key management requires admin scope
     (api-keys/routes {:sqlite sqlite
                       :api-key-cache api-key-cache
                       :auth-config auth-config})
     (scheduled-jobs/routes {:sqlite sqlite
                             :scheduler-registry scheduler-registry
                             :scheduler-executor scheduler-executor
                             :auth-config auth-config})
     (settings/routes {:core-config core-config
                       :app-config app-config
                       :auth-config auth-config})
     (about/routes {:duckdb duckdb-reader
                    :sqlite sqlite
                    :auth-config auth-config})
     ;; Data management — view/block/delete event fields and metric fields
     (data-management/routes {:sqlite sqlite
                              :duckdb-reader duckdb-reader
                              :duckdb-writer-events duckdb-writer-events
                              :duckdb-writer-metrics duckdb-writer-metrics
                              :events-schema events-schema
                              :blocked-fields blocked-fields
                              :auth-config auth-config})
     ;; Catch-all: render a proper 404 page for unmatched browser routes
     ["/*path" {:handler (fn [_] (response/inertia-error 404))}]]))

(defn health-routes
  "Health check routes — no auth, always accessible."
  [_opts]
  ["/api" {:middleware [wrap-api-defaults
                        -api-exception-middleware]}
   (api.health/routes {})])

(defn oauth-token-routes
  "OAuth token exchange route. Separate from page-routes because POST /oauth/token
   is an API-style endpoint (JSON/form body, no CSRF, no session required)."
  [opts]
  ["" {:middleware [wrap-api-defaults -api-exception-middleware]}
   (oauth/token-routes {:auth-config (:auth-config opts)})])

;; ---------------------------------------------------------
;; Router Component

(defn create-router
  "Create the Reitit ring handler with routes and middleware."
  [opts]
  (ring/ring-handler
    (ring/router
      [(health-routes opts)
       (oauth-token-routes opts)
       (api-routes opts)
       (otlp-routes opts)
       (page-routes opts)]
      {;; Reitit's default conflict detection treats literal and parameterized
       ;; sibling paths (e.g. /alert-rules/new vs /alert-rules/:id) as ambiguous.
       ;; Suppressing the check is safe because reitit still matches literal
       ;; segments before falling back to parameter capture.
       :conflicts nil})
    (ring/routes
      (ring/create-default-handler
        {:not-found (constantly (response/not-found))}))))

(defmethod ig/init-key :router/routes
  [_ opts]
  (mulog/log ::router-init)
  (create-router opts))

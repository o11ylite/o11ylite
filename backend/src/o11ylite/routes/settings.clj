;; ---------------------------------------------------------
;; o11ylite.routes.settings
;;
;; Inertia page showing system configuration.
;; Two sections: core config (read-only) and app config (runtime-mutable).
;; ---------------------------------------------------------

(ns o11ylite.routes.settings
  (:require
    [o11ylite.auth.middleware :as auth-mw]
    [o11ylite.components.app-config :as app-config]
    [o11ylite.components.core-config :as core-config]
    [o11ylite.kv :as kv]
    [o11ylite.util.response :as response]
    [o11ylite.version :as version]
    [ring.util.response :as rr]))

;; ---------------------------------------------------------
;; Helpers

(defn- -mask-value
  "Replace a credential value with a masked placeholder."
  [v]
  (when v
    (let [s (str v)
          n (count s)]
      (if (<= n 4)
        "****"
        (str (subs s 0 2) (apply str (repeat (- n 2) \*)))))))

(defn- -build-core-settings
  "Build the core config section for the settings page.
   Masks credential values. Descriptions and credential flags
   come from the config definitions themselves."
  [core-config]
  (mapv (fn [{:keys [key env-var default description credential?]}]
          (let [value   (get core-config key)
                masked? (boolean credential?)]
            {:key         (name key)
             :env_var     env-var
             :default     (if masked? nil (str default))
             :value       (if masked? (-mask-value value) (str value))
             :description description
             :masked      masked?}))
        (core-config/list-config)))

(defn- -build-app-settings
  "Build the app config section for the settings page.
   Resolves each setting with full metadata (value, source, default).
   Descriptions come from the config definitions."
  [app-config]
  (mapv (fn [{:keys [key env-var default description]}]
          (let [{:keys [value source]} (app-config/get-setting app-config key)]
            {:key         (name key)
             :env_var     env-var
             :default     default
             :value       value
             :source      (name source)
             :description description}))
        (app-config/list-config)))

;; ---------------------------------------------------------
;; Handlers

(defn- -make-page-handler
  "GET /system/settings — Render the settings page with both config sections."
  [{:keys [core-config app-config]}]
  (fn [_request]
    (let [core-settings (-build-core-settings core-config)
          app-settings  (-build-app-settings app-config)
          runtime-app-config? (:runtime-app-config? app-config)]
      (response/inertia "Settings"
                        {:version              version/current
                         :core_settings        core-settings
                         :app_settings         app-settings
                         :runtime_app_config   (boolean runtime-app-config?)}))))

(defn- -make-update-handler
  "POST /system/settings — Update a single app config setting via KV store.
   Expects JSON body: {:key \"setting-key\" :value <new-value>}"
  [{:keys [app-config]}]
  (fn [request]
    (let [{:keys [runtime-app-config?]} app-config]
      (if-not runtime-app-config?
        (-> (rr/redirect "/system/settings" :see-other)
            (assoc :flash {:error "Runtime configuration is disabled."}))
        (let [{setting-key :key setting-value :value} (:body request)
              {:keys [sqlite]} app-config]
          (kv/set-value! sqlite (keyword setting-key) setting-value)
          (-> (rr/redirect "/system/settings" :see-other)
              (assoc :flash {:message (str (name (keyword setting-key)) " updated.")})))))))

(defn- -make-reset-handler
  "DELETE /system/settings/:key — Reset a setting to default by removing KV override."
  [{:keys [app-config]}]
  (fn [request]
    (let [{:keys [runtime-app-config?]} app-config]
      (if-not runtime-app-config?
        (-> (rr/redirect "/system/settings" :see-other)
            (assoc :flash {:error "Runtime configuration is disabled."}))
        (let [setting-key (get-in request [:path-params :key])
              {:keys [sqlite]} app-config]
          (kv/delete-value! sqlite (keyword setting-key))
          (-> (rr/redirect "/system/settings" :see-other)
              (assoc :flash {:message (str setting-key " reset to default.")})))))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "Settings page routes. Requires admin scope when auth is active."
  [{:keys [auth-config] :as opts}]
  (let [middleware (when-not (:open-mode? auth-config)
                     [(auth-mw/make-wrap-require-scope "admin")])]
    ["/system/settings" {:middleware middleware}
     ["" {:get  {:handler (-make-page-handler opts)}
          :post {:handler (-make-update-handler opts)}}]
     ["/:key" {:delete {:handler (-make-reset-handler opts)}}]]))

;; ---------------------------------------------------------
;; o11ylite.routes.alert-rules
;;
;; Alert rules Inertia page routes.
;; Handles both page rendering (GET) and form mutations
;; (POST/PUT/DELETE) following the Inertia.js pattern:
;; mutations redirect back to the list page with updated props.
;; ---------------------------------------------------------

(ns o11ylite.routes.alert-rules
  (:require
    [clojure.string :as str]
    [o11ylite.alert-rule :as alert-rule]
    [o11ylite.alert-rule.schema :as alert-rule-schema]
    [o11ylite.util.response :as response]
    [o11ylite.util.validation :as validation]
    [ring.util.response :as rr])
  (:import
    [com.github.f4b6a3.uuid UuidCreator]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -blank->nil
  [s]
  (when-not (str/blank? s) s))

(defn- -parse-form-params
  "Extract and normalize alert rule form params from an Inertia request body."
  [body]
  {:name (:name body)
   :description (:description body)
   :enabled (if (false? (:enabled body)) false true)
   :query_mode (:query_mode body)
   :query (:query body)
   :eval_window_ms (some-> (:eval_window_ms body) long)
   :eval_interval_ms (some-> (:eval_interval_ms body) long)
   :alert_on (:alert_on body)
   :alert_target (-blank->nil (:alert_target body))})

;; ---------------------------------------------------------
;; Handlers

(defn- -make-list-handler
  "GET /alert-rules - List all alert rules."
  [{:keys [sqlite]}]
  (fn [_request]
    (let [rules (alert-rule/list-all sqlite)]
      (response/inertia "AlertRules"
                        {:alert_rules rules}))))

(defn- -make-new-handler
  "GET /alert-rules/new - Render create form."
  [_opts]
  (fn [request]
    (response/inertia "AlertRuleEdit"
                      {:alert_rule nil
                       :errors (get-in request [:flash :errors] {})})))

(defn- -make-edit-handler
  "GET /alert-rules/:id/edit - Render edit form. Includes the rule's
   tracked alert instances so the form can surface and dismiss them."
  [{:keys [sqlite]}]
  (fn [request]
    (let [id (get-in request [:path-params :id])
          rule (alert-rule/get-by-id sqlite id)]
      (if rule
        (response/inertia "AlertRuleEdit"
                          {:alert_rule rule
                           :instances (alert-rule/list-instances sqlite id)
                           :errors (get-in request [:flash :errors] {})})
        (rr/not-found "Alert rule not found")))))

(defn- -make-create-handler
  "POST /alert-rules - Create a new alert rule."
  [{:keys [sqlite]}]
  (fn [request]
    (let [params (-parse-form-params (:body request))]
      (if-let [validation-error (alert-rule-schema/validate params)]
        (-> (rr/redirect "/alert-rules/new" :see-other)
            (assoc :flash {:errors (validation/flatten-for-inertia (:error validation-error))}))
        (let [id (str (UuidCreator/getTimeOrderedEpoch))]
          (alert-rule/create! sqlite id params)
          (rr/redirect "/alert-rules" :see-other))))))

(defn- -make-update-handler
  "PUT /alert-rules/:id - Update an existing alert rule."
  [{:keys [sqlite]}]
  (fn [request]
    (let [id (get-in request [:path-params :id])
          params (-parse-form-params (:body request))]
      (if-let [validation-error (alert-rule-schema/validate params)]
        (-> (rr/redirect (str "/alert-rules/" id "/edit") :see-other)
            (assoc :flash {:errors (validation/flatten-for-inertia (:error validation-error))}))
        (do
          (alert-rule/update! sqlite id params)
          (rr/redirect "/alert-rules" :see-other))))))

(defn- -make-delete-handler
  "DELETE /alert-rules/:id - Delete an alert rule."
  [{:keys [sqlite]}]
  (fn [request]
    (let [id (get-in request [:path-params :id])]
      (alert-rule/delete! sqlite id)
      (rr/redirect "/alert-rules" :see-other))))

(defn- -make-dismiss-instances-handler
  "POST /alert-rules/:id/instances/dismiss - Dismiss tracked instances by
   fingerprint. Deletes the rows; a dismissed group re-tracks naturally on
   a later eval (an ungrouped rule re-fires on the next empty eval, a
   grouped rule re-tracks the next time the group is seen present)."
  [{:keys [sqlite]}]
  (fn [request]
    (let [id (get-in request [:path-params :id])
          fingerprints (get-in request [:body :fingerprints])]
      (alert-rule/dismiss-instances! sqlite id fingerprints)
      (rr/redirect (str "/alert-rules/" id "/edit") :see-other))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "Alert rules routes."
  [opts]
  ["/alert-rules"
   ["" {:get {:handler (-make-list-handler opts)}
        :post {:handler (-make-create-handler opts)}}]
   ["/new" {:get {:handler (-make-new-handler opts)}}]
   ["/:id"
    ["" {:put {:handler (-make-update-handler opts)}
         :delete {:handler (-make-delete-handler opts)}}]
    ["/edit" {:get {:handler (-make-edit-handler opts)}}]
    ["/instances/dismiss" {:post {:handler (-make-dismiss-instances-handler opts)}}]]])

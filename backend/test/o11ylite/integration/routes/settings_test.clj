;; ---------------------------------------------------------
;; o11ylite.integration.routes.settings-test
;;
;; Integration tests for the Settings page.
;; Tests the read-only core config display and
;; runtime app config update/reset flow.
;;
;; Uses :server/web partial system with runtime app config
;; enabled — gives us the HTTP server + router without
;; gRPC or service discovery overhead.
;; ---------------------------------------------------------

(ns o11ylite.integration.routes.settings-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.components.app-config :as app-config]
    [o11ylite.components.core-config :as core-config]
    [o11ylite.test-helpers :as h]))

(use-fixtures :each
  (h/with-partial-system [:server/web]
    {:config/core {:runtime-app-config? true}}))

;; ---------------------------------------------------------
;; Helpers

(defn- -settings-props
  "Fetch settings page props via Inertia JSON request."
  []
  (let [response (h/inertia-json-request "/system/settings")]
    (is (= 200 (h/status response)))
    (:props (h/body response))))

(defn- -find-setting
  "Find a setting by key name in a settings vector."
  [settings key-name]
  (some #(when (= key-name (:key %)) %) settings))

;; ---------------------------------------------------------
;; Settings Page

(deftest settings-page-test
  (testing "renders HTML with Settings component"
    (let [response (h/get-request "/system/settings")]
      (is (= 200 (h/status response)))
      (is (h/html-response? response))
      (is (str/includes? (h/body response) "Settings"))))

  (testing "returns Inertia JSON with all registered settings"
    (let [response (h/inertia-json-request "/system/settings")
          body     (h/body response)
          {:keys [core_settings app_settings version runtime_app_config]} (:props body)
          expected-core-keys (set (map (comp name :key) (core-config/list-config)))
          expected-app-keys  (set (map (comp name :key) (app-config/list-config)))]
      (is (= "Settings" (:component body)))
      (is (string? version))
      (is (true? runtime_app_config))

      ;; Response keys match registered config definitions exactly
      (is (= expected-core-keys (set (map :key core_settings))))
      (is (= expected-app-keys (set (map :key app_settings))))

      ;; Every core entry has description and env_var populated
      (doseq [s core_settings]
        (is (not (str/blank? (:description s)))
            (str "core setting " (:key s) " missing description"))
        (is (str/starts-with? (:env_var s) "O11YLITE_")
            (str "core setting " (:key s) " env_var should start with O11YLITE_")))

      ;; Every app entry has description, env_var, and valid source
      (doseq [s app_settings]
        (is (not (str/blank? (:description s)))
            (str "app setting " (:key s) " missing description"))
        (is (str/starts-with? (:env_var s) "O11YLITE_")
            (str "app setting " (:key s) " env_var should start with O11YLITE_"))
        (is (contains? #{"kv" "env" "default"} (:source s))
            (str "app setting " (:key s) " has unexpected source: " (:source s))))))

  (testing "credential values are masked, non-credentials are not"
    (let [{:keys [core_settings]} (-settings-props)
          secret   (-find-setting core_settings "session-secret")
          web-port (-find-setting core_settings "web-port")]
      (is (true? (:masked secret)))
      (is (nil? (:default secret)))
      (is (not (:masked web-port)))
      (is (= (str h/test-http-port) (:value web-port)))))

  (testing "app settings reflect test config overrides"
    (let [{:keys [app_settings]} (-settings-props)
          retention (-find-setting app_settings "data-retention-days")
          flush     (-find-setting app_settings "ingest-flush-interval-ms")]
      (is (= 30 (:value retention)))
      (is (= 30 (:default retention)))
      (is (= "default" (:source retention)))
      (is (= 100 (:value flush)))
      (is (= 1000 (:default flush))))))

;; ---------------------------------------------------------
;; Settings Mutations

(deftest settings-mutation-test
  (let [session (h/csrf-session "/system/settings")]

    (testing "update a setting via POST and verify KV override"
      (let [response (h/post-mutation "/system/settings" session
                                      {:key "data-retention-days" :value 7})]
        (is (= 303 (h/status response)))
        (is (= "/system/settings" (h/header response "location"))))
      (let [{:keys [app_settings]} (-settings-props)
            retention (-find-setting app_settings "data-retention-days")]
        (is (= 7 (:value retention)))
        (is (= "kv" (:source retention)))
        (is (= 30 (:default retention)))))

    (testing "update another setting to verify independence"
      (h/post-mutation "/system/settings" session
                       {:key "webhook-url" :value "https://example.com/hook"})
      (let [{:keys [app_settings]} (-settings-props)
            webhook   (-find-setting app_settings "webhook-url")
            retention (-find-setting app_settings "data-retention-days")]
        ;; New override applied
        (is (= "https://example.com/hook" (:value webhook)))
        (is (= "kv" (:source webhook)))
        ;; Previous override still intact
        (is (= 7 (:value retention)))
        (is (= "kv" (:source retention)))))

    (testing "reset a setting via DELETE and verify it returns to default"
      (let [response (h/delete-mutation "/system/settings/data-retention-days"
                                        session)]
        (is (= 303 (h/status response)))
        (is (= "/system/settings" (h/header response "location"))))
      (let [{:keys [app_settings]} (-settings-props)
            retention (-find-setting app_settings "data-retention-days")
            webhook   (-find-setting app_settings "webhook-url")]
        ;; Reset setting returns to default
        (is (= 30 (:value retention)))
        (is (= "default" (:source retention)))
        ;; Other override unaffected
        (is (= "https://example.com/hook" (:value webhook)))
        (is (= "kv" (:source webhook)))))))

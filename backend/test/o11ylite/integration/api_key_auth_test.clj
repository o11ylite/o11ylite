;; ---------------------------------------------------------
;; o11ylite.integration.api-key-auth-test
;;
;; Integration tests for API key authentication.
;; Tests API key auth on OTLP routes.
;; OIDC flow is not tested here (requires IdP).
;;
;; Tests run in open mode (no OIDC configured). The OTLP
;; auth layer has its own opt-in logic: open when no API
;; keys exist, enforced once the first key is created.
;; ---------------------------------------------------------

(ns o11ylite.integration.api-key-auth-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.api-key :as api-key]
    [o11ylite.api-key.crypto :as crypto]
    [o11ylite.components.api-key-cache :as api-key-cache]
    [o11ylite.test-helpers :as h])
  (:import
    [io.grpc Status$Code StatusRuntimeException]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helpers

(defn- -create-key!
  "Create an API key directly via store + cache refresh.
   Returns the full key string."
  [scope]
  (let [sqlite (:db/sqlite h/*system*)
        akc (:auth/api-key-cache h/*system*)
        key-data (crypto/generate-key)
        id (str "test-" scope "-" (System/currentTimeMillis))]
    (api-key/create! sqlite {:id id
                             :name (str "test-" scope)
                             :prefix (:prefix key-data)
                             :key-hash (:key-hash key-data)
                             :scope scope})
    (api-key-cache/refresh! akc)
    (:key key-data)))

;; ---------------------------------------------------------
;; Open Mode (no API keys, no OIDC)

;; No tests, many integration test can assert this :)

;; ---------------------------------------------------------
;; OTLP API Key Auth (opt-in via first key creation)

(deftest otlp-api-key-auth-test
  (let [ingest-key (-create-key! "ingest")
        read-key (-create-key! "read")]

    (testing "OTLP rejects requests without auth header"
      (let [response (h/post "/v1/traces"
                             {:headers {"Content-Type" "application/json"}
                              :body "{}"})]
        (is (= 401 (h/status response)))))

    (testing "OTLP accepts valid ingest-scoped key"
      (let [response (h/post "/v1/traces"
                             {:headers {"Content-Type" "application/json"
                                        "Authorization" (str "Bearer " ingest-key)}
                              :body "{}"})]
        (is (not= 401 (h/status response)))
        (is (not= 403 (h/status response)))))

    (testing "OTLP rejects key without ingest scope"
      (let [response (h/post "/v1/traces"
                             {:headers {"Content-Type" "application/json"
                                        "Authorization" (str "Bearer " read-key)}
                              :body "{}"})]
        (is (= 403 (h/status response)))))

    (testing "OTLP rejects invalid token"
      (let [response (h/post "/v1/traces"
                             {:headers {"Content-Type" "application/json"
                                        "Authorization" "Bearer o11y_bogus_token"}
                              :body "{}"})]
        (is (= 401 (h/status response)))))

    (testing "Health endpoint remains accessible without auth"
      (let [response (h/get-json "/api/status")]
        (is (= 200 (h/status response)))))

    (let [trace-request {:service-name "grpc-auth-test"
                         :tracer-name "test-tracer"
                         :spans [{:trace-id "0af7651916cd43dd8448eb211c80319c"
                                  :span-id "b7ad6b7169203331"
                                  :name "test-span"
                                  :kind :internal
                                  :start-time-ns 1000000000
                                  :end-time-ns 1000100000}]}]

      (testing "gRPC OTLP rejects requests without auth"
        (is (thrown-with-msg? StatusRuntimeException #"UNAUTHENTICATED"
              (h/export-traces! trace-request))))

      (testing "gRPC OTLP accepts valid ingest-scoped key"
        (is (some? (h/export-traces! trace-request :token ingest-key))))

      (testing "gRPC OTLP rejects key without ingest scope"
        (let [e (try (h/export-traces! trace-request :token read-key)
                     nil
                     (catch StatusRuntimeException e e))]
          (is (some? e))
          (is (= Status$Code/PERMISSION_DENIED
                 (.getCode (.getStatus e))))))

      (testing "gRPC OTLP rejects invalid token"
        (is (thrown-with-msg? StatusRuntimeException #"UNAUTHENTICATED"
              (h/export-traces! trace-request :token "o11y_bogus_token")))))))

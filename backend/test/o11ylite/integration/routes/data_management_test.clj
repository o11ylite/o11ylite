;; ---------------------------------------------------------
;; o11ylite.integration.routes.data-management-test
;;
;; Integration tests for the Data Management page.
;; Tests the page render, block/activate flow, and
;; delete (drop + auto-block) for event fields and
;; metric attributes.
;; ---------------------------------------------------------

(ns o11ylite.integration.routes.data-management-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.components.blocked-fields :as blocked-fields]
    [o11ylite.components.events-schema-cache :as events-schema-cache]
    [o11ylite.store.schema :as schema]
    [o11ylite.store.services :as services]
    [o11ylite.store.telemetry-catalog :as telemetry-catalog]
    [o11ylite.test-helpers :as h]))

(use-fixtures :each (h/with-partial-system [:server/web]))

;; ---------------------------------------------------------
;; Helpers

(defn- -dm-props
  "Fetch data management page props via Inertia JSON request."
  []
  (let [response (h/inertia-json-request "/system/data-management")]
    (is (= 200 (h/status response)))
    (:props (h/body response))))

(defn- -find-field
  "Find a field by name in a vector of {:name ...} maps."
  [fields name]
  (some #(when (= name (:name %)) %) fields))

(defn- -add-event-attr!
  "Add an attr.* event field via DuckDB and refresh the schema cache."
  [field-name]
  (let [duckdb (:db/duckdb h/*system*)
        esc (:cache/events-schema h/*system*)]
    (schema/add-event-fields! duckdb {(keyword field-name) {:type :string}})
    @(events-schema-cache/refresh! esc)))

(defn- -add-metric-attr!
  "Add an attr.* metric attribute via DuckDB for testing."
  [field-name]
  (let [duckdb (:db/duckdb h/*system*)]
    (schema/add-metrics-fields! duckdb #{(keyword field-name)})))

;; ---------------------------------------------------------
;; Page render

(deftest page-render-test
  (testing "renders HTML with DataManagement component"
    (let [response (h/get-request "/system/data-management")]
      (is (= 200 (h/status response)))
      (is (h/html-response? response))
      (is (str/includes? (h/body response) "DataManagement"))))

  (testing "returns Inertia JSON with expected prop keys"
    (let [response (h/inertia-json-request "/system/data-management")
          body (h/body response)]
      (is (= "DataManagement" (:component body)))
      (is (vector? (get-in body [:props :event_fields])))
      (is (vector? (get-in body [:props :metrics])))
      (is (vector? (get-in body [:props :metric_attributes])))
      (is (vector? (get-in body [:props :services])))))

  (testing "services prop joins service_metadata with catalog counts"
    ;; svc-a has metrics + fields; svc-b has nothing in the catalog tables
    (let [sqlite (:db/sqlite h/*system*)
          now (System/currentTimeMillis)]
      (services/upsert-services! sqlite ["svc-a" "svc-b"] now)
      (telemetry-catalog/upsert-service-metrics!
        sqlite
        [{:service "svc-a" :metric-name "cpu.util" :last-seen-at now}
         {:service "svc-a" :metric-name "mem.rss" :last-seen-at now}])
      (telemetry-catalog/upsert-service-event-fields!
        sqlite
        [{:service "svc-a" :field "attr.http.method" :last-seen-at now}]))
    (let [{:keys [services]} (-dm-props)
          svc-a (-find-field services "svc-a")
          svc-b (-find-field services "svc-b")]
      (is (= 2 (:metric_count svc-a)))
      (is (= 1 (:event_field_count svc-a)))
      (is (some? (:last_seen_at svc-a)))
      ;; svc-b exists in service_metadata but has no catalog rows yet
      (is (= 0 (:metric_count svc-b)))
      (is (= 0 (:event_field_count svc-b)))))

  (testing "event fields join blocked set to produce correct status"
    (-add-event-attr! "attr.test.render")
    (let [bf (:cache/blocked-fields h/*system*)
          sqlite (:db/sqlite h/*system*)]
      (blocked-fields/block-event-fields! bf sqlite ["attr.test.render"]))
    (let [{:keys [event_fields]} (-dm-props)
          service (-find-field event_fields "service")
          blocked-f (-find-field event_fields "attr.test.render")]
      ;; Core field is active (not in blocked set)
      (is (= "system" (:category service)))
      (is (= "active" (:status service)))
      ;; Attr field we just blocked shows blocked
      (is (= "attribute" (:category blocked-f)))
      (is (= "blocked" (:status blocked-f))))))

;; ---------------------------------------------------------
;; Event field block/activate/delete

(deftest event-fields-mutation-test
  (-add-event-attr! "attr.test.ef")
  (let [session (h/csrf-session "/system/data-management")]

    (testing "block an event field"
      (let [response (h/put-mutation "/system/data-management/event-fields/status"
                                     session {:fields ["attr.test.ef"] :status "blocked"})]
        (is (= 303 (h/status response)))
        (is (= "/system/data-management" (h/header response "location"))))
      (let [f (-find-field (:event_fields (-dm-props)) "attr.test.ef")]
        (is (= "blocked" (:status f)))))

    (testing "activate an event field"
      (h/put-mutation "/system/data-management/event-fields/status"
                      session {:fields ["attr.test.ef"] :status "active"})
      (let [f (-find-field (:event_fields (-dm-props)) "attr.test.ef")]
        (is (= "active" (:status f)))))

    (testing "delete an event field drops column and auto-blocks"
      (let [response (h/delete-mutation "/system/data-management/event-fields"
                                        session {:fields ["attr.test.ef"]})]
        (is (= 303 (h/status response))))
      (let [{:keys [event_fields]} (-dm-props)]
        (is (nil? (-find-field event_fields "attr.test.ef")))))))

;; ---------------------------------------------------------
;; Metric attribute block/activate/delete

(deftest metric-attrs-mutation-test
  (-add-metric-attr! "attr.test.ma")
  (let [session (h/csrf-session "/system/data-management")]

    (testing "block a metric attribute"
      (let [response (h/put-mutation "/system/data-management/metric-attributes/status"
                                     session {:fields ["attr.test.ma"] :status "blocked"})]
        (is (= 303 (h/status response))))
      (let [f (-find-field (:metric_attributes (-dm-props)) "attr.test.ma")]
        (is (= "blocked" (:status f)))))

    (testing "activate a metric attribute"
      (h/put-mutation "/system/data-management/metric-attributes/status"
                      session {:fields ["attr.test.ma"] :status "active"})
      (let [f (-find-field (:metric_attributes (-dm-props)) "attr.test.ma")]
        (is (= "active" (:status f)))))

    (testing "delete a metric attribute drops column and auto-blocks"
      (let [response (h/delete-mutation "/system/data-management/metric-attributes"
                                        session {:fields ["attr.test.ma"]})]
        (is (= 303 (h/status response))))
      (let [{:keys [metric_attributes]} (-dm-props)]
        (is (nil? (-find-field metric_attributes "attr.test.ma")))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.routes.data-management-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

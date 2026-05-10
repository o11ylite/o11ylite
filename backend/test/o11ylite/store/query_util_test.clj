;; ---------------------------------------------------------
;; o11ylite.store.query-util-test
;;
;; Unit tests for the shared HoneySQL filter builder.
;; ---------------------------------------------------------

(ns o11ylite.store.query-util-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [honey.sql :as sql]
    [o11ylite.store.query-util :as qu]))

(defn- format-where
  [filter-expr]
  (sql/format {:select [:*]
               :from [:events]
               :where (qu/build-filter-clause filter-expr)}))

(defn- where-sql
  [filter-expr]
  (let [[stmt & _] (format-where filter-expr)]
    (subs stmt (+ (.indexOf ^String stmt " WHERE ") (count " WHERE ")))))

(defn- normalize
  [field-metadata filter-expr]
  (-> {:filter filter-expr}
      (->> (qu/normalize-filter field-metadata))
      :filter))

(deftest exists-filter-test
  (testing "defaults to <> '' when metadata absent (safe fallback)"
    (is (= "trace_id <> ''"
           (where-sql {:field "trace_id" :op "exists" :value nil}))))

  (testing "string field uses <> ''"
    (is (= "trace_id <> ''"
           (where-sql (normalize {:trace_id {:type :string}}
                                 {:field "trace_id" :op "exists" :value nil})))))

  (testing "non-string fields use IS NOT NULL"
    (doseq [[field type expected]
            [["attr.http.status_code" :integer "\"attr.http.status_code\" IS NOT NULL"]
             ["span.duration_ms" :float "\"span.duration_ms\" IS NOT NULL"]
             ["error" :boolean "error IS NOT NULL"]]]
      (is (= expected
             (where-sql (normalize {(keyword field) {:type type}}
                                   {:field field :op "exists" :value nil}))))))

  (testing "normalize rewrites exists ops nested inside AND/OR"
    (let [normalized (normalize {:trace_id {:type :string}
                                 :error {:type :boolean}}
                                {:and [{:field "trace_id" :op "exists" :value nil}
                                       {:field "error" :op "exists" :value nil}]})
          [stmt & _] (format-where normalized)]
      (is (re-find #"trace_id <> ''" stmt))
      (is (re-find #"error IS NOT NULL" stmt)))))

(deftest normalize-filter-test
  (testing "value coercion still works for non-exists ops"
    (let [normalized (normalize {:error {:type :boolean}}
                                {:field "error" :op "=" :value "true"})]
      (is (= true (:value normalized)))
      (is (= "=" (:op normalized))))))

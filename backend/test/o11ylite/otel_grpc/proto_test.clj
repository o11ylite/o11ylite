;; ---------------------------------------------------------
;; o11ylite.otel-grpc.proto-test
;;
;; Unit tests for OTLP protobuf helpers.
;; ---------------------------------------------------------

(ns o11ylite.otel-grpc.proto-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [o11ylite.otel-grpc.proto :as proto])
  (:import
    [io.opentelemetry.proto.common.v1 AnyValue KeyValue ArrayValue KeyValueList]))

;; ---------------------------------------------------------
;; Test Helpers

(defn- build-any-value-string
  [s]
  (-> (AnyValue/newBuilder)
      (.setStringValue s)
      (.build)))

(defn- build-any-value-int
  [n]
  (-> (AnyValue/newBuilder)
      (.setIntValue n)
      (.build)))

(defn- build-any-value-bool
  [b]
  (-> (AnyValue/newBuilder)
      (.setBoolValue b)
      (.build)))

(defn- build-any-value-double
  [d]
  (-> (AnyValue/newBuilder)
      (.setDoubleValue d)
      (.build)))

(defn- build-any-value-array
  [values]
  (let [array-builder (ArrayValue/newBuilder)]
    (doseq [v values]
      (.addValues array-builder v))
    (-> (AnyValue/newBuilder)
        (.setArrayValue (.build array-builder))
        (.build))))

(defn- build-any-value-kvlist
  [kvs]
  (let [kvlist-builder (KeyValueList/newBuilder)]
    (doseq [[k v] kvs]
      (.addValues kvlist-builder
                  (-> (KeyValue/newBuilder)
                      (.setKey k)
                      (.setValue v)
                      (.build))))
    (-> (AnyValue/newBuilder)
        (.setKvlistValue (.build kvlist-builder))
        (.build))))

;; ---------------------------------------------------------
;; any-value->clj Tests

(deftest any-value->clj-primitives-test
  (testing "Converts primitive types"
    (is (= "hello" (proto/any-value->clj (build-any-value-string "hello"))))
    (is (= 42 (proto/any-value->clj (build-any-value-int 42))))
    (is (= true (proto/any-value->clj (build-any-value-bool true))))
    (is (= false (proto/any-value->clj (build-any-value-bool false))))
    (is (= 3.14 (proto/any-value->clj (build-any-value-double 3.14))))))

(deftest any-value->clj-array-test
  (testing "Converts arrays to JSON strings"
    (let [arr (build-any-value-array [(build-any-value-string "a")
                                      (build-any-value-string "b")
                                      (build-any-value-int 123)])]
      (is (= "[\"a\",\"b\",123]" (proto/any-value->clj arr))))))

(deftest any-value->clj-nested-array-test
  (testing "Converts nested arrays to JSON strings"
    (let [inner (build-any-value-array [(build-any-value-int 1)
                                        (build-any-value-int 2)])
          outer (build-any-value-array [(build-any-value-string "x")
                                        inner])]
      (is (= "[\"x\",[1,2]]" (proto/any-value->clj outer))))))

(deftest any-value->clj-kvlist-test
  (testing "Converts kvlist to map (for flattening by extract-attributes)"
    (let [kvlist (build-any-value-kvlist [["name" (build-any-value-string "alice")]
                                          ["age" (build-any-value-int 30)]])]
      (is (= {"name" "alice" "age" 30} (proto/any-value->clj kvlist))))))

(deftest any-value->clj-nested-kvlist-test
  (testing "Converts nested kvlist to nested map"
    (let [inner (build-any-value-kvlist [["x" (build-any-value-int 1)]])
          outer (build-any-value-kvlist [["nested" inner]
                                         ["flat" (build-any-value-string "value")]])]
      (is (= {"nested" {"x" 1} "flat" "value"} (proto/any-value->clj outer))))))

(deftest any-value->clj-nil-test
  (testing "Handles nil input"
    (is (nil? (proto/any-value->clj nil)))))

;; ---------------------------------------------------------
;; extract-attributes Tests

(defn- build-kv
  [k v]
  (-> (KeyValue/newBuilder)
      (.setKey k)
      (.setValue v)
      (.build)))

(deftest extract-attributes-basic-test
  (testing "Extracts flat attributes"
    (let [kvs [(build-kv "http.method" (build-any-value-string "GET"))
               (build-kv "http.status" (build-any-value-int 200))]]
      (is (= {"http.method" "GET" "http.status" 200}
             (proto/extract-attributes kvs))))))

(deftest extract-attributes-flattens-nested-test
  (testing "Flattens nested kvlist with dot notation"
    (let [nested (build-any-value-kvlist [["id" (build-any-value-int 123)]
                                          ["name" (build-any-value-string "alice")]])
          kvs [(build-kv "user" nested)
               (build-kv "action" (build-any-value-string "login"))]]
      (is (= {"user.id" 123 "user.name" "alice" "action" "login"}
             (proto/extract-attributes kvs))))))

(deftest extract-attributes-deeply-nested-test
  (testing "Flattens deeply nested kvlist"
    (let [inner (build-any-value-kvlist [["city" (build-any-value-string "NYC")]])
          outer (build-any-value-kvlist [["address" inner]])
          kvs [(build-kv "user" outer)]]
      (is (= {"user.address.city" "NYC"}
             (proto/extract-attributes kvs))))))

;; ---------------------------------------------------------
;; prefix-attributes Tests

(deftest prefix-attributes-test
  (testing "Adds attr. prefix and converts to keywords"
    (is (= {:attr.http.method "GET"
            :attr.custom.field 123}
           (proto/prefix-attributes {"http.method" "GET"}
                                    {"custom.field" 123}))))
  (testing "Handles empty input"
    (is (= {} (proto/prefix-attributes)))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.otel-grpc.proto-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

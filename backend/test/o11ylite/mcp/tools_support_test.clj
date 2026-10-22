;; ---------------------------------------------------------
;; o11ylite.mcp.tools-support-test
;;
;; Unit tests for MCP tool helpers: time range resolution,
;; argument validation, and output formatting.
;; ---------------------------------------------------------

(ns o11ylite.mcp.tools-support-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [o11ylite.mcp.json-schema :as js]
    [o11ylite.mcp.tools :as tools]
    [o11ylite.mcp.tools.support :as support]
    [o11ylite.store.events.query-schema :as events-schema]))

;; ---------------------------------------------------------
;; Helpers

(def ^:private now 1767225600000) ; 2026-01-01T00:00:00Z

(defn- -tool-error-message
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

;; ---------------------------------------------------------
;; Tests

(deftest resolve-time-range-test
  (testing "relative ranges"
    (is (= {:start (- now 3600000) :end now} (support/resolve-time-range nil "1h" now)))
    (is (= {:start (- now (* 15 60000)) :end now}
           (support/resolve-time-range {:last "15m"} "1h" now)))
    (is (= {:start (- now (* 7 86400000)) :end now}
           (support/resolve-time-range {:last "1w"} "1h" now))))

  (testing "absolute ranges accept ISO-8601 and epoch ms; end defaults to now"
    (is (= {:start 1767139200000 :end now}
           (support/resolve-time-range {:start "2025-12-31T00:00:00Z"} "1h" now)))
    (is (= {:start 1767139200000 :end 1767142800000}
           (support/resolve-time-range {:start "2025-12-31T09:00:00+09:00"
                                        :end 1767142800000} "1h" now))))

  (testing "invalid input produces tool errors"
    (is (some? (-tool-error-message #(support/resolve-time-range {:last "1y"} "1h" now))))
    (is (some? (-tool-error-message #(support/resolve-time-range {:last "99999999999999999999h"} "1h" now))))
    (is (some? (-tool-error-message #(support/resolve-time-range {:last "0m"} "1h" now))))
    (is (some? (-tool-error-message #(support/resolve-time-range {:last "1h" :start 1} "1h" now))))
    (is (some? (-tool-error-message #(support/resolve-time-range {:start "yesterday"} "1h" now))))
    (is (some? (-tool-error-message #(support/resolve-time-range {:start now :end 1} "1h" now))))
    (is (some? (-tool-error-message #(support/resolve-time-range {:end now} "1h" now))))))

(deftest output-helpers-test
  (is (= {:a 1} (support/drop-nil-values {:a 1 :b nil})))
  (is (= "2026-01-01T00:00:00Z" (support/epoch-ms->iso 1.7672256E12)))
  (is (= {:items [{:name "Http.a"}] :total 2 :truncated true}
         (support/filter-by-search [{:name "Http.a"} {:name "x.http"} {:name "db"}] "HTTP" 1))))

(deftest json-schema-test
  (testing "recursive malli definitions are hoisted to root $defs"
    (let [schema (js/object-schema {:filter (js/from-malli events-schema/filter-expr "f")})
          ref (get-in schema [:properties :filter :$ref])]
      (is (= "#/$defs/o11ylite.store.events.query-schema.filter" ref))
      (is (contains? (:$defs schema) "o11ylite.store.events.query-schema.filter"))
      (is (nil? (get-in schema [:properties :filter :definitions])))))

  (testing "every tool has a unique name, an object inputSchema, and a scope"
    (is (= (count tools/all) (count (set (map :name tools/all)))))
    (doseq [tool tools/all]
      (is (= "object" (get-in tool [:input-schema :type])) (:name tool))
      (is (#{"read" "write"} (:scope tool)) (:name tool))
      (is (re-matches #"^[a-z_]{1,64}$" (:name tool))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.mcp.tools-support-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

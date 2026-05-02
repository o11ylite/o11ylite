;; ---------------------------------------------------------
;; o11ylite.store.metrics.formula-test
;;
;; Unit tests for the metric formula parser, evaluator, and apply-formulas.
;; Covers tokenize -> parse -> AST, ref extraction, single-bucket eval,
;; and series-level inner-join on labels and timestamps.
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.formula-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [o11ylite.store.metrics.formula :as formula]))

(deftest parse-test
  (testing "literals"
    (is (= [:num 1.0]   (formula/parse "1")))
    (is (= [:num 1.5]   (formula/parse "1.5")))
    (is (= [:num -2.0]  (formula/parse "-2"))))

  (testing "metric refs"
    (is (= [:ref "A"] (formula/parse "A"))))

  (testing "operator precedence"
    ;; A + B * C  =>  A + (B * C)
    (is (= [:+ [:ref "A"] [:* [:ref "B"] [:ref "C"]]]
           (formula/parse "A + B * C"))))

  (testing "parentheses override"
    (is (= [:* [:+ [:ref "A"] [:ref "B"]] [:ref "C"]]
           (formula/parse "(A + B) * C"))))

  (testing "left-associativity for same precedence"
    ;; A - B - C  =>  (A - B) - C, not A - (B - C)
    (is (= [:- [:- [:ref "A"] [:ref "B"]] [:ref "C"]]
           (formula/parse "A - B - C"))))

  (testing "real-world examples"
    (is (some? (formula/parse "A / B * 100")))
    (is (some? (formula/parse "(A - B) / A * 100"))))

  (testing "unary minus on non-literals rewrites as 0 - expr"
    (is (= [:- [:num 0.0] [:ref "A"]] (formula/parse "-A"))))

  (testing "rejects invalid"
    (is (thrown? Exception (formula/parse "")))
    (is (thrown? Exception (formula/parse "A +")))
    (is (thrown? Exception (formula/parse "A B")))
    (is (thrown? Exception (formula/parse "A + (B")))
    (is (thrown? Exception (formula/parse "@@")))
    (is (thrown? Exception (formula/parse "AB")))))   ; refs are 1 char

(deftest refs-test
  (testing "extracts unique refs in order of first appearance"
    (is (= ["A" "B"]     (formula/refs (formula/parse "A / B * 100"))))
    (is (= ["A"]         (formula/refs (formula/parse "A * 2"))))
    (is (= ["A" "B"]     (formula/refs (formula/parse "A + B + A"))))
    (is (= []            (formula/refs (formula/parse "1 + 2"))))))

(deftest eval-ast-test
  (testing "literal"
    (is (= 3.0 (formula/eval-ast [:num 3.0] {}))))
  (testing "ref lookup"
    (is (= 5.0 (formula/eval-ast [:ref "A"] {"A" 5.0}))))
  (testing "arithmetic"
    (is (= 7.0  (formula/eval-ast (formula/parse "A + 2") {"A" 5.0})))
    (is (= 50.0 (formula/eval-ast (formula/parse "A / B * 100")
                                  {"A" 1.0 "B" 2.0}))))
  (testing "division by zero returns nil"
    (is (nil? (formula/eval-ast (formula/parse "A / B")
                                {"A" 1.0 "B" 0.0}))))
  (testing "missing ref returns nil"
    (is (nil? (formula/eval-ast (formula/parse "A + B") {"A" 1.0}))))
  (testing "nil propagates"
    (is (nil? (formula/eval-ast (formula/parse "A + B")
                                {"A" 1.0 "B" nil})))))

(defn- -ts
  "Helper to build a series with bucket=>value pairs."
  [id labels pairs]
  {:id id
   :labels labels
   :data (mapv (fn [[t v]] {:timestamp t :value v}) pairs)})

(deftest apply-formulas-test
  (testing "single formula, no group_by labels"
    (let [series [(-ts "A" {} [[1000 10.0] [2000 20.0]])
                  (-ts "B" {} [[1000 2.0]  [2000 5.0]])]
          formula {:id "F1" :expr "A / B"}
          result (formula/apply-formulas series [formula])]
      ;; Source series preserved
      (is (= 3 (count result)))
      (is (= [10.0 20.0] (mapv :value (:data (first result)))))
      ;; Formula appended last
      (let [f1 (last result)]
        (is (= "F1" (:id f1)))
        (is (= "A / B" (:formula f1)))
        (is (= [{:timestamp 1000 :value 5.0}
                {:timestamp 2000 :value 4.0}]
               (:data f1))))))

  (testing "inner-join on labels"
    (let [series [(-ts "A" {:host "h1"} [[1000 10.0]])
                  (-ts "A" {:host "h2"} [[1000 30.0]])
                  (-ts "B" {:host "h1"} [[1000 2.0]])
                  (-ts "B" {:host "h2"} [[1000 3.0]])]
          formulas [{:id "F1" :expr "A / B"}]
          result-formulas (filter #(= "F1" (:id %))
                                  (formula/apply-formulas series formulas))]
      (is (= 2 (count result-formulas)))
      (is (= #{{:host "h1"} {:host "h2"}}
             (set (map :labels result-formulas))))
      (is (= {{:host "h1"} 5.0 {:host "h2"} 10.0}
             (into {} (map (juxt :labels #(-> % :data first :value))
                           result-formulas))))))

  (testing "inner-join on bucket — missing bucket dropped"
    (let [series [(-ts "A" {} [[1000 10.0] [2000 20.0]])
                  (-ts "B" {} [[1000 2.0]])]                  ; no 2000
          result (formula/apply-formulas series
                                         [{:id "F1" :expr "A / B"}])
          f1 (last result)]
      (is (= [{:timestamp 1000 :value 5.0}] (:data f1)))))

  (testing "division by zero — bucket dropped"
    (let [series [(-ts "A" {} [[1000 10.0] [2000 20.0]])
                  (-ts "B" {} [[1000 0.0]  [2000 5.0]])]
          result (formula/apply-formulas series
                                         [{:id "F1" :expr "A / B"}])
          f1 (last result)]
      (is (= [{:timestamp 2000 :value 4.0}] (:data f1)))))

  (testing "name and unit echoed"
    (let [series [(-ts "A" {} [[1000 1.0]])
                  (-ts "B" {} [[1000 2.0]])]
          formulas [{:id "F1" :expr "A / B" :name "ratio" :unit "%"}]
          f1 (last (formula/apply-formulas series formulas))]
      (is (= "F1: ratio" (:name f1)))
      (is (= "%" (:unit f1)))))

  (testing "unit inferred from operands when all share the same unit"
    (let [series [(assoc (-ts "A" {} [[1000 100.0]]) :unit "By")
                  (assoc (-ts "B" {} [[1000 30.0]])  :unit "By")]
          f1 (last (formula/apply-formulas series [{:id "F1" :expr "A - B"}]))]
      (is (= "By" (:unit f1)))))

  (testing "unit not inferred when operands have differing units"
    (let [series [(assoc (-ts "A" {} [[1000 100.0]]) :unit "By")
                  (assoc (-ts "B" {} [[1000 30.0]])  :unit "%")]
          f1 (last (formula/apply-formulas series [{:id "F1" :expr "A + B"}]))]
      (is (nil? (:unit f1)))))

  (testing "unit not inferred when any operand has no unit"
    (let [series [(assoc (-ts "A" {} [[1000 100.0]]) :unit "By")
                  (-ts "B" {} [[1000 30.0]])]
          f1 (last (formula/apply-formulas series [{:id "F1" :expr "A + B"}]))]
      (is (nil? (:unit f1)))))

  (testing "explicit unit wins over inferable common unit"
    (let [series [(assoc (-ts "A" {} [[1000 100.0]]) :unit "By")
                  (assoc (-ts "B" {} [[1000 30.0]])  :unit "By")]
          f1 (last (formula/apply-formulas series [{:id "F1" :expr "A / B" :unit "%"}]))]
      (is (= "%" (:unit f1)))))

  (testing "single-ref formula inherits the operand's unit"
    (let [series [(assoc (-ts "A" {} [[1000 7.0]]) :unit "ms")]
          f1 (last (formula/apply-formulas series [{:id "F1" :expr "A * 2"}]))]
      (is (= "ms" (:unit f1)))))

  (testing "missing operand series — formula yields zero series"
    ;; Only A present, formula references B
    (let [series [(-ts "A" {} [[1000 1.0]])]
          result (formula/apply-formulas series
                                         [{:id "F1" :expr "A / B"}])]
      ;; Source preserved, no formula series emitted
      (is (= 1 (count result)))
      (is (= "A" (:id (first result))))))

  (testing "empty formulas list returns source series unchanged"
    (let [series [(-ts "A" {} [[1000 1.0]])]]
      (is (= series (formula/apply-formulas series [])))))

  (testing "single-ref formula"
    (let [series [(-ts "A" {} [[1000 7.0] [2000 14.0]])]
          f1 (last (formula/apply-formulas series [{:id "F1" :expr "A * 2"}]))]
      (is (= [{:timestamp 1000 :value 14.0}
              {:timestamp 2000 :value 28.0}] (:data f1)))))

  (testing "multiple formulas evaluated independently"
    (let [series [(-ts "A" {} [[1000 10.0]]) (-ts "B" {} [[1000 2.0]])]
          result (formula/apply-formulas series
                                         [{:id "F1" :expr "A + B"} {:id "F2" :expr "A / B"}])
          by-id (group-by :id result)]
      (is (= 12.0 (-> (get by-id "F1") first :data first :value)))
      (is (= 5.0  (-> (get by-id "F2") first :data first :value))))))

(deftest apply-having-to-formula-test
  (testing "drops per-bucket points failing predicate, keeps source series untouched"
    (let [series [(-ts "A"  {} [[1000 10.0] [2000 20.0]])
                  (-ts "F1" {} [[1000 5.0]  [2000 50.0] [3000 100.0]])]
          result (formula/apply-having-to-formula
                   series {:ref "F1" :op ">" :value 40})
          by-id (group-by :id result)]
      ;; Source series A unchanged
      (is (= [{:timestamp 1000 :value 10.0}
              {:timestamp 2000 :value 20.0}]
             (-> (get by-id "A") first :data)))
      ;; F1 has only the >40 buckets
      (is (= [{:timestamp 2000 :value 50.0}
              {:timestamp 3000 :value 100.0}]
             (-> (get by-id "F1") first :data)))))

  (testing "drops series whose data is fully filtered out"
    (let [series [(-ts "A"  {} [[1000 10.0]])
                  (-ts "F1" {} [[1000 5.0]])]
          result (formula/apply-having-to-formula
                   series {:ref "F1" :op ">" :value 100})]
      (is (= 1 (count result)))
      (is (= "A" (:id (first result))))))

  (testing "filters per-label-set independently"
    (let [series [(-ts "F1" {:host "h1"} [[1000 50.0] [2000 5.0]])
                  (-ts "F1" {:host "h2"} [[1000 5.0]])]
          result (formula/apply-having-to-formula
                   series {:ref "F1" :op ">" :value 10})]
      (is (= 1 (count result)))
      (is (= {:host "h1"} (:labels (first result))))
      (is (= [{:timestamp 1000 :value 50.0}] (:data (first result))))))

  (testing "all numeric operators"
    (let [series [(-ts "F1" {} [[1000 10.0] [2000 20.0] [3000 30.0]])]]
      (doseq [[op threshold expected]
              [[">"  20 [30.0]]
               ["<"  20 [10.0]]
               [">=" 20 [20.0 30.0]]
               ["<=" 20 [10.0 20.0]]
               ["="  20 [20.0]]
               ["!=" 20 [10.0 30.0]]]]
        (let [r (formula/apply-having-to-formula
                  series {:ref "F1" :op op :value threshold})]
          (is (= expected (mapv :value (:data (first r))))
              (str "operator " op)))))))

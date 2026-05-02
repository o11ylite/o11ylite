;; ---------------------------------------------------------
;; o11ylite.store.metrics.formula
;;
;; Tiny expression language for metric formulas.
;; Supports +, -, *, /, parentheses, unary minus over numeric literals
;; and single-letter metric refs (A-Z). Pure functions; no I/O.
;;
;; AST shape:
;;   [:num d] | [:ref "A"] | [:+ a b] | [:- a b] | [:* a b] | [:/ a b]
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.formula
  (:require
    [clojure.set :as set]
    [clojure.string :as str]))

;; ---------------------------------------------------------
;; Tokenizer

(defn- -tokenize
  "Lex a formula string into tokens.
   Token shapes: [:num x] | [:ref \"A\"] | [:op \"+\"] | [:lparen] | [:rparen]"
  [^String s]
  (loop [i 0, out []]
    (if (>= i (count s))
      out
      (let [c (.charAt s i)]
        (cond
          (Character/isWhitespace c)  (recur (inc i) out)
          (= c \()                    (recur (inc i) (conj out [:lparen]))
          (= c \))                    (recur (inc i) (conj out [:rparen]))
          (#{\+ \- \* \/} c)          (recur (inc i) (conj out [:op (str c)]))
          (Character/isDigit c)
          (let [m (re-find #"^\d+(?:\.\d+)?" (subs s i))]
            (recur (+ i (count m)) (conj out [:num (Double/parseDouble m)])))
          (and (Character/isLetter c) (Character/isUpperCase c))
          ;; Single-letter ref. If the next char is also a letter/digit,
          ;; that's a multi-char identifier — reject.
          (let [j (inc i)]
            (when (and (< j (count s))
                       (Character/isLetterOrDigit (.charAt s j)))
              (throw (ex-info (str "Invalid identifier at pos " i)
                              {:pos i :char c})))
            (recur j (conj out [:ref (str c)])))
          :else
          (throw (ex-info (str "Unexpected char '" c "' at pos " i)
                          {:pos i :char c})))))))

;; ---------------------------------------------------------
;; Pratt parser

(def ^:private -binop-prec
  {"+" 10 "-" 10 "*" 20 "/" 20})

(declare -parse-expr)

(defn- -parse-primary
  [tokens]
  (let [[t & more] tokens]
    (case (first t)
      :num    [[:num (second t)] more]
      :ref    [[:ref (second t)] more]
      :lparen (let [[expr rest1] (-parse-expr more 0)]
                (when-not (= [:rparen] (first rest1))
                  (throw (ex-info "Expected ')'" {})))
                [expr (next rest1)])
      :op     (if (= "-" (second t))
                ;; Unary minus. Constant-fold when operand is a literal so
                ;; "-2" parses to [:num -2.0]; otherwise rewrite as 0 - expr.
                (let [[expr rest1] (-parse-primary more)]
                  (if (and (vector? expr) (= :num (first expr)))
                    [[:num (- (second expr))] rest1]
                    [[:- [:num 0.0] expr] rest1]))
                (throw (ex-info (str "Unexpected operator " (second t)) {})))
      (throw (ex-info "Unexpected end of expression"
                      {:remaining tokens})))))

(defn- -parse-expr
  [tokens min-prec]
  (loop [[lhs tokens] (-parse-primary tokens)]
    (let [[t] tokens
          op (when (= :op (first t)) (second t))
          prec (some-> op -binop-prec)]
      (if (and prec (>= prec min-prec))
        (let [[rhs rest1] (-parse-expr (next tokens) (inc prec))]
          (recur [[(keyword op) lhs rhs] rest1]))
        [lhs tokens]))))

(defn parse
  "Parse a formula string into an AST.
   AST shape: [:num d] | [:ref \"A\"] | [:+ a b] | [:- a b] | [:* a b] | [:/ a b]
   Throws ex-info on parse error."
  [s]
  (when (str/blank? s)
    (throw (ex-info "Empty formula" {})))
  (let [tokens (-tokenize s)
        [expr leftover] (-parse-expr tokens 0)]
    (when (seq leftover)
      (throw (ex-info "Unexpected trailing tokens"
                      {:leftover leftover})))
    expr))

;; ---------------------------------------------------------
;; Ref extraction

(defn refs
  "Return distinct metric refs in AST in order of first appearance."
  [ast]
  (let [seen (volatile! #{})
        order (volatile! [])
        walk (fn walk
               [node]
               (case (first node)
                 :num nil
                 :ref (let [r (second node)]
                        (when-not (@seen r)
                          (vswap! seen conj r)
                          (vswap! order conj r)))
                 (do (walk (nth node 1))
                     (walk (nth node 2)))))]
    (walk ast)
    @order))

;; ---------------------------------------------------------
;; Evaluator (single bucket)

(defn eval-ast
  "Evaluate AST against a {ref => number-or-nil} env.
   Returns nil if any input is nil or if division by zero occurs."
  [ast env]
  (case (first ast)
    :num (second ast)
    :ref (get env (second ast))
    (let [a (eval-ast (nth ast 1) env)
          b (eval-ast (nth ast 2) env)]
      (cond
        (or (nil? a) (nil? b)) nil
        (and (= :/ (first ast)) (zero? b)) nil
        :else (case (first ast)
                :+ (+ a b)
                :- (- a b)
                :* (* a b)
                :/ (/ a b))))))

;; ---------------------------------------------------------
;; Series merge — inner join on (bucket, labels)

(defn- -index-by-id
  "Group series by metric id => seq of series."
  [series]
  (group-by :id series))

(defn- -bucket-map
  "Convert one series's :data into {timestamp => value}."
  [s]
  (into {} (map (juxt :timestamp :value)) (:data s)))

(defn- -join-series-by-labels
  "For each unique :labels combination, return {ref => series} where
   every required ref has a series with that label set.
   Series without all required refs are skipped."
  [series-by-id required-refs]
  (when (every? series-by-id required-refs)
    (let [;; All label sets that appear under each required ref
          label-sets-per-ref (mapv #(set (map :labels (series-by-id %)))
                                   required-refs)
          ;; Inner-join across all refs
          common-labels (reduce set/intersection label-sets-per-ref)]
      (for [labels common-labels]
        {:labels labels
         :series-by-ref
         (into {}
               (for [r required-refs]
                 [r (first (filter #(= labels (:labels %))
                                   (series-by-id r)))]))}))))

(defn- -evaluate-formula
  "Evaluate one formula across joined series, returning synthetic
   series (zero or more)."
  [series-by-id {:keys [id expr name unit]}]
  (let [ast (parse expr)
        required (refs ast)]
    (for [{:keys [labels series-by-ref]}
          (-join-series-by-labels series-by-id required)
          :let [bucket-maps (into {}
                                  (map (fn [[r s]] [r (-bucket-map s)]))
                                  series-by-ref)
                ;; Inner-join on timestamps: only buckets present in
                ;; *every* operand survive.
                common-buckets (sort (reduce
                                       set/intersection
                                       (map #(set (keys %))
                                            (vals bucket-maps))))
                points (keep
                         (fn [t]
                           (let [env (into {}
                                           (map (fn [[r m]] [r (get m t)]))
                                           bucket-maps)
                                 v (eval-ast ast env)]
                             (when (some? v) {:timestamp t :value v})))
                         common-buckets)]
          :when (seq points)]
      (cond-> {:id id
               :name (if name (str id ": " name) id)
               :metric nil
               :formula expr
               :labels labels
               :data (vec points)}
        unit (assoc :unit unit)))))

(defn- -series->id-unit
  "Build a {metric-ref => unit} map from source series.
   When the same id appears across multiple label combinations, the first
   non-nil unit wins (all series for one metric share its unit)."
  [series]
  (reduce (fn [acc s]
            (let [id (:id s)
                  u (:unit s)]
              (if (and id (some? u) (not (contains? acc id)))
                (assoc acc id u)
                acc)))
          {}
          series))

(defn- -infer-formula-unit
  "Infer a formula's unit from its referenced metrics.
   Returns the common unit if every referenced metric has a known unit
   and all share the same unit. Returns nil otherwise."
  [expr metric-id->unit]
  (let [required (refs (parse expr))
        ref-units (keep metric-id->unit required)]
    (when (and (seq required)
               (= (count ref-units) (count required))
               (apply = ref-units)
               (first ref-units))
      (first ref-units))))

(defn- -resolve-formula-unit
  "Returns the formula with its :unit field set: explicit if provided,
   otherwise inferred from referenced source series, otherwise unset."
  [formula metric-id->unit]
  (if (:unit formula)
    formula
    (if-let [inferred (-infer-formula-unit (:expr formula) metric-id->unit)]
      (assoc formula :unit inferred)
      formula)))

(defn apply-formulas
  "Append one synthetic series per (formula × matching label combo) to
    the input series. Source series are returned unchanged.
    `formulas` is a vector of {:id :expr :name? :unit?}.

    Each emitted formula series carries a :unit:
      - the formula's explicit :unit if provided, else
      - the common unit of all referenced source series (when they agree), else
      - unset."
  [series formulas]
  (let [series-by-id (-index-by-id series)
        metric-id->unit (-series->id-unit series)
        resolved (map #(-resolve-formula-unit % metric-id->unit) formulas)]
    (vec (concat series
                 (mapcat #(-evaluate-formula series-by-id %) resolved)))))

;; ---------------------------------------------------------
;; Having filter on formula series

(def ^:private -having-ops
  {">"  >
   "<"  <
   ">=" >=
   "<=" <=
   "="  ==
   "!=" (fn [a b] (not (== a b)))})

(defn- -filter-series-data
  "Return `s` with :data filtered by `pred`, or nil if all points are
   dropped. Series whose :id doesn't match `ref` pass through."
  [pred ref s]
  (if-not (= ref (:id s))
    s
    (let [filtered (filterv pred (:data s))]
      (when (seq filtered)
        (assoc s :data filtered)))))

(defn apply-having-to-formula
  "Apply a having predicate to formula series.
   Per-bucket filter: drops data points where (op value threshold) is
   false, then drops series whose :data becomes empty. Series whose
   :id doesn't match having's :ref are returned unchanged. Mirrors
   per-metric SQL HAVING semantics."
  [series {:keys [ref op value]}]
  (let [op-fn (get -having-ops op)
        point-pred #(op-fn (:value %) value)]
    (vec (keep #(-filter-series-data point-pred ref %) series))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Parse a simple ratio expression
  (parse "A / B * 100")
  ;; => [:* [:/ [:ref "A"] [:ref "B"]] [:num 100.0]]

  ;; Unary minus on a literal folds to a single :num node
  (parse "-2")
  ;; => [:num -2.0]

  ;; Extract referenced metric ids in order of first appearance
  (refs (parse "(A - B) / A * 100"))
  ;; => ["A" "B"]

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

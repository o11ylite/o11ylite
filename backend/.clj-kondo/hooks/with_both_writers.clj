(ns hooks.with-both-writers
  (:require [clj-kondo.hooks-api :as api]))

(defn analyze
  "Rewrite (with-both-writers [conn events-writer metrics-writer] body...)
   into (let [conn events-writer _ metrics-writer] body...) so clj-kondo
   sees `conn` as a bound local and lints the value expressions and body."
  [{:keys [node]}]
  (let [[binding-vec & body] (rest (:children node))
        [conn-sym events-writer metrics-writer] (:children binding-vec)
        rewritten (api/list-node
                    (list*
                      (api/token-node 'let)
                      (api/vector-node
                        [conn-sym events-writer
                         (api/token-node '_) metrics-writer])
                      body))]
    {:node (with-meta rewritten (meta node))}))

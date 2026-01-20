;; ---------------------------------------------------------
;; o11ylite.components.id-gen
;;
;; Snowflake-style ID generator for event pagination.
;; Generates 63-bit monotonically increasing IDs:
;;   41 bits: milliseconds since custom epoch (~69 years)
;;    6 bits: node ID (64 nodes max)
;;   16 bits: sequence (65,536 per ms per node)
;;
;; Thread-safe via atomic CAS. No coordination needed between nodes.
;; ---------------------------------------------------------

(ns o11ylite.components.id-gen
  (:require
   [integrant.core :as ig]))

;; ---------------------------------------------------------
;; Constants

(def ^:private custom-epoch
  "2024-01-01T00:00:00Z in milliseconds.
   IDs are valid for ~69 years from this epoch (until ~2093)."
  1704067200000)

(def ^:private node-bits 6)
(def ^:private sequence-bits 16)
(def ^:private max-sequence (bit-shift-left 1 sequence-bits)) ; 65536

;; ---------------------------------------------------------
;; ID Generation

(defn- -generate-next-state
  "Compute next generator state given current time.
   Returns new state map with :last-ts, :sequence, :node-id.
   Handles clock drift by borrowing from future if needed."
  [{:keys [node-id last-ts sequence]} now]
  (cond
    ;; Clock moved forward: reset sequence
    (> now last-ts)
    {:last-ts now :sequence 0 :node-id node-id}

    ;; Same or backwards clock: increment sequence
    ;; If sequence exhausted, borrow from future by advancing last-ts
    (< sequence (dec max-sequence))
    {:last-ts last-ts :sequence (inc sequence) :node-id node-id}

    :else
    {:last-ts (inc last-ts) :sequence 0 :node-id node-id}))

(defn- -state->id
  "Convert generator state to a 63-bit ID."
  [{:keys [last-ts sequence node-id]}]
  (bit-or (bit-shift-left last-ts (+ node-bits sequence-bits))
          (bit-shift-left node-id sequence-bits)
          sequence))

;; ---------------------------------------------------------
;; Public API

(defn next-id!
  "Generate next unique ID. Thread-safe via atomic CAS."
  [generator]
  (let [now (- (System/currentTimeMillis) custom-epoch)
        new-state (swap! (:state generator) -generate-next-state now)]
    (-state->id new-state)))

(defn next-ids!
  "Generate n unique IDs. More efficient than calling next-id! n times.
   Returns a vector of IDs in ascending order, or [] if n is 0."
  [generator n]
  (assert (not (neg? n)) "n must not be negative")
  (let [now (- (System/currentTimeMillis) custom-epoch)
        new-state (swap! (:state generator)
                        (fn [state]
                          (loop [s state
                                 remaining n
                                 ids []]
                            (if (zero? remaining)
                              (with-meta s {::ids ids})
                              (let [next-s (-generate-next-state s now)]
                                (recur next-s
                                       (dec remaining)
                                       (conj ids (-state->id next-s))))))))]
    (::ids (meta new-state))))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :id/generator
  [_ {:keys [node-id] :or {node-id 0}}]
  (assert (<= 0 node-id 63) "node-id must be 0-63")
  {:node-id node-id
   :state (atom {:node-id node-id
                 :last-ts 0
                 :sequence 0})})

;; No halt needed - atom is garbage collected

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.core :as ig])

  ;; Create generator
  (def gen (ig/init-key :id/generator {:node-id 0}))

  ;; Generate IDs
  (next-id! gen)
  ;; => 1234567890123456789 (example)

  ;; Generate batch of IDs
  (next-ids! gen 5)
  ;; => [id1 id2 id3 id4 id5]

  ;; IDs are monotonically increasing
  (let [ids (repeatedly 1000 #(next-id! gen))]
    (= ids (sort ids)))
  ;; => true

  ;; Decode ID to see components (for debugging)
  (defn decode-id [id]
    (let [seq-mask (dec (bit-shift-left 1 16))
          node-mask (dec (bit-shift-left 1 6))]
      {:timestamp (+ custom-epoch (bit-shift-right id 22))
       :node-id (bit-and (bit-shift-right id 16) node-mask)
       :sequence (bit-and id seq-mask)}))

  (decode-id (next-id! gen))
  ;; => {:timestamp 1704067200123, :node-id 0, :sequence 42}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

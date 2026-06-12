;; ---------------------------------------------------------
;; o11ylite.alert-rule.transitions
;;
;; The alert state machine, expressed as data.
;;
;; The entire difference between match ("result") and absence
;; ("no_result") rules lives in the `transitions` table below. A single
;; generic engine (`step`) consumes it; the engine knows nothing about
;; match or absence semantics. To change the state machine, change the
;; data — not the engine, and not the eval loop.
;;
;; A transition is keyed by [mode [stored-state presence]] where:
;;   - mode is :on-match or :on-absence
;;   - stored-state is :none (no instance row yet), :ok, or :firing
;;   - presence is :present (the fingerprint appeared in this tick's
;;     results) or :absent (it did not)
;;
;; The looked-up value is either nil (no-op: persist nothing, notify
;; nothing) or an effect map:
;;   :next          -> the state to store (:firing | :ok | :resolved)
;;   :notify        -> :firing | :resolved, or absent for silent
;;   :update-value  -> refresh last_value/last_seen from the current row
;;   :then-delete   -> delete the row after persisting/notifying (match resolve)
;;
;; Transitions depend ONLY on current-tick presence, never on re-reading
;; historical data, so backfilled telemetry after an ingestion outage is
;; harmless. They are also immediate: a breach fires on the tick it
;; appears and resolves on the tick it clears.
;;
;; Absence bootstrap: an ungrouped absence rule fires the first time its
;; query returns empty, even with no instance row yet ([:none :absent] ->
;; firing). The eval engine guarantees the empty fingerprint is always
;; considered for such rules. Grouped absence rules have no such guarantee
;; — a never-seen group is never in the candidate set, so [:none :absent]
;; cannot fire for them (you can't alert on a group disappearing if it was
;; never observed present).
;; ---------------------------------------------------------

(ns o11ylite.alert-rule.transitions)

;; ---------------------------------------------------------
;; The state machine

(def transitions
  "Mode-aware transition table. See namespace docstring for the shape."
  {:on-match
   {[:none   :present] {:next :firing   :notify :firing}
    [:firing :present] {:next :firing   :update-value true}
    [:firing :absent]  {:next :resolved :notify :resolved :then-delete true}
    [:none   :absent]  nil}

   :on-absence
   {[:none   :present] {:next :ok}
    [:none   :absent]  {:next :firing :notify :firing}
    [:ok     :present] {:next :ok}
    [:ok     :absent]  {:next :firing :notify :firing}
    [:firing :present] {:next :ok     :notify :resolved}
    [:firing :absent]  nil}})

;; ---------------------------------------------------------
;; Mode mapping

(defn alert-on->mode
  "Map the stored alert_on column value to a transition-table mode key.
   'result' fires on rows present (match); 'no_result' fires on absence."
  [alert-on]
  (case alert-on
    "no_result" :on-absence
    ;; default: "result"
    :on-match))

;; ---------------------------------------------------------
;; Engine

(defn step
  "Pure engine. Given the rule mode, the stored state of one instance
   (:none if no row exists), and whether the fingerprint is present this
   tick, return what to do.

   Returns either:
     nil
       no-op (no row to write, nothing to notify)
     {:action :commit :next s :notify k :update-value? b :delete? b}
       apply the transition"
  [mode stored-state present?]
  (let [presence (if present? :present :absent)
        effect (get-in transitions [mode [stored-state presence]])]
    (when effect
      {:action :commit
       :next (:next effect)
       :notify (:notify effect)
       :update-value? (boolean (:update-value effect))
       :delete? (boolean (:then-delete effect))})))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Match rule, group first breaches:
  (step :on-match :none true)
  ;; => {:action :commit :next :firing :notify :firing ...}

  ;; Match rule, group clears -> resolve + delete:
  (step :on-match :firing false)
  ;; => {:action :commit :next :resolved :notify :resolved :delete? true ...}

  ;; Absence rule, expected group disappears:
  (step :on-absence :ok false)
  ;; => {:action :commit :next :firing :notify :firing ...}

  ;; Absence rule, empty from the first eval -> fires immediately:
  (step :on-absence :none false)
  ;; => {:action :commit :next :firing :notify :firing ...}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

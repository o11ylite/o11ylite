;; ---------------------------------------------------------
;; o11ylite.util.ticker
;;
;; Go-style ticker utility using virtual threads.
;; Provides periodic ticks with buffer size 1 (non-blocking).
;; ---------------------------------------------------------

(ns o11ylite.util.ticker
  (:require
   [clojure.core.async :as a]))

;; ---------------------------------------------------------
;; Ticker Implementation

(defn ticker
  "Create a ticker that sends the current time to a channel at regular intervals.
   Similar to Go's time.Ticker with buffer size 1.

   Arguments:
     interval-ms - The interval between ticks in milliseconds

   Returns a map with:
     :ch   - The channel that receives tick values (current time in ms)
     :stop - A function to stop the ticker

   The ticker uses a buffer of size 1 with non-blocking offer, meaning:
     - If the consumer is slow, new ticks are skipped (not queued)
     - The consumer gets the oldest pending tick
     - The producer never blocks

   Example:
     (let [{:keys [ch stop]} (ticker 1000)]
       ;; Consume ticks
       (loop []
         (when-let [t (a/<!! ch)]
           (println \"Tick at\" t)
           (recur)))
       ;; Later, stop the ticker
       (stop))"
  [interval-ms]
  (let [ch (a/chan 1)
        running? (atom true)]
    ;; Start the ticker loop in a virtual thread
    (future
      (while @running?
        (Thread/sleep interval-ms)
        (when @running?
          (a/offer! ch (System/currentTimeMillis)))))
    ;; Return the ticker map
    {:ch ch
     :stop (fn []
             (when (compare-and-set! running? true false)
               (a/close! ch)))}))

(defn tick!
  "Take a tick from the ticker channel. Blocks until a tick is available.
   Returns nil if the ticker has been stopped."
  [ticker]
  (a/<!! (:ch ticker)))

(defn stop!
  "Stop the ticker. Safe to call multiple times."
  [ticker]
  ((:stop ticker)))

;; ---------------------------------------------------------
;; Rich comment block for REPL experimentation

(comment
  ;; Create a ticker that ticks every X seconds
  (def t (ticker 5000))

  ;; Take ticks (blocking)
  (tick! t)

  ;; Use in a future/virtual thread
  (future
    (loop []
      (when-let [tick (tick! t)]
        (println "Tick at:" tick)
        (recur)))
    (println "Ticker stopped"))

  ;; Stop the ticker
  (stop! t)

  :rcf)

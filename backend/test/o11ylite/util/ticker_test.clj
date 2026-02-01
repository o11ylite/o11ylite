;; ---------------------------------------------------------
;; o11ylite.util.ticker-test
;;
;; Unit tests for the ticker utility.
;; ---------------------------------------------------------

(ns o11ylite.util.ticker-test
  (:require
    [clojure.core.async :as a]
    [clojure.test :refer [deftest is testing]]
    [o11ylite.util.ticker :as ticker]))

;; ---------------------------------------------------------
;; Tests

(deftest ticker-returns-expected-structure-test
  (testing "Ticker returns a map with :ch and :stop"
    (let [t (ticker/ticker 100)]
      (try
        (is (map? t))
        (is (contains? t :ch))
        (is (contains? t :stop))
        (is (fn? (:stop t)))
        (finally
          (ticker/stop! t))))))

(deftest ticker-produces-ticks-test
  (testing "Ticker produces ticks at intervals"
    (let [t (ticker/ticker 50)
          ticks (atom [])]
      (try
        ;; Collect 3 ticks
        (dotimes [_ 3]
          (when-let [tick (ticker/tick! t)]
            (swap! ticks conj tick)))
        (is (= 3 (count @ticks)))
        ;; Ticks should be increasing timestamps
        (is (apply < @ticks))
        (finally
          (ticker/stop! t))))))

(deftest ticker-buffer-behavior-test
  (testing "Ticker skips new ticks when buffer is full (consumer is slow)"
    (let [t (ticker/ticker 10)]
      (try
        ;; Let several ticks accumulate (consumer is slow)
        (Thread/sleep 100)
        ;; Should only get one tick from the buffer (new ticks were skipped)
        (let [tick1 (a/poll! (:ch t))
              tick2 (a/poll! (:ch t))]
          (is (some? tick1) "Should have at least one tick")
          (is (nil? tick2) "Buffer size 1 means only one tick available"))
        (finally
          (ticker/stop! t))))))

(deftest ticker-stop-closes-channel-test
  (testing "Stopping ticker closes the channel"
    (let [t (ticker/ticker 50)]
      ;; Get one tick to ensure it's working
      (ticker/tick! t)
      ;; Stop the ticker
      (ticker/stop! t)
      ;; Channel should be closed, next take returns nil
      (is (nil? (a/poll! (:ch t)))))))

(deftest ticker-stop-idempotent-test
  (testing "Stopping ticker multiple times is safe"
    (let [t (ticker/ticker 100)]
      ;; Stop multiple times - should not throw
      (ticker/stop! t)
      (ticker/stop! t)
      (ticker/stop! t)
      (is true "Multiple stops should not throw"))))

(deftest ticker-in-future-test
  (testing "Ticker works correctly in future/virtual threads"
    (let [t (ticker/ticker 30)
          results (atom [])
          done (promise)]
      (try
        ;; Start a future that collects ticks
        (future
          (loop [cnt 0]
            (if (< cnt 3)
              (when-let [tick (ticker/tick! t)]
                (swap! results conj tick)
                (recur (inc cnt)))
              (deliver done true))))
        ;; Wait for completion with timeout
        (is (deref done 500 false) "Should complete within timeout")
        ;; Check we got 3 results
        (is (= 3 (count @results)))
        (finally
          (ticker/stop! t))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.util.ticker-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

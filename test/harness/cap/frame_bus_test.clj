(ns harness.cap.frame-bus-test
  "The frame bus through its contract: who receives what, at what cost to the
  publisher, and how a subscription ends.

  These are process-local facts about a table in memory, so the namespace
  needs no home, no server and no provider -- which is exactly what makes the
  assertions below honest: a bus that needed more than this to test would be
  a bus with somewhere else to fail."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [harness.cap.frame-bus :as bus]))

(defn- drain
  "Every frame the channel answers before the stream ends. THE STREAM ENDS ON
  THE MARKER OR THE CLOSE, whichever comes first -- and that is the reader's
  rule, not the bus's: a close alone is the backstop, the marker is the
  graceful answer, and a reader that only recognized the close would sit
  waiting between the two whenever the ending was announced in band. (This is
  the exact shape ticket 02's route reader must implement; the bus's stop!
  always delivers marker-then-close, and this helper consumes it the way the
  route will.)"
  [ch]
  (loop [acc []]
    (let [frame (async/<!! ch)]
      (cond
        (nil? frame) acc
        (= bus/closed-marker frame) acc
        :else (recur (conj acc frame))))))

(deftest a-frame-goes-to-the-threads-subscribers-and-nobody-else
  (let [s (bus/subscribe! "fb-keyed")
        ch (:ch s)
        stop (:stop! s)]
    (try
      (bus/publish! "fb-keyed" {:type "TEXT_MESSAGE_START" :messageId "m1"})
      (bus/publish! "fb-not-mine" {:type "TEXT_MESSAGE_START" :messageId "m2"})
      (is (= {:type "TEXT_MESSAGE_START" :messageId "m1"}
             (async/<!! ch))
          "the frame lands on the thread it was published for")
      (testing "and nothing arrives from another thread's publish"
        ;; End this reader the way the bus itself would: the marker rides
        ;; in-band, the drain stops on it (the close is the backstop), and
        ;; nothing from fb-not-mine ever crossed over.
        (async/put! ch bus/closed-marker)
        (is (= [] (drain ch))))
      (finally
        (stop)))))

(deftest publishing-with-no-subscribers-costs-nothing
  (is (zero? (bus/subscriber-count "fb-empty")))
  ;; The call itself is the assertion: it answers without constructing a
  ;; delivery for anyone -- there is nothing observable here, which is the
  ;; point; the zero-lookup shape is what the count pins.
  (bus/publish! "fb-empty" {:type "TEXT_MESSAGE_START" :messageId "m0"}))

(deftest a-subscription-ends-with-a-marker-and-a-close
  (let [s (bus/subscribe! "fb-stop")
        ch (:ch s)
        stop (:stop! s)]
    (bus/publish! "fb-stop" {:type "TEXT_MESSAGE_START" :messageId "m1"})
    (stop)
    (is (= [{:type "TEXT_MESSAGE_START" :messageId "m1"}] (drain ch))
        "frames written before the stop are delivered, and the drain ends on
         the marker without it being a frame of its own")
    (is (zero? (bus/subscriber-count "fb-stop")))
    (testing "a second stop is a no-op, not a second marker"
      (stop)
      (is (nil? (async/<!! ch))))))

(deftest two-subscriptions-are-independent
  (let [s1 (bus/subscribe! "fb-two")
        ch1 (:ch s1)
        stop1 (:stop! s1)
        s2 (bus/subscribe! "fb-two")
        ch2 (:ch s2)
        stop2 (:stop! s2)]
    (try
      (is (= 2 (bus/subscriber-count "fb-two")))
      (bus/publish! "fb-two" {:type "TEXT_MESSAGE_START" :messageId "m1"})
      (is (= {:type "TEXT_MESSAGE_START" :messageId "m1"} (async/<!! ch1)))
      (is (= {:type "TEXT_MESSAGE_START" :messageId "m1"} (async/<!! ch2))
          "both followers see the same frame")
      (stop1)
      (is (= 1 (bus/subscriber-count "fb-two")) "one row left")
      (bus/publish! "fb-two" {:type "TEXT_MESSAGE_START" :messageId "m2"})
      (is (= {:type "TEXT_MESSAGE_START" :messageId "m2"} (async/<!! ch2))
          "the surviving subscription still receives")
      (finally
        (stop1)
        (stop2)))
    (is (zero? (bus/subscriber-count "fb-two")))))

(deftest a-slow-reader-cannot-block-the-publisher
  ;; THE ONE TRAP THE TICKET NAMES, tested at the shape the route will meet:
  ;; a subscriber that has stopped reading (nothing is taking from its
  ;; channel) must not stall publish!, no matter how many frames arrive --
  ;; the publisher is a delegation's tool thread, and a blocked publish is a
  ;; blocked delegation. The bound is generous; the failure it guards against
  ;; is a publish! that waits forever.
  (let [s (bus/subscribe! "fb-flood")
        ch (:ch s)
        stop (:stop! s)]
    (try
      (let [frame {:type "TEXT_MESSAGE_CONTENT" :delta "x"}
            start (System/nanoTime)]
        (dotimes [_ 10000]
          (bus/publish! "fb-flood" frame))
        (let [ms (/ (- (System/nanoTime) start) 1000000.0)]
          (is (< ms 5000)
              (str "10000 publishes to a non-reading subscriber took " ms "ms"))))
      ;; After the stop the channel yields the buffered frames and then
      ;; closes: a drain runs dry rather than hanging, and that -- not the
      ;; count -- is the property the route's reader leans on. (A poll!
      ;; here would answer one frame and leave the rest queued, which is
      ;; why this reads to the end instead of peeking.)
      (finally
        (stop)))
    (is (some? (drain ch)) "the drained read ends when the channel closes")))

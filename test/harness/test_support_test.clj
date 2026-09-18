(ns harness.test-support-test
  "The interleaving helpers, on their own.

  THEY EARN THEIR KEEP ONLY IF THEY WITNESS WHAT THEY FORCE. A gate that lets a case
  pass without racing anything is worse than no gate at all: it also hides the fact
  that nothing was checked. So each case here drives the gate the way a real one would
  and then asserts on what the gate can say about it -- including the case where the
  threads never meet at all."
  (:require [clojure.test :refer [deftest is testing]]
            [harness.test-support :as support]))

(defn- probe
  "A var for `window-gate` to wrap: the tests need one they own."
  []
  :answer)

(deftest a-start-gate-lets-nobody-through-until-everybody-is-there
  (let [gate    (support/start-gate 4)
        through (atom 0)
        workers (mapv (fn [_] (future ((:arrive gate)) (swap! through inc) true))
                      (range 4))]
    (is (every? true? (map #(deref % 10000 ::timeout) workers))
        "every thread came back")
    (is (= 4 @through) "and every one of them ran, none was left behind")
    (is (= 4 ((:witness gate)))
        "the gate can say all four met -- which is what a race case asserts on")))

(deftest a-start-gate-nobody-completes-is-a-failure-that-names-itself
  (let [gate (support/start-gate 2 100)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"never opened"
                          ((:arrive gate)))
        "one arrival against a gate of two: red and specific, not a hung suite")
    (is (= 1 ((:witness gate))) "and it says how many did turn up")))

(deftest a-window-gate-holds-a-call-inside-the-window-until-it-is-released
  (let [gate (support/window-gate #'probe)]
    (try
      (let [caller (future (probe))]
        (is (support/holds-within? #(= 1 ((:entered gate))) 5000) "the call reached the window")
        (is (not (realized? caller)) "and is stopped inside it")
        ((:release gate))
        (is (= :answer (deref caller 5000 ::timeout)) "released, it finishes its work"))
      (finally ((:restore gate))))
    (is (= :answer (probe)) "the var is itself again once restored")))

(deftest a-window-gate-that-is-never-released-lets-the-call-go-anyway
  (let [gate (support/window-gate #'probe 100)]
    (try
      (is (= :answer (deref (future (probe)) 5000 ::timeout))
          "the deadline passes and the call proceeds: a slow case is slow, not stuck")
      (finally ((:restore gate))))))

(deftest a-window-gate-can-hold-only-the-first-call
  ;; What a window inside a function BOTH threads run needs: the first caller is held
  ;; there while the second one finishes, and holding the second one too would leave the
  ;; test waiting for a release only the test itself could give.
  (let [gate (support/window-gate #'probe 30000 1)
        first- (future (probe))]
    (try
      (is (support/holds-within? #(= 1 ((:entered gate))) 5000) "the first call is in")
      (is (not (realized? first-)) "and is held")
      (is (= :answer (deref (future (probe)) 5000 ::timeout))
          "while a second call goes straight through")
      ((:release gate))
      (is (= :answer (deref first- 5000 ::timeout)) "released, the held one finishes")
      (finally
        ((:release gate))
        ((:restore gate))))))

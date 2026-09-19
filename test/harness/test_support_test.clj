(ns harness.test-support-test
  "Two of the process-wide helpers, on their own: the interleaving gates, and the one
  promise `temp-dir` makes to the fifty namespaces that lean on it.

  THEY EARN THEIR KEEP ONLY IF THEY WITNESS WHAT THEY FORCE. A gate that lets a case
  pass without racing anything is worse than no gate at all: it also hides the fact
  that nothing was checked. So each case here drives the gate the way a real one would
  and then asserts on what the gate can say about it -- including the case where the
  threads never meet at all."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [harness.test-support :as support]))

(defn- probe
  "A var for `window-gate` to wrap: the tests need one they own."
  []
  :answer)

;; --------------------------------------------------------------- the temp directory
;;
;; THE PROPERTY THE WHOLE SUITE NOW LEANS ON, asserted here because it is the one a
;; helper can lose without anything else going red: a path composed as `<tmpdir>/<label>`
;; names the same directory on every run and in every process at once, so two cases
;; running side by side -- the suite and a `--scripted` walkthrough, say -- silently
;; share one scratch tree, and one run's leftovers are the next run's input. Asking the
;; OS for a name is what removes both; this is the case that would catch a well-meant
;; 'simplification' back to a composed name.

(deftest a-temp-dir-is-one-nobody-else-has-and-comes-back-empty
  (let [a (support/temp-dir "probe")
        b (support/temp-dir "probe")]
    (try
      (is (not= a b)
          "the same label twice is two directories: that difference from a composed name is the point")
      (is (.isDirectory (io/file a)) "and the directory exists when it comes back")
      (is (empty? (seq (.listFiles (io/file a))))
          "with nothing in it, so a case plants its own files rather than meeting another's")
      (is (.startsWith (.getCanonicalPath (io/file a))
                       (.getCanonicalPath (io/file (System/getProperty "java.io.tmpdir"))))
          "and it is under the system temp directory, where a run is expected to leave it")
      (finally
        (support/wipe-tree! a)
        (support/wipe-tree! b)))))

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

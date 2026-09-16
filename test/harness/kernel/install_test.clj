(ns harness.kernel.install-test
  "The install door's contract, asserted directly: an empty table when nothing is
  installed, later-wins for a name two layers both define, a switch-off that KEEPS
  the name visible, a teardown that withdraws only its own layer, and a session
  overlay the door does not touch.

  WHY THESE FIVE AND NOT SOMETHING ELSE. The door exists so that a capability can
  be put in and taken out at setup, and every one of these is a way that shape
  fails silently rather than loudly: a teardown that deletes a name instead of
  withdrawing a layer corrupts a LATER layer's work and only shows up when the
  uninstall order happens to be unlucky; a switch-off implemented as a removal
  makes a capability vanish from the model's view, which this repo has already
  ruled out once (see CONTEXT.md on :removed); and an install that quietly
  reached into the per-session overlay would break the per-thread promise every
  session switch makes.

  NOTHING HERE INSTALLS THE BUILT-INS, deliberately -- the empty-table assertion
  needs the table to actually be empty, so this namespace does not use the
  `with-builtins` fixture the rest of the suite does."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.kernel.tools :as tools]))

(defn- widget
  "A tool definition with a recognisable description, so a test can say WHICH
  layer's definition it is looking at."
  [description]
  {:description description
   :parameters  {:type "object" :properties {}}
   :required    []
   :run         (fn [_] (str "ran " description))})

(defn- a-call
  "NAME as the provider sends a call, which is the shape run! takes."
  [name]
  {:id "call-1" :type "function" :function {:name name :arguments "{}"}})

(deftest an-empty-table-when-nothing-is-installed
  (testing "the seam ships no tools of its own"
    ;; This is the assertion that makes the split real. Before it, requiring the
    ;; seam gave you eleven tools; now a process that installed nothing has none,
    ;; and that is a fact about the code rather than a convention.
    (is (empty? @tools/registry) "the base table is empty")
    (is (= [] (tools/specs "some-thread")) "so the model is offered nothing")
    (is (empty? (tools/effective-tools "some-thread"))))
  (testing "and an uninstalled name is refused as unknown, not as disabled"
    ;; The distinction is the model's: 'unknown' means look elsewhere, 'disabled'
    ;; means it exists and is switched off. An empty table must say the first.
    (let [r (tools/run! (a-call "widget") "some-thread")]
      (is (:error r))
      (is (str/includes? (:content r) "unknown tool")
          (str "expected 'unknown tool', got: " (:content r))))))

(deftest a-later-layer-replaces-an-earlier-definition
  (let [a (tools/install! {:name "A" :tools {"widget" (widget "from A")}})]
    (try
      (is (= "from A" (:description (@tools/registry "widget"))))
      (let [b (tools/install! {:name "B" :tools {"widget" (widget "from B")}})]
        (try
          (testing "later wins, without the earlier layer being unloaded first"
            (is (= "from B" (:description (@tools/registry "widget")))))
          (finally (b))))
      (testing "and withdrawing the later layer restores the earlier one"
        (is (= "from A" (:description (@tools/registry "widget")))))
      (finally (a)))))

(deftest teardown-withdraws-only-its-own-layer
  ;; The failure this guards against is an uninstall that says 'delete this name'
  ;; rather than 'take my layer back'. Reversed, it corrupts the layer that
  ;; REPLACED it, and the damage only appears when the uninstall order is
  ;; unlucky -- which is exactly the coupling setup/teardown exists to remove.
  (let [a (tools/install! {:name "A" :tools {"widget" (widget "from A")}})
        b (tools/install! {:name "B" :tools {"widget" (widget "from B")}})]
    (try
      (a)
      (testing "B's definition survives A's teardown"
        (is (contains? @tools/registry "widget")
            "an uninstall that deleted the name would have taken B's with it")
        (is (= "from B" (:description (@tools/registry "widget")))))
      (testing "and a teardown may be called twice"
        (a)
        (is (= "from B" (:description (@tools/registry "widget")))))
      (finally
        (b)
        (is (not (contains? @tools/registry "widget"))
            "withdrawing the last layer leaves nothing behind")))))

(deftest switching-a-name-off-keeps-it-visible-and-refuses-its-call
  (let [td (tools/install! {:name "switches"
                            :tools {"widget" (widget "w")}
                            :disable ["widget"]})]
    (try
      (testing "the definition is still there"
        (is (contains? @tools/registry "widget")
            "a switch-off is not a removal -- see CONTEXT.md on :removed"))
      (testing "and the model can still see it, which is the point of keeping it"
        (is (some #(= "widget" (get-in % [:function :name])) (tools/specs "s"))
            "a tool that cannot be seen reads as 'this does not exist'"))
      (testing "but its calls are refused, and the refusal names who switched it off"
        (let [r (tools/run! (a-call "widget") "s")]
          (is (:error r))
          (is (str/includes? (:content r) "disabled") (:content r))
          (is (str/includes? (:content r) "switches")
              (str "the refusal must name the layer, got: " (:content r)))))
      (finally (td)))
    (testing "and withdrawing the layer takes the switch-off AND the definition with it"
      ;; A switch-off is not a removal, but a TEARDOWN is: it withdraws a
      ;; contribution. The two are different statements about a name, and only the
      ;; second makes the name disappear -- which is why a caller that wants a name
      ;; gone says so by unloading the layer that brought it, not by disabling it.
      (let [r (tools/run! (a-call "widget") "s")]
        (is (:error r))
        (is (str/includes? (:content r) "unknown tool") (:content r))))))

(deftest the-door-does-not-touch-the-session-overlay
  ;; Installing is process-wide; a session's own additions are per-thread. The two
  ;; are layers of different kinds, and an install that reached the second would
  ;; break the promise every session switch makes to the other sessions.
  (let [thread "install-test-thread"
        mine   (widget "the session's own")]
    (tools/session-register! thread "mine" mine)
    (let [td (tools/install! {:name "layer" :tools {"widget" (widget "from a layer")}})]
      (try
        (is (contains? (tools/effective-tools thread) "mine"))
        (is (contains? (tools/effective-tools thread) "widget")
            "the session sees the layer's tool too -- it is the base it overlays")
        (finally (td)))
      (testing "the teardown takes the layer and leaves the session's own addition"
        (is (contains? (tools/effective-tools thread) "mine"))
        (is (not (contains? (tools/effective-tools thread) "widget")))))
    (testing "and a switch-off in a layer is not a session switch"
      ;; session-enable! is the session's door; a layer's switch-off is not the
      ;; session's to undo, and the seam must not pretend otherwise.
      (let [td (tools/install! {:name "off" :tools {"gadget" (widget "g")} :disable ["gadget"]})]
        (try
          (tools/session-enable! thread "gadget")
          (is (str/includes? (:content (tools/run! (a-call "gadget") thread)) "off")
              "still refused by the layer after the session tried to re-enable it")
          (finally (td)))))
    (tools/session-unregister! thread "mine")))

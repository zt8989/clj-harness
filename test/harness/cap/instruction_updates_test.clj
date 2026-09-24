(ns harness.cap.instruction-updates-test
  "The instruction signature, the per-thread memory and the delivery plan
  (`.scratch/instruction-updates` tickets 01 and 02).

  THESE ARE UNIT CASES OVER THE RAW SEAMS, so they pin the questions the HTTP cases
  cannot isolate: which facts move the signature, that the hooks are not re-run when
  nothing moved, that the first send is not a change, and that a change under
  :in-place freezes message[0] while riding the tail. The end-to-end shape -- a real
  run, a real record -- lives in harness.edge.http-test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.instruction-updates :as instructions]
            [harness.cap.system-prompt :as system-prompt]
            [harness.kernel.hooks.dispatch :as dispatch]
            [harness.kernel.tools :as tools]
            [harness.test-support :as support]))

(use-fixtures :once support/with-builtins)

;; A hooks.edn written here declares for EVERY thread in this process, and the memory
;; is process-wide too, so both are wiped around every test.
(use-fixtures :each
  (fn [f]
    (support/wipe-hooks!)
    (instructions/forget!)
    (try (f)
         (finally (support/wipe-hooks!) (instructions/forget!)))))

(defn- tool-table
  "One OpenAI-shaped tool, N characters of description -- the description is what a
  re-description case moves without moving the name set."
  [name n]
  [{:type "function"
    :function {:name name
               :description (apply str (repeat n "d"))
               :parameters {:type "object" :properties {}}}}])

(defn- plan-run
  "One `plan` with the hook sink bound, collecting the audit lines the point wrote
  (one per trigger that had matching declarations). `:audits` empty therefore means
  the hooks were NOT RUN, which is the fact the reuse cases rest on."
  [thread-id delivery]
  (let [audits (atom [])]
    (try
      {:plan  (binding [dispatch/*sink* {:thread-id thread-id
                                         :run-id "r-instruction-updates-test"
                                         :audit #(swap! audits conj %)}]
               (instructions/plan thread-id delivery))
       :audits @audits}
      (catch Exception e {:error e :audits @audits}))))

(defn- complete!
  "`plan` + `commit!` -- what a run that reached the provider does -- answering the
  plan it committed."
  [thread-id delivery]
  (let [answer (plan-run thread-id delivery)]
    (if (:plan answer)
      (do (instructions/commit! thread-id (:plan answer)) (:plan answer))
      (throw (:error answer)))))

(defn- tool-hash [table]
  (with-redefs [tools/specs (fn [_] table)]
    (:tools-names-hash (instructions/signature "iu-tools"))))

;; ------------------------------------------------------------------ the signature

(deftest the-tool-half-moves-with-the-names-and-not-with-the-descriptions
  (is (= (tool-hash (tool-table "read" 10)) (tool-hash (tool-table "read" 40)))
      "a re-description does not move the name set")
  (is (not= (tool-hash (tool-table "read" 10)) (tool-hash (tool-table "grep" 10)))
      "an added or removed tool does")
  (is (nil? (tool-hash [])) "no table, no half -- a nil hash is 'nothing to compare'"))

(deftest the-hook-half-moves-when-a-declaration-appears
  (let [before (:hooks-names-hash (instructions/signature "iu-hooks"))]
    (support/write-hooks! {:system-prompt [{:command "printf 'A BLOCK'"}]})
    (is (not= before (:hooks-names-hash (instructions/signature "iu-hooks"))))))

;; --------------------------------------------------------------- plan and reuse

(deftest the-first-send-assembles-and-the-second-reuses-it
  (support/write-hooks! {:system-prompt [{:command "printf 'A BLOCK'"}]})
  (let [first  (plan-run "iu-two-sends" :replace)
        _      (instructions/commit! "iu-two-sends" (:plan first))
        second (plan-run "iu-two-sends" :replace)]
    (is (empty? (:error first)))
    (is (false? (:changed? (:plan first))) "the first send is not a change (decision 4)")
    (is (false? (:reused? (:plan first))) "and it does assemble")
    (is (seq (:audits first)) "which runs the hooks")
    (is (true? (:reused? (:plan second))) "nothing moved, so assembly is skipped")
    (is (empty? (:audits second)) "and NO hook is re-run -- the whole saving")
    (is (= (:text (:plan first)) (:text (:plan second))) "the same text is reused")))

(deftest a-run-that-never-committed-is-not-remembered
  ;; `plan` writes nothing: the memory is committed by a run that got as far as sending.
  (support/write-hooks! {:system-prompt [{:command "printf 'A BLOCK'"}]})
  (plan-run "iu-uncommitted" :replace)
  (is (nil? (instructions/remembered "iu-uncommitted")))
  (is (false? (:reused? (:plan (plan-run "iu-uncommitted" :replace))))
      "so the next run assembles again"))

(deftest a-refused-assembly-leaves-the-memory-alone
  (support/write-hooks! {:system-prompt [{:command "exit 2"}]})
  (let [answer (plan-run "iu-refused" :replace)]
    (is (some? (:error answer)) "the point refused the run")
    (is (nil? (instructions/remembered "iu-refused"))
        "nothing was assembled, so nothing is remembered (decision 5)")))

(deftest two-threads-do-not-share-their-memory
  (support/write-hooks! {:system-prompt [{:command "printf 'A BLOCK'"}]})
  (complete! "iu-thread-a" :replace)
  (is (false? (:reused? (:plan (plan-run "iu-thread-b" :replace))))
      "a thread this process has not served assembles once")
  (is (true? (:reused? (:plan (plan-run "iu-thread-a" :replace))))
      "while the one it has still reuses"))

;; -------------------------------------------------------------------- delivery

(deftest an-in-place-change-freezes-message-zero-and-rides-the-tail
  (support/write-hooks! {:system-prompt [{:command "printf 'BLOCK ONE'"}]})
  (let [first  (complete! "iu-in-place" :in-place)
        _      (support/write-hooks! {:system-prompt [{:command "printf 'BLOCK ONE'"}
                                                      {:command "printf 'BLOCK TWO'"}]})
        second (complete! "iu-in-place" :in-place)]
    (is (false? (:changed? first)))
    (is (true? (:changed? second)))
    (is (= (:content (:system first)) (:content (:system second)))
        "message[0] is the bytes the model already read")
    (is (not= (:text first) (:text second)) "but a NEW assembly happened")
    (is (= [(:text second)] (:updates second))
        "exactly one developer message, carrying the FULL new text")))

(deftest an-in-place-run-that-changed-nothing-resends-the-same-chain
  (support/write-hooks! {:system-prompt [{:command "printf 'BLOCK ONE'"}]})
  (complete! "iu-in-place-idle" :in-place)
  (support/write-hooks! {:system-prompt [{:command "printf 'BLOCK ONE'"}
                                         {:command "printf 'BLOCK TWO'"}]})
  (complete! "iu-in-place-idle" :in-place)
  (let [third (complete! "iu-in-place-idle" :in-place)]
    (is (false? (:changed? third)))
    (is (true? (:reused? third)) "the signature held, so no assembly")
    (is (= 1 (count (:updates third))) "the client does not hold it, so it is resent")))

(deftest a-chain-of-changes-keeps-the-order-they-happened-in
  (support/write-hooks! {:system-prompt [{:command "printf 'ONE'"}]})
  (complete! "iu-chain" :in-place)
  (support/write-hooks! {:system-prompt [{:command "printf 'ONE'"}
                                         {:command "printf 'TWO'"}]})
  (let [second (complete! "iu-chain" :in-place)]
    (support/write-hooks! {:system-prompt [{:command "printf 'ONE'"}
                                           {:command "printf 'TWO'"}
                                           {:command "printf 'THREE'"}]})
    (let [third (complete! "iu-chain" :in-place)]
      (is (= [(:text second) (:text third)] (:updates third))
          "both updates, and the later one is the source of truth"))))

(deftest a-replace-change-swaps-message-zero-and-sends-no-update
  (support/write-hooks! {:system-prompt [{:command "printf 'BLOCK ONE'"}]})
  (let [first  (complete! "iu-replace" :replace)
        _      (support/write-hooks! {:system-prompt [{:command "printf 'BLOCK ONE'"}
                                                      {:command "printf 'BLOCK TWO'"}]})
        second (complete! "iu-replace" :replace)]
    (is (true? (:changed? second)))
    (is (= (:text second) (:content (:system second)))
        "the fresh text IS message[0] -- today's behaviour, named")
    (is (empty? (:updates second)) "and nothing rides the tail")
    (is (not= (:content (:system first)) (:content (:system second))))))

(deftest switching-a-replace-session-to-in-place-freezes-what-it-last-sent
  (support/write-hooks! {:system-prompt [{:command "printf 'ONE'"}]})
  (let [replace (complete! "iu-switch" :replace)]
    (support/write-hooks! {:system-prompt [{:command "printf 'ONE'"}
                                           {:command "printf 'TWO'"}]})
    (let [in-place (complete! "iu-switch" :in-place)]
      (is (= (:content (:system replace)) (:content (:system in-place)))
          "the frozen base is what the previous run actually sent")
      (is (= [(:text in-place)] (:updates in-place))))))

(deftest the-fallback-drops-the-chain-and-uses-the-fresh-text
  (support/write-hooks! {:system-prompt [{:command "printf 'BLOCK ONE'"}]})
  (complete! "iu-fallback" :in-place)
  (support/write-hooks! {:system-prompt [{:command "printf 'BLOCK ONE'"}
                                         {:command "printf 'BLOCK TWO'"}]})
  (let [plan (plan-run "iu-fallback" :in-place)
        fb   (instructions/fallback (:plan plan))]
    (is (= (:text (:plan plan)) (:content (:system fb))))
    (is (empty? (:updates fb)))
    (is (= :replace (:mode fb)))))

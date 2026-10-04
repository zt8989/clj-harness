(ns harness.no-id-tool-call-test
  "A MODEL THAT NAMES ONLY SOME OF ITS CALLS.

  THE RED THIS FILE EXISTS FOR (owner report, 2026-10-04, session 71683598): a vendor
  returned a real id for the first tool call of a run and an EMPTY STRING for every
  call after it. Four readers assume a call has an id, so the blank one did not fail
  loudly -- it failed as a question nobody could answer. The parked `ask` drew NO
  CARD AT ALL, because `ElicitationGate` finds its interrupt by matching
  `toolCallId` and the client gives an idless call a random one, so the two never
  meet. The run sat at the approval mark with nothing to click.

  THESE DRIVE `consume-sse` AND NOT THE EVENT API, because that is the layer the
  vendor's own bytes go through: the blank id enters at the SSE delta, and the fix
  belongs where the deltas become whole calls (llm/fold-tool-calls). A test that
  handed :tool/call a blank id directly would pass with the pipeline unfixed -- it
  would be testing a shape no vendor ever sent."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [harness.edge.ag-ui :as ag]
            [harness.kernel.event :as ev]
            [harness.kernel.frames :as frames]
            [harness.kernel.llm :as llm]))

(defn- payload
  "The tool_calls delta as the map it is, before it is spelled onto the wire."
  [index m]
  {:choices [{:index 0
              :delta {:tool_calls [{:index index
                                    :id (:id m)
                                    :function {:name (get-in m [:function :name])
                                               :arguments (get-in m [:function :arguments])}}]}}]})

(defn- call-delta
  "One SSE line carrying a tool-call delta."
  [index m]
  (str "data: " (json/write-str (payload index m))))

(defn- events-of
  "The kernel events those lines produce."
  [lines]
  (let [seen (atom [])]
    (llm/consume-sse lines #(swap! seen conj %))
    @seen))

(defn- ids-of
  "The call ids the stream ended up with, in order."
  [lines]
  (->> (events-of lines)
       (filter #(= :tool/call (:type %)))
       (mapv :id)))

(defn- frames-of
  "The frames those events become on the wire."
  [lines]
  (let [emit (ag/outbound "thr-1" "run-1")]
    (vec (mapcat emit (cons (ev/run-start) (events-of lines))))))

(defn- start-ids
  "The ids the frames announce on TOOL_CALL_START."
  [lines]
  (->> (frames-of lines)
       (filter #(= "TOOL_CALL_START" (:type %)))
       (mapv :toolCallId)))

(defn- folded-calls
  "The calls the folded frames carry, as id -> name."
  [lines]
  (let [msgs (frames/apply-frames (frames-of lines))
        held (first (filter #(seq (:toolCalls %)) msgs))]
    (into {} (map (juxt :id #(get-in % [:function :name]))) (:toolCalls held))))

(defn- folded-args
  "The calls the folded frames carry, as name -> arguments."
  [lines]
  (let [msgs (frames/apply-frames (frames-of lines))
        held (first (filter #(seq (:toolCalls %)) msgs))]
    (into {} (map (juxt #(get-in % [:function :name])
                           #(get-in % [:function :arguments])))
          (:toolCalls held))))

(deftest a-call-the-vendor-left-unnamed-still-gets-an-id
(let [lines [(call-delta 0 {:id "call_abc" :function {:name "read" :arguments "{}"}})
(call-delta 1 {:id "" :function {:name "write" :arguments "{}"}})]]
    (testing "the blank one is NAMED rather than carried through"
      (is (every? seq (ids-of lines))))
    (testing "and it does not take the vendor's id with it"
      (is (= 2 (count (distinct (ids-of lines)))))
      (is (= "call_abc" (first (ids-of lines)))))
    (testing "and the frames agree, because they carry this same id"
      (is (every? seq (start-ids lines))))))

(deftest a-vendor-that-names-every-call-is-left-alone
  ;; THE OTHER HALF: a vendor that names its calls keeps its own ids. Re-spelling
  ;; them would break the pairing with the record message rows, whose
  ;; tool_calls[].id is that same vendor string -- prune-messages keys on it.
(let [lines [(call-delta 0 {:id "call_abc" :function {:name "read" :arguments "{}"}})
(call-delta 1 {:id "call_def" :function {:name "bash" :arguments "{}"}})]]
    (is (= ["call_abc" "call_def"] (ids-of lines)))))

(deftest two-unnamed-calls-of-one-turn-do-not-share-one-id
  ;; PLACE 1 OF 4, AT ITS SHARPEST. apply-frames finds a call's ARGUMENTS by matching
  ;; that id, so two calls sharing a blank one append one call's JSON to the other's.
  ;; The index the vendor keys its deltas by is what keeps the two apart.
  (let [lines [(call-delta 0 {:id "" :function {:name "read" :arguments "{\"a\":1}"}})
               (call-delta 1 {:id "" :function {:name "bash" :arguments "{\"b\":2}"}})]]
    (is (= 2 (count (distinct (ids-of lines)))))
    (testing "and each call keeps its OWN arguments, which a shared id destroys"
(is (= {"read" "{\"a\":1}" "bash" "{\"b\":2}"} (folded-args lines)))
(is (= #{"read" "bash"} (set (vals (folded-calls lines))))))
    (testing "and the two are two names in the fold, not one"
      (is (= 2 (count (folded-calls lines)))))))

(deftest a-vendor-that-REUSES-one-id-gets-a-different-one-per-call
  ;; A BLANK ID IS NOT THE ONLY WAY AN ID CAN FAIL, and this is the other one: the owner
  ;; reported (2026-10-04, session 6f1986e9) that several `bash` cards all drew the SAME `ask`
  ;; card. That is not a UI fault. The vendor that numbers its calls from the start of the
  ;; REQUEST spells every call in the conversation `call-0` -- in that one run, 181 of its 190
  ;; calls shared the id `call-0`, across five different tools.
  ;;
  ;; FOUR READERS BREAK AT ONCE, and the arguments are only the first: `apply-frames` finds a call's
  ;; ARGUMENTS by matching that id, so a `read` ends up holding a `bash`'s JSON spliced on the end
  ;; (measured on that run: 181 of 190 calls, every one of them carrying two calls' arguments);
  ;; `replay/park-on-call` and `prune-messages` key on it the same way; and the client's
  ;; `ElicitationGate` finds its interrupt by `toolCallId`, so ONE parked `ask` drew its card on
  ;; 181 tool cards at once. That last one is what the owner saw.
  ;;
  ;; THE OLD DOCSTRING SAID A CALL IS ONLY EVER LOOKED UP INSIDE ITS OWN ASSISTANT MESSAGE, and this
  ;; record is what says otherwise: the client matches a card to an interrupt by id, across the
  ;; whole conversation, and the frames are folded back out of the record the same way. So uniqueness
  ;; has to hold for the WHOLE conversation, not one message.
  (let [lines [(call-delta 0 {:id "call-0" :function {:name "bash" :arguments "{\"a\":1}"}})
               (call-delta 1 {:id "call-0" :function {:name "read" :arguments "{\"b\":2}"}})
               (call-delta 2 {:id "call-0" :function {:name "write" :arguments "{\"c\":3}"}})]]
    (testing "three calls that all call themselves call-0 are three ids"
      (is (= 3 (count (distinct (ids-of lines))))))
    (testing "and each call keeps its OWN arguments, which a shared id destroys"
      (is (= {"bash" "{\"a\":1}" "read" "{\"b\":2}" "write" "{\"c\":3}"}
            (folded-args lines))
            (str "a shared id appends one call's JSON to the other's, which is what the"
                 " fold produced on the real record")))
    (testing "and the frames agree, because they carry these same ids"
      (is (= 3 (count (distinct (start-ids lines))))))
    (testing "and a vendor that names every call distinctly keeps its own names"
      (let [good [(call-delta 0 {:id "call_abc" :function {:name "read" :arguments "{}"}})
                  (call-delta 1 {:id "call_def" :function {:name "bash" :arguments "{}"}})]]
        (is (= ["call_abc" "call_def"] (ids-of good))
            "re-spelling them would break the pairing with the record's own message rows")))))

(deftest the-SAME-id-in-a-LATER-turn-is-re-spelled-too
  ;; THE SHAPE THE REAL RECORD HAS, and the one the test above cannot reach. On session
  ;; `6f1986e9` the 181 colliding calls were 181 SEPARATE turns -- one call each, each its own
  ;; assistant message -- so nothing about a single message is wrong and a fold that only looks
  ;; inside one turn would pass while the conversation still carried 181 calls all named `call-0`.
  ;;
  ;; This is why the ids already in the CONVERSATION are handed into the fold rather than read
  ;; off the turn: the client matches a card to a parked interrupt by id across the whole
  ;; conversation, so `call-0` is a collision with history and not only with the turn beside it."
  (let [history [{:role "assistant" :content ""
                   :tool_calls [{:id "call-0" :type "function"
                                 :function {:name "bash" :arguments "{}"}}]}
                 {:role "tool" :tool_call_id "call-0" :content "done"}]
        ids    (volatile! #{})
        ;; What `stream!` reads out of the conversation before it hands the ids down.
        in-use (#'llm/call-ids-in-use history)
        _      (is (= #{"call-0"} in-use) "the conversation already owns that id")
        lines  [(call-delta 0 {:id "call-0" :function {:name "read" :arguments "{\"b\":2}"}})]
        seen   (llm/consume-sse lines (fn [_] (vswap! ids conj nil)) in-use)
        id     (get-in seen [:message :tool_calls 0 :id])]
    (testing "the later call does not take the name the conversation already uses"
      (is (not= "call-0" id)))
    (testing "and a vendor naming a FRESH id still keeps it, even with `call-0` in history"
      (let [out (llm/consume-sse [(call-delta 0 {:id "call_zzz"
                                             :function {:name "read" :arguments "{}"}})]
                                 (fn [_] nil)
                                 in-use)]
        (is (= "call_zzz" (get-in out [:message :tool_calls 0 :id])))))))

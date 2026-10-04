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

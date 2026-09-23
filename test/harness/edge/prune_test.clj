(ns harness.edge.prune-test
  "Tool-result pruning, asserted as ordinary function calls.

  Ticket 06 is the FREE step before a compaction: a giant tool result is elided in the middle and
  the whole decision -- which results, how much, and how many code points that took out -- is a
  pure function over the entries. Two properties are pinned that a bare `subs` gets wrong: the
  counts are CODE POINTS (a character outside the BMP is two UTF-16 units) and a cut never lands
  inside a surrogate pair. The read half (`replay/prune-messages`, `replay/prune-facts`) is
  asserted here too, because the writer and the reader must agree on the fact's shape."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.edge.prune :as prune]
            [harness.edge.replay :as replay]))

(defn- big [n] (apply str (repeat n "x")))

(defn- tool-entry
  "An entry in `harness.edge.replay/entries`' shape whose message is a tool result."
  ([seq content] (tool-entry seq "c1" content))
  ([seq id content]
   {:seq seq :message {:role "tool" :toolCallId id :content content}}))

;; --------------------------------------------------------------- code points, not code units

(deftest code-points-not-code-units
  (is (= 1 (prune/code-point-count "😀")))
  (is (= 2 (prune/code-point-count "a😀")))
  (is (= 3 (prune/code-point-count "abc")))
  (is (= 0 (prune/code-point-count nil)) "a non-string counts as nothing"))

(deftest a-cut-never-splits-a-surrogate-pair
  ;; "😀" is ONE code point and TWO UTF-16 units; a cut made by char index could land between
  ;; the halves and produce two lone halves.
  (let [s (str "a" "😀" "b")]
    (is (= "a😀" (prune/slice s 0 2)))
    (is (= "😀b" (prune/slice s 1 3)))
    (is (= s (prune/slice s 0 99)) "to is clamped to the string")
    (is (= "" (prune/slice s 5 9)) "a from past the end clamps to empty")))

(deftest elide-keeps-a-fixed-head-and-tail-and-counts-code-points
  (let [{:keys [content before after removed]} (prune/elide (big 20000))]
    (is (= 20000 before))
    (is (= (- 20000 (* 2 prune/keep-chars)) removed))
    (is (str/includes? content (str "pruned " removed " characters")))
    (is (= (+ (* 2 prune/keep-chars)
              (prune/code-point-count "\n…[pruned 16000 characters]…\n"))
           after)
        "after counts the marker too")))

(deftest a-short-tool-result-is-left-alone
  (is (nil? (prune/elide (big 100))))
  (is (empty? (prune/prune-plan [(tool-entry 0 (big 100))]))))

;; ------------------------------------------------------------------------------- the plan

(deftest the-plan-only-touches-oversized-tool-results
  (let [entries [(tool-entry 0 "short")
                 (tool-entry 1 (big 20000))
                 {:seq 2 :message {:role "user" :content (big 20000)}}]  ;; not a tool result
        plan    (prune/prune-plan entries)]
    (is (= 1 (count plan)) "only the oversized TOOL result is in the plan")
    (is (= "c1" (:toolCallId (first plan))))
    (is (= [1] (:shadowed (first plan))) "it names the event it replaces")
    (is (= {:pruned 1 :removed (:removed (first plan))} (prune/reduction plan)))))

(deftest an-already-pruned-call-is-not-pruned-again
  ;; IDEMPOTENCE: the record keeps the ORIGINAL tool message and a separate receipt, so
  ;; `entries` still holds the giant text on every read. Without DONE-IDS a caller would elide the
  ;; same result -- and append the same receipt -- once per run, for ever.
  (let [entries [(tool-entry 0 (big 20000))]]
    (is (= 1 (count (prune/prune-plan entries))))
    (is (empty? (prune/prune-plan entries #{"c1"})))
    (is (empty? (prune/prune-plan entries (into #{} (map :toolCallId)
                                               (prune/prune-plan entries))))
        "the plan's own call ids are exactly what the caller excludes next time")))

(deftest the-receipt-names-the-call-and-both-counts
  (let [r (prune/receipt (first (prune/prune-plan [(tool-entry 7 (big 20000))])))]
    (is (= "c1" (:toolCallId r)))
    (is (= [7] (:shadowed r)))
    (is (= 20000 (:before r)))
    (is (< (:after r) (:before r)))
    (is (string? (:content r)))))

;; ------------------------------------------------------------------ the fold (read half)

(deftest the-model-view-reads-a-pruned-tool-result
  (let [entries [{:seq 0 :message {:role "user" :content "hi"}}
                 {:seq 1 :message {:role "tool" :toolCallId "c1" :content "long"}}]]
    (is (= ["hi" "SHORT"]
           (mapv :content (replay/compacted-messages
                           entries []
                           [{:toolCallId "c1" :content "SHORT"}]))))
    (is (= ["hi" "long"]
           (mapv :content (replay/compacted-messages entries [])))
        "the two-argument arity has no prunings and pays nothing for them")))

(deftest a-pruning-finds-its-message-in-either-spelling
  (let [facts [{:toolCallId "c1" :content "ELIDED"}]]
    (is (= [{:role "tool" :tool_call_id "c1" :content "ELIDED"}]
           (replay/prune-messages [{:role "tool" :tool_call_id "c1" :content "original"}] facts))
        "a message read off the record is provider-shaped")
    (is (= [{:role "tool" :toolCallId "c1" :content "ELIDED"}]
           (replay/prune-messages [{:role "tool" :toolCallId "c1" :content "original"}] facts))
        "one folded out of a run's frames is AG-UI's")
    (is (= [{:role "user" :content "hi"}]
           (replay/prune-messages [{:role "user" :content "hi"}] facts))
        "a message without that call is untouched")))

(deftest prune-facts-are-read-back-with-their-offsets
  (let [rows [{:ts 0 :type "event"
               :payload {:type "CUSTOM" :name "context/pruned"
                         :value {:toolCallId "c1" :before 10 :after 4 :shadowed [0]}}}
              {:ts 1 :type "message" :payload {:role "user" :content "x"}}]]
    (is (= [{:toolCallId "c1" :before 10 :after 4 :shadowed [0] :seq 0}]
           (replay/prune-facts rows)))
    (testing "a record with no prunings reads as an empty list"
      (is (= [] (replay/prune-facts [(second rows)]))))))

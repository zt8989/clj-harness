(ns harness.edge.compaction-test
  "The compaction projection, asserted over HAND-WRITTEN RECORDS.

  Ticket 02 is the READ half of compaction and nothing else: a compaction the record
  declares changes what the MODEL is handed -- the shadowed messages drop out and one
  summary message stands where they stood -- while the record keeps every row and the
  client keeps reading the originals. So these cases pin three things:

    - the model view shrinks and the summary goes to the RIGHT PLACE (the head of the
      range, not the end of the log);
    - the conversation projection (`replay/entries`) is untouched;
    - a SECOND compaction that shadows the first summary works, and its range inverts
      (`start` greater than `end`) -- the case a numeric interval comparison gets wrong."
  (:require [clojure.test :refer [deftest is testing]]
            [harness.edge.replay :as replay]))

;; ------------------------------------------------------------------ the records

(defn- entry
  "A message the CLIENT sent: a `message` row whose envelope carries the id and the source
  `replay/entries` recognises a conversation entry by. Its record offset is the seq the
  compaction facts name."
  [ts id text]
  {:ts ts :runId "r1" :type "message" :payload {:role "user" :content text}
   :source "client" :id id})

(defn- compacted
  "A compaction FACT: an `event` row carrying the `context/compacted` CUSTOM frame. Its own
  record offset is the identity a later compaction shadows it by."
  ([ts shadowed] (compacted ts shadowed "S"))
  ([ts shadowed summary]
   {:ts ts :runId "r1" :type "event"
    :payload {:type "CUSTOM" :name "context/compacted"
              :value {:summary summary
                      :shadowed (vec shadowed)
                      :range (when (seq shadowed) {:start (first shadowed) :end (last shadowed)})}}}))

(defn- model-view [records]
  (replay/compacted-messages (replay/entries (vec records))
                             (replay/compaction-facts (vec records))))

(defn- conversation [records]
  (mapv (comp :content :message) (replay/entries (vec records))))

;; -------------------------------------------------------------- the projection

(deftest a-compaction-hides-its-range-from-the-model-and-leaves-the-record-alone
  (let [records [(entry 0 "u1" "one")
                 (entry 1 "u2" "two")
                 (entry 2 "u3" "three")
                 (entry 3 "u4" "four")
                 (compacted 4 [0 1] "S1")
                 (entry 5 "u5" "five")]]
    (is (= ["one" "two" "three" "four" "five"] (conversation records))
        "the conversation keeps every original -- the client reads the source")
    (is (= ["<compacted-summary>S1</compacted-summary>" "three" "four" "five"]
           (mapv :content (model-view records)))
        "the model reads the summary where the range stood, and the tail in order")
    (is (= 6 (count records)) "the record keeps every row, the fact included")))

(deftest a-second-compaction-can-shadow-the-first-summary-and-the-range-inverts
  (let [records [(entry 0 "u1" "one")
                 (entry 1 "u2" "two")
                 (entry 2 "u3" "three")
                 (entry 3 "u4" "four")
                 (compacted 4 [0 1] "S1")
                 (entry 5 "u5" "five")
                 (compacted 6 [4 2] "S2")]
        facts   (replay/compaction-facts (vec records))]
    (is (= [4 2] (:shadowed (second facts)))
        "the second range names the first summary (seq 4) and an OLDER node (seq 2): start > end")
    (is (= ["<compacted-summary>S2</compacted-summary>" "four" "five"]
           (mapv :content (model-view records)))
        "the first summary is gone, the second stands at the range's head, the tail survives")))

(deftest a-single-node-range-still-lands-its-summary-in-place
  ;; WITH ONE NODE `start` AND `end` ARE THE SAME NUMBER, so a numeric comparison would
  ;; happen to work here -- which is exactly why the fold must not rely on it. This is the
  ;; case that passes for the wrong reason.
  (let [records [(entry 0 "u1" "one")
                 (entry 1 "u2" "two")
                 (entry 2 "u3" "three")
                 (compacted 3 [1] "S")]]
    (is (= ["one" "<compacted-summary>S</compacted-summary>" "three"]
           (mapv :content (model-view records))))))

(deftest two-non-overlapping-compactions-both-stand-in-order
  (let [records [(entry 0 "u1" "one")
                 (entry 1 "u2" "two")
                 (entry 2 "u3" "three")
                 (entry 3 "u4" "four")
                 (entry 4 "u5" "five")
                 (entry 5 "u6" "six")
                 (compacted 6 [0] "HEAD")
                 (compacted 7 [4 5] "TAIL")]]
    (is (= ["<compacted-summary>HEAD</compacted-summary>" "two" "three" "four"
            "<compacted-summary>TAIL</compacted-summary>"]
           (mapv :content (model-view records)))
        "each summary stands where its own range stood, and the untouched middle survives")))

(deftest a-compaction-that-shadows-nothing-changes-nothing
  (testing "a seq that is not on the surface"
    (let [records [(entry 0 "u1" "one") (entry 1 "u2" "two") (compacted 2 [99] "S")]]
      (is (= ["one" "two"] (mapv :content (model-view records))))))
  (testing "an empty shadowed list"
    (let [records [(entry 0 "u1" "one") (compacted 1 [] "S")]]
      (is (= ["one"] (mapv :content (model-view records)))))))

(deftest the-fold-does-not-touch-what-it-reads
  (let [records [(entry 0 "u1" "one") (entry 1 "u2" "two") (compacted 2 [0] "S")]
        entries (replay/entries (vec records))
        facts   (replay/compaction-facts (vec records))]
    (replay/compacted-messages entries facts)
    (is (= 2 (count entries)) "the entries vector is unchanged")
    (is (= [0 1] (mapv :seq entries)))
    (is (= [2] (mapv :seq facts)))
    (is (= 3 (count records)) "and the records vector is unchanged")))

(ns harness.cap.hashline.anchors-test
  "The anchor pool and the checksum: the two pieces everything else in this
  feature is built on, tested where they can be tested without a file, a session
  or a database in the way."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.cap.hashline.anchors :as anchors]))

;; ---------------------------------------------------------------- the pool

(deftest the-anchor-table-loads-and-is-checked
  (testing "the pool is the size upstream's table says it is"
    (is (= 1353139 (anchors/pool-size))))
  (testing "an anchor is four letters"
    (doseq [i [0 1 2 999 100000 (dec (anchors/pool-size))]]
      (is (re-matches #"[A-Za-z]{4}" (anchors/anchor-at i))
          (str "anchor-at " i))))
  (testing "and the pool starts and ends where the alphabet does"
    (is (= "AAAA" (anchors/anchor-at 0)))
    (is (= "zzzz" (anchors/anchor-at (dec (anchors/pool-size)))))))

(deftest the-pool-does-not-repeat
  ;; The property the whole scheme rests on: two different positions hold two
  ;; different names. Checked over a window at each end and a random sample --
  ;; walking all 1.35 million would be a slow test of the same claim. The sample
  ;; is DISTINCT by construction: what is under test is the table, not rand-int.
  (let [n   (anchors/pool-size)
        idx (set/union (set (range 0 5000))
                       (set (range (- n 5000) n))
                       (set (repeatedly 5000 #(rand-int n))))]
    (is (= (count idx) (count (set (map anchors/anchor-at idx))))
        (str "collision among " (count idx) " distinct positions"))))

(deftest rank-and-select-agree
  ;; anchor-at walks the table by position; anchor-index walks it back. If they
  ;; disagreed, a round trip through the database would silently rename a line.
  (let [n (anchors/pool-size)]
    (doseq [i (concat (range 0 2000)
                      (range (- n 2000) n)
                      (repeatedly 2000 #(rand-int n)))]
      (is (= i (anchors/anchor-index (anchors/anchor-at i)))
          (str "round trip at " i)))))

(deftest only-pool-members-are-anchors
  ;; The table is a CURATED subset: four letters is not enough to be an anchor,
  ;; which is the second thing that makes a made-up name useless to a model.
  (testing "a curated member is an anchor"
    (is (true? (anchors/anchor? "AAAB")))
    (is (true? (anchors/anchor? "zzzz"))))
  (testing "a four-letter string that was filtered out is not"
    (is (false? (anchors/anchor? "Hasu"))
        "the upstream table dropped this one; see the vendored artifact"))
  (testing "and neither is anything that is not four letters of the alphabet"
    (doseq [s ["Has" "Hasuu" "" "Has1" "hasu│" "   " nil 42]]
      (is (false? (anchors/anchor? s)) (pr-str s)))))

(deftest the-shape-check-is-looser-than-membership-on-purpose
  ;; A model's slip is as often a digit as a letter, and "not a 4-character
  ;; anchor" is a more useful refusal than "not a pool member" when the real
  ;; problem is a pasted line number.
  (is (true? (anchors/anchor-shape? "Hasu")))
  (is (true? (anchors/anchor-shape? "Ha5u")) "digits are accepted in shape")
  (is (false? (anchors/anchor? "Ha5u")) "...but no digit-only string is in the table")
  (is (false? (anchors/anchor-shape? "Has")))
  (is (false? (anchors/anchor-shape? "Hasu│line")))
  (is (false? (anchors/anchor-shape? nil))))

(deftest asking-for-an-anchor-outside-the-pool-is-a-named-failure
  (let [n (anchors/pool-size)]
    (doseq [bad [-1 n 1.5 "3" nil]]
      (let [e (try (anchors/anchor-at bad) nil (catch Exception e e))]
        (is (some? e) (pr-str bad))
        (is (= :out-of-range (:reason (ex-data e))) (pr-str bad))
        (is (str/includes? (ex-message e) (pr-str bad)) "the message says what was asked for")))))

;; ------------------------------------------------------------- the checksum

(deftest the-checksum-forgives-what-an-editor-does
  (testing "carriage returns are not content"
    (is (= (anchors/line-checksum "abc") (anchors/line-checksum "abc\r")))
    (is (= (anchors/line-checksum "abc") (anchors/line-checksum "a\rbc"))))
  (testing "trailing whitespace is not content"
    (is (= (anchors/line-checksum "abc") (anchors/line-checksum "abc   ")))
    (is (= (anchors/line-checksum "abc") (anchors/line-checksum "abc\t"))))
  (testing "leading whitespace IS content -- it is indentation"
    (is (not= (anchors/line-checksum "abc") (anchors/line-checksum " abc"))))
  (testing "and so is anything else"
    (is (not= (anchors/line-checksum "abc") (anchors/line-checksum "abd")))
    (is (not= (anchors/line-checksum "abc") (anchors/line-checksum "")))))

(deftest a-checksum-is-sixteen-hex-characters
  (let [c (anchors/line-checksum "anything")]
    (is (= 16 (count c)))
    (is (re-matches #"[0-9a-f]{16}" c))))

(deftest a-very-long-line-is-hashed-from-its-first-five-hundred-bytes
  ;; Stated as the behaviour rather than as a regret: two long lines that agree in
  ;; their first 500 bytes agree in checksum, and the failure that risks is a
  ;; missed staleness signal on such a line, not a wrong edit.
  (let [base (apply str (repeat 600 "x"))]
    (is (= (anchors/line-checksum base)
           (anchors/line-checksum (str base "and more"))))
    (is (not= (anchors/line-checksum base)
              (anchors/line-checksum (str "y" (subs base 1)))))
    (testing "and the truncation is by BYTES, never through a code point"
      ;; 499 ASCII bytes then a 3-byte character: cutting at 500 must not split it.
      (let [line (str (apply str (repeat 499 "a")) "中" (apply str (repeat 50 "b")))
            c    (anchors/line-checksum line)]
        (is (re-matches #"[0-9a-f]{16}" c))
        (testing "a line that differs only AFTER the cut and the character has the same checksum"
          (is (= c (anchors/line-checksum (str line "tail"))))
          (testing "-- while one that differs before the cut does not"
            (is (not= c (anchors/line-checksum (str "Z" (subs line 1)))))))))))

;; ---------------------------------------------------------------- the lines

(deftest lines-are-split-the-one-way-an-anchor-can-be-attached
  (is (= ["a" "b"] (anchors/split-lines "a\nb")))
  (is (= ["a" "b"] (anchors/split-lines "a\nb\n"))
      "a trailing newline does not open an empty last line")
  (is (= ["a" ""] (anchors/split-lines "a\n\n")) "but a blank line in the middle is real")
  (is (= ["a" "b"] (anchors/split-lines "a\r\nb\r\n")) "CRLF splits the same way")
  (testing "an empty file is ONE empty line, not none"
    ;; This is what gives an empty file something to address: read can show a row
    ;; the model may replace, and insert can put the first content after it.
    (is (= [""] (anchors/split-lines "")))
    (is (= [""] (anchors/split-lines "\n")))
    (is (= 1 (count (anchors/split-lines ""))))))

(deftest a-file-checksum-is-derived-from-its-line-checksums
  (let [a (anchors/line-checksums "one\ntwo\nthree")]
    (is (= (anchors/file-checksum a) (anchors/file-checksum a)))
    (testing "reordering the same lines is a different file"
      (is (not= (anchors/file-checksum a)
                (anchors/file-checksum (vec (reverse a))))))
    (testing "and a change that moved no line checksum moved no file checksum"
      ;; CRLF is the case this exists for: every line checksum identical, so the
      ;; snapshot must not look stale.
      (is (= (anchors/file-checksum (anchors/line-checksums "one\ntwo\n"))
             (anchors/file-checksum (anchors/line-checksums "one\r\ntwo\r\n")))))))

;; ----------------------------------------------------------- the allocation

(deftest the-stride-visits-every-anchor-before-repeating
  ;; Coprimality, not an approximation of it: the walk has to reach every member
  ;; or the pool is smaller than it looks.
  (let [n (anchors/pool-size)
        g (loop [a anchors/anchor-stride, b n] (if (zero? b) a (recur b (mod a b))))]
    (is (= 1 g) "stride and pool size are coprime")))

(deftest a-sessions-seed-is-stable-and-its-own
  (is (= (anchors/seed "thread-a") (anchors/seed "thread-a"))
      "the same session seeds the same way -- in this process AND the next")
  (is (not= (anchors/seed "thread-a") (anchors/seed "thread-b")))
  (is (<= 0 (anchors/seed "thread-a") (dec (anchors/pool-size))))
  (is (<= 0 (anchors/seed nil) (dec (anchors/pool-size))))
  (testing "and it does not depend on the process -- this is the restart promise"
    ;; Upstream mixes the pid in, which would make one session seed differently in
    ;; two processes. Stated here as an assertion so nobody re-adds it.
    (is (= (anchors/seed "same") (anchors/seed "same")))))

(deftest minting-walks-past-what-the-session-owns
  (let [seed       (anchors/seed "t")
        first-mint (anchors/mint {:probe seed :owned #{}})]
    (testing "the probe moves on, so the next mint starts somewhere else"
      (is (not= seed (:probe first-mint))))
    (testing "an anchor the session already owns is never handed out again"
      (let [owned #{(:anchor first-mint)}
            next  (anchors/mint {:probe (:probe first-mint) :owned owned})]
        (is (not (contains? owned (:anchor next))))))
    (testing "a probe standing ON an owned anchor walks past it"
      (let [a (anchors/anchor-at seed)
            m (anchors/mint {:probe seed :owned #{a}})]
        (is (not= a (:anchor m)))))))

(deftest minting-visits-the-whole-pool-before-giving-up
  ;; The walk's reach, stated as a fact about a small window: from one probe, the
  ;; first N mints are N distinct anchors. Combined with coprimality above and the
  ;; probe-limit failure below, that is the whole allocation guarantee.
  (loop [n 500, seen #{}, probe (anchors/seed "t")]
    (when (pos? n)
      (let [{:keys [anchor probe]} (anchors/mint {:probe probe :owned seen})]
        (is (not (contains? seen anchor)))
        (recur (dec n) (conj seen anchor) probe)))))

(deftest an-exhausted-pool-is-said-out-loud
  ;; Built by owning exactly the positions the probe would walk, so the failure is
  ;; reached in 8192 steps rather than by materialising 1.35 million anchors --
  ;; the same path, without a test that takes minutes to prove it.
  (let [n     (anchors/pool-size)
        start (anchors/seed "t")
        owned (into #{}
                    (map #(anchors/anchor-at (mod (+ start (* % anchors/anchor-stride)) n)))
                    (range anchors/probe-limit))]
    (is (= anchors/probe-limit (count owned)))
    (let [e (try (anchors/mint {:probe start :owned owned}) nil (catch Exception e e))]
      (is (some? e))
      (is (= :pool-exhausted (:reason (ex-data e))))
      (is (str/includes? (ex-message e) "1353139")
          "the message says how big the pool is, so the reader knows what was hit")
      (is (str/includes? (ex-message e) "write")
          "and it names the way out"))))

;; ------------------------------------------------------------ the alignment

(defn- aligned
  "Align a single edit, with the whole file as one span when SPANS is not given."
  [old-lines new-lines opts]
  (let [old-chs (mapv anchors/line-checksum old-lines)
        new-chs (mapv anchors/line-checksum new-lines)]
    (anchors/align (merge {:old-anchors    (map anchors/anchor-at (range (count old-lines)))
                           :old-checksums  old-chs
                           :new-checksums  new-chs
                           :path           "/tmp/f"
                           :owned          {}
                           :probe          (anchors/seed "t")}
                          opts))))

(deftest a-fresh-file-gets-one-anchor-per-line-and-they-are-all-different
  (let [r (aligned [] ["a" "b" "c"] {})]
    (is (= 3 (count (:anchors r))))
    (is (= 3 (count (set (:anchors r)))) "two identical lines would still get two anchors")
    (is (= (set (:anchors r)) (:added r)))
    (is (empty? (:freed r)))))

(deftest two-identical-lines-get-two-anchors
  ;; The reason this feature exists at all: `old_string` cannot tell these apart.
  (let [r (aligned [] ["same" "same" "same"] {})]
    (is (= 3 (count (set (:anchors r)))))
    (is (apply distinct? (:anchors r)))))

(deftest an-untouched-line-keeps-its-anchor
  (let [old ["a" "b" "c"]
        r   (aligned old ["a" "B" "c"]
                    {:old-anchors (map anchors/anchor-at (range 3))
                     :spans [{:old-start 1 :old-end 2 :new-start 1 :new-end 2}]})]
    (is (= (anchors/anchor-at 0) (nth (:anchors r) 0)))
    (is (= (anchors/anchor-at 2) (nth (:anchors r) 2)))
    (testing "the changed line is the only one that moved"
      (is (not= (anchors/anchor-at 1) (nth (:anchors r) 1)))
      (is (= #{(anchors/anchor-at 1)} (:freed r)))
      (is (= 1 (count (:added r)))))))

(deftest replacing-a-line-with-itself-keeps-the-anchor
  ;; Positional survival: the slot's checksum did not move, so the name does not
  ;; either. This is what makes a no-op edit genuinely a no-op.
  (let [old ["a" "b" "c"]
        r   (aligned old ["a" "b" "c"]
                    {:old-anchors (map anchors/anchor-at (range 3))
                     :spans [{:old-start 1 :old-end 2 :new-start 1 :new-end 2}]})]
    (is (= (map anchors/anchor-at (range 3)) (:anchors r)))
    (is (empty? (:freed r)))
    (is (empty? (:added r)))))

(deftest deleting-a-line-frees-exactly-that-anchor
  (let [old ["a" "b" "c"]
        r   (aligned old ["a" "c"]
                    {:old-anchors (map anchors/anchor-at (range 3))
                     :spans [{:old-start 1 :old-end 2 :new-start 1 :new-end 1}]})]
    (is (= [(anchors/anchor-at 0) (anchors/anchor-at 2)] (:anchors r)))
    (is (= #{(anchors/anchor-at 1)} (:freed r)))
    (is (empty? (:added r)))))

(deftest inserting-at-the-top-leaves-everything-below-alone
  (let [old ["a" "b"]
        r   (aligned old ["x" "y" "a" "b"]
                    {:old-anchors (map anchors/anchor-at (range 2))
                     :spans [{:old-start 0 :old-end 0 :new-start 0 :new-end 2}]})]
    (is (= [(anchors/anchor-at 0) (anchors/anchor-at 1)] (subvec (:anchors r) 2)))
    (is (empty? (:freed r)))
    (is (= 2 (count (:added r))))))

(deftest appending-leaves-everything-above-alone
  (let [old ["a" "b"]
        r   (aligned old ["a" "b" "c"]
                    {:old-anchors (map anchors/anchor-at (range 2))
                     :spans [{:old-start 2 :old-end 2 :new-start 2 :new-end 3}]})]
    (is (= [(anchors/anchor-at 0) (anchors/anchor-at 1)] (subvec (:anchors r) 0 2)))
    (is (empty? (:freed r)))
    (is (= 1 (count (:added r))))))

(deftest several-spans-in-one-edit
  (let [old ["a" "b" "c" "d" "e"]
        r   (aligned old ["A" "b" "c" "D" "E"]
                    {:old-anchors (map anchors/anchor-at (range 5))
                     :spans [{:old-start 0 :old-end 1 :new-start 0 :new-end 1}
                             {:old-start 3 :old-end 5 :new-start 3 :new-end 5}]})]
    (testing "the lines between the spans kept their anchors"
      (is (= [(anchors/anchor-at 1) (anchors/anchor-at 2)] (subvec (:anchors r) 1 3))))
    (testing "the spanned lines that changed all moved"
      (is (= 3 (count (:freed r)))))
    (testing "and D/E got new anchors even though the count did not change"
      (is (not= (anchors/anchor-at 3) (nth (:anchors r) 3)))
      (is (not= (anchors/anchor-at 4) (nth (:anchors r) 4))))))

(deftest an-anchor-owned-by-another-file-is-never-kept
  ;; The invariant an alignment must not break: an anchor names a line in ONE
  ;; file for ONE session.
  (let [old ["a" "b"]
        chs (mapv anchors/line-checksum old)
        r   (anchors/align {:old-anchors   (map anchors/anchor-at (range 2))
                            :old-checksums chs
                            :new-checksums chs
                            :spans         [{:old-start 0 :old-end 2 :new-start 0 :new-end 2}]
                            :path          "/tmp/f"
                            :owned         {(anchors/anchor-at 0) "/tmp/other"}
                            :probe         (anchors/seed "t")})]
    (is (not= (anchors/anchor-at 0) (nth (:anchors r) 0))
        "the slot kept its content but not the anchor another file owns")
    (is (= (anchors/anchor-at 1) (nth (:anchors r) 1)))))

(deftest without-spans-the-alignment-falls-back-to-prefix-and-suffix
  ;; A file that changed on disk: nobody is saying how. The answer may mint more
  ;; than it had to, but it must never keep an anchor that no longer describes
  ;; its line.
  (let [old ["a" "b" "c"]
        r   (aligned old ["a" "b" "Z"]
                    {:old-anchors (map anchors/anchor-at (range 3))})]
    (is (= [(anchors/anchor-at 0) (anchors/anchor-at 1)] (subvec (:anchors r) 0 2))
        "the common prefix is kept")
    (is (not= (anchors/anchor-at 2) (nth (:anchors r) 2)) "the tail moved")
    (is (= #{(anchors/anchor-at 2)} (:freed r)))))

(deftest without-spans-a-shorter-file-frees-what-it-lost
  (let [old ["a" "b" "c" "d"]
        r   (aligned old ["a" "b"]
                    {:old-anchors (map anchors/anchor-at (range 4))})]
    (is (= [(anchors/anchor-at 0) (anchors/anchor-at 1)] (:anchors r)))
    (is (= #{(anchors/anchor-at 2) (anchors/anchor-at 3)} (:freed r)))))

(deftest an-empty-result-is-legal-alignment-even-though-no-tool-produces-it
  ;; `replace` refuses to empty a file (ticket 05), but the alignment itself must
  ;; not be the thing that breaks if it ever happens.
  (let [old ["a" "b"]
        r   (aligned old []
                    {:old-anchors (map anchors/anchor-at (range 2))})]
    (is (= [] (:anchors r)))
    (is (= #{(anchors/anchor-at 0) (anchors/anchor-at 1)} (:freed r)))))

(deftest alignment-reports-a-probe-that-moved-only-when-it-minted
  (let [old  ["a" "b" "c"]
        anch (map anchors/anchor-at (range 3))
        seed (anchors/seed "t")]
    (testing "a no-op edit does not mint, so the probe stands still"
      (let [r (aligned old old {:old-anchors anch :probe seed
                                :spans [{:old-start 0 :old-end 3 :new-start 0 :new-end 3}]})]
        (is (= seed (:probe r)))))
    (testing "an edit that mints moves it"
      (let [r (aligned old ["a" "B" "c"] {:old-anchors anch :probe seed
                                          :spans [{:old-start 1 :old-end 2 :new-start 1 :new-end 2}]})]
        (is (not= seed (:probe r)))))))

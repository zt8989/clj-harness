(ns harness.edge.relieve-pressure-test
  "The mid-run pressure question (ticket 04 of `.scratch/compaction-shape`).

  `relieve-pressure!` is what the loop asks before EVERY model call, handing it the array that is
  about to go out. It is asserted here as the function it is; the loop's own half of the seam is
  `harness.kernel.loop-test`'s.

  THE FIXTURES ARE SMALL ON PURPOSE. With no live session in this process the meter estimates the
  array it was handed, so 'an array that crosses a small window' is the whole setup -- and the
  scripted provider's window is 128,000 tokens, seven tenths of which is 89,600."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.cap.providers :as providers]
            [harness.edge.http :as http]
            [harness.edge.replay :as replay]
            [harness.edge.pressure :as pressure]
            [harness.edge.sessions :as sessions]
            [harness.fake :as fake]
            [harness.infra.home :as home]))

;; ------------------------------------------------------------------ the records

(defn- entry [ts id text]
  {:ts ts :runId "r1" :type "message" :payload {:role "user" :content text}
   :source "client" :id id})

(defn- big-rows
  "N four-thousand-character entries -- 1008 estimated tokens each."
  [n]
  (vec (map (fn [i] (entry i (str "u" i) (apply str (repeat 4000 "a")))) (range n))))

(defn- as-array
  "The array the edge would hand in: the record's own messages, in order."
  [records]
  (mapv :message (replay/entries (vec records))))

(defn- plant! [thread-id rows]
  (let [f (home/log-file (#'http/unbound-dir) thread-id)]
    (.mkdirs (.getParentFile f))
    (spit f (str (str/join "\n" (map json/write-str rows)) "\n") :encoding "UTF-8")
    f))

(defn- until [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 10) (recur))))))

;; -------------------------------------------------------------------- the seam

(deftest a-crossed-threshold-is-relieved-before-the-call
  ;; 95 x 1008 = 95,760 estimated tokens against a 128,000-token window: over seven tenths.
  (let [stop (http/start! {:port 0})]
    (try
      (let [thread-id "relieve-over"
            log       (plant! thread-id (big-rows 95))]
        (providers/use-provider! thread-id (fake/scripted [{:content "MID SUMMARY"}]))
        (try
          (let [array (as-array (replay/read-records log))
                said  (atom [])
                view  (#'http/relieve-pressure! thread-id
                                                (fake/scripted [{:content "MID SUMMARY"}])
                                                array
                                                (fn [frame] (swap! said conj frame)))]
            (is (until #(some #{"context/compacted"}
                              (mapv replay/kind (replay/read-records log)))
                       3000)
                "the summary reached the record")
            (is (some? view) "and the caller is handed an array to send instead")
            (is (< (count view) (count array)))
            (is (str/starts-with? (str (:content (first view))) "<compacted-summary>")
                "and it is the compacted conversation, not the one handed in")
            ;; AND THE RUN IS TOLD IN THE SAME BREATH (`.scratch/compaction-frames`): the frame
            ;; names the compaction the ROWS name, so the card a client draws is the one a rebuild
            ;; hands back afterwards.
            (let [fact (first (filter #(contains? % :compactionId)
                                     (map replay/payload (replay/read-records log))))
                  card (first @said)]
              (is (= ["compacted-context"] (mapv :name @said))
                  "one card frame, named for the card")
              (is (= "MID SUMMARY" (get-in card [:value :summary])))
              (is (= (:compactionId fact) (:messageId card))
                  "and it is folded under the id the rows carry")))
          (finally
            (providers/use-provider! thread-id nil)
            (io/delete-file log true))))
      (finally (stop)))))

(deftest a-threshold-not-crossed-costs-nothing
  (let [stop (http/start! {:port 0})]
    (try
      (let [thread-id "relieve-under"
            log       (plant! thread-id (big-rows 3))]
        (providers/use-provider! thread-id (fake/scripted [{:content "SHOULD NOT BE USED"}]))
        (try
          (let [array (as-array (replay/read-records log))
                said  (atom [])
                view  (#'http/relieve-pressure! thread-id
                                                (fake/scripted [{:content "SHOULD NOT BE USED"}])
                                                array
                                                (fn [frame] (swap! said conj frame)))]
            (is (nil? view) "nothing to answer")
            (is (not-any? #{"compaction/start"} (mapv replay/kind (replay/read-records log)))
                "no rows, no model call")
            (is (= [] @said)
                "and nothing is said to the client: a run under the threshold is not told anything"))
          (finally
            (providers/use-provider! thread-id nil)
            (io/delete-file log true))))
      (finally (stop)))))

(deftest the-fold-takes-the-array-the-trigger-measured
  ;; TICKET 05 OF `.scratch/compaction-shape`, with the real session in the loop.
  ;;
  ;; A COMPACTION HAS TWO HALVES AND THEY HAVE TO MEASURE ONE THING: the trigger asks 'is the array
  ;; the MODEL is about to be handed too full?' (`pressure/live-surface`: the session's own
  ;; messages, the system message, this run's injections), and the plan says WHAT TO FOLD. The
  ;; record's fold is NOT that array -- it drops every run's injections except the last -- so a
  ;; plan over the record fold answers a head for a surface nobody measured. On the real thread
  ;; `62f30024-…` (2026-09-30) that head was ONE summary: the fold re-wrote a summary of the same
  ;; size, the surface did not move, and the trigger fired again 2 min 22 s worth of compactions
  ;; later.
  ;;
  ;; THE ARITHMETIC, once (the window is scaled up on purpose: the RATIO is what the case is
  ;; about, and the scripted provider's own 128,000 is too small to hold a conversation of this
  ;; shape -- retain 16% = 160,000, threshold 70% = 700,000):
  ;;
  ;;   the record's own fold   160 x 1008 = 161,280  -- a hair over the retain budget, so a plan
  ;;                                                   over it can fold ONE entry (1,008 tokens)
  ;;   the live array          + 550 x 1008 = 715,680 -- over the threshold, so the trigger fires
  ;;
  ;; A plan over the RECORD fold therefore asks for 1,008 tokens of relief on a request that is
  ;; 15,680 over its threshold: the relief guard refuses it (`:min-head-tokens`, which the trigger
  ;; hands all the way down through `run-compaction!` and `perform!`), nothing is written, and the
  ;; NEXT model call asks again. A plan over the LIVE array folds its excess (554,400) and the array
  ;; comes back to ~166k -- ONE compaction, under the threshold, which is the whole difference
  ;; between this and a loop.
  (let [stop (http/start! {:port 0})]
    (try
      (let [thread-id "relieve-two-surfaces"
            log       (plant! thread-id (big-rows 160))
            provider  (assoc (fake/scripted [{:content "MID SUMMARY"}]) :context-window 1000000)]
        (providers/use-provider! thread-id provider)
        (try
          ;; THE INJECTIONS OF EARLIER RUNS: only a live conversation has them (they enter the
          ;; session and never a row), and they carry the record lines their own runs numbered them
          ;; with -- `settle!` gives every entry the line it arrived in (`.scratch/entry-numbering`).
          (sessions/append! thread-id "injected"
                            (vec (map (fn [i] {:id (str "inj" i) :role "user"
                                               :content (apply str (repeat 4000 "b"))})
                                      (range 550))))
          (sessions/number-entries! thread-id "injected"
                                    (into {} (map (fn [i] [(str "inj" i) (+ 5000 i)]))
                                                 (range 550)))
          (let [array  (sessions/messages thread-id)
                before (pressure/estimate-messages array)
                said   (atom [])
                view   (#'http/relieve-pressure! thread-id
                                                 ;; THE SAME WINDOW the session's provider was given,
                                                 ;; or the trigger would measure a different one than
                                                 ;; the plan folds against.
                                                 (assoc (fake/scripted [{:content "MID SUMMARY"}])
                                                        :context-window 1000000)
                                                 array
                                                 (fn [frame] (swap! said conj frame)))]
            (is (> before 700000)
                "the live array is over seven tenths of the window -- the trigger fires")
            (is (some? view) "and a compaction happens at all")
            (is (some #{"context/compacted"} (mapv replay/kind (replay/read-records log)))
                "the summary reached the record")
            (is (= ["compacted-context"] (mapv :name @said)) "one card, named for the card")
            (let [after (pressure/estimate-messages (sessions/messages thread-id))]
              (is (< after (* 0.5 before))
                  "the array the model is handed really did shrink -- the fold took the live excess")
              (is (< after 700000)
                  "and ONE compaction left it under the threshold: no second round is due"))
            (is (< (count view) (count array)) "which the caller is handed as a shorter array"))
          (finally
            (providers/use-provider! thread-id nil)
            (io/delete-file log true))))
      (finally (stop)))))

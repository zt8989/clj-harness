(ns scratch-reasoning-out-of-the-record
  "Ticket 03 of `.scratch/event-persistence`: the per-token REASONING frames are no longer written to
  the record, and the same text comes back off the run's OWN `message` row.

  THE CLAIM, MEASURED ON A REAL RECORD: take a log the OLD write side produced, drop every
  `REASONING_*` frame from it exactly as the new write side now does -- and
  `harness.edge.replay/history` must answer THE SAME CONVERSATION, message for message: same count,
  same roles, same per-message `reasoning_content`, same tool calls. The entries' `:seq`s differ and
  that is the point, not a bug: a `:seq` is the LINE an entry arrived in (ADR 0003 decision 9), and
  the two records are not spelled the same way -- that is the whole saving.

  The record is `39be8804-22da-43e1-b624-71eaa2e4c91b` (2026-09-25): 13,631 lines, 8,640 of them
  reasoning frames -- 63% of the lines. `~/.clj-harness` IS READ AND NEVER WRITTEN: the log is read
  where it lives, and the played copy is written into a scratch file under the OS temp directory.

  Run with, FROM THE REPO ROOT (a `.clj` under `.scratch/` is not on any classpath, so it is loaded
  by PATH rather than looked up as a namespace):

    clojure -J-Dstdout.encoding=UTF-8 -M:dev \
      -i .scratch/event-persistence/evidence/03-reasoning-out-of-the-record.clj

  It prints its findings and appends the same lines to
  `<temp>/reasoning-out-of-the-record.txt`; `evidence/03-reasoning-out-of-the-record.txt` is one
  run's transcript, kept beside this file."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.edge.replay :as replay]
            [harness.test-runner :as tr]))

(tr/isolate!)

(def real-home "C:/Users/zhouteng/.clj-harness")
(def stem "39be8804-22da-43e1-b624-71eaa2e4c91b")
(def real-log (io/file real-home
                       "projects/C__Users_zhouteng_Documents_workspace_lisp-harness"
                       (str stem ".jsonl")))
(def played (io/file (System/getProperty "java.io.tmpdir") (str stem "-no-reasoning.jsonl")))
(def out (io/file (System/getProperty "java.io.tmpdir") "reasoning-out-of-the-record.txt"))
(io/delete-file out true)

(defn- say [& xs]
  (let [s (apply str (interpose " " (map str xs)))]
    (println s)
    (spit out (str s "\n") :append true :encoding "UTF-8")))

;; ------------------------------------------------------------ the two records
(def lines (with-open [r (io/reader real-log :encoding "UTF-8")] (vec (line-seq r))))

(defn- reasoning-frame-line?
  "The very predicate the WRITE side uses (`harness.edge.http/runner`): the row is a recorded frame
  whose type begins with REASONING. Parsed, not searched for the word -- a `message` row of this
  very repository's session may QUOTE such a frame in its text, and dropping that row would be
  dropping a person's sentence."
  [line]
  (let [row (try (json/read-str line :key-fn keyword) (catch Throwable _ nil))]
    (and (= "event" (:type row))
         (str/starts-with? (str (get-in row [:payload :type])) "REASONING"))))

(def kept (remove reasoning-frame-line? lines))
(spit played (str (str/join "\n" kept) "\n") :encoding "UTF-8")

;; ------------------------------------------------------------ the two folds
(def before (replay/history real-log))
(def after (replay/history played))
(defn- shapes [history]
  (mapv (fn [m] [(str (:role m))
                 (count (str (:content m)))
                 (count (str (:reasoning_content m)))
                 (count (:tool_calls m))])
        history))

;; ------------------------------------------------------------ what a NEW record cannot carry
;; A run writes its `message` rows at `:run/done`. A run that never got there has NO row to carry its
;; reasoning -- and under the new write side its reasoning frames are not on the record either. This
;; counts how many runs of a real conversation that would touch (the honest cost of the change).
(def rows (vec (replay/read-records real-log)))
(def by-run (group-by :runId rows))
(defn- reasoning-frames? [rs]
  (some #(and (= "event" (:type %))
              (str/starts-with? (str (get-in % [:payload :type])) "REASONING"))
        rs))
(def runs-with-frames (set (keep (fn [[run rs]] (when (reasoning-frames? rs) run)) by-run)))
(def runs-with-model-rows (set (keep (fn [r] (when (and (= "message" (:type r))
                                                       (= "model" (:source r)))
                                              (:runId r)))
                                     rows)))

;; ------------------------------------------------------------ the answer
(say "== the record ==")
(say "source:" (.getPath real-log))
(say "lines:" (count lines) "reasoning frames dropped:" (- (count lines) (count kept))
     "kept:" (count kept))
(say "bytes:" (.length real-log) "->" (.length played)
     (str "(" (format "%.1f" (* 100.0 (- 1 (/ (double (.length played))
                                               (double (.length real-log))))))
          "% gone)"))
(say "")
(say "== the conversation both ways ==")
(say "messages:" (count before) "vs" (count after))
(say "== [role content-chars reasoning-chars tool-calls] per message ==")
(say "before:" (pr-str (shapes before)))
(say "after: " (pr-str (shapes after)))
(say "")
(say "EQUAL (message for message, provider shape):" (= before after))
(say "reasoning reached the assistant:"
     (count (filter #(seq (str (:reasoning_content %))) after))
     "messages")
(say "")
(say "== runs the new write side cannot carry reasoning for ==")
(say "runs in the record:" (count by-run)
     "with reasoning frames:" (count runs-with-frames)
     "with a model row:" (count runs-with-model-rows))
(say "would lose their reasoning:" (count (remove runs-with-model-rows runs-with-frames))
     (pr-str (vec (sort (remove runs-with-model-rows runs-with-frames)))))

(spit out "\n" :append true :encoding "UTF-8")
(shutdown-agents)

(defn -main [& _] nil)

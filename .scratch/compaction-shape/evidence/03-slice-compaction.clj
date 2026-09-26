(ns scratch-real-slice-compaction
  "Ticket 03 of `.scratch/compaction-shape`: a REAL slice of a REAL record, compacted against the
  REAL vendor.

  The record is `f59c09dd-…` (2026-09-25): 459,765 lines, the session whose 15 compactions all
  died on `messages[N].role: unknown variant `reasoning``, and whose pressure meter read 62% when
  the vendor was about to be asked for 97%. The first 20,000 lines are copied INTO THE ISOLATED
  HOME -- `~/.clj-harness` is read, never written.

  Two calls go out, and the pair is the point:
    1. RAW -- the plan's messages exactly as the old code handed them over. The vendor must
       REFUSE this one, in its own words.
    2. THE CODE'S OWN PATH (`run-compaction!`, aggressive) -- folded. The vendor must answer, and
       `context/compacted` must land on the record.

  Run with:  clojure -J-Dstdout.encoding=UTF-8 -M:dev -m scratch-real-slice-compaction"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.edge.ag-ui :as ag]
            [harness.edge.compaction :as compaction]
            [harness.edge.http :as http]
            [harness.edge.pressure :as pressure]
            [harness.edge.replay :as replay]
            [harness.infra.home :as home]
            [harness.kernel.llm :as llm]
            [harness.test-runner :as tr]))

(tr/isolate!)

(def real-home "C:/Users/zhouteng/.clj-harness")
(def stem "f59c09dd-bedd-4fe6-b852-e643d25fbc5a")
(def real-log (io/file real-home (str "projects/C__Users_zhouteng_Documents_workspace_lisp-harness/"
                                      stem ".jsonl")))
(def slice-lines 20000)
(def out (io/file "C:/Users/zhouteng/AppData/Local/Temp/walkthrough-evidence.txt"))

(def lines (vec (take slice-lines (line-seq (io/reader real-log :encoding "UTF-8")))))

(defn- say [& xs]
  (let [s (apply str (interpose " " (map str xs)))]
    (println s)
    (spit out (str s "\n") :append true :encoding "UTF-8")))

;; ---------------------------------------------------------------- the planted slice
(def log (home/log-file (#'http/unbound-dir) stem))
(.mkdirs (.getParentFile log))
(spit log (str (str/join "\n" lines) "\n") :encoding "UTF-8")

;; ---------------------------------------------------------------- the real vendor
(defn- env-value [name]
  (->> (str/split-lines (slurp (io/file real-home ".env")))
       (keep (fn [l]
               (let [i (.indexOf ^String l "=")]
                 (when (and (pos? i) (= name (subs l 0 i))) (subs l (inc i))))))
       first))

(def provider
  {:protocol         :openai-completions
   :base-url         "https://model-info.forwe.store/v1"
   :model            "deepseek-v4.1-flash-expires-on-0910"
   :reasoning-effort "high"
   :context-window   1048576
   :max-output-tokens 384000
   :api-key          (env-value "KONGMING_API_KEY")})

(defn- view-tokens [records]
  (pressure/estimate-messages
   (replay/compacted-messages (replay/entries (vec records)) (replay/compaction-facts (vec records)))))

(def stop (http/start! {:port 0}))

(try
  (let [records (vec (replay/read-records log))
        plan    (compaction/overflow-plan records)
        roles   (frequencies (map :role (:messages plan)))]
    (say "== the slice ==")
    (say "record:" (.getPath real-log))
    (say "lines taken:" (count lines) "of" (count (line-seq (io/reader real-log :encoding "UTF-8")))
         "-- planted at" (.getPath log))
    (say "entries:" (count (replay/entries records))
         "plan head messages:" (count (:messages plan)) (pr-str roles))
    (say "model view estimated tokens before:" (view-tokens records))

    (say "")
    (say "== 1. RAW: the plan's messages, exactly as the old code sent them ==")
    (try
      (let [{:keys [telemetry]}
            (llm/stream! (assoc provider :tools [])
                         (conj (vec (:messages plan))
                               {:role "user" :content compaction/summary-instruction})
                         (fn [_]) stem)]
        (say "ACCEPTED (unexpected):" (pr-str (:usage telemetry))))
      (catch Throwable t
        (say "REFUSED, as the vendor refused all fifteen:" (ex-message t))))

    (say "")
    (say "== 2. THE CODE'S OWN PATH: run-compaction! (aggressive) ==")
    (let [result (#'http/run-compaction! stem provider records
                                         (:context-window provider)
                                         (compaction/config stem)
                                         {:aggressive? true})
          _      (Thread/sleep 300)
          after  (vec (replay/read-records log))
          kinds  (mapv replay/kind after)
          line   (last (filter #(= "context/compacted" (replay/kind %)) after))]
      (say "shadowed:" (count (:shadowed result)))
      (say "summary chars:" (count (str (:summary result))))
      (say "summary opens:" (subs (str (:summary result)) 0 (min 240 (count (str (:summary result))))))
      (say "rows:" (pr-str (frequencies kinds)))
      (say "compaction/end error:"
           (pr-str (:error (replay/payload (last (filter #(= "compaction/end" (replay/kind %)) after))))))
      (let [v (replay/payload line)]
        (say "the fact:" (pr-str (select-keys v [:compactionId :tokens :range]))))
      (say "what the code sends instead (roles after the fold):"
           (pr-str (frequencies (map :role (ag/provider-messages (:messages plan))))))
      (say "model view estimated tokens after:" (view-tokens after))))
  (finally
    (stop)))

(spit out "\n" :append true :encoding "UTF-8")
(shutdown-agents)

(defn -main [& _] nil)

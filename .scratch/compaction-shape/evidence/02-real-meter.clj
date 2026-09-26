(ns scratch-real-meter
  "Ticket 02 of `.scratch/compaction-shape`, measured on the real record.

  The incident's `context/pressure` line (thread `f59c09dd-…`, line 449641) said
  `651,432 tokens / 62%` for the run whose very first request the vendor then charged
  **1,020,335** tokens. This recomputes the LIVE reading for that exact prefix -- the meter
  fed the array the edge actually assembled (the PROVIDER shape) -- and the record's own fold
  (the AG-UI shape) beside it.

  Read-only: `~/.clj-harness` is read, never written."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [harness.edge.ag-ui :as ag]
            [harness.edge.pressure :as pressure]
            [harness.test-runner :as tr]))

(tr/isolate!)

(def stem "f59c09dd-bedd-4fe6-b852-e643d25fbc5a")
(def real-log (io/file "C:/Users/zhouteng/.clj-harness"
                       "projects/C__Users_zhouteng_Documents_workspace_lisp-harness"
                       (str stem ".jsonl")))

(def prefix-lines 449640)                                   ;; everything before that row

(def records
  (with-open [r (io/reader real-log :encoding "UTF-8")]
    (vec (map #(json/read-str % :key-fn keyword) (take prefix-lines (line-seq r))))))

(def ratios pressure/default-ratios)
(def band   (pressure/meter-of-records records))
(def as-agui (@#'pressure/messages-in records))              ;; system + model view + injections
(def as-provider (ag/provider-messages as-agui))             ;; what the edge hands a provider

(defn- say [& xs] (println (apply str (interpose " " (map str xs)))))

(say "prefix lines:" (count records))
(say "anchor prompt (the vendor's own last number):" (:prompt (:anchor band)))
(say "AG-UI shape:      roles" (pr-str (frequencies (map :role as-agui)))
     "estimate" (pressure/estimate-messages as-agui))
(say "provider shape:   roles" (pr-str (frequencies (map :role as-provider)))
     "estimate" (pressure/estimate-messages as-provider))
(say "")
(say "meter on the AG-UI shape (what `records->pressure` answers):"
     (pr-str (pressure/state->pressure band as-agui ratios)))
(say "meter on the PROVIDER shape (what the run start hands in -- the LIVE reading):"
     (pr-str (pressure/state->pressure band as-provider ratios)))
(say "")
(say "the incident logged 651,432 / 62% here; the first request cost 1,020,335 tokens.")

(shutdown-agents)
(defn -main [& _] nil)

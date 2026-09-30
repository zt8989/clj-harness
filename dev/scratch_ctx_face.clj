(ns scratch-ctx-face
  "真记录上，环的三桶现在是谁：把一份真会话的日志折一遍，印出 `harness.edge.context` 的答案，以及
  它拿来做拆分的那份「那一发被交给的数组」有多大 —— 旁边再算一遍**老口径**（只数这一轮 run 自己的
  行，也就是把 ADR 0002 留在会话里的整段历史漏掉的那一支），好把两边的差别摆在同一个会话上。

  背景（2026-09-30）：圈变黑，是因为只要日志最后一个 run 没有终结帧，`state->context` 就整支不给
  `:parts`，前端退回 `UNKNOWN_HUE`（浅色主题里的近黑）。而三桶当时数的是「这一轮 run 自己的行」，
  工具表因此被摊成 68.7%（`.scratch/context-ring/spec.md`）。

  Run: clojure -J-Dstdout.encoding=UTF-8 -M:dev -m scratch-ctx-face [日志前缀，默认 a0621fce]"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.edge.context :as context]
            [harness.edge.pressure :as pressure]
            [harness.edge.replay :as replay]
            [harness.edge.stats :as stats]
            [harness.edge.trajectory :as trajectory]
            [harness.test-runner :as runner]))

(runner/isolate!)

(def ^:private dir
  "C:/Users/zhouteng/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness")

(defn- the-file [prefix]
  (->> (io/file dir)
       .listFiles
       (filter #(str/starts-with? (.getName ^java.io.File %) prefix))
       first))

(defn- chars-of [messages] (reduce + 0 (map context/size-of messages)))

(defn- state-of [row]
  (let [payload (:payload row)
        name (if (= "event" (:type row)) (:name payload) (:type payload))]
    (str (:type row) " " name)))

(defn- print-parts [answer]
  (let [parts (:parts answer)
        total (reduce + 0 (map :tokens parts))]
    (when (seq parts)
      (doseq [p parts]
        (println (format "    %-13s %9d tokens  %5.1f%%"
                         (:key p) (:tokens p) (* 100.0 (/ (double (:tokens p)) (double total)))))))))

(defn -main [& [prefix]]
  (let [f       (the-file (or prefix "a0621fce"))
        records (vec (stats/read-records f))
        band    (pressure/meter-of-records records)
        face    (pressure/anchor-face band)
        answer  (context/records->context records face)
        runs    (trajectory/run-segments records)
        last-run (last runs)
        old-rows (concat (:submitted last-run) (:returned last-run))]
    (println "log           " (.getName ^java.io.File f))
    (println "records       " (count records) " rows")
    (println "last row      " (state-of (last records)))
    (println "runs          " (count runs))
    (println)
    (println "== what the ring draws now ==")
    (println "face messages " (count face) "  (the array the CHOSEN call was handed)")
    (println "face chars    " (chars-of face))
    (println "used / window " (:usedTokens answer) "/" (:windowTokens answer)
             "=" (:percent answer) "%")
    (print-parts answer)
    (println)
    (println "== the old rule, on the same session (the last run's own rows) ==")
    (println "that run's rows" (count old-rows) "  chars" (chars-of old-rows))
    (println "  system      " (chars-of (filter #(= "system" (:role %)) old-rows)))
    (println "  other       " (chars-of (remove #(= "system" (:role %)) old-rows)))
    (println)
    ;; THE PRICE OF THE NEW READING, measured rather than guessed: one sizing pass over the
    ;; array is what a push now pays ON TOP of what it already paid (`band-pressure` prices
    ;; the same conversation twice already, and `live-surface` builds it), at every
    ;; model/start and every model/end. A session this size is the worst case on this machine.
    ;;
    ;; `chars-of` IS THE SIZING ALONE and the full answer below includes it, so the two
    ;; numbers say which half of the ring got more expensive.
    (dotimes [_ 2] (time (chars-of face)))
    (dotimes [_ 2] (time (context/records->context records face))))
  (System/exit 0))

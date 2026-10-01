(ns scratch-ctx-face
  "真记录上：环那三桶现在是谁、以及「量字符」这件事现在花在哪儿。

  背景（2026-09-30）：圈变黑，根因是 `harness.edge.context` 只要日志最后一个 run 没有终结帧就整支
  不给 `:parts`，而三桶当时数的还是「这一轮 run 自己的行」（漏掉整段历史 → 工具表被摊成 68.7%）。
  修完第一版后，三桶改成量「那一发被交给的数组」——数对了，但**每一次读**都要把整段对话逐条
  JSON 编码一遍（~50ms / 2 MB），而推送里 `model/start`、`model/end` 各读一次。

  现在（第二版）：这一步挪进 `harness.edge.pressure` 的 band，按节点 id 记账（`sized`），每条消息
  只量一次；环读的是 `anchor-sizes` 拿到的两个数，不再碰数组。这个脚本把三件事并排印出来：

    - 记账的数和「从零量一遍」的数是否**一模一样**；
    - 从前一次读要付多少（`size-of` 逐条）；
    - 现在一次读要付多少。

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
  (let [f        (the-file (or prefix "a0621fce"))
        records  (vec (stats/read-records f))
        band     (pressure/meter-of-records records)
        face     (pressure/anchor-face band)
        sizes    (pressure/anchor-sizes band)
        measured {:system       (chars-of (filter #(= "system" (:role %)) face))
                  :conversation (chars-of (remove #(= "system" (:role %)) face))}
        st       (reduce (fn [st pair] (context/state-step st nil pair))
                         (context/state-init)
                         (map-indexed vector records))
        answer   (context/state->context st sizes)]
    (println "log           " (.getName ^java.io.File f))
    (println "records       " (count records) " rows")
    (println "last row      " (state-of (last records)))
    (println "runs          " (count (trajectory/run-segments records)))
    (println)
    (println "== what the ring draws ==")
    (println "used / window " (:usedTokens answer) "/" (:windowTokens answer)
             "=" (:percent answer) "%")
    (print-parts answer)
    (println)
    (println "== the sizes the band kept, against a from-scratch measurement ==")
    (println "array messages" (count face) "   (the array the CHOSEN call was handed)")
    (println "kept          " sizes)
    (println "measured      " measured)
    (println "IDENTICAL?    " (= sizes measured))
    (println)
    (println "== what a read costs, before and after ==")
    (println "逐条量一遍（改之前每读一次付的钱）:")
    (dotimes [_ 2] (time (chars-of face)))
    (println "现在一次读（数已经在手上，只剩那一节的算术）:")
    (dotimes [_ 2] (time (context/state->context st sizes)))
    (println "冷读整份记录（band 一边走一边记账，含折行本身）:")
    (dotimes [_ 2] (time (pressure/meter-of-records records))))
  (System/exit 0))

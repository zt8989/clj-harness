(ns scratch-two-folds
  "票 06 的前一半：同一条记录，`replay/entries`（向量、严格）与 `entries-fold`/`entries-of-fold`
  （流式、`sofar`/会话用的那条）折出来的条目**一样多吗**？

  Run: clojure -J-Dstdout.encoding=UTF-8 -M:dev -m scratch-two-folds [文件] [截止行]"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.edge.replay :as replay]
            [harness.test-runner :as runner]))

(runner/isolate!)

(def default-file
  (io/file "C:/Users/zhouteng/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness"
           "a0621fce-9bf4-4f33-8671-026e783196f6.jsonl"))

(defn -main [& [path upto]]
  (let [f     (if path (io/file path) default-file)
        cut   (when upto (Long/parseLong upto))
        rows  (vec (if cut (take cut (replay/read-records f)) (replay/read-records f)))
        strict (replay/entries rows)
        stream (replay/entries-of-fold (replay/entries-fold (replay/entries-fold)
                                                          (map-indexed vector rows)))]
    (println "记录:" (.getName f) "行" (count rows))
    (println "严格 entries:" (count strict) "  流式 entries-of-fold:" (count stream))
    (let [sseq (set (map :seq strict))
          fseq (set (map :seq stream))]
      (println "只在严格里:" (count (remove fseq sseq))
               "  只在流式里:" (count (remove sseq fseq)))
      (println "--- 只在严格里的前 8 条（seq / role / source / 前 60 字）:")
      (doseq [e (take 8 (sort (remove fseq sseq)))]
        (let [m (:message (first (filter #(= (:seq %) e) strict)))]
          (println (format "  %-6d %-10s %-10s %s" e (str (:role m)) (str (:source m))
                           (subs (str/replace (str (:content m)) #"\s+" " ") 0 (min 60 (count (str (:content m)))))))))
      (println "--- 只在流式里的前 8 条:")
      (doseq [e (take 8 (sort (remove sseq fseq)))]
        (let [m (:message (first (filter #(= (:seq %) e) stream)))]
          (println (format "  %-6d %-10s %-10s %s" e (str (:role m)) (str (:source m))
                           (subs (str/replace (str (:content m)) #"\s+" " ") 0 (min 60 (count (str (:content m))))))))))
    (System/exit 0)))

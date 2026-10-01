(ns scratch-cjk-share
  "票 06 的 CJK 检验：会话里有多少字符是 CJK？四字符一 token 对 CJK 的定价是错的
  （真 tokenizer 大约一字一 token），所以 CJK 占比决定了估价偏低多少。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.edge.ag-ui :as ag]
            [harness.edge.replay :as replay]
            [harness.test-runner :as runner]))

(runner/isolate!)

(def ^:private cjk (re-pattern "[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]"))

(defn- text-of [m]
  (let [c (:content m)]
    (cond
      (string? c) (str c (when (string? (:reasoning_content m)) (:reasoning_content m)))
      :else (str c " " (str (:reasoning_content m))))))

(defn -main [& [path]]
  (let [f   (if path (io/file path)
                (io/file "C:/Users/zhouteng/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness"
                         "a0621fce-9bf4-4f33-8671-026e783196f6.jsonl"))
        rows (replay/read-records f)
        msgs (->> rows
                  (filter #(= "message" (replay/kind %)))
                  (map replay/payload)
                  (filter #(contains? % :role)))
        all  (str/join (map text-of msgs))
        n    (count all)
        c    (count (re-seq cjk all))]
    (println "会话全部消息字符:" n)
    (println (format "CJK 字符: %d (%.1f%%)" c (* 100.0 (/ (double c) (double n)))))
    (println)
    (println "以 a=非CJK、b=CJK 计，混合 tokenizer 大致 tokens = a/4 + b:")
    (let [a (- n c)]
      (println (format "  字符法(全/4): %d   混合法(a/4+b): %d" (quot n 4) (+ (quot a 4) c))))
    (System/exit 0)))

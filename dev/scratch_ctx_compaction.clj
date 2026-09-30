(ns scratch-ctx-compaction
  "压缩那一刻，圆环报的是谁：把一份真记录（86c1c343-…，2026-09-28 那次压缩）从头折一遍，在压缩前后
  每一个 `model/end` 上印出 `harness.edge.context` 的答案。

  修之前：摘要调用自己那一行（281,889 tokens）会变成环上的数（27%），13 秒后真调用把它顶回 66%。
  修之后：整个压缩期间环上的数都是会话自己的（66%），直到下一次真调用报出新数。

  Run: clojure -J-Dstdout.encoding=UTF-8 -M:dev -m scratch-ctx-compaction"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.edge.context :as ctx]
            [harness.edge.replay :as replay]
            [harness.test-runner :as runner]))

(runner/isolate!)

(def ^:private dir
  "C:/Users/zhouteng/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness")

(defn- the-file [prefix]
  (->> (io/file dir)
       .listFiles
       (filter #(str/starts-with? (.getName ^java.io.File %) prefix))
       first))

(defn- interesting? [row]
  (contains? #{"compaction/start" "compaction/end" "context/compacted" "model/end"}
             (if (= "event" (replay/kind row))
               (:name (replay/payload row))
               (replay/kind row))))

(defn -main [& _]
  (let [src  (the-file "86c1c343")
        rows (replay/read-records src)]
    (println "record" (.getName ^java.io.File src) "rows" (count rows))
    (reduce (fn [st [i row]]
              (let [st' (ctx/state-step st nil [i row])]
                (when (and (interesting? row) (<= 10100 i 10245))
                  ;; THE FACE IS NIL HERE ON PURPOSE: this script watches the NUMBER a compression
                  ;; moves (`:usedTokens` / `:percent`), not the three buckets -- and the buckets
                  ;; are the array the chosen call was handed, which this walk does not keep.
                  (let [{:keys [usedTokens percent]} (ctx/state->context st' nil)]
                    (println (format "%-6d %-18s ts=%s used=%s pct=%s"
                                     i (name (or (:name (replay/payload row))
                                                 (replay/kind row)))
                                     (:ts row) usedTokens percent))))
                st'))
            (ctx/state-init)
            (map-indexed vector rows))
    (println "expected: nothing between compaction/start and compaction/end moves the number;"
             "the 281889/6022 rows are the summarizer's own calls"))
  (System/exit 0))

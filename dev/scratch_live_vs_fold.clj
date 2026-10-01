(ns scratch-live-vs-fold
  "票 06：live 面比记录折叠大出来的那部分是什么。

  取一条真记录（默认 a0621fce-…，那次压缩就发生在里面），**一趟扫过**：
  每遇到 `model/end`（厂商自己的 usage），就地算一遍「记录折叠 + 系统消息 + 工具表签名」
  的估价，和厂商报的 prompt token 摆在一起比。差额在哪些调用上跳、跳多少，就是本票要的回答。

  Run: clojure -J-Dstdout.encoding=UTF-8 -M:dev -m scratch-live-vs-fold [文件]"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.edge.ag-ui :as ag]
            [harness.edge.pressure :as pressure]
            [harness.edge.replay :as replay]
            [harness.test-runner :as runner]))

(runner/isolate!)

(def default-file
  (io/file "C:/Users/zhouteng/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness"
           "a0621fce-9bf4-4f33-8671-026e783196f6.jsonl"))

(defn- prompt-tokens
  "The vendor's own count of what it was handed: `prompt_tokens` when it says so, otherwise
  the total minus the completion it reported."
  [usage]
  (when (map? usage)
    (or (:prompt_tokens usage)
        (when (and (number? (:total_tokens usage)) (number? (:completion_tokens usage)))
          (- (long (:total_tokens usage)) (long (:completion_tokens usage)))))))

(defn- chars [msgs] (reduce + 0 (map (fn [m] (count (str (:content m)))) msgs)))

(defn- reasoning-chars [msgs]
  (reduce + 0 (map (fn [m] (count (str (:reasoning_content m)))) msgs)))

(defn- call-shape [msgs] (pr-str (into (sorted-map) (frequencies (map :role msgs)))))

(defn -main [& [path]]
  (let [f     (if path (io/file path) default-file)
        rows  (vec (replay/read-records f))]
    (println "记录:" (.getName f) "行" (count rows))
    (println (format "%-6s %-9s %10s %10s %6s %5s %9s %9s  %s"
                     "行" "run" "实测" "估价" "比" "条" "内容字符" "思考字符" "角色"))
    (let [state  (atom (replay/entries-fold))
          facts  (atom [])
          prunes (atom [])
          system (atom nil)
          start  (atom nil)]
      (doseq [[i row] (map-indexed vector rows)]
        (let [k (replay/kind row)
              p (replay/payload row)]
          (swap! state replay/entries-fold [[i row]])
          (cond
            (= "context/compacted" k) (swap! facts conj (assoc p :seq i))
            (= "context/pruned" k)    (swap! prunes conj (assoc p :seq i))
            (and (= "message" k) (= "system" (:role p))) (reset! system p)
            (= "model/start" k)       (reset! start {:i i :p p})
            (= "model/end" k)
            (when-some [s @start]
              (when (= (:runId row) (:runId (nth rows (:i s))))
                (let [msgs  (replay/compacted-messages (replay/entries-of-fold @state) @facts @prunes)
                      array (into (if (some? @system) [@system] []) (ag/provider-messages msgs))
                      est   (+ (pressure/estimate-messages array)
                               (pressure/estimate-tools (:p s)))
                      does  (prompt-tokens (:usage p))]
                  (when (and does (pos? (long does)))
                    (let [rid (str (:runId row))]
                      (println (format "%-6d %-9s %10d %10d %6.2f %7d %5d %9d %9d  %s"
                                       i (subs rid 0 (min 8 (count rid))) does est
                                       (/ (double does) (double est)) (- (long does) (long est))
                                       (count array) (chars array) (reasoning-chars array)
                                       (call-shape array))))))))))))
    (System/exit 0)))

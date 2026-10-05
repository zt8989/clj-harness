(ns harness.edge.fold-timing
  "量一次 walk 的时间花在哪。跑法：clojure -M:dev -m harness.edge.fold-timing [record 路径]"
  (:require [harness.edge.replay :as replay]
            [harness.kernel.frames :as frames]
            [clojure.string :as str]))

(defn- timed [label f]
  (let [t0 (System/nanoTime)
        s   (f)
        t1 (System/nanoTime)]
    (println (format "%-24s: %6.2f s   %s" label (/ (- t1 t0) 1e9) s))))

(defn -main [& [path]]
  (let [f (clojure.java.io/file
            (or path
                "C:/Users/zhouteng/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness/ae9d3598-537b-4162-b4fe-81f1672d29a5.jsonl"))
        recs (vec (replay/read-records f))
        evs  (filter #(= "event" (replay/kind %)) recs)
        fs   (mapv replay/payload evs)]
    (println (format "record: %d 行, %d bytes" (count recs) (.length f)))
    (println (format "  事件帧 %d 条：%s"
                     (count fs)
                     (str/join " " (map (fn [[k v]] (str k "=" v))
                                        (take 8 (sort-by (comp - val)
                                                         (frequencies (map #(or (:type %) "?") fs))))))))
    (let [lens (sort > (map count (vals (group-by :runId fs))))]
      (println (format "  每个 run 的帧数：最多 %d，%d 个 run，前五 %s"
                       (first lens) (count lens) (pr-str (take 5 lens)))))
    (timed "runs-step x N"
           (fn [] (format "%d runs" (count (reduce (var-get #'replay/runs-step) [] recs)))))
    (timed "apply-frames (全部帧)"
           (fn [] (format "%d 帧 -> %d 条消息" (count fs) (count (frames/apply-frames fs)))))
    (timed "fold-entries"
           (fn [] (format "%d entries" (count (replay/fold-entries f)))))
    (timed "fold-sofar"
           (fn [] (format "%d entries" (count (:entries (replay/fold-sofar f))))))))
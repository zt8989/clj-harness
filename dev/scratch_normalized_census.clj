(ns scratch-normalized-census
  "把「已重整化」的判据对着**真记录**跑一遍（只读 `~/.clj-harness`，一个字不写）：
  哪几条记录过、哪几条不过、理由是哪一条，以及被判「裸露」的行到底长什么样。

  为什么要有这份读数：主人那一句「以后所有的消息都要被那个 start/end 这种信封包裹」有两种读法，
  读数把「位置读法」（每条 message 行夹在自己的 START/END **之间**）排除了——真记录一条都不满足，
  因为写手是「帧先落、它描述的那条 message 行紧跟其后」。落在 `normalized?` 里的是**成对读法**。

  Run: clojure -J-Dstdout.encoding=UTF-8 -M:dev -m scratch-normalized-census"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.edge.normalized :as normalized]
            [harness.edge.replay :as replay]
            [harness.test-runner :as runner]))

(runner/isolate!)

(def ^:private start-frames #{"TEXT_MESSAGE_START" "TOOL_CALL_START"})
(def ^:private end-frames   #{"TEXT_MESSAGE_END"   "TOOL_CALL_END"})

(defn- ft [row] (get-in row [:payload :type]))
(defn- fid [row] (let [p (:payload row)] (or (:messageId p) (:toolCallId p))))
(defn- mid [row] (or (:id row) (get-in row [:payload :tool_call_id])))
(defn- msg? [row] (= "message" (:type row)))
(defn- looks-old? [row] (not (and (contains? row :type) (contains? row :payload))))

(defn- face [row]
  (let [m (:payload row)]
    {:src (:source row) :role (:role m) :call (:tool_call_id m) :id (:id row)}))

(defn- positional-loose
  "**位置读法**下的裸露行：message 行没有夹在自己的 START/END 之间。"
  [rows]
  (let [rows  (vec rows)
        spans (reduce (fn [acc [i row]]
                        (let [t (ft row) id (fid row)]
                          (cond
                            (nil? id) acc
                            (contains? start-frames t) (assoc-in acc [id :start] i)
                            (contains? end-frames t)   (assoc-in acc [id :end] i)
                            :else acc)))
                      {} (map-indexed vector rows))]
    (vec (keep-indexed (fn [i row]
                         (when (msg? row)
                           (let [{:keys [start end]} (get spans (mid row))]
                             (when-not (and (some? start) (some? end) (< start i) (< i end))
                               (face row)))))
                       rows))))

(defn diagnose [rows]
  (let [rows (vec rows)]
    {:rows       (count rows)
     :messages   (count (filter msg? rows))
     :old-rows   (count (filter looks-old? rows))
     ;; 判据（成对读法）：落地的那一条
     :verdict    (normalized/normalized? rows)
     ;; 位置读法：它判什么（真记录一条都不满足）
     :positional (count (positional-loose rows))
     :faces      (vec (distinct (positional-loose rows)))}))

(defn- jsons [dir max-bytes]
  (->> (file-seq (io/file dir))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".jsonl"))
       (filter #(< 5000 (.length %) max-bytes))
       (sort-by #(.length %))))

(defn -main [& _]
  (let [fs (jsons (str (System/getProperty "user.home") "/.clj-harness/projects") 3000000)
        ds (vec (keep (fn [f]
                        (try {:file (.getName f) :d (diagnose (replay/read-records f))}
                             (catch Throwable t {:file (.getName f) :error (ex-message t)})))
                      fs))
        bad (remove #(true? (:normalized? (:verdict (:d %)))) ds)]
    (println (format "== 真记录 %d 条：判成未重整化 %d 条" (count ds) (count bad)))
    (doseq [{:keys [file d error]} ds]
      (when (or error (not (true? (:normalized? (:verdict d)))))
        (println (format "  ! %-44s %s" file (pr-str (or error (:verdict d)))))))
    (println)
    (println (str "  「位置读法」在**每一条**真记录上发声（写手是帧先行），成对读法不发："))
    (doseq [{:keys [file d error]} ds]
      (if error
        (println (format "    %-44s READ FAILED: %s" file error))
        (println (format "    %-44s rows=%-6d msg=%-4d 位置读法裸露=%-4d 成对读法=%s %s"
                         file (:rows d) (:messages d) (:positional d)
                         (:normalized? (:verdict d))
                         (pr-str (:reasons (:verdict d)))))))
    (when-some [{:keys [file d]} (first (filter #(pos? (long (or (:positional (:d %)) 0))) ds))]
      (println)
      (println (str "  例（" file "）位置读法判裸露的行："))
      (doseq [f (:faces d)] (println "    " (pr-str f))))
    (println)
    (println "== 旧格式（`~/.clj-harness/logs`）：读侧在判据之前就按名字拒绝")
    (doseq [f (take 6 (jsons (str (System/getProperty "user.home") "/.clj-harness/logs") 3000000))]
      (println (format "  %-44s %s" (.getName f)
                       (try (str "读过了（" (count (replay/read-records f)) " 行）")
                            (catch Throwable t (str (pr-str (:reason (ex-data t))) " " (ex-message t))))))))
  (shutdown-agents))

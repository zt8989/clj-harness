(ns scratch-measure-twice
  "票 06 的第二个测法：**同一段数组**连着发两次给厂商，量出来的 prompt token 一样吗？

  顺带量两件事：这段数组的字符数（与估价对比，看 chars/token），以及**加上工具表**之后涨多少
  （真事那一发比我的重建多的东西里，工具表是唯一确定的一项）。

  Run: clojure -J-Dstdout.encoding=UTF-8 -M:dev -m scratch-measure-twice"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.cap.providers :as providers]
            [harness.edge.ag-ui :as ag]
            [harness.edge.compaction :as compaction]
            [harness.edge.pressure :as pressure]
            [harness.edge.replay :as replay]
            [harness.kernel.llm :as llm]
            [harness.test-runner :as runner]))

(def ^:private real-home (io/file (System/getProperty "user.home") ".clj-harness"))

(def ^:private root
  (let [dir (runner/isolate!)]
    (doseq [n ["config.edn" ".env"]
            :let [src (io/file real-home n)]
            :when (.isFile src)]
      (io/copy src (io/file dir n)))
    dir))

(def thread-id "a0621fce-9bf4-4f33-8671-026e783196f6")

(def file
  (io/file "C:/Users/zhouteng/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness"
           (str thread-id ".jsonl")))

(def cut-line 9507)

(defn- rows [] (replay/read-records (io/file (System/getProperty "java.io.tmpdir") "a0621-cut.jsonl")))

(defn- cut-copy []
  (let [tmp (io/file (System/getProperty "java.io.tmpdir") "a0621-cut.jsonl")]
    (with-open [r (io/reader file) w (io/writer tmp)]
      (doseq [line (take cut-line (line-seq r))]
        (.write w ^String line)
        (.write w "\n")))
    tmp))

(defn- chars [array]
  (reduce + 0 (map (fn [m]
                     (+ (count (str (:content m)))
                        (count (str (:reasoning_content m)))
                        (count (str (:tool_calls m)))))
                   array)))


(defn- ask
  "ONE CALL, UP TO N TRIES: the gateway answers 503 `no healthy account` when its pool is dry,
  which is a WAIT, not an answer. Returns the usage map it ended with."
  [provider label array tries]
  (loop [n tries]
    (let [r (try {:ok (llm/stream! provider array (fn [_]) thread-id)}
                 (catch Throwable t {:err t}))]
      (cond
        (:ok r)
        (let [u (get-in r [:ok :telemetry :usage])
              m (get-in r [:ok :message])]
          (println (format "%-28s prompt=%s completion=%s 条=%d 字符=%d est=%d  答=%s"
                           label (:prompt_tokens u) (:completion_tokens u) (count array)
                           (chars array) (pressure/estimate-messages array)
                           (let [t (str/replace (str (:content m)) #"\s+" " ")]
                             (subs t 0 (min 24 (count t))))))
          (flush)
          u)

        (and (pos? (dec n)) (re-find #"no_healthy_account|503" (str (ex-message (:err r)))))
        (do (println (format "  (%s 第 %d 次没号了，等 20s 再来)" label (- tries (dec n))))
            (flush)
            (Thread/sleep 20000)
            (recur (dec n)))

        :else (do (println label "失败:" (ex-message (:err r))) nil)))))

(defn -main [& _]
  (let [f        (cut-copy)
        records  (replay/read-records f)
        facts    (replay/compaction-facts records)
        nodes    (replay/model-nodes (replay/entries records) facts (replay/prune-facts records))
        sidx     (first (keep-indexed (fn [i n] (when (= (:id n) (:seq (first facts))) i)) nodes))
        system   (->> records
                      (filter #(= "system" (:role (replay/payload %))))
                      last replay/payload)
        array    (vec (concat [system]
                              (ag/provider-messages
                               (into (mapv :message (subvec nodes 0 sidx))
                                     (mapv :message (subvec nodes (inc sidx)))))))
        ;; THE SUMMARIZER IS THE MODEL THE OWNER MOVED THE SESSION TO (glm-5.3-flash, same
        ;; workbuddy gateway): pinned with `use-provider!`, the seam the tests use -- the real
        ;; home's config.edn is read for the ROUTE and not modified.
        provider (do (providers/use-provider!
                      thread-id
                      (assoc (:provider (providers/resolve-provider thread-id))
                             :model "glm-5.3-flash"))
                     (providers/pinned-provider thread-id))
        tools    (mapv (fn [i]
                         {:type "function"
                          :function {:name (str "read_file_" i)
                                     :description (apply str (repeat 900 "x"))
                                     :parameters {:type "object" :properties {}}}})
                         (range 45))]
    (println "provider:" (pr-str (select-keys provider [:model :base-url :reasoning-effort])))
    (println "工具表（造出来的，45 个，约" (count (json/write-str tools)) "字节）")
    (println "--- 同一段数组，连着两次（不加工具）:")
    (ask provider "第一发（原样）" array 4)
    (ask provider "第二发（原样）" array 4)
    (println "--- 同一段数组 + 45 个工具（约 45k 字节）:")
    (ask (assoc provider :tools tools) "第三发（+工具表）" array 4)
    (System/exit 0)))

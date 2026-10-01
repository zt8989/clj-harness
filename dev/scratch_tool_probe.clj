(ns scratch-tool-probe
  "票 06 的工具表检验：对这条会话的 vendor，加一张真工具表后 prompt 涨多少？
  （真事那一发每次都带 45 个工具，我重建的那发不带。）
  Run: clojure -J-Dstdout.encoding=UTF-8 -M:dev -m scratch-tool-probe"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.cap.providers :as providers]
            [harness.edge.ag-ui :as ag]
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

(def ^:private small-array
  "一段**小**对话（从同一条记录截出来的第一轮），两次都能过、都有 usage。"
  (delay
    (let [rows (replay/read-records
                (io/file "C:/Users/zhouteng/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness"
                         (str thread-id ".jsonl")))
          msgs (->> (take 30 rows)
                    (filter #(= "message" (replay/kind %)))
                    (map replay/payload)
                    (filter #(contains? % :role))
                    (remove #(= "system" (:role %))))]
      (vec msgs))))


(defn -main [& _]
  ;; THE SUMMARIZER IS THE MODEL THE OWNER MOVED THE SESSION TO (glm-5.3-flash, same
  ;; workbuddy gateway): pinned with `use-provider!`, the same seam the tests use -- the
  ;; real home's config.edn is read for the ROUTE and not modified.
  (let [tools (mapv (fn [i]
                      {:type "function"
                       :function {:name (str "read_file_" i)
                                  :description (apply str (repeat 900 "x"))
                                  :parameters {:type "object" :properties {}}}})
                    (range 45))
        ;; THE PIN, RESOLVED ONCE: `use-provider!` stores the provider map and
        ;; `current-provider` consults it first, so the LAST thing resolved here -- the one
        ;; this process actually calls with -- is the pinned map itself (glm-5.3-flash).
        provider (do (providers/use-provider!
                      thread-id
                      (assoc (:provider (providers/resolve-provider thread-id))
                             :model "glm-5.3-flash"))
                     (providers/pinned-provider thread-id))
        array @small-array]
    (println "provider:" (pr-str (select-keys provider [:model])))
    (println "数组条数:" (count array) " 内容字符:"
             (reduce + 0 (map (fn [m] (count (str (:content m)))) array)))
    (doseq [[label p] [["不带工具" provider]
                       ["带 45 个工具（每条 900 字符描述）" (assoc provider :tools tools)]]]
      (loop [n 4]
        (let [r (try {:ok (llm/stream! p array (fn [_]) thread-id)}
                     (catch Throwable t {:err t}))]
          (cond
            (:ok r)
            (println (format "%-38s prompt=%s completion=%s"
                             label (:prompt_tokens (get-in r [:ok :telemetry :usage]))
                             (:completion_tokens (get-in r [:ok :telemetry :usage]))))
            (and (pos? (dec n)) (re-find #"no_healthy_account|503" (str (ex-message (:err r)))))
            (do (println "  (没号，等 30s)") (Thread/sleep 30000) (recur (dec n)))
            :else (println label "失败:" (ex-message (:err r))))))))
  (System/exit 0))

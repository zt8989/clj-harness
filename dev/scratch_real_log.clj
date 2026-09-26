(ns scratch-real-log
  (:require [harness.test-runner :as tr]
            [harness.edge.replay :as replay]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(tr/isolate!)

(def src
  "C:/Users/zhouteng/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness/39be8804-22da-43e1-b7a0-*.jsonl")

(defn- find-log []
  (let [dir (io/file "C:/Users/zhouteng/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness")]
    (->> (.listFiles dir)
         (filter #(str/starts-with? (.getName ^java.io.File %) "39be8804"))
         first)))

(defn- reasoning-frame? [line]
  (str/includes? line "REASONING"))

(defn- keep-lines [lines pred] (remove pred lines))

(defn- summary [f label]
  (let [h (try (replay/history f) (catch Throwable t [(str "THREW: " (ex-message t))]))]
    (println label :messages (count h)
             :reasoning-lengths (mapv #(count (str (:reasoning_content %))) h)
             :roles (vec (distinct (mapv :role h))))))

(let [src (find-log)
      lines (with-open [r (io/reader src :encoding "UTF-8")] (doall (line-seq r)))
      kept  (keep-lines lines reasoning-frame?)]
  (println :src (.getName ^java.io.File src) :lines (count lines)
           :dropped (- (count lines) (count kept)))
  (summary src :AS-WRITTEN)
  (let [tmp (io/file (System/getProperty "java.io.tmpdir") "scratch-real-nothink.jsonl")]
    (spit tmp (str (str/join "\n" kept) "\n") :encoding "UTF-8")
    (summary tmp :WITHOUT-REASONING-FRAMES)))
(System/exit 0)

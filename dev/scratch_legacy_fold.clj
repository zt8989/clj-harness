(ns scratch-legacy-fold
  "旧格式翻译之后，会话折得出来吗；fork 出来的那一份又是新格式吗（「旧格式迁移」的现场探针）。\n\n写在临时目录里（`with-temp-env`），不碰真家。两半：\n\n  1. 手搓一份三行的旧记录（`kind` 那套），印出翻译后的行、它折出来的消息、判据、fork 点；\n  2. 起一个 edge，把它注册成一场会话，走 `fork-session!` 全路，印出新记录的原字节、行、与 sofar。\n\n期望：行是 `message`/`event` 的新信封且每行带 `:old-contract <行号>`；判据说「3 行还是旧格式」；新记录里一个 `:old-contract` 都没有、原文件一个字节没动。\n\n  Run: clojure -J-Dstdout.encoding=UTF-8 -M:dev -m scratch-legacy-fold"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [harness.edge.replay :as replay]
            [harness.cap.project :as project]
            [harness.edge.normalized :as normalized]
            [harness.edge.http :as http]
            [harness.infra.home :as home]
            [harness.test-support :as support]
            [harness.test-runner :as runner]))

(runner/isolate!)

(defn- line [m] (json/write-str m))

(defn -main [& _]
  (let [f (io/file (str (System/getProperty "java.io.tmpdir") "/legacy-probe.jsonl"))]
    (spit f
          (str (str/join "\n"
                         [(line {:ts 1 :runId "r1" :kind "input"
                                 :payload {:threadId "t"
                                           :messages [{:id "u1" :role "user" :content "hi"}
                                                      {:role "system" :content "S"}]}})
                          (line {:ts 2 :runId "r1" :kind "event"
                                 :payload {:type "RUN_FINISHED" :threadId "t" :runId "r1"}})
                          (line {:ts 3 :runId nil :kind "step/end" :payload {}})])
               "\n")
          :encoding "UTF-8")
    (let [rows  (replay/read-records f)
          sofar (replay/sofar f)]
      (println "ROWS"
               (pr-str (mapv (fn [r] [(replay/kind r) (:source r) (:old-contract r)
                                      (get-in r [:payload :role]) (get-in r [:payload :content])])
                             rows)))
      (println "MESSAGES" (pr-str (mapv :content (:messages sofar))))
      (println "STATE" (:state sofar) "VERDICT" (pr-str (normalized/normalized? rows)))
      (println "FORK-CUT" (pr-str (replay/fork-cut (vec rows) nil)))))
  ;; AND THE FORK ITSELF, through the edge, on a session bound to a temp env.
  (support/with-temp-env [_root _home]
    (let [stop (http/start! {:port 0})]
      (try
        (project/register-session! "src-legacy")
        (let [src (home/log-file (io/file (home/projects-dir) http/unbound-workspace) "src-legacy")]
          (io/make-parents src)
          (spit src
                (str (str/join "\n"
                               [(line {:ts 1 :runId "r1" :kind "input"
                                       :payload {:threadId "src-legacy"
                                                 :messages [{:id "u1" :role "user" :content "hi"}
                                                            {:role "system" :content "S"}]}})
                                (line {:ts 2 :runId "r1" :kind "event"
                                       :payload {:type "RUN_FINISHED" :threadId "src-legacy" :runId "r1"}})
                                (line {:ts 3 :runId nil :kind "step/end" :payload {}})])
                     "\n")
                :encoding "UTF-8")
          (let [forked ((deref #'harness.edge.http/fork-session!) "src-legacy" nil)
                new-id (:threadId forked)
                dest   (home/log-file (io/file (home/projects-dir) http/unbound-workspace) new-id)]
            (println "FORKED" new-id)
            (println "NEW RAW" (pr-str (with-open [r (io/reader dest :encoding "UTF-8")] (doall (line-seq r)))))
            (println "NEW ROWS"
                     (pr-str (mapv (fn [r] [(replay/kind r) (:source r) (:old-contract r)
                                            (get-in r [:payload :role]) (get-in r [:payload :content])])
                                   (replay/read-records dest))))
            (println "NEW SOFAR MESSAGES" (pr-str (mapv :content (:messages (replay/sofar dest)))))))
        (finally (stop))))))

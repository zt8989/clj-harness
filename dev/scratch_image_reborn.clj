(ns scratch-image-reborn
  "THE LOOP for the 2026-09-21 RUN_ERROR: `unsupported content part type \"image_url\"`.

  Phase 1 of /diagnosing-bugs: one command, RED while the bug lives, green once fixed.
  It asserts the one thing the run edge does before it calls a provider -- fold the
  conversation the session holds and hand it to `ag/inbound` (harness.edge.http:1186) --
  and it uses the session's OWN reader (`harness.edge.sessions/model-view`), so a green
  here is the run's own door letting the message through.

  THREE PARTS, narrowing from the artifact to the one load-bearing element:
    A. the user's own log, folded with the reader a session is born from
       (`replay/sofar`, which is what `sessions/build` calls), as the run would -- RED.
    B. the same log with the client's turn cut down to the message itself -- RED.
    C. that one message, MINIMISED: a `message` row is provider-shaped and the fold
       stamps the entry's `:id` on it. Drop the `:id` and the loop goes green, which is
       what names the cause: `ag/provider-shaped?` reads `:id` as 'AG-UI spelling left',
       so an already-translated `image_url` part is translated a second time and
       `ag/provider-part` refuses it.

  Run: clojure -M:dev -m scratch-image-reborn [session-log]
  Exits 1 while the bug lives (that is the point), 0 once it is fixed."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.edge.ag-ui :as ag]
            [harness.edge.replay :as replay]
            [harness.edge.sessions :as sessions]
            [harness.test-runner :as runner]))

;; ABSOLUTELY FIRST: nothing below may resolve the real ~/.clj-harness. The log below is
;; read at its own absolute path (it is the artifact this diagnosis is about); every
;; harness resolver in this process points at a temp root.
(runner/isolate!)

(def ^:private default-log
  (str (System/getProperty "user.home")
       "/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness"
       "/89e5d695-06cd-47d7-8a0d-5919d30f7a4b.jsonl"))

(defn- handed
  "The vector a run assembles from a conversation, at the seam the edge is at
  (`harness.edge.http/run-agent!`): the session's model view, through `inbound`."
  [messages]
  (ag/inbound (sessions/model-view messages) "SYSTEM PROMPT" nil))

(defn- report [label messages]
  (let [verdict (try
                  (let [sent (handed messages)]
                    (str "green   " (count sent) " provider messages"))
                  (catch Throwable t (str "RED     " (ex-message t))))]
    (println (format "  %-46s %s" label verdict))
    (str/starts-with? verdict "RED")))

(defn- image-parts
  "The messages whose content carries a part only one of the two dialects spells."
  [messages]
  (filter #(some (fn [p] (contains? #{"image" "image_url"} (:type p)))
                 (when (sequential? (:content %)) (:content %)))
          messages))

(defn- from-the-log [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (println "SKIP  no session log at" path)
      (System/exit 0))
    (let [entries (:entries (replay/sofar f))
          messages (vec (mapv :message entries))
          mine (vec (image-parts messages))]
      (println)
      (println "A. the user's own record:" (.getName f))
      (println "   folded" (count messages) "entries;"
               (count mine) "carry an image part:")
      (doseq [m mine]
        (println "     " (pr-str (select-keys m [:id :role]))
                 (pr-str (mapv :type (:content m)))))
      (let [red? (report "the whole conversation" messages)]
        (println)
        (println "B. the same record, cut down to the image-bearing message")
        (report "one entry message" mine)
        (println)
        (println "C. minimised: the SAME message, with and without the fold's `:id`")
        (let [m (first mine)
              born (update m :content
                           (fn [parts]
                             ;; the row's payload as the RECORD keeps it -- this is
                             ;; byte for byte what the log at line 401 holds
                             (mapv (fn [p]
                                     (if (= "image" (:type p))
                                       {:type "image_url"
                                        :image_url {:url (str "data:" (get-in p [:source :mimeType])
                                                              ";base64," (get-in p [:source :value]))}}
                                       p))
                                   parts)))
              stripped (dissoc born :id)]
          (report "a folded entry (carries :id)" [born])
          (report "the same message with :id off" [stripped]))
        (System/exit (if red? 1 0))))))

(defn- -main [& [path]]
  (from-the-log (or path default-log))
  (println "green: the session's own vector survives a rebuild")
  (System/exit 0))

(apply -main *command-line-args*)
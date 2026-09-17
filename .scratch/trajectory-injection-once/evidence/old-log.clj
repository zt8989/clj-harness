;; ONE-OFF, and kept because its answer is the point: the read side does NOT
;; retroactively repair a log an older build wrote.
;;
;;   clojure -M:test -e '(load-file ".scratch/trajectory-injection-once/evidence/old-log.clj")'
;;
;; `80de94f6…jsonl` is a real session of this repo (its first turn is `/to-tickets …`),
;; written BEFORE the edge started folding the session's injections into the submitted
;; side. Its record therefore still says what it said: the second run's returned tail is
;; the shifted one, and a client message sits in it. The fold draws what the record has
;; -- 'correcting' it would mean guessing, which is the one thing this view may not do.
;;
;; Point OLD at any other log of that vintage and the shape is the same.

(require '[clojure.string :as str]
         '[harness.edge.trajectory :as trajectory])

(def old (java.io.File.
          (str (System/getProperty "user.home")
               "/.clj-harness/projects/_Users_zhouteng_Documents_workspace_clj-harness"
               "/80de94f6-1e7a-49da-8670-cf54044a53ec.jsonl")))

(defn- head [s]
  (let [s (str/replace (str s) #"\n" " ")]
    (subs s 0 (min 46 (count s)))))

(println (str "folding " (.getPath old)))
(println (str "written by an OLDER build: " (.lastModified old)))
(println "the injection items it draws per turn (kind + source + text):")
(doseq [turn (:turns (trajectory/log-trajectory old))]
  (println (format "  turn %d" (:index turn)))
  (doseq [i (:items turn)
          :when (= "context" (:kind i))]
    (println (format "    %-8s %s" (or (:source i) "") (head (:text i))))))

(ns scratch-hooks-config
  "Every factual claim hooks.edn.example / docs/architecture/hooks.md makes about
  a hooks.edn, as a check that either passes or prints why it did not. The point
  is not to test the engine -- the suite does that -- but to test the DOCUMENT:
  a claim in the reference that is not true is worse than a missing one, because
  it is the one a reader will act on.

  Run: clojure -M:dev -m scratch-hooks-config"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.cap.hooks :as cap-hooks]
            [harness.infra.home :as home]
            [harness.kernel.hooks :as hooks]
            [harness.kernel.hooks.dispatch :as dispatch]
            [harness.test-runner :as runner]))

(def ^:private results (atom {:pass 0 :fail 0}))

(defn- check [what ok & [detail]]
  (swap! results update (if ok :pass :fail) inc)
  (println (format "  %-4s %s%s" (if ok "PASS" "FAIL") what
                   (if (and (not ok) detail) (str "  <- " detail) ""))))

(defn- with-hooks! [text]
  (spit (io/file (home/hooks-file)) text :encoding "UTF-8"))

(defn- write-fails
  "The message the reader throws for TEXT, or nil when it accepts it."
  [text]
  (with-hooks! text)
  (try (cap-hooks/config "t") nil
       (catch Exception e (ex-message e))))

(defn- fire [point fact]
  (dispatch/fire {:point point :thread-id "doc" :fact fact :audit (fn [_])}))

(defn- example-bodies
  "Every example map in the .example file, as the text a reader gets by deleting
  the leading `;; `. A body opens at a line of exactly `;;` plus four spaces plus
  `{`, and ends when the braces balance -- the strict indent matters, because
  prose in these files also quotes small maps inline."
  [file]
  (let [braces (fn [t] (- (count (filter #{\{} t)) (count (filter #{\}} t))))]
    (loop [lines (str/split-lines (slurp file :encoding "UTF-8"))
           body nil depth 0 acc []]
      (if-let [line (first lines)]
        (let [text  (second (re-find #"^;;\s{4,}(.*)$" line))
              opens (second (re-find #"^;; {4}(\{.*)$" line))]
          (cond
            body (let [depth' (+ depth (braces text))]
                   (if (zero? depth')
                     (recur (rest lines) nil 0 (conj acc (str body "\n" text)))
                     (recur (rest lines) (str body "\n" text) depth' acc)))
            opens (let [depth' (braces opens)]
                    (if (zero? depth')
                      (recur (rest lines) nil 0 (conj acc opens))
                      (recur (rest lines) opens depth' acc)))
            :else (recur (rest lines) nil 0 acc)))
        acc))))

(defn -main [& _]
  (runner/isolate!)
  (cap-hooks/install!)
  (println "hooks.edn claims, checked against" (str (home/hooks-file)))

  (println "\n-- the file itself")
  (check "{} means nothing said"
         (do (with-hooks! "{}\n") (= {} (cap-hooks/config "t"))))
  (check "an EMPTY file is a named failure, not an empty configuration"
         (some? (write-fails "")))
  (check "a file of only comments is a named failure too"
         (some? (write-fails ";; nothing here\n")))
  (check "the failure names the absolute path"
         (let [m (write-fails "")]
           (and m (str/includes? m (str (home/hooks-file))))))

  (println "\n-- one declaration runs exactly one thing")
  (check "a misspelled key fails by name"
         (let [m (write-fails "{:stop [{:commnd \"true\"}]}")]
           (and m (str/includes? m ":commnd"))))
  (check ":run in a file is refused (a file cannot hold a function)"
         (let [m (write-fails "{:stop [{:run 1}]}")]
           (and m (str/includes? m "cannot hold a function"))))
  (check ":command and :run together are refused"
         (some? (write-fails "{:stop [{:command \"true\" :run 1}]}")))
  (check ":timeout with a callable :run is refused -- reachable from a SESSION declaration"
         (some? (try (hooks/session-add! "doc" :stop {:run (fn [_] {:exit 0}) :timeout 5})
                     nil
                     (catch Exception e (ex-message e)))))
  (check "a point with no match target refuses :matcher"
         (some? (write-fails "{:stop [{:command \"true\" :matcher \"x\"}]}")))
  (check "a matcher that does not compile fails at READ time"
         (some? (write-fails "{:pre-tool-use [{:command \"true\" :matcher \"[\"}]}")))

  (println "\n-- the exit code is the verdict")
  (with-hooks! "{:pre-tool-use [{:command \"cat >/dev/null; echo nope >&2; exit 2\" :matcher \"bash\"}]}\n")
  (check "exit 2 at a GATE blocks, and stderr is the reason the model gets"
         (let [v (fire :pre-tool-use {:tool_name "bash"})]
           (and (= :block (:verdict v)) (= "nope" (:reason v)))))
  (check "the matcher is compared with re-find -- a PARTIAL match counts"
         (do (with-hooks! "{:post-tool-use [{:command \"true\" :matcher \"as\"}]}\n")
             (= 1 (:matched (fire :post-tool-use {:tool_name "bash"})))))
  (with-hooks! "{:pre-tool-use [{:command \"cat >/dev/null; echo nope >&2; exit 2\" :matcher \"bash\"}]}\n")
  (check "no match means the declaration is not spawned and not reported"
         (let [v (fire :pre-tool-use {:tool_name "write"})]
           (and (= :allow (:verdict v)) (= 0 (:matched v)))))

  (println "\n-- observers cannot block a run")
  (with-hooks! "{:post-tool-use [{:command \"cat >/dev/null; echo broke >&2; exit 3\"}]}\n")
  (check "a non-zero exit at an OBSERVER still allows, with the reason kept"
         (let [v (fire :post-tool-use {:tool_name "bash"})]
           (and (= :allow (:verdict v)) (str/includes? (str (:reason v)) "broke"))))
  (with-hooks! "{:post-tool-use [{:command \"sleep 5\" :timeout 500}]}\n")
  (check "a TIMEOUT at an observer allows too -- 'could not decide' is not 'yes'"
         (let [v (fire :post-tool-use {:tool_name "bash"})]
           (and (= :allow (:verdict v)) (str/includes? (str (:reason v)) "timed out"))))

  (println "\n-- the one point whose stdout is content")
  (with-hooks! (str "{:system-prompt [{:command \"echo policy-one\"}\n"
                    "                 {:command \"echo policy-two\"}\n"
                    "                 {:command \"true\"}]}\n"))
  (check "every matched declaration appends, in order; empty output says nothing"
         (= ["policy-one" "policy-two"] (:blocks (fire :system-prompt {}))))

  (println "\n-- what a command actually receives on stdin")
  (let [out (io/file (home/root) "payload.json")]
    (with-hooks! (str "{:pre-tool-use [{:command \"cat > " (.getAbsolutePath out) "\"}]}\n"))
    (fire :pre-tool-use {:tool_name "bash" :tool_input {:path "a" :offset 3}})
    (check "stdin is written and then CLOSED -- the command finished instead of waiting"
           (.exists out))
    (let [m (if (.exists out) (json/read-str (slurp out :encoding "UTF-8")) {})]
      (check "the three common keys are always there, and project_dir is null when unbound"
             (and (contains? m "hook") (contains? m "thread_id")
                  (contains? m "project_dir") (nil? (get m "project_dir"))))
      (check "the point's own fields are there spelled as the table says"
             (and (= "PreToolUse" (get m "hook")) (= "bash" (get m "tool_name"))
                  (contains? m "tool_input")))
      (check "a non-string value arrives as its PRINTED form, not as JSON"
             (str/includes? (get m "tool_input") ":path"))))

  (println "\n-- the reference file people actually copy")
  (let [example (io/file (System/getProperty "user.dir") "hooks.edn.example")]
    (check "hooks.edn.example exists"
           (.exists example))
    (check "copying it as-is gives a working hooks.edn (no failure, nothing declared)"
           (do (io/copy example (io/file (home/hooks-file)))
               (= {} (cap-hooks/config "t"))))
    (check "...and every worked example in it reads as EDN once uncommented"
           (let [bodies (example-bodies example)]
             (and (seq bodies)
                  (every? map? (map edn/read-string bodies)))))
    (check "...and every one of those maps has a legal point key and legal declarations"
           (every? (fn [body]
                     (with-hooks! body)
                     (nil? (try (cap-hooks/config "t") nil (catch Exception _ :bad))))
                   (example-bodies example))))

  (let [{:keys [pass fail]} @results]
    (println (format "\n%d passed, %d failed" pass fail))
    (shutdown-agents)
    (System/exit (if (zero? fail) 0 1))))

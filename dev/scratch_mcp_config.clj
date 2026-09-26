(ns scratch-mcp-config
  "Every factual claim mcp.edn.example / docs/architecture/mcp.md make about an
  mcp.edn, as a check that either passes or prints why it did not. Same purpose as
  scratch-hooks-config: this tests the DOCUMENT, not the engine.

  Run: clojure -M:dev -m scratch-mcp-config"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.cap.mcp :as mcp]
            [harness.infra.home :as home]
            [harness.infra.shell :as shell]
            [harness.test-runner :as runner]))

(def ^:private results (atom {:pass 0 :fail 0}))

(defn- check [what ok & [detail]]
  (swap! results update (if ok :pass :fail) inc)
  (println (format "  %-4s %s%s" (if ok "PASS" "FAIL") what
                   (if (and (not ok) detail) (str "  <- " detail) ""))))

(defn- read-fails
  "The message the reader throws for TEXT, or nil when it accepts it."
  [text]
  (spit (io/file (home/mcp-file)) text :encoding "UTF-8")
  (try (mcp/config "t") nil
       (catch Exception e (ex-message e))))

(defn- stdio-command
  "harness.cap.mcp's own command-line builder, private but the thing the docs make
  a claim about."
  [decl]
  ((var-get (ns-resolve 'harness.cap.mcp 'stdio-command)) decl))

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
  (println "mcp.edn claims, checked against" (str (home/mcp-file)))

  (println "\n-- the file itself")
  (check "{:servers {}} means nothing declared"
         (do (spit (io/file (home/mcp-file)) "{:servers {}}\n") (= {} (mcp/config "t"))))
  (check "{} is accepted too, and declares nothing"
         (do (spit (io/file (home/mcp-file)) "{}\n") (= {} (mcp/config "t"))))
  (check "an EMPTY file is a named failure: {:servers {}} is not the same thing"
         (some? (read-fails "")))
  (check "a file of only comments is a named failure too"
         (some? (read-fails ";; nothing here\n")))
  (check "the failure names the absolute path"
         (let [m (read-fails "")]
           (and m (str/includes? m (str (home/mcp-file))))))
  (check "a top-level key other than :servers fails by name"
         (let [m (read-fails "{:server {}}")]
           (and m (str/includes? m ":server"))))

  (println "\n-- :command and :url are one transport, chosen by which is present")
  (check ":command alone is accepted"
         (do (spit (io/file (home/mcp-file)) "{:servers {\"a\" {:command \"true\"}}}\n")
             (= ["a"] (keys (mcp/config "t")))))
  (check ":url alone is accepted"
         (do (spit (io/file (home/mcp-file)) "{:servers {\"a\" {:url \"https://x/mcp\"}}}\n")
             (= ["a"] (keys (mcp/config "t")))))
  (check "both is refused"
         (let [m (read-fails "{:servers {\"a\" {:command \"true\" :url \"https://x\"}}}")]
           (and m (str/includes? m "ONE transport"))))
  (check "neither is refused"
         (some? (read-fails "{:servers {\"a\" {:timeout 100}}}")))
  (check "an empty :command is refused"
         (some? (read-fails "{:servers {\"a\" {:command \"\"}}}")))

  (println "\n-- the other four keys")
  (check "a misspelled declaration key fails by name"
         (let [m (read-fails "{:servers {\"a\" {:commandd \"true\"}}}")]
           (and m (str/includes? m ":commandd"))))
  (check ":args must be a vector of strings"
         (some? (read-fails "{:servers {\"a\" {:command \"true\" :args [1]}}}")))
  (check ":env must be string -> string"
         (some? (read-fails "{:servers {\"a\" {:command \"true\" :env {\"K\" 1}}}}")))
  (check ":timeout must be a POSITIVE whole number of milliseconds"
         (and (some? (read-fails "{:servers {\"a\" {:command \"true\" :timeout 0}}}"))
              (some? (read-fails "{:servers {\"a\" {:command \"true\" :timeout -5}}}"))))
  (check "no :timeout at all is fine (it defaults to 60000)"
         (do (spit (io/file (home/mcp-file)) "{:servers {\"a\" {:command \"true\"}}}\n")
             (nil? (read-fails "{:servers {\"a\" {:command \"true\"}}}\n"))))

  (println "\n-- server names")
  (check "a name with `__` is refused (two servers could produce one tool name)"
         (let [m (read-fails "{:servers {\"a__b\" {:command \"true\"}}}")]
           (and m (str/includes? m "__"))))
  (check "a name outside ^[A-Za-z0-9_-]+$ is refused"
         (some? (read-fails "{:servers {\"a.b\" {:command \"true\"}}}")))
  (check "letters, digits, `-` and `_` are all fine"
         (do (spit (io/file (home/mcp-file)) "{:servers {\"a-B_9\" {:command \"true\"}}}\n")
             (= ["a-B_9"] (keys (mcp/config "t")))))

  (println "\n-- :args are quoted as POSIX words and joined into ONE line")
  (check "the doc's own example becomes `npx '@playwright/mcp@latest'`"
         (= "npx '@playwright/mcp@latest'"
            (stdio-command {:command "npx" :args ["@playwright/mcp@latest"]})))
  (check "an argument with a space or a quote survives as ONE word"
         (= "run 'a b' 'it'\\''s'"
            (stdio-command {:command "run" :args ["a b" "it's"]})))
  (check "quote-arg is the one place that says how a value is quoted"
         (= "'it'\\''s'" (shell/quote-arg "it's")))

  (println "\n-- the reference file people actually copy")
  (let [example (io/file (System/getProperty "user.dir") "mcp.edn.example")]
    (check "mcp.edn.example exists"
           (.exists example))
    (check "copying it as-is gives a working mcp.edn (no failure, nothing declared)"
           (do (io/copy example (io/file (home/mcp-file)))
               (= {} (mcp/config "t"))))
    (check "...and every worked example in it reads as EDN once uncommented"
           (let [bodies (example-bodies example)]
             (and (seq bodies)
                  (every? map? (map edn/read-string bodies)))))
    (check "...and every one of those maps has :servers and validates"
           (every? (fn [body]
                     (spit (io/file (home/mcp-file)) body :encoding "UTF-8")
                     (nil? (try (mcp/config "t") nil (catch Exception _ :bad))))
                   (example-bodies example))))

  (let [{:keys [pass fail]} @results]
    (println (format "\n%d passed, %d failed" pass fail))
    (shutdown-agents)
    (System/exit (if (zero? fail) 0 1))))

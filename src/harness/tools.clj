(ns harness.tools
  "The five tools. A tool is
     {:description string :parameters JSON-Schema :required [kw..] :run (fn [args] string)}

  RUN gets a keyword-keyed argument map and returns a string.

  run! takes a tool call in the PROVIDER's shape -- {:id .. :type \"function\"
  :function {:name .. :arguments json-string}} -- because the kernel keeps messages
  in the provider shape and never converts. It never throws and never returns nil:
  a tool failure is information for the model, not a failure of the run."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.util.regex Pattern]))

(defonce registry (atom {}))

(defn register! [name tool] (swap! registry assoc name tool))

(defn specs
  "The tools array as an OpenAI-compatible provider expects it."
  []
  (mapv (fn [[n t]] {:type "function"
                     :function {:name n
                                :description (:description t)
                                :parameters (:parameters t)}})
        (sort-by key @registry)))

;; A resident namespace, so `def`s in eval persist across calls. This is what lets
;; the agent build itself a toolset -- and hot-swap the kernel with (require .. :reload).
(create-ns 'harness.user)
(binding [*ns* (the-ns 'harness.user)] (clojure.core/refer-clojure))

;; ------------------------------------------------------------------- helpers

(defn- tool [description props required run]
  {:description description
   :parameters  {:type "object" :properties props :required (mapv name required)}
   :required    required
   :run         run})

(defn- clip [s]
  (if (> (count s) 8000) (str (subs s 0 8000) "\n...[truncated]") s))

(defn- write-file! [path content]
  (let [f (io/file path)]
    (when-let [p (.getParentFile f)] (.mkdirs p))
    (spit f content :encoding "UTF-8")))

;; `bash` on PATH is C:\WINDOWS\System32\bash.exe -- the WSL launcher, a different
;; filesystem entirely, which fails silently from a JVM. Pin Git Bash by path.
(defonce git-bash
  (or (first (filter #(.exists (io/file %))
                     ["C:\\Program Files\\Git\\bin\\bash.exe"
                      "C:\\Program Files\\Git\\usr\\bin\\bash.exe"]))
      "bash"))

;; --------------------------------------------------------------------- tools

(defn- t-read [{:keys [path]}] (slurp path :encoding "UTF-8"))

(defn- t-write [{:keys [path content]}]
  (write-file! path content)
  (str "wrote " (count content) " chars to " path))

(defn- t-edit [{:keys [path old_string new_string]}]
  (let [s (slurp path :encoding "UTF-8")
        n (count (re-seq (re-pattern (Pattern/quote old_string)) s))]
    (when (zero? n) (throw (ex-info (str "old_string not found in " path) {})))
    (when (> n 1)
      (throw (ex-info (str "old_string occurs " n " times in " path ", make it unique") {})))
    (write-file! path (str/replace-first s old_string new_string))
    (str "edited " path)))

(defn- t-bash [{:keys [command]}]
  (let [{:keys [exit out err]} (shell/sh git-bash "-lc" command :out-enc "UTF-8")
        body (str out err)]
    (str (if (str/blank? body) "(no output)" body)
         (when-not (zero? exit) (str "\n[exit " exit "]")))))

(defn- t-eval [{:keys [code]}]
  (let [sw (java.io.StringWriter.)
        v  (binding [*ns* (the-ns 'harness.user) *out* sw]
             (last (map eval (read-string (str "[" code "]")))))
        printed (.toString sw)]
    (clip (str (when (seq printed) (str printed "\n"))
               (pr-str v)))))

;; ------------------------------------------------------------------ registry

(register! "read"
  (tool "Read a file."
        {"path" {:type "string" :description "File path."}}
        [:path] t-read))

(register! "write"
  (tool "Write a file, overwriting it."
        {"path"    {:type "string" :description "File path."}
         "content" {:type "string" :description "Full new contents."}}
        [:path :content] t-write))

(register! "edit"
  (tool "Replace an exact string in a file. Fails if old_string is absent or not unique."
        {"path"       {:type "string" :description "File path."}
         "old_string" {:type "string" :description "Exact text to replace."}
         "new_string" {:type "string" :description "Replacement text."}}
        [:path :old_string :new_string] t-edit))

(register! "bash"
  (tool "Run a shell command in Git Bash."
        {"command" {:type "string" :description "Command line."}}
        [:command] t-bash))

(register! "eval"
  (tool "Evaluate Clojure in this process. Defs persist across calls."
        {"code" {:type "string" :description "Clojure source."}}
        [:code] t-eval))

;; ------------------------------------------------------------------ dispatch

(defn- validate! [{:keys [required]} args]
  (let [missing (remove #(contains? args %) required)]
    (when (seq missing)
      (throw (ex-info (str "missing required argument(s): "
                           (str/join ", " (map name missing)))
                      {})))))

(defn run!
  [{:keys [function]}]
  (try
    (let [{:keys [name arguments]} function
          tool   (or (get @registry name)
                     (throw (ex-info (str "unknown tool: " name) {})))
          parsed (json/read-str (if (str/blank? arguments) "{}" arguments) :key-fn keyword)]
      (validate! tool parsed)
      {:content (str ((:run tool) parsed)) :error false})
    (catch Throwable t
      {:content (ex-message t) :error true})))

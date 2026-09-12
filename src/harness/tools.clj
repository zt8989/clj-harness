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
            [clojure.string :as str]
            [harness.event :as ev]
            [harness.memory :as mem])
  (:import [java.util.regex Pattern]))

;; The tool registry itself lives in harness.memory (the introspectable
;; surface): the agent reads and extends its own toolset through eval. This
;; namespace owns only the tool SHAPE, the five built-ins, and dispatch.

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

(defn specs
  "The tools array as an OpenAI-compatible provider expects it, for THREAD-ID's
  effective toolset (base overlaid with its session additions/removals)."
  ([] (specs nil))
  ([thread-id]
   (mapv (fn [[n t]] {:type "function"
                      :function {:name n
                                 :description (:description t)
                                 :parameters (:parameters t)}})
         (sort-by key (mem/effective-tools thread-id)))))

;; ------------------------------------------------------------------ registry

(mem/register! "read"
  (tool "Read a file."
        {"path" {:type "string" :description "File path."}}
        [:path] t-read))

(mem/register! "write"
  (tool "Write a file, overwriting it."
        {"path"    {:type "string" :description "File path."}
         "content" {:type "string" :description "Full new contents."}}
        [:path :content] t-write))

(mem/register! "edit"
  (tool "Replace an exact string in a file. Fails if old_string is absent or not unique."
        {"path"       {:type "string" :description "File path."}
         "old_string" {:type "string" :description "Exact text to replace."}
         "new_string" {:type "string" :description "Replacement text."}}
        [:path :old_string :new_string] t-edit))

(mem/register! "bash"
  (tool "Run a shell command in Git Bash."
        {"command" {:type "string" :description "Command line."}}
        [:command] t-bash))

(mem/register! "eval"
  (tool "Evaluate Clojure in this process. Defs persist across calls."
        {"code" {:type "string" :description "Clojure source."}}
        [:code] t-eval))

;; ------------------------------------------------------------------ dispatch

(defn- missing-args [{:keys [required]} args]
  (vec (remove #(contains? args %) required)))

(defn run!
  "The ONE tool execution seam. The call's lifecycle is reported to ON-PHASE
  (a fn of kernel events, may be nil) as it passes through:
    :tool/pre-execute   -- entered the seam; outcome :pass, :unknown-tool or
                           :missing-args (with the missing names)
    :tool/execute       -- left execution; the error message, or nil
    :tool/post-execute  -- closes the lifecycle, whatever the phases decided
  A call that never passes pre-execute (unknown tool, missing arguments) skips
  the :tool/execute phase, but its :tool/post-execute still arrives -- the
  lifecycle is always closed."
  ([call] (run! call nil nil))
  ([call thread-id] (run! call thread-id nil))
  ([{:keys [id function] :as _call} thread-id on-phase]
   (let [report (fn [e] (when on-phase (on-phase e)))
         {:keys [name arguments]} function]
     (if-let [tool (get (mem/effective-tools thread-id) name)]
       (try
         (let [parsed (json/read-str (if (str/blank? arguments) "{}" arguments)
                                     :key-fn keyword)
               missing (missing-args tool parsed)]
           (if (seq missing)
             (do (report (ev/tool-pre-execute id name :missing-args missing))
                 (report (ev/tool-post-execute id name))
                 {:content (str "missing required argument(s): "
                                (str/join ", " (map (fn [k] (clojure.core/name k)) missing)))
                  :error true})
             (do (report (ev/tool-pre-execute id name :pass []))
                 ;; *thread-id* is bound around the tool body so code running
                 ;; inside a tool -- eval above all -- can address its own
                 ;; session (harness.memory).
                 (let [[result err]
                       (try [(binding [mem/*thread-id* thread-id] ((:run tool) parsed)) nil]
                            (catch Throwable t [nil t]))
                       _ (report (ev/tool-executed id name (some-> err ex-message)))
                       _ (report (ev/tool-post-execute id name))]
                   (if err
                     {:content (ex-message err) :error true}
                     {:content (str result) :error false})))))
         (catch Throwable t
           {:content (ex-message t) :error true}))
       (do (report (ev/tool-pre-execute id name :unknown-tool []))
           (report (ev/tool-post-execute id name))
           {:content (str "unknown tool: " name) :error true})))))

(ns harness.layers-test
  "The layering law, enforced: every namespace under src/ is in one of the four
  layers, no namespace requires a layer above its own, and every namespace's PATH
  matches its NAME.

  IT READS SOURCE, IT DOES NOT LOAD ANYTHING. `require` is a compile-time fact, so a
  namespace that is never loaded cannot be asked what it requires -- and loading the
  whole tree from inside a test runner that is already loading it is a way to find
  out what the compiler does with that. The ns form is read as data instead, which
  is also why the first assertion below can be about a file nobody has ever loaded.

  WHAT IT CANNOT SEE, and this is worth reading before trusting a green run: it
  checks REQUIRE, not CONTENT. A namespace that required nothing from a capability
  but hardcoded one of its names would pass here. That is not hypothetical -- it
  happened while this layer split was being built, and it was caught by a person
  grepping for tool names, not by a test. So this file pins the half that can be
  pinned mechanically and says so, rather than implying the other half is covered."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private allowed
  "Layer -> the layers it may require. A layer may always require itself."
  {:infra  #{:infra}
   :kernel #{:infra :kernel}
   :cap    #{:infra :kernel :cap}
   :edge   #{:infra :kernel :cap :edge}})

(def ^:private the-ways-up
  "Layer -> what a requirement on it means, for the failure message. The point of
  the message is that a reader who has never heard of this feature learns the rule
  from the failure itself."
  {:infra  "infrastructure: nothing above it may be assumed"
   :kernel "the kernel: mechanisms only, and it must not require capabilities"
   :cap    "a capability: the kernel must not require one -- it installs into it"
   :edge   "the adapter: nothing may require it, it is the top"})

(defn- source-files
  "Every .clj under ROOT and its subdirectories, as Files, in a stable order."
  [root]
  (->> (file-seq (io/file root))
       (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".clj")))
       (sort-by #(.getPath ^java.io.File %))))

(defn- ns-form
  "The ns form of FILE, as data. Throws if there is none -- a .clj with no ns form
  is not source this repo writes."
  [^java.io.File file]
  (with-open [r (java.io.PushbackReader. (io/reader file :encoding "UTF-8"))]
    (read r)))

(defn- ns-name-of [form] (second form))

(defn- requires-of
  "The namespaces FORM requires. The ns form is a list whose tail is a run of
  clauses, so this walks them rather than treating it as a map."
  [form]
  (let [clauses (drop 2 form)
        reqs    (filter #(and (sequential? %) (= :require (first %))) clauses)]
    (->> (mapcat rest reqs)
         (keep #(when (sequential? %) (first %)))
         (filter symbol?))))

(defn- layer-of
  "The layer a namespace name belongs to, or nil when it is not in one."
  [ns-sym]
  (let [parts (str/split (str ns-sym) #"\.")]
    (when (and (= "harness" (first parts)) (>= (count parts) 2))
      (let [l (keyword (second parts))]
        (when (contains? allowed l) l)))))

(defn- expected-ns
  "The namespace a file at PATH must declare: the path relative to its source root,
  with underscores as dashes and slashes as dots."
  [root ^java.io.File file]
  (-> (str (.getPath file))
      (str/replace (str root "/") "")
      (str/replace #"\.clj$" "")
      (str/replace "_" "-")
      (str/replace "/" ".")))

(defn- leaked
  "The requirements of FILE that a namespace in ITS-LAYER may not make, as
  [required required-layer] pairs. Only harness.* requirements are judged: this repo
  may require clojure.* and java.* from anywhere."
  [its-layer required-namespaces]
  (for [r required-namespaces
        :let [rl (layer-of r)]
        :when (and rl (not (contains? (allowed its-layer) rl)))]
    [r rl]))

(deftest every-namespace-is-in-a-layer
  (doseq [f (source-files "src")]
    (let [n (ns-name-of (ns-form f))]
      (is (some? (layer-of n))
          (str n " (" (.getPath ^java.io.File f) ") is not in a layer. Every"
               " namespace under src/ belongs to harness.infra, harness.kernel,"
               " harness.cap or harness.edge -- a new file at the top of"
               " src/harness/ is a file nothing will place for you.")))))

(deftest no-namespace-requires-a-layer-above-it
  (doseq [f (source-files "src")]
    (let [form       (ns-form f)
          n          (ns-name-of form)
          its-layer  (layer-of n)]
      (when its-layer
        (doseq [[r rl] (leaked its-layer (requires-of form))]
          (is false
              (str n " (" (name its-layer) ") requires " r " (" (name rl) "). "
                   "A namespace may require " (pr-str (sort (allowed its-layer)))
                   " -- " (the-ways-up rl) ".")))))))

(deftest every-namespace-lives-where-its-name-says
  ;; Clojure's hard rule, and the way it breaks is quiet until load time: a rename
  ;; that rewrites a namespace whose LEADING TOKEN happens to be a moved namespace's
  ;; name leaves a file whose path and name disagree, and nothing loads it until
  ;; something requires it. (It happened here: a test namespace beginning with the
  ;; moved hooks namespace's name was rewritten while its file stayed put.)
  (doseq [root ["src" "test"]]
    (doseq [f (source-files root)]
      (let [n (ns-name-of (ns-form f))]
        (is (= (expected-ns root f) (str n))
            (str (.getPath ^java.io.File f) " declares " n ", but its path says "
                 (expected-ns root f) ". A namespace's path IS its name."))))))

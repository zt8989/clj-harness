(ns harness.tools-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.project :as project]
            [harness.tools :as tools]))

(def ^:private dir (str (System/getProperty "java.io.tmpdir") "/harness-tools-test"))

;; Every test in this namespace shares one scratch directory; start it clean.
;; Done at load time rather than in a test, so ordering cannot break it.
(io/delete-file dir true)

(defn- call [name args]
  (tools/run! {:function {:name name :arguments (json/write-str args)}}))

(defn- tmp [name] (str dir "/" name))

(deftest write-then-read-roundtrips
  (let [p (tmp "round-trip.txt")]
    (is (false? (:error (call "write" {:path p :content "第一行\nsecond"}))))
    (let [{:keys [content error]} (call "read" {:path p})]
      (is (false? error))
      (is (= "第一行\nsecond" content)))))

(deftest edit-requires-an-exact-unique-match
  (let [p (tmp "edit.txt")]
    (call "write" {:path p :content "alpha beta gamma"})
    (testing "a unique match is replaced"
      (is (false? (:error (call "edit" {:path p :old_string "beta" :new_string "BETA"}))))
      (is (= "alpha BETA gamma" (:content (call "read" {:path p})))))
    (testing "a missing match is an error, not a silent no-op"
      (let [{:keys [content error]} (call "edit" {:path p :old_string "nope" :new_string "x"})]
        (is (true? error))
        (is (str/includes? content "not found"))))
    (testing "an ambiguous match is refused"
      (call "write" {:path p :content "aa"})
      (let [{:keys [content error]} (call "edit" {:path p :old_string "a" :new_string "b"})]
        (is (true? error))
        (is (str/includes? content "2 times"))))))

(deftest bash-runs-git-bash-not-wsl
  (let [{:keys [content error]} (call "bash" {:command "uname -s"})]
    (is (false? error))
    (testing "uname reports MINGW -- Git Bash, not the WSL launcher on PATH"
      (is (str/includes? content "MINGW"))))
  (testing "the working directory is the project"
    ;; Assert the invariant, not a substring of the project's name: bash must be
    ;; sitting in the same directory the JVM considers its own. Git Bash reports
    ;; POSIX paths (/c/Users/...) where the JVM says C:\Users\..., so compare the
    ;; normalized tail -- the drive letter is the only legitimate difference.
    (let [pwd  (str/trim (:content (call "bash" {:command "pwd"})))
          cwd  (System/getProperty "user.dir")
          norm (fn [p] (-> p (str/replace "\\" "/") (str/replace #"^[A-Za-z]:" "")
                           (str/replace #"^/c/" "/") (str/replace #"/+$" "")))]
      (is (= (norm cwd) (norm pwd)))))
  (testing "a non-zero exit is surfaced, not swallowed"
    (let [{:keys [content error]} (call "bash" {:command "exit 3"})]
      (is (false? error))
      (is (str/includes? content "[exit 3]")))))

(deftest eval-captures-stdout-and-value
  (let [{:keys [content error]} (call "eval" {:code "(println \"hi\") (+ 1 2)"})]
    (is (false? error))
    (is (str/includes? content "hi"))
    (is (str/includes? content "3"))))

(deftest eval-defs-persist-across-calls
  (call "eval" {:code "(def persist-probe 41)"})
  (is (= "42" (:content (call "eval" {:code "(inc persist-probe)"})))))

(deftest eval-truncates-runaway-output
  (let [{:keys [content]} (call "eval" {:code "(apply str (repeat 9000 \"a\"))"})]
    (is (str/ends-with? content "...[truncated]"))
    (is (< (count content) 8100))))

(deftest eval-reports-errors-as-content
  (let [{:keys [content error]} (call "eval" {:code "(throw (ex-info \"boom\" {}))"})]
    (is (true? error))
    (is (str/includes? content "boom"))))

(deftest malformed-arguments-are-an-error-not-a-crash
  (let [{:keys [content error]} (tools/run! {:function {:name "read" :arguments "{not json"}})]
    (is (true? error))
    (is (string? content)))
  (testing "the lifecycle still closes: execute carries the error, post always arrives"
    ;; Malformed JSON dies before the pass branch starts, so there is no
    ;; :tool/pre-execute -- but post-execute must never be skipped.
    (let [seen (atom [])]
      (tools/run! {:function {:name "read" :arguments "{not json"}}
                  nil #(swap! seen conj %))
      (is (= [:tool/execute :tool/post-execute] (mapv :type @seen)))
      (is (string? (:error (first @seen)))))))

(deftest missing-args-and-unknown-tools-are-errors
  (let [{:keys [content error]} (call "read" {})]
    (is (true? error))
    (is (str/includes? content "missing required argument")))
  (is (true? (:error (call "nope" {})))))

(deftest specs-expose-every-base-tool
  (let [names (mapv #(get-in % [:function :name]) (tools/specs))]
    ;; Sorted, so this doubles as a check that the session-configure tool is
    ;; registered like any other -- it is only special in being marked for
    ;; approval, which is a property of the tool, not of the list.
    (is (= ["bash" "edit" "eval" "read" "session-configure" "write"] names))
    (is (every? #(seq (get-in % [:function :description])) (tools/specs)))))

(deftest a-bound-session-roots-relative-paths-at-its-project
  (let [pdir (str (System/getProperty "java.io.tmpdir") "/harness-tools-project")]
    (io/delete-file pdir true)
    (.mkdirs (io/file pdir))
    (project/bind! "tt-bound" pdir)
    (letfn [(call-as [name args]
              ;; run! binds *thread-id* to this thread around the tool body --
              ;; exactly what a real run does -- which is what makes the tools
              ;; consult the binding.
              (tools/run! {:function {:name name :arguments (json/write-str args)}}
                          "tt-bound"))]
      (testing "a relative write lands in the project directory"
        (let [{:keys [content error]} (call-as "write" {:path "marker.txt" :content "here"})]
          (is (false? error))
          (is (str/includes? content "marker.txt"))
          (is (= "here" (slurp (str (io/file pdir "marker.txt")) :encoding "UTF-8")))))
      (testing "a relative read reads from the project directory"
        (is (= "here" (:content (call-as "read" {:path "marker.txt"})))))
      (testing "an absolute path is not redirected"
        (is (= "here" (:content (call-as "read" {:path (str pdir "/marker.txt")})))))
      (testing "a relative edit re-roots too"
        (let [{:keys [error]} (call-as "edit" {:path "marker.txt"
                                               :old_string "here" :new_string "there"})]
          (is (false? error))
          (is (= "there" (slurp (str (io/file pdir "marker.txt")) :encoding "UTF-8")))))
      (testing "bash runs with the project directory as its cwd"
        ;; Proven WITHOUT parsing pwd: Git Bash prints POSIX-style paths
        ;; (/c/Users/...) where the JVM says C:\Users\..., so instead cat a
        ;; file that exists only in the project directory -- a relative cat
        ;; finding it proves exactly where bash was sitting.
        (let [{:keys [content error]} (call-as "bash" {:command "cat marker.txt"})]
          (is (false? error))
          (is (str/includes? content "there")))))
    (testing "an unbound thread still resolves relative to the process cwd"
      ;; The same relative read through a DIFFERENT thread: no binding, so the
      ;; path passes through unchanged and lands in the process cwd.
      (let [{:keys [content error]}
            (tools/run! {:function {:name "read"
                                    :arguments (json/write-str {:path "deps.edn"})}}
                        "tt-unbound")]
        (is (false? error))
        (is (str/includes? content "{:paths"))))))

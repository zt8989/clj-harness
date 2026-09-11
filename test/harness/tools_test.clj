(ns harness.tools-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
    (is (str/includes? (:content (call "bash" {:command "pwd"})) "lisp-harness")))
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
    (is (string? content))))

(deftest missing-args-and-unknown-tools-are-errors
  (let [{:keys [content error]} (call "read" {})]
    (is (true? error))
    (is (str/includes? content "missing required argument")))
  (is (true? (:error (call "nope" {})))))

(deftest specs-expose-all-five-tools
  (let [names (mapv #(get-in % [:function :name]) (tools/specs))]
    (is (= ["bash" "edit" "eval" "read" "write"] names))
    (is (every? #(seq (get-in % [:function :description])) (tools/specs)))))

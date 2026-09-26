(ns harness.infra.llm-debug-test
  "The LLM traffic log, driven through a REAL file under a fresh root -- the four
  things worth knowing are all properties of the writing rather than of a wrapper:
  that a call leaves exactly one JSON line, that the request line holds the body
  byte for byte, that the switch being off leaves no file at all, and that a home
  which cannot be written costs the line and not the run."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.infra.home :as home]
            [harness.infra.llm-debug :as llm-debug]
            [harness.test-support :as ts]))

(defn- fresh-root [label]
  (ts/temp-dir (str "llm-debug-test-" label)))

(defn- log-file [root] (io/file root "logs" llm-debug/file-name))

(defn- rotated-file [root] (io/file root "logs" llm-debug/rotated-name))

(defn- lines
  "Every line the traffic log currently holds, parsed. Empty when there is no file
  -- which is itself an assertion (`nothing-is-written-when-the-switch-is-off`)."
  [root]
  (let [f (log-file root)]
    (if (.exists f)
      (mapv #(json/read-str % :key-fn keyword)
            (remove str/blank? (str/split-lines (slurp f :encoding "UTF-8"))))
      [])))

(defn- with-log
  "Run BODY with the switch ON and the root at a fresh temp directory, bound to
  ROOT.

  THE ROOT BINDING IS NOT DECORATION, for the same reason harness.infra.log-test
  spells out: `home/root` is read on every call, so a test that did not move it
  would be writing into the developer's real home -- the one thing the whole test
  fixture exists to prevent."
  [label body]
  (let [root (fresh-root label)]
    (binding [home/*root-override* root
              llm-debug/*override* true]
      (body root))))

(deftest the-switch-names-the-spellings-of-on
  ;; THE VALUE IS THE WHOLE CONFIGURATION SURFACE, so the spellings are worth
  ;; pinning rather than being discovered by whoever sets the variable by hand.
  (testing "the four ways of saying yes, surrounding blanks and case aside"
    (doseq [v ["1" "true" "TRUE" "yes" "on" "  on  " "On"]]
      (is (true? (llm-debug/enabled-name? v)) (pr-str v))))
  (testing "and everything else is off -- including the values that look like a
            switch and are not one"
    (doseq [v ["0" "false" "no" "off" "" "  " "enabled" nil]]
      (is (false? (llm-debug/enabled-name? v)) (pr-str v)))))

(deftest the-override-decides-without-an-environment-variable
  ;; `System/getenv` cannot be written from inside a JVM, so the switch the tests
  ;; drive is the dynamic var -- and it must be the whole answer while it is bound,
  ;; or a developer who happens to have the variable set would see different
  ;; results from the same test.
  (binding [llm-debug/*override* false]
    (is (false? (llm-debug/enabled?))))
  (binding [llm-debug/*override* true]
    (is (true? (llm-debug/enabled?)))))

(deftest one-call-is-one-line-and-the-request-is-verbatim
  (with-log "request"
    (fn [root]
      (let [body "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"]
        (llm-debug/record! {:at :request :thread-id "t-1" :model "m"
                            :base-url "http://localhost:1/v1" :body body})
        (let [ls (lines root)]
          (is (= 1 (count ls)) "one call, one line")
          (let [line (first ls)]
            (is (= "request" (:at line)))
            (is (= "t-1" (:thread-id line)))
            (is (= "m" (:model line)))
            (is (= body (:body line))
                "THE BYTES, not a re-encoding of them -- the prefix cache keys on
                 these, so a second spelling would make the analysis a fiction")
            (is (number? (:ts line)) "stamped here, epoch millis")))))))

(deftest the-response-carries-what-the-vendor-said
  (with-log "response"
    (fn [root]
      (llm-debug/record! {:at :response :thread-id "t-1" :model "m"
                          :message {:role "assistant" :content "hi"}
                          :telemetry {:usage {:prompt_tokens 10
                                              :prompt_tokens_details {:cached_tokens 8}}}})
      (let [line (first (lines root))]
        (is (= "response" (:at line)))
        (is (= "hi" (get-in line [:message :content])))
        (testing "the cached-token cell the analysis exists for survives the encoding"
          (is (= 8 (get-in line [:telemetry :usage :prompt_tokens_details :cached_tokens]))))))))

(deftest nothing-is-written-when-the-switch-is-off
  (let [root (fresh-root "off")]
    (binding [home/*root-override* root
              llm-debug/*override* false]
      (llm-debug/record! {:at :request :thread-id "t-1" :body "{}"})
      (is (not (.exists (log-file root)))
          "an unset switch writes no file -- not an empty one, and not the directory"))))

(deftest the-file-is-capped-by-rotation-one-generation-deep
  ;; THE CAP IS A PARAMETER rather than `max-bytes`, because the alternative is a
  ;; test that writes 32 MB to watch one rename. What is being pinned is the DECISION
  ;; (past the cap, the live file becomes the previous generation), not the size.
  (with-log "rotate"
    (fn [root]
      (let [f (log-file root)]
        (.mkdirs (.getParentFile f))
        (spit f "a line that is long enough\n" :encoding "UTF-8")
        (let [cap (.length f)]
          (testing "under the cap nothing moves"
            (llm-debug/rotate-when-full! f (inc cap))
            (is (.exists f))
            (is (not (.exists (rotated-file root)))))
          (testing "at the cap the live file becomes the one previous generation"
            (llm-debug/rotate-when-full! f cap)
            (is (not (.exists f)))
            (is (.exists (rotated-file root))))
          (testing "and the generation after that replaces it, rather than accumulating"
            (spit f (apply str (repeat 200 "b")) :encoding "UTF-8")
            (llm-debug/rotate-when-full! f 1)
            (is (.exists (rotated-file root)))
            (is (= (apply str (repeat 200 "b")) (slurp (rotated-file root) :encoding "UTF-8"))
                "the previous generation was dropped, not kept beside a third file")))))))

(deftest a-home-that-cannot-be-written-costs-the-line-and-not-the-run
  ;; The wire path calls this. A read-only home, a full disk, a `logs` that is
  ;; somebody's FILE -- all of them are the line's problem, and none of them may
  ;; become the run's.
  (let [not-a-directory (ts/temp-dir "llm-debug-test-blocked")]
    (spit (io/file not-a-directory "logs") "in the way" :encoding "UTF-8")
    (reset! @#'llm-debug/warned false)
    (let [err (java.io.StringWriter.)]
      (binding [home/*root-override* not-a-directory
                llm-debug/*override* true
                *err* err]
        (is (nil? (llm-debug/record! {:at :request :thread-id "t-1" :body "{}"}))
            "record! answers nil and does not throw")
        (is (str/includes? (str err) llm-debug/file-name)
            "and it says which file it could not write")))
    ;; ONCE PER PROCESS, not once per call: a run makes a call every turn, and a
    ;; note per call would be a louder problem than the one it reports.
    (is (true? @@#'llm-debug/warned))))
(ns harness.cap.spill-test
  "Spill, asserted as ordinary function calls plus one real write under a temp home.

  Ticket 09 is the third way to shrink a request: a SINGLE just-produced tool result, moved out
  of the conversation at the moment it is produced, with a pickup slip left in its place. The
  properties pinned here are the ones the checklist names -- the slip carries a preview, an
  OPAQUE locator and a retrieval sentence; the original is retrievable where the slip says; the
  write is EXCLUSIVE; and a write that cannot be made leaves the result INLINE rather than
  turning a successful call into an error."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.cap.spill :as spill]
            [harness.infra.home :as home]
            [harness.test-support :as support]))

(defn- big [n c] (apply str (repeat n c)))

(defn- locator-of [slip]
  (second (re-find #"(?m)^Locator: (.+)$" slip)))

(deftest a-huge-result-becomes-a-slip-and-the-original-is-where-the-slip-says
  (let [body (big 50000 "x")]
    (binding [home/*root-override* (support/temp-dir "spill-unit")]
      (let [slip (spill/slip "t-spill" body)
            f    (io/file (locator-of slip))]
        (testing "the model reads a slip, not the giant text"
          (is (not= body slip))
          (is (str/includes? slip "spilled"))
          (is (str/includes? slip "use the read tool"))
          (is (str/includes? slip (big 100 "x")) "a preview of the head is inline"))
        (testing "and the whole original is retrievable where the slip says"
          (is (.exists f))
          (is (= body (slurp f :encoding "UTF-8"))))))))

(deftest a-small-result-is-left-alone
  (binding [home/*root-override* (support/temp-dir "spill-small")]
    (is (= "small" (spill/slip "t" "small")))
    (is (= "" (spill/slip "t" "")))
    (is (nil? (spill/slip "t" nil)))))

(deftest the-slip-is-built-from-the-locator-and-the-retrieval-sentence-not-from-a-path
  ;; THE LOCATOR IS OPAQUE: a backend may answer a URI or a key, and this layer must render it
  ;; rather than reach for a file. Two very different backends prove the point.
  (let [body (big 50000 "x")]
    (is (str/includes? (spill/slip "t" body (fn [_ _] {:locator "opaque://abc"
                                                       :retrieval "Fetch it with the fetch tool."}))
                       "opaque://abc"))
    (is (str/includes? (spill/slip "t" body (fn [_ _] {:locator "k1"
                                                       :retrieval "Ask the fetch tool for k1."}))
                       "Ask the fetch tool for k1.")
        "the retrieval sentence is what the model acts on")))

(deftest a-failing-backend-leaves-the-result-inline
  ;; BEST EFFORT: a record that cannot be written must NEVER break a tool call that succeeded.
  (binding [home/*root-override* (support/temp-dir "spill-fail")]
    (let [body (big 50000 "x")]
      (is (= body (spill/slip "t" body (fn [_ _] (throw (ex-info "disk full" {})))))
          "the original is returned unchanged, and nothing is thrown"))))

(deftest the-write-refuses-a-path-that-already-exists
  ;; EXCLUSIVE: CREATE_NEW refuses an existing path, so a file (or a symlink) somebody planted
  ;; is not followed and not written through.
  (binding [home/*root-override* (support/temp-dir "spill-excl")]
    (let [f (io/file (home/root) "spill" "t" "planted.txt")]
      (io/make-parents f)
      (spit f "mine" :encoding "UTF-8")
      (is (thrown? java.nio.file.FileAlreadyExistsException
                   (spill/write-new! f "theirs")))
      (is (= "mine" (slurp f :encoding "UTF-8")) "the planted file is untouched"))))

(deftest each-spill-gets-its-own-file
  (binding [home/*root-override* (support/temp-dir "spill-two")]
    (let [a (big 50000 "a")
          b (big 50000 "b")
          fa (io/file (locator-of (spill/slip "t" a)))
          fb (io/file (locator-of (spill/slip "t" b)))]
      (is (not= (.getPath fa) (.getPath fb)))
      (is (= a (slurp fa :encoding "UTF-8")))
      (is (= b (slurp fb :encoding "UTF-8"))))))

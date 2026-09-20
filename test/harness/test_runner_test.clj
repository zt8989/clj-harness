(ns harness.test-runner-test
  "The isolation verdict's three branches, driven directly.

  WHY THIS FILE EXISTS AT ALL, and why it is not just 'the runner ran and was
  green': `harness.test-runner/isolation-verdict` decides the ONE claim this repo
  makes about its own test runs -- that they touch the developer's home nowhere --
  and until this file it had exactly one witness, the happy path. The two branches
  below it are the interesting ones, and BOTH were seen in the wild before either
  had a test: a suite that resolved the real store (the failure), and a file moved
  by the live harness session while a green suite ran (the note).

  PURE INPUTS, which is the point of the verdict's shape: a File, two fingerprints
  and a set of paths. Nothing here opens a store, writes anything, or waits --
  which is also how a test can assert the failure branch WITHOUT doing the thing
  the failure is about."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [harness.test-runner :as runner]))

(def ^:private real-store
  ;; A PATH THAT IS NEVER OPENED, and one of the shapes a real home has. No file is
  ;; created: the verdict reads the fingerprints it is handed, so a path is enough.
  (io/file "/nowhere/the-developer/.clj-harness/harness.db"))

(defn- said
  "What F printed on the process's stderr, as one string."
  [f]
  (with-out-str (binding [*err* *out*] (f))))

(deftest nothing-happened-is-green-and-silent
  (is (true? (runner/isolation-verdict real-store [100 1] #{} [100 1]))
      "an untouched store, never opened here, is exactly what this fixture promises")
  (is (= "" (said #(runner/isolation-verdict real-store [100 1] #{} [100 1])))
      "and it says nothing: a green run has no note to print"))

(deftest a-store-this-process-opened-fails-the-run-by-name
  (testing "the file MOVED -- the plain case, and the one the fixture was built for"
    (let [path (.getAbsolutePath real-store)]
      (is (false? (runner/isolation-verdict real-store [100 1] #{path} [200 2])))
      (let [message (said #(runner/isolation-verdict real-store [100 1] #{path} [200 2]))]
        (is (str/includes? message "ISOLATION FAILURE"))
        (is (str/includes? message path) "the store's path, so the offender can be found")
        (is (str/includes? message "this process opened")
            "attributed to this process, which is the only thing it can be sure of"))))

  (testing "opened and NOT moved: a read into the wrong home is already the wrong home"
    ;; A suite that reads the developer's store is not hermetic even when it writes
    ;; nothing -- their rows, their config, their anchors would be part of the answer.
    ;; So this branch fails too, and says which of the two it is seeing.
    (let [path (.getAbsolutePath real-store)]
      (is (false? (runner/isolation-verdict real-store [100 1] #{path} [100 1])))
      (is (str/includes? (said #(runner/isolation-verdict real-store [100 1] #{path} [100 1]))
                         "unchanged"))))

  (testing "a DIFFERENT store than the one being asked about is not a hit"
    ;; The set is compared by absolute path, so the temp store this run actually
    ;; used cannot be mistaken for the developer's -- which is the whole reason the
    ;; set holds paths rather than, say, a count.
    (is (true? (runner/isolation-verdict real-store [100 1]
                                         #{(.getAbsolutePath (io/file "/tmp/x/harness.db"))}
                                         [200 2]))
        "somebody else's change, with our opening going to another path: a note, not a failure")))

(deftest somebody-elses-write-is-a-note-and-not-a-failure
  ;; THE CASE THAT MADE THIS VERDICT EXISTS: on 2026-09-20 a full suite run left the
  ;; developer's store byte-and-mtime identical, while a single anchored file read by
  ;; the LIVE HARNESS SESSION moved its mtime -- and the verdict, built from the file
  ;; alone, reported the neighbour as a violation. The run was green; the signal was
  ;; wrong. Now the file moving without an opening says so out loud and stays green.
  (is (true? (runner/isolation-verdict real-store [100 1] #{} [200 2])))
  (let [message (said #(runner/isolation-verdict real-store [100 1] #{} [200 2]))]
    (is (str/includes? message "ISOLATION NOTE"))
    (is (not (str/includes? message "ISOLATION FAILURE")))
    (is (str/includes? message "NEVER OPENED IT")
        "it names the one fact it can stand behind: this process did not go there")
    (is (str/includes? message "NOT a failure")))

  (testing "a store that APPEARED during the run is the same kind of news"
    (is (true? (runner/isolation-verdict real-store nil #{} [100 1]))
        "nil is absent: a store created by somebody else's session is still not ours")))

(deftest the-record-of-what-this-process-opened-is-what-decides-it
  ;; The two halves meet here: harness.infra.db REMEMBERS the paths, and the verdict
  ;; READS them. If the remembering ever stopped, this verdict would silently become
  ;; the file-only check it was -- so the seam is asserted rather than assumed.
  (testing "opening a store records its path, and the record can be emptied"
    (db/forget-store-paths-opened!)
    (is (= [] (sort (db/store-paths-opened))) "empty to start with, as the runner leaves it")
    (db/select "SELECT 1") ;; whatever the root in force is, this opens THAT store
    (is (contains? (db/store-paths-opened) (.getAbsolutePath (home/db-file)))
        "the store this process just opened is in the set")
    (db/forget-store-paths-opened!)
    (is (= [] (sort (db/store-paths-opened))) "and the runner's clearing call is what it says"))
  (db/forget-store-paths-opened!))

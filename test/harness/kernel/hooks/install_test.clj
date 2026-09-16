(ns harness.kernel.hooks.install-test
  "The hook engine's install door, asserted directly: nothing installed means no
  files and no built-in rows, installing each contribution makes exactly its rows
  appear, and a teardown leaves the table as it found it.

  WHY THE EMPTINESS MATTERS HERE TOO. The three sources this engine folds used to be
  half require-and-it-happens: the kernel's own rows were registered as a side effect
  of harness.cap.system-prompt being LOADED, and the files were read by a function
  that required harness.cap.project. So 'what is in the table' depended on what else
  happened to be loaded, and a test could not say 'the kernel's own rows' without
  also getting whatever the load graph dragged in. Both halves arrive through the door
  now, which is what makes the assertions below sayable.

  NOTHING HERE USES THE with-builtins FIXTURE, deliberately: the first assertion is
  about a table with NOTHING installed, so this namespace must not install anything
  until it means to."
  (:require [clojure.test :refer [deftest is testing]]
            [harness.cap.hooks :as cap-hooks]
            [harness.cap.system-prompt :as system-prompt]
            [harness.kernel.hooks :as hooks]))

(defn- ids-at
  "The ids in force at POINT for THREAD-ID, in order."
  [point thread-id]
  (->> (vals (hooks/effective-hooks thread-id))
       (filter #(= point (:point %)))
       (sort-by #(get-in % [:order-index] 0))
       (mapv :id)))

(deftest an-empty-table-when-nothing-is-installed
  (testing "no built-in rows, no files -- the state a fresh kernel is in"
    (is (= [] (ids-at :system-prompt "t-empty"))
        "the kernel's own three rows are installed, not registered at load")
    (is (= {} (hooks/config "t-empty"))
        "and no layer has installed a reader, so there are no files to read")))

(deftest the-kernel-rows-arrive-and-leave-as-one-layer
  (let [before (hooks/effective-hooks "t-rows")
        td     (system-prompt/install!)]
    (try
      (testing "installing makes exactly the three rows appear, named as they were"
        (is (= ["builtin:tools" "builtin:project" "builtin:provider"]
               (ids-at :system-prompt "t-rows")))
        (is (every? #(= :built-in (:source (get (hooks/effective-hooks "t-rows") %)))
                    ["builtin:tools" "builtin:project" "builtin:provider"])))
      (testing "and they are switchable like any other row, which is the point of them being rows"
        (hooks/session-disable! "t-rows" "builtin:tools")
        (is (true? (:disabled? (get (hooks/effective-hooks "t-rows") "builtin:tools"))))
        (hooks/session-enable! "t-rows" "builtin:tools"))
      (finally (td)))
    (testing "the teardown leaves the table as it found it"
      (is (= before (hooks/effective-hooks "t-rows"))))))

(deftest the-file-source-arrives-and-leaves-as-one-layer
  (let [before (hooks/effective-hooks "t-files")
        td     (cap-hooks/install!)]
    (try
      (testing "with the reader installed, a written hooks.edn is what the engine reads"
        (is (map? (hooks/config "t-files")))
        (is (= (cap-hooks/config "t-files") (hooks/config "t-files"))
            "the kernel's relay answers exactly what the reader answers"))
      (finally (td)))
    (testing "and withdrawing the reader takes the files away again"
      (is (= {} (hooks/config "t-files")))
      (is (= before (hooks/effective-hooks "t-files"))))))

(deftest both-halves-are-independent-layers
  ;; Two contributions from two namespaces, and each teardown withdraws only its own:
  ;; that is the layering claim, and it is what lets a test install the rows without
  ;; also getting a file reader it never asked for.
  (let [rows (system-prompt/install!)
        files (cap-hooks/install!)]
    (try
      (is (= 3 (count (ids-at :system-prompt "t-both"))))
      (files)
      (testing "withdrawing the file reader leaves the kernel's own rows"
        (is (= 3 (count (ids-at :system-prompt "t-both"))))
        (is (= {} (hooks/config "t-both"))))
      (finally (rows)))
    (is (= [] (ids-at :system-prompt "t-both")))))

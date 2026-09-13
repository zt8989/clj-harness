(ns harness.project-test
  "harness.project's external behavior: bind validates, resolve roots relative
  paths at the binding, out-of-bounds? answers the fence's containment question
  -- and, the regression guarantee, an unbound session is the identity function
  on paths and never out of bounds."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.home :as home]
            [harness.project :as project])
  (:import (java.io File)))

(def ^:private root (str (System/getProperty "java.io.tmpdir") "/harness-project-test"))

;; One scratch directory for the whole namespace; start it clean at load time
;; so test ordering cannot break it (the tools-test precedent).
(io/delete-file root true)
(.mkdirs (io/file root))
(spit (str root "/file.txt") "plain file")

(defn- tmp [name] (str root "/" name))

(deftest bind-validates-the-directory-before-binding
  (testing "a path that does not exist is a NAMED error"
    (is (thrown-with-msg? Exception #"no such directory"
                          (project/bind! "pt-missing" (tmp "nope")))))
  (testing "a path that exists but is a file is refused too"
    (is (thrown-with-msg? Exception #"not a directory"
                          (project/bind! "pt-file" (tmp "file.txt")))))
  (testing "neither refusal left a binding behind"
    (is (nil? (project/binding-for "pt-missing")))
    (is (nil? (project/binding-for "pt-file")))))

(deftest bind-stores-an-absolute-path-and-answers-it-back
  (let [abs (project/bind! "pt-abs" root)]
    (is (.isAbsolute (io/file abs)))
    (is (= abs (project/binding-for "pt-abs")))
    ;; Relative input, absolute answer: the stored value does not depend on
    ;; where the caller was sitting when they typed it. "test" is a directory
    ;; of this repo -- the JVM's working directory during tests -- so a plain
    ;; relative path is enough to pin the resolve-against-cwd behavior.
    (let [rel (project/bind! "pt-rel" "test")]
      (is (.isAbsolute (io/file rel)))
      (is (= (str (.getAbsoluteFile (io/file "test"))) rel)))))

(deftest unbound-is-the-explicit-no-binding-answer
  (testing "nil, not an error -- most sessions are unbound"
    (is (nil? (project/binding-for "pt-never-bound"))))
  (testing "and resolve is the identity, relative or absolute"
    (let [abs (System/getProperty "user.dir")]
      (is (= "deps.edn"  (project/resolve-path "pt-never-bound" "deps.edn")))
      (is (= abs         (project/resolve-path "pt-never-bound" abs))))))

(deftest relative-paths-resolve-into-the-project
  (project/bind! "pt-rooted" root)
  (testing "a relative path lands inside the project directory"
    (is (= (str (io/file root "a.txt"))
           (project/resolve-path "pt-rooted" "a.txt")))
    (is (= (str (io/file root "sub" "b.txt"))
           (project/resolve-path "pt-rooted" "sub/b.txt"))))
  (testing "an absolute path passes through untouched"
    (let [abs (System/getProperty "user.dir")]
      (is (= abs (project/resolve-path "pt-rooted" abs)))))
  (testing "the binding answers for its thread and nobody else"
    (is (nil? (project/binding-for "pt-other")))))

(deftest rebinding-moves-the-root
  (let [first-root (project/bind! "pt-move" root)]
    (is (= (str (io/file first-root "x.txt"))
           (project/resolve-path "pt-move" "x.txt")))
    ;; A second directory, bound over the first: the last bind wins.
    (let [second (tmp "rebound")]
      (.mkdirs (io/file second))
      (let [now (project/bind! "pt-move" second)]
        (is (= (str (io/file now "x.txt"))
               (project/resolve-path "pt-move" "x.txt")))))))

(deftest a-binding-can-be-dropped
  (project/bind! "pt-drop" root)
  (is (some? (project/binding-for "pt-drop")))
  (project/bind! "pt-drop" nil)
  (is (nil? (project/binding-for "pt-drop")))
  (testing "and a dropped session resolves paths unchanged again"
    (is (= "a.txt" (project/resolve-path "pt-drop" "a.txt")))))

(deftest the-no-session-slot-behaves-like-any-unbound-thread
  ;; The nil-thread-id arity mirrors the rest of the session-scoped surface:
  ;; offline tools and replay run outside a session and get the identity.
  (is (nil? (project/binding-for nil)))
  (is (= "deps.edn" (project/resolve-path "deps.edn"))))

(deftest resolved-paths-are-usable-not-just-displayed
  ;; The point of resolve-path is that the result opens the right file.
  (project/bind! "pt-real" root)
  (spit (project/resolve-path "pt-real" "real.txt") "landed" :encoding "UTF-8")
  (is (= "landed" (slurp (str (io/file root "real.txt")) :encoding "UTF-8"))))

(deftest out-of-bounds-answers-the-containment-question
  ;; The fence's boolean, unit-level. Allowed set: the project directory and
  ;; the configuration home; everything else -- including lookalike siblings
  ;; and .. escapes -- is out. The config home is exercised through the REAL
  ;; (test-runner-seeded) root, since allowing it is the fence's deliberate
  ;; carve-out: reading one's own config must not be an approval offense.
  (let [outside (str (System/getProperty "java.io.tmpdir")
                     "/harness-project-outside.txt")]
    (testing "an unbound session is never out of bounds -- the regression guarantee"
      (is (false? (project/out-of-bounds? "pt-fence" outside)))
      (is (false? (project/out-of-bounds? "pt-fence" "anything.txt"))))
    (project/bind! "pt-fence" root)
    (testing "inside the project: relative and absolute, both allowed"
      (is (false? (project/out-of-bounds? "pt-fence" "in.txt")))
      (is (false? (project/out-of-bounds? "pt-fence" (str (io/file root "in.txt")))))
      (is (false? (project/out-of-bounds? "pt-fence" "sub/in.txt"))))
    (testing "the configuration home is allowed, root itself included"
      (is (false? (project/out-of-bounds? "pt-fence" (str (io/file (home/root) "config.edn")))))
      (is (false? (project/out-of-bounds? "pt-fence" (home/root)))))
    (testing "outside both allowed roots is out of bounds"
      (is (true?  (project/out-of-bounds? "pt-fence" outside)))
      (is (true?  (project/out-of-bounds? "pt-fence" "../escape.txt")))
      (is (true?  (project/out-of-bounds? "pt-fence" (str (io/file root ".." "escape.txt"))))))
    (testing "a sibling whose name extends the project's is not confused with it"
      ;; C:\\proj must not contain C:\\project2: the separator boundary.
      (is (true? (project/out-of-bounds? "pt-fence"
                                         (str (str root "2") File/separator "f.txt")))))
    (testing "the answer follows the CURRENT binding -- drop it, fence off"
      (project/bind! "pt-fence" nil)
      (is (false? (project/out-of-bounds? "pt-fence" outside))))))

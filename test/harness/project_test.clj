(ns harness.project-test
  "harness.project's external behavior: bind validates, resolve roots relative
  paths at the binding, out-of-bounds? answers the fence's containment question
  -- and, the regression guarantee, an unbound session is the identity function
  on paths and never out of bounds."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
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

;; Tests in this namespace write a USER-level harness.edn into the (shared,
;; test-runner-owned) config home. Wipe it around every test: a leftover would
;; leak into the NEXT namespace's fence assertions (http-test runs after this
;; one) as a silent strict mode -- the exact "config that says nothing vs a
;; config that was ignored" confusion the reader refuses to conflate.
;; A fixture is (fn [f] ... (f) ...): it RUNS f between its own two halves.
;; Wrapping the whole body in another (fn []) would swallow f -- every test
;; silently skipped, :test 0, no error anywhere (measured, not guessed).
(defn- wipe-user-harness-edn [f]
  (io/delete-file (io/file (home/root) "harness.edn") true)
  ;; The project-level file lives under the SHARED root, which tmpdir
  ;; keeps across JVM runs: a leftover strict/allow from the last run
  ;; would silently move this run's fence. Same wipe before and after.
  (io/delete-file (io/file root ".harness" "harness.edn") true)
  (f)
  (io/delete-file (io/file (home/root) "harness.edn") true)
  (io/delete-file (io/file root ".harness" "harness.edn") true))

(use-fixtures :each wipe-user-harness-edn)

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

(defn- write-project-harness!
  "Drop an EDN string at the project's .harness/harness.edn. SLASHED paths in
  the EDN: io/File accepts forward slashes on Windows, and EDN strings would
  need the backslashes escaped anyway."
  [edn]
  (let [f (io/file root ".harness" "harness.edn")]
    (.mkdirs (.getParentFile f))
    (spit f edn :encoding "UTF-8")
    f))

(deftest harness-config-assembles-two-levels
  ;; User level = the config home's harness.edn; project level = the bound
  ;; project's .harness/harness.edn. Top-level shallow merge, project wins.
  (let [user-file (io/file (home/root) "harness.edn")]
    (project/bind! "pt-cfg" root)
    (testing "missing at both levels is the empty map -- and a .harness dir
              without the file is just as empty"
      (.mkdirs (io/file root ".harness"))
      (is (= {} (project/harness-config "pt-cfg"))))
    (testing "the user level alone answers the user level"
      (spit user-file "{:approval {:allow [\"shared\"]} :other 1}" :encoding "UTF-8")
      (is (= {:approval {:allow ["shared"]} :other 1}
             (project/harness-config "pt-cfg")))
      (testing "an UNBOUND thread sees only the user level, even while another
                thread is bound to a project with its own file"
        (write-project-harness! "{:approval {:strict true}}")
        (is (= {:approval {:allow ["shared"]} :other 1}
               (project/harness-config)))))
    (testing "the project level alone answers the project level"
      (io/delete-file user-file true)
      (is (= {:approval {:strict true}} (project/harness-config "pt-cfg"))))
    (testing "both levels: the project REPLACES the user's top-level keys whole"
      (spit user-file "{:approval {:allow [\"shared\"]} :other 1}" :encoding "UTF-8")
      ;; :approval is replaced entirely (no deep merge, no union); :other,
      ;; which the project does not mention, survives the merge.
      (is (= {:approval {:strict true} :other 1}
             (project/harness-config "pt-cfg"))))))

(deftest a-broken-harness-edn-is-a-named-failure-not-a-silent-empty
  (let [user-file (io/file (home/root) "harness.edn")]
    (project/bind! "pt-bad" root)
    (testing "invalid EDN at the user level names the absolute path"
      (spit user-file "{:approval " :encoding "UTF-8")
      (let [e (try (project/harness-config "pt-bad") nil (catch Exception e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) (.getAbsolutePath user-file)))
        (is (= :invalid-edn (:reason (ex-data e))))))
    (testing "invalid EDN at the user level breaks even an UNBOUND query"
      (is (thrown-with-msg? Exception #"not valid EDN" (project/harness-config))))
    (testing "valid EDN that is not a map is broken too, project level"
      (io/delete-file user-file true)
      (let [pf (write-project-harness! "42")]
        (let [e (try (project/harness-config "pt-bad") nil (catch Exception e e))]
          (is (some? e))
          (is (str/includes? (ex-message e) (.getAbsolutePath pf)))
          (is (= :not-a-map (:reason (ex-data e)))))))))

(deftest the-fence-obeys-harness-edn
  ;; The first real consumer of the assembly: the approval boundary.
  (let [outside (str (System/getProperty "java.io.tmpdir")
                     "/harness-project-outside2.txt")]
    (.mkdirs (io/file root ".harness"))
    (project/bind! "pt-fence-cfg" root)
    (testing ":allow frees a declared path, resolved like any tool path"
      (write-project-harness! "{:approval {:allow [\"../shared\"]}}")
      (is (false? (project/out-of-bounds? "pt-fence-cfg"
                                          (str (io/file root ".." "shared" "x.txt")))))
      (is (true? (project/out-of-bounds? "pt-fence-cfg" outside))
          "only what was declared is freed, not the world"))
    (testing ":strict tightens the fence over the project itself, config home kept"
      (write-project-harness! "{:approval {:strict true}}")
      (is (true? (project/out-of-bounds? "pt-fence-cfg" "in.txt"))
          "a project-relative path is out of bounds under strict")
      (is (false? (project/out-of-bounds? "pt-fence-cfg"
                                          (str (io/file (home/root) "config.edn"))))
          "the configuration home is never tightened away"))
    (testing "the file is read fresh per call: edits move the fence live"
      (write-project-harness! "{}")
      (is (false? (project/out-of-bounds? "pt-fence-cfg" "in.txt"))))))

(deftest cwd-changed-is-the-hook-payload-shape
  ;; The CwdChanged event source (ticket 04): the facts a P2 hook engine will
  ;; consume verbatim at the binding-change point. Locked here so the shape
  ;; cannot drift between this ticket and the hook wiring; what to DO with
  ;; the event is the hook engine's business, not this namespace's.
  (is (= {:hook "CwdChanged" :thread_id "t" :project_dir "/b" :before "/a"}
         (project/cwd-changed "t" "/a" "/b")))
  (is (nil? (:before (project/cwd-changed "t" nil "/b")))
      "a first bind has no previous directory")
  (is (= "/b" (:project_dir (project/cwd-changed "t" "/a" "/b")))
      "project_dir is where the working directory moved TO"))

(deftest the-fence-lets-a-skill-reach-its-own-reference-files
  ;; A skill body routinely says "read references/x.md", and that path resolves
  ;; NEXT TO THE SKILL -- outside the project. Without this allowance every
  ;; reference file would park a human, which is the same as making the skill
  ;; unusable. The roots get the configuration home's standing, and no other.
  (let [proj (str (System/getProperty "java.io.tmpdir")
                  "/harness-project-skills-" (System/nanoTime))
        skill-dir (str (io/file (home/user-home) ".agents" "skills" "alpha"))
        elsewhere (str (System/getProperty "java.io.tmpdir")
                       "/harness-project-elsewhere-" (System/nanoTime))]
    (.mkdirs (io/file proj))
    (.mkdirs (io/file skill-dir))
    (.mkdirs (io/file elsewhere))
    (project/bind! "pt-skills" proj)
    ;; This test's project is its own directory, so its harness.edn goes THERE --
    ;; write-project-harness! targets the namespace's shared root, which this
    ;; thread is not bound to.
    (let [proj-edn! (fn [text]
                      (.mkdirs (io/file proj ".harness"))
                      (spit (str (io/file proj ".harness" "harness.edn")) text :encoding "UTF-8"))]
    (try
      (testing "a file inside a skill root is free of the fence"
        (is (false? (project/out-of-bounds? "pt-skills"
                                            (str (io/file skill-dir "references" "x.md"))))))

      (testing "and it stays free under :strict, exactly like the config home"
        (proj-edn! "{:approval {:strict true}}")
        (is (false? (project/out-of-bounds? "pt-skills" (str (io/file skill-dir "x.md")))))
        (proj-edn! "{}"))

      (testing "but the world outside the roots still parks -- the allowance is the roots, not everything"
        (is (true? (project/out-of-bounds? "pt-skills" (str (io/file elsewhere "x.md"))))))

      (testing "and a CONFIGURED root is what is allowed, not a hardcoded pair"
        (let [custom (str (io/file (System/getProperty "java.io.tmpdir")
                                   (str "harness-custom-skills-" (System/nanoTime))))]
          (.mkdirs (io/file custom))
          (proj-edn! (str "{:skills {:roots [\"" custom "\"]}}"))
          (is (false? (project/out-of-bounds? "pt-skills" (str (io/file custom "x.md")))))
          (is (true? (project/out-of-bounds? "pt-skills" (str (io/file skill-dir "x.md"))))
              "the default root is no longer in force, so it is no longer free")
          (proj-edn! "{}")))

      (testing "an INSTRUCTION file's content grants nothing: a path it merely mentions still parks"
        ;; The point of the boundary. AGENTS.md is READ BY harness, not by the
        ;; `read` tool -- and a document that could widen the fence would be a
        ;; capability granting itself, which is a different security story.
        (spit (str (io/file (home/user-home) "AGENTS.md"))
              (str "Go read " elsewhere "/notes.md\n") :encoding "UTF-8")
        (is (true? (project/out-of-bounds? "pt-skills" (str (io/file elsewhere "notes.md"))))))

      (finally
        (project/bind! "pt-skills" nil)
        (doseq [d [proj skill-dir elsewhere]]
          (io/delete-file (io/file d) true)))))))

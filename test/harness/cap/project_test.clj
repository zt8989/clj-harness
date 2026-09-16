(ns harness.cap.project-test
  "harness.cap.project's external behavior: bind validates, resolve roots relative
  paths at the binding, out-of-bounds? answers the fence's containment question
  -- and, the regression guarantee, an unbound session is the identity function
  on paths and never out of bounds.

  Since the sqlite ticket the binding is a ROW in the home's store rather than an
  atom entry, so this namespace also carries the tests that would fail if
  somebody reintroduced a cache: the second half of the file asks the store
  through a FOREIGN connection and checks that the answers track the file."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.infra.home :as home]
            [harness.cap.project :as project])
  (:import (java.io File)
           (java.sql DriverManager)))

(def ^:private root (str (System/getProperty "java.io.tmpdir") "/harness-project-test"))

;; One scratch directory for the whole namespace; start it clean at load time
;; so test ordering cannot break it (the tools-test precedent).
(io/delete-file root true)
(.mkdirs (io/file root))
(spit (str root "/file.txt") "plain file")

(defn- tmp [name] (str root "/" name))

(defn- canonical-of
  "The identity of a scratch directory, as the store keys it. Derived the way the
  server derives it rather than hard-coded, so a change to the canonical rule
  shows up as a failing test instead of a test that agrees with itself."
  [dir]
  (.getCanonicalPath (io/file dir)))

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

;; ------------------------------------------- the binding is a ROW, not an atom
;;
;; The ticket's reason for existing: a binding that outlives the process. These
;; tests are written so that a CACHE would fail them -- the atom version would,
;; and so would any in-process copy somebody adds later to save a query.

(defn- foreign-rows
  "Rows of SQL read through a connection the TEST owns, with none of
  harness.infra.db's settings -- an outside program's view of the store's file."
  [sql]
  (with-open [c (DriverManager/getConnection (str "jdbc:sqlite:" (.getAbsolutePath (home/db-file))))]
    (with-open [st (.createStatement c)
                rs (.executeQuery st sql)]
      (loop [acc []]
        (if-not (.next rs)
          acc
          (let [n (.getColumnCount (.getMetaData rs))]
            (recur (conj acc (mapv #(.getObject rs (int %)) (range 1 (inc n)))))))))))

(defn- foreign-exec!
  "Write to the store's file from outside harness.infra.db entirely."
  [sql]
  (with-open [c (DriverManager/getConnection (str "jdbc:sqlite:" (.getAbsolutePath (home/db-file))))]
    (with-open [st (.createStatement c)]
      (.execute st sql))))

(deftest a-binding-outlives-the-process-that-made-it
  (testing "the binding is IN the store's file -- an outside reader can see it"
    (project/bind! "pr-durable" root)
    (let [rows (foreign-rows "SELECT s.path FROM sessions s
                                JOIN projects p ON p.id = s.project_id
                               WHERE s.id = 'pr-durable'")]
      (is (= 1 (count rows)) "one row, on disk, readable without this namespace")
      (is (= (project/binding-for "pr-durable") (first (first rows)))
          "and it is the same string this namespace answers with")))
  (testing "nothing is cached in memory: change the row behind the door, and
            the next question answers the NEW value"
    ;; This is what 'the binding survives a restart' reduces to once there is no
    ;; in-memory state to lose. An atom (or any cache added later to save a
    ;; query) fails here by answering the old path.
    (foreign-exec! "UPDATE sessions SET path = '/moved/by/somebody/else'
                     WHERE id = 'pr-durable'")
    (is (= "/moved/by/somebody/else" (project/binding-for "pr-durable"))
        "the answer tracks the file, not a copy of the file")))

(defn- in-a-fresh-jvm
  "Run FORM -- a string of Clojure -- in a NEW JVM whose home is DIR, and return
  {:exit :out}.

  A real second process, not a second `with-redefs`: what a restart has to prove
  is that a store written by one JVM opens, migrates and answers in another, and
  that is precisely the part an in-process test cannot see. The identity stamp,
  the WAL switch and the migration walk all run again in there. This process
  needs no api-key and no model -- the form is ordinary Clojure -- so the child
  is a plain JVM.

  DIR travels as the ENVIRONMENT VARIABLE, never inside FORM: the repo's standing
  rule is that no byte crosses a process boundary without an explicit charset
  (deps.edn's comment block -- this machine's default is GBK), and a path
  interpolated into an argv string would be exactly that fault. `clojure` is
  looked up on PATH rather than pinned, the way the UI suite spawns it; a missing
  CLI is a broken environment, not a case to skip."
  [^File dir ^String form]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec ["clojure" "-M" "-e" form]))
             (.directory (io/file (System/getProperty "user.dir")))
             (.redirectErrorStream true))]
    (.put (.environment pb) "CLJ_HARNESS_HOME" (.getAbsolutePath dir))
    (let [p (.start pb)
          out (slurp (.getInputStream p) :encoding "UTF-8")]
      (when-not (.waitFor p 120 java.util.concurrent.TimeUnit/SECONDS)
        (.destroyForcibly p)
        (throw (ex-info "a fresh JVM did not finish in 120s" {:form form})))
      {:exit (.exitValue p) :out out})))

(deftest a-binding-survives-a-real-restart
  ;; THE ticket's reason for existing, and the one claim no in-process test can
  ;; make: bind here, then let a brand-new JVM answer for the same home.
  ;;
  ;; The child derives the directory from its OWN environment variable, which is
  ;; also what makes the second half a genuine test: it is told a home, not a
  ;; project, and has to find the binding the first process left there.
  (let [dir  (io/file (System/getProperty "java.io.tmpdir")
                      (str "harness-project-restart-" (System/nanoTime)))
        proj (io/file dir "workspace")]
    (.mkdirs proj)
    (try
      (let [write (in-a-fresh-jvm dir "(require '[harness.cap.project :as p]) (println (p/bind! \"restart-thread\" (str (System/getenv \"CLJ_HARNESS_HOME\") \"/workspace\")))")
            read  (in-a-fresh-jvm dir "(require '[harness.cap.project :as p]) (println (pr-str (p/binding-for \"restart-thread\")))")]
        (testing "the first process bound the directory and said so"
          (is (zero? (:exit write)) (str "first JVM failed:\n" (:out write)))
          (is (= (.getAbsolutePath proj) (str/trim (:out write)))))
        (testing "a SECOND process, which never saw the binding, answers it"
          (is (zero? (:exit read)) (str "second JVM failed:\n" (:out read)))
          (is (= (str "\"" (.getAbsolutePath proj) "\"") (str/trim (:out read)))
              "the binding was read back out of the store by a fresh JVM")))
      (finally
        (doseq [f (reverse (file-seq dir))] (io/delete-file f true))))))

(deftest a-relative-spelling-is-the-same-project-as-an-absolute-one
  ;; The last of the three spellings the ticket names. `..` and symlinks are
  ;; covered above; a RELATIVE path is the one that also has to survive being
  ;; resolved against the process's working directory before it can be compared
  ;; with anything.
  (let [canon (fn [p] (.getCanonicalPath (io/file p)))]
    (testing "binding a directory by a path relative to the cwd"
      ;; "test" is a directory of this repo and the JVM's cwd during a test run,
      ;; which is the same fixture the pre-existing relative-path test uses.
      (project/bind! "pr-rel" "test")
      (is (= (canon "test") (canon (project/binding-for "pr-rel")))
          "a relative bind lands on the directory it names")
      (is (= 1 (count (filter #(= (canon "test") (:canonical-path %)) (project/projects))))
          "and it did not create a second row for a directory already known"))))

(deftest the-same-directory-is-one-project-whatever-it-is-called
  ;; The ticket's naming rule: identity is the CANONICAL path, so every honest
  ;; spelling of one directory lands in one row. `binding-for` then answers the
  ;; spelling each caller used -- both halves matter, and they pull in opposite
  ;; directions, which is why they are asserted together.
  ;;
  ;; Assertions here are SCOPED to this test's own directory: the store is the
  ;; whole test-runner home's, and earlier namespaces (tools, approval) have
  ;; already put their own projects in it.
  (let [canon (fn [p] (.getCanonicalPath (io/file p)))
        mine  (fn [] (filter #(= (canon root) (:canonical-path %)) (project/projects)))]
    (.mkdirs (io/file root "sub"))
    (let [plain  (project/bind! "pr-dedup-1" root)
          dotted (project/bind! "pr-dedup-2" (str root "/sub/.."))]
      (testing "two spellings of one directory are one project row"
        (is (= 1 (count (mine)))
            "one row for that directory, not one per spelling")
        (is (= (:id (first (mine)))
               (:project-id (first (filter #(= "pr-dedup-2" (:id %)) (project/sessions)))))
            "and both sessions point at it"))
      (testing "each session is still answered its OWN spelling"
        (is (= plain (project/binding-for "pr-dedup-1")))
        (is (= dotted (project/binding-for "pr-dedup-2")))
        (is (not= plain dotted) "the two spellings really are different strings")))))

(deftest a-symlinked-directory-is-the-same-project-as-its-target
  (let [alias (io/file root "link-to-root")
        canon (fn [p] (.getCanonicalPath (io/file p)))]
    (let [created? (try (java.nio.file.Files/createSymbolicLink
                         (.toPath alias) (.toPath (io/file root))
                         (make-array java.nio.file.attribute.FileAttribute 0))
                        true
                        (catch Exception _ false))]
      (when created?
        (project/bind! "pr-sym" (str alias))
        (is (= (canon alias) (canon root)) "the fixture really is a symlink to the project")
        (is (= 1 (count (filter #(= (canon root) (:canonical-path %))
                                (project/projects))))
            "binding through a symlink did not create a second project")
        (is (= (str alias) (project/binding-for "pr-sym"))
            "while the session still reports the path it was given")))))

(deftest two-sessions-under-one-directory-move-independently
  (let [dir-a (tmp "pr-indep-a")
        dir-b (tmp "pr-indep-b")]
    (.mkdirs (io/file dir-a))
    (.mkdirs (io/file dir-b))
    (project/bind! "pr-ind-1" dir-a)
    (project/bind! "pr-ind-2" dir-a)
    (testing "two sessions, one project"
      (is (= (project/binding-for "pr-ind-1") (project/binding-for "pr-ind-2"))))
    (testing "rebinding one leaves the other alone"
      (project/bind! "pr-ind-1" dir-b)
      (is (= (str (.getAbsoluteFile (io/file dir-b))) (project/binding-for "pr-ind-1")))
      (is (= (str (.getAbsoluteFile (io/file dir-a))) (project/binding-for "pr-ind-2"))))
    (testing "and dropping one leaves the other alone"
      (project/bind! "pr-ind-1" nil)
      (is (nil? (project/binding-for "pr-ind-1")))
      (is (= (str (.getAbsoluteFile (io/file dir-a))) (project/binding-for "pr-ind-2"))))))

(deftest one-sessions-flags-are-its-own
  ;; "Two sessions under one directory do not interfere", the other half. The
  ;; VERB belongs to the archive ticket; what this one owes is that the state is
  ;; per-session, so that when the verb lands it cannot reach across rows. Writing
  ;; the flag directly is the point: it tests the row, not the (absent) API.
  (let [dir-a (tmp "pr-flags-a")
        dir-b (tmp "pr-flags-b")]
    (.mkdirs (io/file dir-a))
    (.mkdirs (io/file dir-b))
    (project/bind! "pr-flag-1" dir-a)
    (project/bind! "pr-flag-2" dir-a)
    (project/bind! "pr-flag-3" dir-b)
    (foreign-exec! "UPDATE sessions SET archived = 1 WHERE id = 'pr-flag-2'")
    (let [by-id (into {} (map (juxt :id identity) (project/sessions)))]
      (testing "the flagged session is flagged, and its sibling is not"
        (is (true?  (:archived? (by-id "pr-flag-2"))))
        (is (false? (:archived? (by-id "pr-flag-1"))))
        (is (false? (:archived? (by-id "pr-flag-3")))))
      (testing "a flag does not move a session between projects"
        (is (= (str (.getAbsoluteFile (io/file dir-a))) (project/binding-for "pr-flag-1")))
        (is (= (str (.getAbsoluteFile (io/file dir-a))) (project/binding-for "pr-flag-2")))
        (is (= (str (.getAbsoluteFile (io/file dir-b))) (project/binding-for "pr-flag-3"))))
      (testing "nor does a rebind of its sibling disturb it"
        (project/bind! "pr-flag-1" dir-b)
        (is (true? (:archived? (first (filter #(= "pr-flag-2" (:id %)) (project/sessions))))))))))

(deftest the-one-arity-bind-is-refused-by-name
  ;; The old `(bind! dir)` wrote into the no-session slot. A row keyed by nothing
  ;; is a row this schema will not hold, so the signature stays and the call is
  ;; refused with a reason -- rather than being deleted, which would turn a
  ;; caller's mistake into an arity error somewhere unrelated.
  (let [err (try (project/bind! root) nil (catch Exception e e))]
    (is (some? err))
    (is (= :no-session-slot (:reason (ex-data err))))
    (is (str/includes? (ex-message err) "thread id"))))

(deftest dropping-a-binding-keeps-the-project-and-the-session
  (let [dir (tmp "pr-keep")]
    (.mkdirs (io/file dir))
    (project/bind! "pr-keep" dir)
    (let [before (project/projects)]
      (project/bind! "pr-keep" nil)
      (testing "the session is still known -- only its project link is gone"
        (is (nil? (project/binding-for "pr-keep")))
        (let [row (first (filter #(= "pr-keep" (:id %)) (project/sessions)))]
          (is (some? row) "the row survives the unbind")
          (is (nil? (:project-id row)) "with no project")
          (is (false? (:archived? row)) "and its flag is untouched")))
      (testing "the project is still known -- one unbind does not remove a directory"
        (is (= (:id (first before)) (:id (first (project/projects)))))
        (is (= (count before) (count (project/projects))))))))

(deftest a-session-this-home-has-never-seen-has-no-binding-and-cannot-be-unbound
  (testing "asking about a stranger answers nil -- the everyday no-binding answer"
    (is (nil? (project/binding-for "pr-stranger"))))
  (testing "and unbinding one does not invent a session row for it"
    (project/bind! "pr-stranger" nil)
    (is (not-any? #(= "pr-stranger" (:id %)) (project/sessions))
        "a conversation nobody ever started must not appear in the listing")))

(deftest removing-a-project-unbinds-it-and-forgets-nothing-else
  ;; Ticket 07's server half. Removing a project is a statement about a
  ;; DIRECTORY, so the verb deletes one row and lets the schema's BEFORE DELETE
  ;; trigger unbind that project's sessions -- which is what makes "removed" mean
  ;; the same thing to every reader: an unbound session, byte-for-byte the
  ;; behaviour of one that never had a project.
  (let [dir (tmp "pr-removed")]
    (.mkdirs (io/file dir))
    (project/bind! "pr-rm-1" dir)
    (project/bind! "pr-rm-2" dir)
    (project/archive! "pr-rm-2" true)
    (let [before (project/projects)]
      (is (= 2 (:unbound (project/remove-project! (canonical-of dir))))
          "the answer counts the sessions it released")
      (testing "the directory is no longer a project"
        (is (not-any? #(= (canonical-of dir) (:canonical-path %)) (project/projects)))
        (is (= (dec (count before)) (count (project/projects)))))
      (testing "and its sessions are unbound -- the one state every reader knows"
        (doseq [id ["pr-rm-1" "pr-rm-2"]]
          (is (nil? (project/binding-for id)))
          (is (nil? (project/identity-for id)))
          (is (= "/somewhere/else.txt" (project/resolve-path id "/somewhere/else.txt")))
          (is (false? (project/out-of-bounds? id "/etc/passwd"))
              "the fence is off, exactly as for a session that never had a project")))
      (testing "the rows survive -- a removal is not a deletion of conversations"
        (let [by-id (into {} (map (juxt :id identity) (project/sessions)))]
          (is (contains? by-id "pr-rm-1"))
          (is (contains? by-id "pr-rm-2"))))
      (testing "and the archive flag is part of the conversation, so it stays"
        (is (true? (:archived? (first (filter #(= "pr-rm-2" (:id %)) (project/sessions)))))))
      (testing "re-adding the directory brings the sessions back, flags and all"
        (let [added (project/add-project! dir)]
          (is (= (canonical-of dir) (:path added)))
          (is (= 2 (:adopted added)) "both sessions remembered where they were")
          ;; The binding comes back as the project's CANONICAL path, which is the
          ;; one spelling a re-add has: the session's own spelling was released by
          ;; the removal (the trigger clears `path` with `project_id`). Same
          ;; directory, same workspace, same log -- a spelling is not a fact about
          ;; which folder this is.
          (is (= (canonical-of dir) (project/binding-for "pr-rm-1")))
          (is (= (canonical-of dir) (project/binding-for "pr-rm-2")))
          (is (= (canonical-of dir) (project/identity-for "pr-rm-1")))
          (is (true? (:archived? (first (filter #(= "pr-rm-2" (:id %)) (project/sessions))))))))
      (testing "a directory this home does not know is refused by name"
        (let [thrown (try (project/remove-project! "/nowhere/at/all") nil (catch Exception e e))]
          (is (some? thrown))
          (is (= :no-such-project (:reason (ex-data thrown))))
          (is (str/includes? (ex-message thrown) "/nowhere/at/all")))))))

(deftest a-folded-subdirectory-goes-with-its-project
  ;; The boundary of "one removal releases this project's sessions". A binding is
  ;; per-session and lives on the session's row, so a session bound to `dir/sub`
  ;; belongs to a DIFFERENT project than one bound to `dir` -- and `if the
  ;; directory exists, add it as a project too` is exactly what the sidebar's own
  ;; add does. Collapsing the two would be redefining bind, not removing a project.
  (let [dir (tmp "pr-folded")
        sub (str dir "/sub")]
    (.mkdirs (io/file sub))
    (project/bind! "pr-fold-1" sub)
    (project/add-project! dir)
    (is (= 0 (:unbound (project/remove-project! (canonical-of dir))))
        "the project had no sessions of its own -- the subdirectory's is not one")
    (is (= (str (.getAbsoluteFile (io/file sub))) (project/binding-for "pr-fold-1"))
        "the subdirectory's own binding is untouched")
    (testing "and removing the subdirectory releases it"
      (is (= 1 (:unbound (project/remove-project! (canonical-of sub)))))
      (is (nil? (project/binding-for "pr-fold-1"))))))

(deftest a-session-remembering-removed-work-is-not-dragged-back-into-it
  ;; The memory a re-add matches on is deliberately narrow. A session that was
  ;; MOVED after the removal -- or released by hand -- must stay where it is; a
  ;; re-add is not a request to reorganize anybody's conversations.
  (let [gone (tmp "pr-gone")
        home (tmp "pr-home")
        hand (tmp "pr-hand")]
    (doseq [d [gone home hand]] (.mkdirs (io/file d)))
    (project/bind! "pr-loyal" gone)
    (project/bind! "pr-hand" gone)
    (project/remove-project! (canonical-of gone))
    (project/bind! "pr-loyal" home)     ; moved somewhere else, deliberately
    (project/bind! "pr-hand" nil)       ; released by hand
    (let [added (project/add-project! gone)]
      (is (= 0 (:adopted added))
          "neither session comes back: one is bound elsewhere, one was released")
      (is (= (str (.getAbsoluteFile (io/file home))) (project/binding-for "pr-loyal")))
      (is (nil? (project/binding-for "pr-hand"))))
    (testing "and a session still remembering it does come back"
      (project/bind! "pr-waiting" gone)
      (project/remove-project! (canonical-of gone))
      (is (nil? (project/binding-for "pr-waiting")))
      (is (= 1 (:adopted (project/add-project! gone))))
      (is (= (canonical-of gone) (project/binding-for "pr-waiting"))))))

(deftest adding-a-directory-that-is-already-a-project-adopts-nothing
  ;; `add-project!` is find-or-create, and its adoption must inherit that: a
  ;; project that was never removed has its sessions already bound, so a second
  ;; add is a no-op rather than a second write.
  (let [dir (tmp "pr-double-add")]
    (.mkdirs (io/file dir))
    (project/bind! "pr-dbl" dir)
    (is (= 0 (:adopted (project/add-project! dir))))
    (is (= (str (.getAbsoluteFile (io/file dir))) (project/binding-for "pr-dbl")))
    (is (false? (:archived? (first (filter #(= "pr-dbl" (:id %)) (project/sessions))))))))

(deftest two-threads-binding-at-once-do-not-lose-each-others-writes
  ;; The store is opened per call, so two threads binding concurrently are two
  ;; real connections contending for the same file -- and a lost update here
  ;; would mean a session silently pointing at the wrong directory.
  (let [dirs    (mapv (fn [i] (let [d (tmp (str "pr-conc-" i))] (.mkdirs (io/file d)) d))
                      (range 6))
        canon   (fn [p] (.getCanonicalPath (io/file p)))
        fails   (atom [])
        workers (mapv (fn [i]
                        (Thread.
                         (fn []
                           (try
                             (dotimes [n 8]
                               (project/bind! (str "pr-conc-" i "-" n) (nth dirs i)))
                             (catch Throwable t (swap! fails conj (ex-message t)))))))
                      (range (count dirs)))]
    (doseq [w workers] (.start w))
    (doseq [w workers] (.join w 30000))
    (is (empty? @fails))
    (is (= (set (map canon dirs))
           (set (map :canonical-path
                     (filter #(contains? (set (map canon dirs)) (:canonical-path %))
                             (project/projects)))))
        "one project row per directory, however the binds interleaved")
    (doseq [i (range (count dirs))
            n (range 8)]
      (is (= (str (.getAbsoluteFile (io/file (nth dirs i))))
             (project/binding-for (str "pr-conc-" i "-" n)))
          (str "pr-conc-" i "-" n " kept its directory through the contention")))))

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

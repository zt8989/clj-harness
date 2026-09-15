(ns harness.skills-test
  "Where a session's skills come from: the two default roots, the `:skills` key's
  whole-list replacement, the relative-path rule, and the named failures a bad
  value earns.

  Every fixture here writes into the temp OS home the runner pins
  (harness.test-runner/isolate!), never the developer's real one -- which is the
  reason that pin exists: this machine has 51 skills in ~/.agents/skills, and a
  suite that read them would depend on one person's dotfiles."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.home :as home]
            [harness.project :as project]
            [harness.skills :as skills]))

(def ^:private tmp-dirs (atom []))

(defn- tmp-project!
  "A throwaway directory to bind a session to, tracked for teardown."
  [tag]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "harness-skills-" tag "-" (System/nanoTime)))]
    (.mkdirs d)
    (swap! tmp-dirs conj d)
    (str d)))

(use-fixtures :each
  (fn [f]
    (reset! tmp-dirs [])
    (try (f)
         (finally
           (doseq [d @tmp-dirs] (io/delete-file d true))
           (doseq [t ["sk-1" "sk-2" "sk-3" "sk-4" "sk-rel"]]
             (project/bind! t nil))))))

(defn- user-home [] (home/user-home))
(defn- user-skills [] (str (io/file (user-home) ".agents" "skills")))

(deftest the-two-defaults-are-the-host-conventions
  (testing "unbound: the OS home's skills, and nothing else"
    (is (= [(str (io/file (user-home) ".agents" "skills"))]
           (skills/roots))))

  (testing "bound: the project's skills join it, and LOSE a name conflict -- so it comes second"
    (let [proj (tmp-project! "defaults")]
      (project/bind! "sk-1" proj)
      (is (= [(str (io/file (user-home) ".agents" "skills"))
              (str (io/file proj ".agents" "skills"))]
             (skills/roots nil proj))))))

(deftest the-user-root-follows-the-os-home-not-the-configuration-root
  (testing "the config root cannot move it -- it is the host's directory, not harness's"
    ;; Moving the ROOT is what a deployment does (CLJ_HARNESS_HOME); the OS home
    ;; is a different floor and must not follow. The runner pins the two to
    ;; sibling temp dirs, so binding one and watching the other is the assertion.
    (let [other (tmp-project! "cfg-root")]
      (binding [home/*root-override* other]
        (is (= (user-skills) (first (skills/roots))))
        (is (not= other (home/user-home))))))

  (testing "the OS home IS the knob that moves it"
    (let [other (tmp-project! "os-home")]
      (binding [home/*user-home-override* other]
        (is (= [(str (io/file other ".agents" "skills"))]
               (skills/roots)))))))

(deftest the-suite-does-not-read-the-developers-real-home
  (testing "the pinned OS home starts empty -- which is what makes the rest honest"
    ;; If this ever fails, every assertion in this file is being satisfied by
    ;; somebody's dotfiles, and the failure of a NEW test elsewhere would be
    ;; blamed on the wrong change.
    (is (not (.exists (io/file (home/user-home) ".agents")))))
  (testing "and the pinned home is not the developer's real one"
    (is (not= (System/getProperty "user.home") (home/user-home)))))

(deftest a-configured-list-replaces-both-defaults
  (let [proj (tmp-project! "replace")]
    (project/bind! "sk-2" proj)

    (testing "absolute entries pass through, in order"
      (is (= ["/abs/one" "/abs/two"]
             (skills/roots {:roots ["/abs/one" "/abs/two"]} proj))))

    (testing "a relative entry resolves against the project, like any tool path"
      (is (= [(str (io/file proj "skills"))]
             (skills/roots {:roots ["skills"]} proj))))

    (testing "so 'only the project's skills' is expressible, and the OS home drops out"
      (is (= [(str (io/file proj ".agents" "skills"))]
             (skills/roots {:roots [".agents/skills"]} proj))))

    (testing "relative entries in an UNBOUND session pass through unchanged"
      (is (= ["skills"] (skills/roots {:roots ["skills"]} nil))))

    (testing "an empty list is a real answer: no roots at all"
      (is (= [] (skills/roots {:roots []} proj))))))

(deftest a-bad-roots-value-fails-by-name
  (let [proj (tmp-project! "bad")
        check (fn [bad re]
                (let [e (try (skills/roots bad proj) nil (catch Exception e e))]
                  (is (some? e) (str (pr-str bad) " should fail"))
                  (is (re-find re (ex-message e)) (str (pr-str bad) " -> " (ex-message e)))
                  (is (re-find #"harness\.edn" (ex-message e))
                      "the message names the files to look in")))]
    (project/bind! "sk-3" proj)

    (testing "the section itself not being a map is caught before a key is asked for"
      ;; `contains?` on a Long or a String throws a bare JVM error naming neither
      ;; the key nor the file -- so this is checked first, on purpose.
      (doseq [bad [42 "a" [42]]]
        (check bad #"not a map")))

    (testing "a map whose :roots is not a list of paths"
      (doseq [bad [{:roots 42} {:roots "a"} {:roots [42]} {:roots {}}]]
        (check bad #"not a list of paths"))

      (testing "and the message quotes the shape to write instead"
        (let [e (try (skills/roots {:roots 42} proj) nil (catch Exception e e))]
          (is (re-find #":skills \{:roots" (ex-message e))))))))

(deftest a-missing-root-is-not-an-error
  (testing "a root nobody created is simply empty -- same rule as a missing harness.edn"
    (let [roots (skills/roots {:roots [(str (io/file (tmp-project! "ghost") "nope"))]} nil)]
      (is (= 1 (count roots)))
      (is (not (.exists (io/file (first roots))))))))

(deftest it-does-not-require-harness-project
  (testing "the cycle is broken by construction: skills must be loadable without project"
    ;; harness.project requires this namespace (the fence needs the roots), so a
    ;; require back would be a load-time cycle. Assert the ns map, not the intent.
    (is (not (contains? (ns-aliases 'harness.skills) 'harness.project)))))

(deftest the-session-facing-pairing-lives-in-project
  (testing "skill-roots pairs harness.edn with the binding -- the one thing only project can do"
    (let [proj (tmp-project! "pairing")]
      (project/bind! "sk-4" proj)
      (is (= (skills/roots nil proj) (project/skill-roots "sk-4")))
      (is (= [(str (io/file (home/user-home) ".agents" "skills"))
              (str (io/file proj ".agents" "skills"))]
             (project/skill-roots "sk-4")))
      (testing "and it reads harness.edn fresh, project level winning"
        (let [harness-edn (io/file proj ".harness" "harness.edn")]
          (.mkdirs (.getParentFile harness-edn))
          (spit harness-edn "{:skills {:roots [\".agents/skills\"]}}\n" :encoding "UTF-8")
          (is (= [(str (io/file proj ".agents" "skills"))]
                 (project/skill-roots "sk-4"))))))))

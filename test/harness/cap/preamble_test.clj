(ns harness.cap.preamble-test
  "Where a session's instruction files come from, and how the blocks a run opens
  with are assembled and ordered.

  EVERY TEST HERE GETS A HOME OF ITS OWN: the fixture wraps each case in
  harness.test-support/with-temp-env, so the instruction files it plants land in a
  directory nobody else reads and nobody has to clean. Not the developer's real home
  (this machine has a 4.1 KB ~/AGENTS.md, and a suite that read it would depend on one
  person's dotfiles), and not the run-wide temp pair either -- that one is shared by
  every test in the JVM, so a file left there is a file the next test's session opens
  with."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.infra.home :as home]
            [harness.test-support :as support]
            [harness.cap.preamble :as preamble]
            [harness.cap.project :as project]))

(def ^:private tmp-dirs (atom []))

(defn- tmp-project! [tag]
  (let [d (support/temp-dir (str "preamble-" tag))]
    (swap! tmp-dirs conj d)
    d))

(defn- spit!
  "Write CONTENT to PATH, creating parents. Returns PATH -- the fixtures below
  are usually built around 'the path I just wrote', and spit's own nil return
  makes every one of them a second line."
  [path content]
  (let [f (io/file path)]
    (.mkdirs (.getParentFile f))
    (spit f content :encoding "UTF-8")
    path))

(use-fixtures :each
  ;; A HOME PER TEST, made and deleted by the fixture. Nothing to wipe: the
  ;; directory the conventions were planted in goes away with the test.
  (fn [f]
    (reset! tmp-dirs [])
    (support/with-temp-env
     [_root _home]
     (try (f)
          (finally
            (doseq [d @tmp-dirs] (support/wipe-tree! d))
            (doseq [t ["pr-1" "pr-2" "pr-3" "pr-4" "pr-5" "pr-6"]]
              (project/bind! t nil)))))))

(deftest the-two-defaults-are-the-host-conventions
  (testing "unbound: the OS home's AGENTS.md, and nothing else"
    (is (= [(str (io/file (home/user-home) "AGENTS.md"))]
           (preamble/instruction-files))))

  (testing "bound: the project's joins it, and comes second -- more specific, nearer the conversation"
    (let [proj (tmp-project! "defaults")]
      (project/bind! "pr-1" proj)
      (is (= [(str (io/file (home/user-home) "AGENTS.md"))
              (str (io/file proj "AGENTS.md"))]
             (preamble/instruction-files nil proj))))))

(deftest the-user-file-follows-the-os-home-not-the-configuration-root
  (is (not= (home/root) (home/user-home)))
  (is (= (str (io/file (home/user-home) "AGENTS.md"))
         (first (preamble/instruction-files))))
  (let [other (tmp-project! "os-home")]
    (binding [home/*user-home-override* other]
      (is (= [(str (io/file other "AGENTS.md"))]
             (preamble/instruction-files))))))

(deftest a-configured-list-replaces-both-defaults
  (let [proj (tmp-project! "replace")]
    (project/bind! "pr-2" proj)

    (testing "only the project's, expressed relatively"
      (is (= [(str (io/file proj "AGENTS.md"))]
             (preamble/instruction-files {:files ["AGENTS.md"]} proj))))

    (testing "several files, in the order written"
      (is (= [(str (io/file proj "AGENTS.md"))
              (str (io/file proj "docs" "AGENTS.md"))]
             (preamble/instruction-files
              {:files ["AGENTS.md" "docs/AGENTS.md"]} proj))))

    (testing "absolute entries pass through; relative ones resolve against the project"
      ;; ABSOLUTE ON THIS PLATFORM: `/abs/AGENTS.md` is a root-relative path on
      ;; Windows, so it would resolve against the project and the case would read as
      ;; 'absolute entries are rewritten'. See harness.test-support/outside-path.
      (let [abs (support/outside-path "AGENTS.md")]
        (is (= [abs (str (io/file proj "AGENTS.md"))]
               (preamble/instruction-files {:files [abs "AGENTS.md"]} proj)))))

    (testing "an empty list is a real answer: read no instruction files"
      (is (= [] (preamble/instruction-files {:files []} proj))))))

(deftest a-bad-files-value-fails-by-name
  (let [proj (tmp-project! "bad")
        check (fn [bad re]
                (let [e (try (preamble/instruction-files bad proj) nil (catch Exception e e))]
                  (is (some? e) (str (pr-str bad) " should fail"))
                  (is (re-find re (ex-message e)) (str (pr-str bad) " -> " (ex-message e)))
                  (is (re-find #"config\.edn" (ex-message e))
                      "the message names the file to look in -- one file now (.scratch/config-merge)")))]
    (project/bind! "pr-3" proj)

    (testing "the section itself not being a map is caught before a key is asked for"
      (doseq [bad [42 "a" [42]]]
        (check bad #"not a map")))

    (testing "a map whose :files is not a list of paths"
      (doseq [bad [{:files 42} {:files "a"} {:files [42]} {:files {}}]]
        (check bad #"not a list of paths"))

      (testing "and the message quotes the shape to write instead"
        (let [e (try (preamble/instruction-files {:files 42} proj) nil (catch Exception e e))]
          (is (re-find #":instructions \{:files" (ex-message e))))))))

(deftest paths-are-where-to-look-not-what-is-there
  (testing "a file that does not exist is still answered -- reading is the caller's job"
    (let [roots (preamble/instruction-files {:files ["nope.md"]} nil)]
      (is (= ["nope.md"] roots))
      (is (not (.exists (io/file (first roots))))))))

(deftest it-does-not-require-harness-project
  (is (not (contains? (ns-aliases 'harness.cap.preamble) 'harness.cap.project))))

(deftest the-session-facing-pairing-lives-in-project
  (testing "preamble-files pairs harness.edn with the binding, for the other half"
    (let [proj (tmp-project! "pairing")]
      (project/bind! "pr-4" proj)
      (is (= (preamble/instruction-files nil proj) (project/preamble-files "pr-4")))
      (is (= [(str (io/file (home/user-home) "AGENTS.md"))
              (str (io/file proj "AGENTS.md"))]
             (project/preamble-files "pr-4")))
      (testing "and it reads the :session section fresh -- one level, this home's"
        (try
          (support/write-session! "{:instructions {:files [\"AGENTS.md\"]}}")
          (is (= [(str (io/file proj "AGENTS.md"))]
                 (project/preamble-files "pr-4")))
          (finally (support/wipe-session!)))))))

;; ----------------------------------------------------------------- reading

(defn- user-agents []
  (let [f (io/file (home/user-home) "AGENTS.md")]
    (spit! (str f) "user rules\n")
    (str f)))

(defn- project-agents [proj content]
  (let [f (io/file proj "AGENTS.md")]
    (spit! (str f) content)
    (str f)))

(deftest every-instruction-file-lands-in-one-reminder
  (let [proj  (tmp-project! "gather")
        _     (project/bind! "pr-5" proj)
        u     (user-agents)
        p     (project-agents proj "project rules\n")
        msgs  (preamble/messages (preamble/gather {:files [u p]}))
        body  (:content (first msgs))]
    (testing "ONE message for every file -- that is what merging them means"
      (is (= 1 (count msgs)))
      (is (= "user" (:role (first msgs)))))

    (testing "it is a <system-reminder>, and nothing inside it is an XML element"
      (is (str/starts-with? body "<system-reminder>\n"))
      (is (str/ends-with? body "\n</system-reminder>"))
      (is (not (str/includes? body "<instructions"))))

    (testing "each file is its own section, headed by the ABSOLUTE path on a plain line"
      (is (str/includes? body (str "Instructions from: " u)))
      (is (str/includes? body (str "Instructions from: " p))))

    (testing "and the order is the order given -- global first, project second"
      (is (< (.indexOf body "user rules") (.indexOf body "project rules"))))

    (testing "a quote in a path is carried verbatim -- there is no attribute to escape out of"
      ;; ASKED OF THE RENDERER, NOT OF THE FILESYSTEM: the old `<instructions path=…>` tag
      ;; forced an attribute and therefore escaping, and the rule being pinned is that the
      ;; label LINE has neither. `messages` is a pure function of the gathered map, so it
      ;; is handed the path directly -- Windows cannot spell a filename holding a `"` at
      ;; all, and creating one would test escaping only where the platform allows it.
      (let [weird (str proj "/we\"ird.md")
            text  (:content (first (preamble/messages {:instructions [{:path weird
                                                                     :content "x"}]})))]
        (is (str/includes? text (str "Instructions from: " weird)))
        (is (not (str/includes? text "&quot;")))))))

(deftest missing-and-empty-files-are-skipped-with-a-reason
  (let [proj    (tmp-project! "skip")
        missing (str proj "/nope.md")
        empty   (do (spit! (str proj "/empty.md") "   \n\n") (str proj "/empty.md"))
        dir     (do (spit! (str proj "/adir/x.md") "x") (str proj "/adir"))
        g       (preamble/gather {:files [missing empty dir]})]
    (testing "nothing was injected"
      (is (empty? (:instructions g))))
    (testing "and every skip says which path, and why"
      (is (= [{:path missing :reason :missing}
              {:path empty   :reason :empty}
              {:path dir     :reason :not-a-file}]
             (:skipped g))))
    (testing "the report says the same thing"
      (is (= (:skipped g) (:skipped (preamble/report g)))))))

(deftest an-unreadable-file-is-a-named-failure-not-a-skip
  ;; The contrast with a skill, and the reason for it: a skill is one entry on a
  ;; menu, an instruction file is a standing statement about how the session must
  ;; work. Proceeding without it would mean following rules nobody wrote.
  (let [proj (tmp-project! "unreadable")
        f    (io/file proj "AGENTS.md")]
    (spit! (str f) "rules\n")
    (.setReadable f false false)
    (if (.canRead f)
      (is true "running as a user who ignores the permission bits; nothing to assert")
      (let [e (try (preamble/gather {:files [(str f)]}) nil (catch Exception e e))]
        (is (some? e))
        (is (re-find #"cannot read the instruction file" (ex-message e)))
        (is (str/includes? (ex-message e) (str f)) "the message names the absolute path")
        (is (re-find #"will not start" (ex-message e)))))))

(deftest a-file-that-is-not-utf8-is-unreadable-rather-than-mangled
  (let [proj (tmp-project! "binary")
        f    (io/file proj "AGENTS.md")]
    (.mkdirs (.getParentFile f))
    ;; A lone 0x80 continuation byte is not valid UTF-8. slurping it would splice
    ;; U+FFFD into the text and hand the model instructions nobody wrote.
    (with-open [out (java.io.FileOutputStream. f)]
      (.write out (byte-array (map unchecked-byte [0xFF 0xFE 0x41]))))
    (let [e (try (preamble/gather {:files [(str f)]}) nil (catch Exception e e))]
      (is (some? e))
      (is (re-find #"cannot read the instruction file" (ex-message e))))))

(deftest the-report-sizes-what-was-actually-injected
  (let [proj (tmp-project! "report")
        f    (spit! (str proj "/AGENTS.md") "  trimmed  \n")]
    (let [g       (preamble/gather {:files [f]})
          message (:content (first (preamble/messages g)))
          report  (preamble/report g)]
      (testing "the file is named, and the count is the one block that was injected"
        (is (= [f] (get-in report [:instructions :paths])))
        (is (= (count message) (get-in report [:instructions :chars])))
        (is (str/includes? message "trimmed"))))))

(deftest the-catalog-goes-last-of-the-opening-blocks
  (let [proj  (tmp-project! "order")
        _     (project/bind! "pr-6" proj)
        u     (user-agents)
        p     (project-agents proj "project rules\n")
        root  (let [r (str (io/file (home/user-home) ".agents" "skills"))]
                (.mkdirs (io/file r "alpha"))
                (spit! (str (io/file r "alpha" "SKILL.md"))
                       "---\nname: alpha\ndescription: alpha does a thing\n---\n\nBODY\n")
                r)]
    (try
      (let [msgs (preamble/messages (preamble/gather {:files [u p] :roots [root]}))]
        (testing "the merged instruction block first, then the menu of what else is available"
          (is (= 2 (count msgs)))
          (is (str/includes? (:content (first msgs)) "user rules"))
          (is (str/includes? (:content (first msgs)) "project rules"))
          (is (str/starts-with? (:content (second msgs)) "<system-reminder>"))
          (is (str/includes? (:content (second msgs)) "Available skills")))

        (testing "and the catalog body never leaks the skill's text"
          (is (str/includes? (:content (second msgs)) "- alpha: alpha does a thing"))
          (is (not (str/includes? (:content (second msgs)) "BODY")))))

      (testing "no usable skills: the instruction block alone"
        (let [msgs (preamble/messages (preamble/gather {:files [u p] :roots []}))]
          (is (= 1 (count msgs)))))

      (testing "the report counts the catalog too, so its weight is visible"
        (let [g (preamble/gather {:files [u p] :roots [root]})]
          (is (pos? (get-in (preamble/report g) [:skills :chars]))))
        (let [g (preamble/gather {:files [u p] :roots []})]
          (is (nil? (:skills (preamble/report g))))))
      (finally
        (io/delete-file (io/file (home/user-home) ".agents") true)
        (project/bind! "pr-6" nil)))))

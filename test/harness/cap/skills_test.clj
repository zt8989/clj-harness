(ns harness.cap.skills-test
  "Where a session's skills come from: the two default roots, the `:skills` key's
  whole-list replacement, the relative-path rule, and the named failures a bad
  value earns -- plus the two ways a skill gets LOADED (the `skill` tool and a
  person's `/name`), which are two sources for one derivation and so are tested
  against each other as much as against themselves.

  Every fixture here writes into the temp OS home the runner pins
  (harness.test-runner/isolate!), never the developer's real one -- which is the
  reason that pin exists: this machine has 51 skills in ~/.agents/skills, and a
  suite that read them would depend on one person's dotfiles."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.infra.home :as home]
            [harness.cap.project :as project]
            [harness.cap.skills :as skills]
            [harness.kernel.tools :as tools]
            [harness.test-support :as support]))

(use-fixtures :once support/with-builtins)

(def ^:private tmp-dirs (atom []))

(defn- spit!
  "Write CONTENT to PATH, creating parents, and answer PATH. spit's own nil
  return makes every fixture built around 'the file I just wrote' a second line."
  [path content]
  (let [f (io/file path)]
    (.mkdirs (.getParentFile f))
    (spit f content :encoding "UTF-8")
    path))

(defn- tmp-project!
  "A throwaway directory to bind a session to, tracked for teardown."
  [tag]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "harness-skills-" tag "-" (System/nanoTime)))]
    (.mkdirs d)
    (swap! tmp-dirs conj d)
    (str d)))

(defn- wipe-skills!
  "Remove every skill the pinned OS home holds. The pinned home is ONE directory
  for the whole process, so without this a skill one test wrote would be visible
  to every later test -- the same discipline project_test applies to harness.edn.

  Bottom-up, because io/delete-file does NOT recurse: it fails on a non-empty
  directory, and its `silently` flag turns that failure into a scheduled
  deleteOnExit -- so a one-line version of this leaves the directory exactly
  where it was and every later test silently inherits the fixtures."
  []
  (let [dir (io/file (home/user-home) ".agents")]
    (doseq [f (reverse (file-seq dir))]
      (io/delete-file f true))))

(use-fixtures :each
  (fn [f]
    (reset! tmp-dirs [])
    (wipe-skills!)
    (try (f)
         (finally
           (doseq [d @tmp-dirs] (io/delete-file d true))
           (wipe-skills!)
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
  (testing "the pinned home is not the developer's real one"
    ;; If this ever fails, every assertion in this file is being satisfied by
    ;; somebody's dotfiles, and the failure of a NEW test elsewhere would be
    ;; blamed on the wrong change.
    (is (not= (System/getProperty "user.home") (home/user-home))))
  (testing "and it holds nothing this test did not put there"
    (wipe-skills!)
    (is (empty? (skills/scan (skills/roots))))))

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
    ;; harness.cap.project requires this namespace (the fence needs the roots), so a
    ;; require back would be a load-time cycle. Assert the ns map, not the intent.
    (is (not (contains? (ns-aliases 'harness.cap.skills) 'harness.cap.project)))))

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

;; ------------------------------------------------------------------- the catalog

(defn- lay-skill!
  "Write a skill directory ROOT/NAME/SKILL.md with BODY. Returns the SKILL.md path."
  ([root name body] (lay-skill! root name name body))
  ([root name dirname body]
   (let [f (io/file root dirname "SKILL.md")]
     (spit! (str f) body)
     (str f))))

(defn- skill-md [name description & [extra]]
  (str "---\nname: " name "\ndescription: " description "\n" (or extra "") "---\n\n# " name "\n\nBody of " name ".\n"))

(defn- lay-user-skills! [& names]
  (let [root (str (io/file (home/user-home) ".agents" "skills"))]
    (.mkdirs (io/file root))
    (doseq [n names] (lay-skill! root n (skill-md n (str n " does a thing"))))
    root))

(deftest a-skill-is-a-directory-holding-a-SKILL-md
  (let [root (lay-user-skills! "alpha")]
    (testing "the directory name is the identity"
      (is (= ["alpha"] (mapv :name (skills/scan [root]))))
      (is (= root (:root (first (skills/scan [root]))))))

    (testing "a directory without one is not a skill -- that is how _shared stays out"
      (.mkdirs (io/file root "_shared"))
      (spit! (str (io/file root "_shared" "NOTES.md")) "x")
      (is (= ["alpha"] (mapv :name (skills/scan [root])))))

    (testing "a SKILL.md at the root of a root is not a skill either -- there is no directory name"
      (spit! (str (io/file root "SKILL.md")) "x")
      (is (= ["alpha"] (mapv :name (skills/scan [root])))))

    (testing "a missing root is empty, not an error"
      (is (= [] (skills/scan [(str (io/file root "nope"))]))))))

(deftest layer-precedence-the-project-wins-a-name-conflict
  (let [user-root (lay-user-skills! "alpha" "shared")
        proj      (tmp-project! "precedence")
        proj-root (str (io/file proj ".agents" "skills"))]
    (.mkdirs (io/file proj-root))
    (lay-skill! proj-root "shared" (skill-md "shared" "the PROJECT's shared"))
    (lay-skill! proj-root "only-project" (skill-md "only-project" "project only"))

    (let [entries (skills/scan [proj-root user-root])
          by-name (into {} (map (juxt :name identity)) entries)]
      (testing "one entry per name, and the earlier root supplied it"
        (is (= ["alpha" "only-project" "shared"] (mapv :name entries)))
        (is (= "the PROJECT's shared" (:description (by-name "shared"))))
        (is (= proj-root (:root (by-name "shared")))))

      (testing "so 'which root supplied this' is a fact, not a guess"
        (is (= proj-root (:root (by-name "only-project"))))
        (is (= user-root (:root (by-name "alpha"))))))

    (testing "and the OTHER order flips who wins -- order is the whole mechanism"
      (let [by-name (into {} (map (juxt :name identity)) (skills/scan [user-root proj-root]))]
        (is (= "shared does a thing" (:description (by-name "shared"))))
        (is (= user-root (:root (by-name "shared"))))))))

(deftest the-catalog-block-lists-names-and-descriptions-and-never-bodies
  (let [root (lay-user-skills! "alpha" "beta")
        text (skills/catalog-text [root])]
    (testing "one line per skill"
      (is (str/includes? text "- alpha: alpha does a thing"))
      (is (str/includes? text "- beta: beta does a thing")))
    (testing "and the BODIES are not in it -- the catalog is bounded by the skill count"
      (is (not (str/includes? text "Body of alpha")))
      (is (not (str/includes? text "# alpha"))))
    (testing "descriptions go in WHOLE -- no 80-char cap (see the spec for the measurement)"
      (let [long (apply str (repeat 200 "x"))
            root2 (lay-user-skills!)]
        (lay-skill! root2 "verbose" (skill-md "verbose" long))
        (is (str/includes? (skills/catalog-text [root2]) long))))))

(deftest a-session-with-no-usable-skills-has-no-catalog-block
  (testing "no roots at all"
    (is (nil? (skills/catalog-text []))))
  (testing "a root with no skills in it"
    (let [root (str (io/file (home/user-home) ".agents" "skills"))]
      (.mkdirs (io/file root "empty-dir"))
      (is (nil? (skills/catalog-text [root]))))))

(deftest disable-model-invocation-keeps-a-skill-out-of-the-catalog-but-in-the-scan
  ;; The file says this one is not for the model to decide to use. A session has
  ;; no other way in, so it is genuinely unavailable -- and it stays VISIBLE,
  ;; because 'the file is there and the capability is not' must never be a
  ;; mystery somebody has to solve by reading source.
  (let [root (lay-user-skills! "automatic")]
    (lay-skill! root "manual-only"
                (skill-md "manual-only" "only a human runs this" "disable-model-invocation: true\n"))
    (let [catalog (skills/catalog-text [root])
          scanned (skills/scan [root])]
      (is (str/includes? catalog "automatic"))
      (is (not (str/includes? catalog "manual-only")))
      (is (some #(= "manual-only" (:name %)) scanned)
          "it is still in the table a session can read")
      (is (true? (:disable-model-invocation? (skills/skill-for [root] "manual-only")))))))

(deftest a-broken-skill-is-a-diagnostic-not-a-failure
  (let [root (str (io/file (home/user-home) ".agents" "skills"))]
    (.mkdirs (io/file root))
    (lay-skill! root "good" (skill-md "good" "fine"))

    (testing "the four ways a skill is broken, each named"
      ;; no frontmatter
      (lay-skill! root "bare" "# just a heading\n")
      ;; frontmatter whose name disagrees with the directory
      (lay-skill! root "misnamed" (skill-md "something-else" "says a different name"))
      ;; no description
      (lay-skill! root "nodesc" "---\nname: nodesc\n---\n\nbody\n")
      ;; unreadable file
      (lay-skill! root "locked" (skill-md "locked" "x"))
      (.setReadable (io/file root "locked" "SKILL.md") false false))

    (let [entries (skills/scan [root])
          by-name (into {} (map (juxt :name identity)) entries)]
      (testing "every one of them is IN the scan, with a reason"
        (is (some? (by-name "bare")))
        (is (some? (by-name "misnamed")))
        (is (some? (by-name "nodesc")))
        (is (= :no-frontmatter (:reason (by-name "bare"))))
        (is (= :name-mismatch (:reason (by-name "misnamed"))))
        (is (= :no-description (:reason (by-name "nodesc"))))
        (is (false? (:available? (by-name "bare")))))

      (testing "and the run is not sunk by any of them"
        (is (= ["good" ] (keep #(when (:available? %) (:name %)) entries))))

      (testing "the catalog lists only what works"
        (let [text (skills/catalog-text [root])]
          (is (str/includes? text "good"))
          (is (not (str/includes? text "bare")))))

      (testing "a called-but-broken skill is refused by name, with the reason"
        (is (false? (:available? (skills/skill-for [root] "misnamed"))))
        (is (= :name-mismatch (:reason (skills/skill-for [root] "misnamed"))))))))

(deftest an-unreadable-skill-file-is-broken-rather-than-fatal
  ;; On a machine where the permission bits do not apply (root, some filesystems)
  ;; this cannot be provoked; assert the everyday half rather than pretend.
  (let [root (str (io/file (home/user-home) ".agents" "skills"))]
    (.mkdirs (io/file root))
    (lay-skill! root "locked" (skill-md "locked" "x"))
    (let [f (io/file root "locked" "SKILL.md")]
      (.setReadable f false false)
      (if (.canRead f)
        (is true "permission bits do not apply to this user; nothing to assert")
        (let [e (first (filter #(= "locked" (:name %)) (skills/scan [root])))]
          (is (= :unreadable (:reason e)))
          (is (false? (:available? e))))))))

;; --------------------------------------------------------------- the resolution

(deftest a-name-resolves-only-through-the-listing
  (let [root (lay-user-skills! "alpha")]
    (testing "a name that was listed resolves"
      (is (= "alpha" (:name (skills/skill-for [root] "alpha")))))
    (testing "anything else is simply not in the table -- including a path"
      (doseq [bad ["../../etc/passwd" "/etc/passwd" "../alpha" "alpha/SKILL.md" "alpha\0"]]
        (is (nil? (skills/skill-for [root] bad)) (str (pr-str bad) " must not resolve"))))))

(deftest the-body-is-read-fresh-and-never-truncated
  (let [root (lay-user-skills! "alpha")
        f    (str (io/file root "alpha" "SKILL.md"))
        big  (skill-md "alpha" "d" (str "x" (apply str (repeat 20000 "y")) "\n"))]
    (testing "the whole file is the body -- frontmatter included, nothing cut"
      (spit! f big)
      (let [e (skills/skill-for [root] "alpha")]
        (is (= (str/trim big) (:body (skills/body e))))
        (is (> (count (:body (skills/body e))) 8000)
            "an eval-style 8000-char clip would be a wrong instruction, not a smaller one")))

    (testing "editing the file changes the next read -- nothing is cached"
      (spit! f (skill-md "alpha" "d" "NEW BODY\n"))
      (is (str/includes? (:body (skills/body (skills/skill-for [root] "alpha"))) "NEW BODY")))

    (testing "and a skill that vanished says so rather than reading as empty"
      (let [e (skills/skill-for [root] "alpha")]
        (io/delete-file (io/file root "alpha" "SKILL.md") true)
        (let [{:keys [missing]} (skills/body e)]
          (is (some? missing))
          (is (str/includes? missing "no longer")))))))

;; ---------------------------------------------------------------- derivation

(defn- call [id name args]
  {:id id :type "function" :function {:name name :arguments (json/write-str args)}})

(defn- assistant-with-skill-call [id name]
  {:role "assistant" :content "" :tool_calls [(call id "skill" {:name name})]})

(defn- skill-result [id name]
  {:role "tool" :tool_call_id id :content (skills/loaded-summary name 42)})

(deftest a-loaded-body-is-spliced-in-right-after-its-tool-result
  (let [root (lay-user-skills! "alpha")
        msgs [{:role "user" :content "please use alpha"}
              (assistant-with-skill-call "c1" "alpha")
              (skill-result "c1" "alpha")]
        out  (skills/derived-injections msgs [root])]
    (testing "one more message than came in"
      (is (= 4 (count out))))

    (testing "and it is a USER message carrying the whole body, tagged with the name"
      (let [injected (nth out 3)]
        (is (= "user" (:role injected)))
        (is (str/starts-with? (:content injected) "<skill name=\"alpha\">"))
        (is (str/ends-with? (:content injected) "</skill>"))
        (is (str/includes? (:content injected) "Body of alpha"))))

    (testing "placed directly AFTER the tool result -- the calls stay adjacent"
      (is (= "tool" (:role (nth out 2))))
      (is (= "user" (:role (nth out 3)))))))

(deftest derivation-is-idempotent-and-loads-once-per-name
  (let [root (lay-user-skills! "alpha")
        msgs [{:role "user" :content "go"}
              (assistant-with-skill-call "c1" "alpha")
              (skill-result "c1" "alpha")]]

    (testing "applying it again changes nothing -- this is what lets the kernel apply it every turn"
      (let [once   (skills/derived-injections msgs [root])
            twice  (skills/derived-injections once [root])
            thrice (skills/derived-injections twice [root])]
        (is (= once twice))
        (is (= twice thrice))))

    (testing "loading the SAME skill twice contributes its body once"
      (let [msgs2 (into msgs [(assistant-with-skill-call "c2" "alpha")
                              (skill-result "c2" "alpha")])
            out   (skills/derived-injections msgs2 [root])]
        (is (= 1 (count (filter #(str/starts-with? (str (:content %)) "<skill name=") out))))))

    (testing "two DIFFERENT skills both arrive, each after the call that loaded it"
      (lay-skill! root "beta" (skill-md "beta" "b"))
      (let [msgs2    (into msgs [(assistant-with-skill-call "c2" "beta")
                                 (skill-result "c2" "beta")])
            out      (skills/derived-injections msgs2 [root])
            injected (filterv #(str/starts-with? (str (:content %)) "<skill name=") out)]
        (is (= 2 (count injected)))
        (is (str/starts-with? (:content (first injected)) "<skill name=\"alpha\">"))
        (is (str/starts-with? (:content (second injected)) "<skill name=\"beta\">"))
        (is (= ["user" "assistant" "tool" "user" "assistant" "tool" "user"]
               (mapv :role out)))))))


(deftest only-a-real-load-is-injected
  (let [root (lay-user-skills! "alpha")
        base [{:role "user" :content "go"}
              (assistant-with-skill-call "c1" "alpha")]]

    (testing "a call that was vetoed, disabled, or malformed never wrote the confirmation"
      ;; The judgement is the confirmation string, not a re-derivation of which
      ;; of those happened -- that is why the tool and this function share it.
      (doseq [not-a-load ["vetoed by human: the call was not executed."
                          "disabled in this session: skill is switched off."
                          "missing required argument(s): name"
                          "no skill named \"nope\"; this session can load [\"alpha\"]"]]
        (let [out (skills/derived-injections (conj base {:role "tool" :tool_call_id "c1"
                                                         :content not-a-load})
                                             [root])]
          (is (= 3 (count out)) (str (pr-str not-a-load) " must not inject")))))

    (testing "nor does an unrelated tool's success, whatever it says"
      (let [msgs [{:role "user" :content "go"}
                  {:role "assistant" :content ""
                   :tool_calls [(call "c9" "read" {:path "x"})]}
                  {:role "tool" :tool_call_id "c9" :content (skills/loaded-summary "alpha" 1)}]]
        (is (= 3 (count (skills/derived-injections msgs [root]))))))))

(deftest a-body-is-read-fresh-and-a-vanished-skill-says-so
  (let [root (lay-user-skills! "alpha")
        msgs [{:role "user" :content "go"}
              (assistant-with-skill-call "c1" "alpha")
              (skill-result "c1" "alpha")]
        f    (str (io/file root "alpha" "SKILL.md"))]
    (testing "editing the skill changes the next derivation -- nothing is frozen into the conversation"
      (spit! f (skill-md "alpha" "d" "EDITED BODY\n"))
      (is (str/includes? (:content (last (skills/derived-injections msgs [root]))) "EDITED BODY")))

    (testing "a skill that has since vanished plants a notice rather than dropping out"
      ;; The SKILL.md itself, not the directory: io/delete-file does not recurse,
      ;; so deleting a non-empty directory is a silent no-op.
      (io/delete-file (io/file root "alpha" "SKILL.md") true)
      (let [text (:content (last (skills/derived-injections msgs [root])))]
        (is (str/starts-with? text "<skill name=\"alpha\">"))
        ;; The wording is the SAME sentence an unknown name gets (absent-notice):
        ;; the question is the same question, and a second sentence for it would be
        ;; a second answer free to disagree. What it must keep saying is the part
        ;; that matters -- these instructions are not here, do not assume them.
        (is (str/includes? text "no skill named"))
        (is (str/includes? text "should not be assumed"))))))

;; ------------------------------------------------- the slash form, by a person

(deftest a-slash-request-is-read-off-the-front-of-the-message
  ;; The trigger's shape, as a table. It is anchored and needs its separator, which
  ;; is what keeps prose and paths from loading anything by accident.
  (doseq [[text expected] [["/alpha"                      "alpha"]
                           ["/alpha do the thing"          "alpha"]
                           ["/alpha\nsecond line"          "alpha"]
                           ["/alpha\twith a tab"           "alpha"]
                           ["/alpha-beta.v2_x"             "alpha-beta.v2_x"]
                           ["/../etc/passwd"               nil]
                           ["  /alpha"                     nil]
                           ["see /alpha"                   nil]
                           ["/alpha/beta"                  nil]
                           ["/alpha,then"                  nil]
                           ["/ alpha"                      nil]
                           ["/"                            nil]
                           ["alpha"                        nil]
                           [""                             nil]]]
    (is (= expected (skills/slash-request text)) (pr-str text)))

  (testing "and nothing that is not a string asks for anything"
    (is (nil? (skills/slash-request nil)))
    (is (nil? (skills/slash-request 42)))
    (is (nil? (skills/slash-request [{:type "text" :text "/alpha"}]))
        "a parts vector is a caller's problem -- see the derivation, which reads it")))

(deftest a-slash-load-splices-the-body-after-the-message-that-asked
  (let [root (lay-user-skills! "alpha")
        msgs [{:role "user" :content "/alpha go"}
              {:role "assistant" :content "on it"}]
        out  (skills/derived-injections msgs [root])]
    (testing "one more message, and it is a USER message carrying the whole body"
      (is (= 3 (count out)))
      (is (= "user" (:role (nth out 1))))
      (is (str/starts-with? (:content (nth out 1)) "<skill name=\"alpha\">"))
      (is (str/includes? (:content (nth out 1)) "Body of alpha")))

    (testing "the person's own words are left exactly as typed"
      ;; The trigger is not consumed: stripping it would need somewhere to
      ;; remember that it had been stripped, and there is nowhere to remember.
      (is (= "/alpha go" (:content (first out)))))

    (testing "and the assistant's reply still follows it"
      (is (= ["user" "user" "assistant"] (mapv :role out))))))

(deftest a-slash-load-is-idempotent-and-shares-one-load-per-name
  (let [root (lay-user-skills! "alpha")
        msgs [{:role "user" :content "/alpha go"}]]
    (testing "applying it again changes nothing"
      (let [once   (skills/derived-injections msgs [root])
            twice  (skills/derived-injections once [root])
            thrice (skills/derived-injections twice [root])]
        (is (= once twice))
        (is (= twice thrice))))

    (testing "the slash and the tool are two ways to ASK, not two gets of the body"
      (let [slash-then-tool (skills/derived-injections
                             [{:role "user" :content "/alpha go"}
                              (assistant-with-skill-call "c1" "alpha")
                              (skill-result "c1" "alpha")]
                             [root])
            tool-then-slash (skills/derived-injections
                             [{:role "user" :content "go"}
                              (assistant-with-skill-call "c1" "alpha")
                              (skill-result "c1" "alpha")
                              {:role "user" :content "/alpha again"}]
                             [root])]
        (doseq [out [slash-then-tool tool-then-slash]]
          (is (= 1 (count (filter #(str/starts-with? (str (:content %)) "<skill name=") out)))
              "the body arrives once whichever path asked first"))))

    (testing "two different names both arrive, each after the message that asked"
      (lay-skill! root "beta" (skill-md "beta" "b"))
      (let [out      (skills/derived-injections [{:role "user" :content "/alpha go"}
                                                 {:role "user" :content "/beta too"}]
                                                [root])
            injected (filterv #(str/starts-with? (str (:content %)) "<skill name=") out)]
        (is (= 2 (count injected)))
        (is (str/starts-with? (:content (first injected)) "<skill name=\"alpha\">"))
        (is (str/starts-with? (:content (second injected)) "<skill name=\"beta\">"))))))

(deftest a-slash-load-of-a-name-nobody-has-is-a-notice-not-a-silence
  (let [root (lay-user-skills! "alpha")
        out  (skills/derived-injections [{:role "user" :content "/nope go"}] [root])]
    (testing "the body slot is filled with a sentence rather than left empty"
      ;; A typo that loaded nothing must not be indistinguishable from a skill that
      ;; loaded something with nothing to say.
      (is (= 2 (count out)))
      (let [text (:content (second out))]
        (is (str/starts-with? text "<skill name=\"nope\">"))
        (is (str/includes? text "no skill named"))
        (is (str/includes? text "alpha") "and it lists what this session CAN load"))))

  (testing "a BROKEN skill says which reason, and where to look"
    (let [root (lay-user-skills! "alpha")]
      (lay-skill! root "nodesc" "---\nname: nodesc\n---\n\nbody\n")
      (let [text (:content (second (skills/derived-injections
                                    [{:role "user" :content "/nodesc go"}] [root])))]
        (is (str/includes? text "cannot be loaded"))
        (is (str/includes? text "no-description"))
        (is (str/includes? text "nodesc/SKILL.md"))))))

(deftest an-image-beside-a-slash-request-does-not-hide-it
  ;; A person can attach a picture and type the skill name in the same message.
  ;; The trigger is read from the message's TEXT parts, so that message still asks.
  (let [root (lay-user-skills! "alpha")
        out  (skills/derived-injections
              [{:role "user"
                :content [{:type "text" :text "/alpha look at this"}
                          {:type "image" :image_url {:url "data:image/png;base64,AA"}}]}]
              [root])]
    (is (= 2 (count out)))
    (is (str/starts-with? (:content (second out)) "<skill name=\"alpha\">"))))

(deftest a-conversation-that-asks-for-nothing-is-returned-untouched
  ;; The regression the whole feature rests on, in the form this second source
  ;; could break: no tool load, no slash, so the vector comes back as ITSELF.
  (let [root (lay-user-skills! "alpha")
        msgs [{:role "user" :content "prose about alpha, and about /alpha even"}
              {:role "assistant" :content "sure"}]]
    (is (identical? msgs (skills/derived-injections msgs [root])))))

(deftest a-person-can-load-a-skill-the-model-may-not
  ;; `disable-model-invocation: true` is the file saying this is not the model's to
  ;; reach for. Two paths, one difference -- and each half has to be asserted on
  ;; its own, because either one alone would pass while the pair was backwards.
  (let [root (lay-user-skills! "automatic")]
    (lay-skill! root "manual-only"
                (skill-md "manual-only" "only a human runs this" "disable-model-invocation: true\n"))

    (testing "the catalog does not offer it to the model"
      (is (not (str/includes? (skills/catalog-text [root]) "manual-only"))))

    (testing "the tool refuses it, by name, and says who can"
      (let [r (tools/run! {:function {:name "skill"
                                      :arguments (json/write-str {:name "manual-only"})}}
                          "sk-2")]
        (is (true? (:error r)))
        (is (str/includes? (str (:content r)) "not for the model to load"))
        (is (str/includes? (str (:content r)) "/manual-only")
            "and it names the way a person can")))

    (testing "a person typing it gets the body"
      (let [out (skills/derived-injections [{:role "user" :content "/manual-only go"}] [root])]
        (is (= 2 (count out)))
        (is (str/includes? (:content (second out)) "Body of manual-only"))
        (is (not (str/includes? (:content (second out)) "cannot be loaded")))))))

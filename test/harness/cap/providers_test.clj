(ns harness.cap.providers-test
  "The provider catalog, the tier fold, the session-state introspection surface,
  and the outbox the edge drains into the provider timeline.

  Everything here runs under the runner's isolated config root, so the fixtures
  write their own config.edn -- the one file, with its :default and :providers
  sections -- into a temp home rather than touching the developer's real
  ~/.clj-harness."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.infra.home :as home]
            [harness.infra.language :as language]
            [harness.kernel.llm :as llm]
            [harness.cap.providers :as providers]
            [harness.kernel.tools :as tools]
            [harness.test-support :as support]))

(use-fixtures :once support/with-builtins)

;; ------------------------------------------------------------------ fixtures

(defn- write-home! [config providers]
  (.mkdirs (io/file (home/root)))
  (spit (home/config-file) (support/config-text config providers) :encoding "UTF-8")
  ;; The retired file is removed rather than left alone: every case in this
  ;; namespace runs in the runner's shared temp home, and a stray providers.edn
  ;; from an earlier case would make the catalog refuse the very next one.
  (io/delete-file (home/providers-file) true))

(defn- with-home [config providers f]
  (let [old-config (when (.exists (home/config-file))
                     (slurp (home/config-file) :encoding "UTF-8"))]
    (try
      (write-home! config providers)
      (f)
      (finally
        (write-home! nil nil)
        (spit (home/config-file)
              (or old-config "{:default {:protocol :fake}}\n")
              :encoding "UTF-8")))))

(def ^:private sentinel
  "A value shaped like a real API key and recognisable anywhere it turns up. The
  key tests put it in the places a key can come from (a home's .env, a real
  environment variable) and then search the whole rendered answer for it: a secret
  that leaks as a length, a prefix or a digest is still a leak, and only searching
  for the string catches the ones nobody thought of.

  It lives up here with the fixtures rather than beside the settings section it was
  written for: the credential-name tests use it too now, and a def used by two
  sections belongs to neither."
  "sk-or-v1-SENTINEL-DO-NOT-PUBLISH-9f3c2a")

(defn- with-dotenv
  "CONTENTS as this home's .env for the duration of F -- nil means no file at all.
  The previous contents (or absence) come back afterwards, because the runner's
  home is shared by the whole namespace."
  [contents f]
  (let [f*  (home/dotenv-file)
        old (when (.exists f*) (slurp f* :encoding "UTF-8"))]
    (try
      (if (nil? contents)
        (io/delete-file f* true)
        (spit f* contents :encoding "UTF-8"))
      (f)
      (finally
        (if (nil? old) (io/delete-file f* true) (spit f* old :encoding "UTF-8"))))))

(def ^:private reg
  "Three vendors, two of them sharing a protocol and one serving a text-only
  model. The endpoints differ so a test can see the vendor actually change, and
  the modality split is what the guard tests aim at.

  The names are deliberately NOT any built-in name: these tests are about the
  file's entries, so a collision would have them passing on the built-in table
  instead."
  (pr-str {:alpha {:protocol :openai-completions :base-url "https://alpha/v1"
                   :model "alpha-large"
                   :models {"alpha-large" {:input #{:text :image} :output #{:text}}
                            "alpha-small" {:input #{:text :image} :output #{:text}}
                            "alpha-vision-free" {:input #{:text :image} :output #{:text}}}}
           :beta  {:protocol :openai-completions :base-url "https://beta/v1"
                   :model "beta-plain"
                   :models {"beta-plain" {:input #{:text} :output #{:text}}
                            "beta-big"   {:input #{:text} :output #{:text}}}}
           :gamma {:protocol :openai-completions :base-url "http://localhost:11434/v1"
                   :model "qwen3"
                   :models {"qwen3" {:input #{:text} :output #{:text}}}}}))
;; alpha's model ids carry the word "vision" only once, in a model whose :input
;; set is what actually decides. The name is deliberate: it makes "the id does
;; not decide the capability" a thing a reader can see rather than infer.

(defn- cfg
  "config.edn's :default section naming PROVIDER, plus any extra knobs."
  [provider & {:as extra}]
  (pr-str (merge {:provider provider} extra)))

;; --------------------------------------------------------------- the built-ins

(deftest the-built-in-table-stands-on-its-own
  ;; No :providers section at all (the fixture writes an empty one). This is the
  ;; shape's headline claim: the three knobs in :default are enough to start, so a
  ;; fresh install runs without anyone adding a vendor first.
  (with-home (cfg :openrouter) nil
    (fn []
      (let [p (providers/effective-provider "p-builtin")]
        (is (= "https://openrouter.ai/api/v1" (:base-url p)))
        (is (= "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free" (:model p))
            "the built-in entry's default model")
        (is (= #{:text :image} (:input p)) "with its declared modalities"))
      (testing "and a built-in provider's model can be chosen by name"
        (providers/set-override! "p-builtin" {:model "deepseek/deepseek-v4-pro"})
        (let [p (providers/effective-provider "p-builtin")]
          (is (= "deepseek/deepseek-v4-pro" (:model p)))
          (is (= #{:text} (:input p)) "whose modalities are its own, not the default's"))
        (providers/set-override! "p-builtin" nil)))))

(deftest the-built-in-table-covers-more-than-one-vendor
  (with-home (cfg :deepseek) nil
    (fn []
      (is (= "https://api.deepseek.com/v1" (:base-url (providers/effective-provider "p-ds"))))
      (is (= "deepseek-flash" (:model (providers/effective-provider "p-ds"))))
      (testing "each vendor's own endpoint, not a shared one"
        (providers/set-override! "p-ds" {:provider :ollama})
        (is (= "http://localhost:11434/v1" (:base-url (providers/effective-provider "p-ds"))))
        (is (= "qwen3" (:model (providers/effective-provider "p-ds"))))
        (providers/set-override! "p-ds" nil)))))

;; -------------------------------------------------- the file's two sections

(deftest the-top-level-is-two-sections-and-nothing-else
  ;; The shape config.edn used to have WAS the default tier: :provider / :model /
  ;; :reasoning-effort sitting at the top. Reading that as a second shape is what
  ;; this catalog refuses everywhere else (see the far older flat PROVIDER shape
  ;; below), so it is a named failure -- and the sentence has to say where the
  ;; knobs moved, because the person meeting it is holding a file that used to
  ;; work.
  (doseq [flat [(pr-str {:provider :openrouter})
                (pr-str {:provider :openrouter :model "some-id"})
                ;; the inline form, which was the OTHER thing a top level could be
                (pr-str {:protocol :fake :base-url "https://inline/v1" :model "flat"})]]
    (with-home nil nil
      (fn []
        (spit (home/config-file) flat :encoding "UTF-8")
        (let [e (try (providers/effective-provider "t-shape") nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) (str "a flat top level is refused: " flat))
          (is (str/includes? (ex-message e) ":default") "and the sentence names :default")
          (is (str/includes? (ex-message e) ":providers") "and :providers"))))))

(deftest a-section-that-is-not-a-map-is-refused
  (doseq [[section body] [[:default "{:default [{:provider :alpha}]}"]
                          [:providers "{:providers [\"alpha\"]}"]]]
    (with-home nil nil
      (fn []
        (spit (home/config-file) body :encoding "UTF-8")
        (let [e (try (providers/catalog) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) (str section " that is not a map fails rather than being iterated"))
          (is (str/includes? (ex-message e) (name section))
              "and the sentence names the section"))))))

(deftest either-section-may-be-absent
  ;; The two sections answer different questions, so a home may answer only one:
  ;; a :providers section with no :default is a catalog a SESSION can still pick
  ;; from (the session tier is what the composer writes), and a :default naming a
  ;; built-in needs no :providers at all -- the built-ins test above.
  (with-home nil reg
    (fn []
      (let [e (try (providers/effective-provider "t-nodefault") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "no default tier means no provider for a run that asks for none")
        (is (str/includes? (ex-message e) "no provider")
            "and the failure names the provider rather than the missing section"))
      (is (contains? (providers/catalog) :alpha) "the catalog itself is there")
      (providers/set-override! "t-nodefault" {:provider :alpha :model "alpha-small"})
      (is (= "https://alpha/v1" (:base-url (providers/effective-provider "t-nodefault")))
          "and a session that picks from it is served")
      (providers/set-override! "t-nodefault" nil))))

(deftest an-empty-config-edn-is-a-file-that-says-nothing
  ;; `touch config.edn` is what a person does after the missing-file failure tells
  ;; them to create it. An empty file (or one holding only comments) says nothing --
  ;; which this shape spells `{}` -- so it must not be answered with "not nil": the
  ;; built-in catalog stands, no default tier is named, and what a run meets is the
  ;; sentence that says which shape to write.
  (doseq [[what body] {"an empty file"        ""
                       "a file of comments"    ";; nothing to see here\n"
                       "and only whitespace"   "\n\n   \n"}]
    (with-home nil nil
      (fn []
        (spit (home/config-file) body :encoding "UTF-8")
        (testing what
          (is (= {} (providers/config)) "it parses as the empty map, not as a failure")
          (is (contains? (providers/catalog) :openrouter)
              "and the built-in table stands, as it does with no :providers section")
          (let [e (try (providers/effective-provider "t-empty") nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (some? e) "and a run with no default tier fails")
            (is (str/includes? (ex-message e) ":default")
                "with the sentence that says WHERE the knobs go")))))))

(deftest an-empty-file-does-not-leave-an-empty-backup
  ;; The backup's whole meaning is "what it held before this write". A zero-byte file
  ;; held nothing, so a zero-byte config.edn.bak would be a promise about nothing --
  ;; and the header would be telling its reader about comments that never existed.
  (with-home nil nil
    (fn []
      (spit (home/config-file) "" :encoding "UTF-8")
      ;; The runner's home is shared with every other case in this namespace, so a
      ;; backup left by an earlier one is not evidence about THIS write.
      (io/delete-file (home/config-backup-file) true)
      (providers/put-defaults! {:provider "openrouter"})
      (is (not (.exists (home/config-backup-file)))
          "nothing to keep, so nothing is kept")
      (let [written (slurp (home/config-file))]
        (is (str/includes? written "WRITTEN BY THE SETTINGS FORM"))
        (is (not (str/includes? written "are gone"))
            "and the header does not claim comments were lost"))
      (testing "but a file with anything in it IS backed up"
        (spit (home/config-file) "{:default {:provider :openrouter}}\n" :encoding "UTF-8")
        (providers/put-defaults! {:reasoning-effort "high"})
        (is (str/includes? (slurp (home/config-backup-file)) ":provider :openrouter"))))))

(deftest a-config-that-says-something-which-is-not-a-map-still-fails
  ;; The other half of the paragraph above: silence is fine, a vector is a mistake.
  (doseq [body ["[1 2]" "\"a string\"" "42" "{:default []}"]]
    (with-home nil nil
      (fn []
        (spit (home/config-file) body :encoding "UTF-8")
        (let [e (try (providers/config) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) (str (pr-str body) " is refused"))
          (is (str/includes? (ex-message e) "config.edn")
              "and the sentence names the file"))))))

(deftest the-no-provider-sentence-teaches-the-files-own-shape
  ;; It is the sentence a fresh home meets, so its examples have to be writable in the
  ;; file as it is now: `:default`, not the flat shape this file used to have.
  (with-home nil nil
    (fn []
      (let [e (try (providers/resolve-provider "t-shape2") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "{:default {:provider :openrouter}}"))
        (is (not (str/includes? (ex-message e) "e.g. {:provider :openrouter}"))
            "and not the old flat example, which no longer parses"))
      (let [e (try (providers/assemble (providers/catalog) {:provider {}}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "an inline description with nothing in it is refused too")
        (is (str/includes? (ex-message e) ":default")
            "and that sentence also names the section")))))

(deftest an-old-top-level-is-upgraded-once-not-refused
  ;; THE FILE SOMEBODY WAS USING. config.edn used to BE the default tier -- the knobs
  ;; at the top level, or a provider described inline -- and refusing that outright
  ;; took a working home and left its owner chasing failures into an empty file. The
  ;; boot moves it instead.
  (doseq [[what old-shape] {"a provider described inline"
                            (pr-str {:protocol :openai-completions
                                     :base-url "https://openrouter.ai/api/v1"
                                     :model    "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"
                                     :reasoning-effort "low"
                                     :context-window 256000
                                     :max-output-tokens 65536})
                            "the three knobs by name"
                            (pr-str {:provider :openrouter :model "openai/gpt-4o-mini"
                                     :reasoning-effort "high"})}]
    (with-home nil nil
      (fn []
        (spit (home/config-file) old-shape :encoding "UTF-8")
        (testing what
          (is (thrown? clojure.lang.ExceptionInfo (providers/config))
              "the reader alone still refuses it -- the upgrade is the boot's job")
          (is (true? (:migrated? (providers/migrate-config!))))
          (let [after (slurp (home/config-file))]
            (is (str/includes? after ":default") "the old top level is now the :default section")
            (is (not (str/includes? after ":providers {")) "and nothing else was invented")
            (is (= :openai-completions
                   (if (= what "a provider described inline")
                     (:protocol (providers/effective-provider "t-mig"))
                     :openai-completions))
                "the file it now holds is the configuration it used to be"))
          (is (= old-shape (slurp (home/config-backup-file)))
              "and the previous contents are in the backup, byte for byte")
          (is (false? (:migrated? (providers/migrate-config!)))
              "running it again does nothing -- the upgrade happens once"))))))

(deftest a-file-that-is-not-a-configuration-is-left-alone
  ;; The migration recognizes an OLD configuration, not any map: a typo in a section
  ;; name and a map of something else both keep their own sentences, and neither gets
  ;; rewritten into a shape that hides the mistake.
  (doseq [body ["{:nonsense 1}"
                "{:default {:provider :openrouter} :providers {} :oops 1}"
                "[1 2 3]"]]
    (with-home nil nil
      (fn []
        (spit (home/config-file) body :encoding "UTF-8")
        (is (false? (:migrated? (providers/migrate-config!)))
            (str (pr-str body) " is not upgraded"))
        (is (= body (slurp (home/config-file))) "and the file is exactly as it was")))))

(deftest a-home-with-no-config-edn-gets-one-at-boot-and-only-then
  ;; NEW: a home nobody has configured is a home the FIRST SERVING PROCESS hands a file
  ;; to, because a person who has just installed this should have something to open.
  ;; The reader stays pure: asking a home what it says never writes.
  (with-home nil nil
    (fn []
      (io/delete-file (home/config-file) true)
      (testing "asking reads a missing file as an empty one -- and creates nothing"
        (is (= "" (home/config)))
        (is (not (.exists (home/config-file))) "the home is as it was found"))
      (testing "the boot seeds it"
        (let [{:keys [file created?]} (providers/ensure-config!)]
          (is (true? created?))
          (is (.exists ^java.io.File file))
          (is (str/includes? (slurp file :encoding "UTF-8") ":default")
              "with a few lines saying which sections exist")
          (is (= {} (providers/config))
              "and an empty map, which is how this shape says 'says nothing'")
          (is (contains? (providers/catalog) :openrouter) "so the built-ins stand")))
      (testing "and it never overwrites a home that already has one"
        (spit (home/config-file) "{:default {:provider :openrouter}}" :encoding "UTF-8")
        (is (false? (:created? (providers/ensure-config!))))
        (is (str/includes? (slurp (home/config-file)) ":provider :openrouter")
            "the person's file, not the skeleton")))))

(deftest a-providers-edn-file-is-a-named-failure
  ;; The file held this section until the catalog moved into config.edn. A home
  ;; that still has one is told to move its entries and delete it -- rather than
  ;; having every entry in it quietly do nothing, which is the silent no-op this
  ;; catalog exists to kill.
  (with-home (cfg :alpha) reg
    (fn []
      (spit (home/providers-file)
            (pr-str {:ghost {:protocol :fake :base-url "https://ghost/v1" :model "g"
                             :models {"g" {:input #{:text} :output #{:text}}}}})
            :encoding "UTF-8")
      (let [e (try (providers/effective-provider "t-provfile") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a file that no longer means anything stops the run")
        (is (str/includes? (ex-message e) "providers.edn") "the sentence names the file")
        (is (str/includes? (ex-message e) ":providers") "and where its entries go"))
      (io/delete-file (home/providers-file) true)
      (is (not (contains? (providers/catalog) :ghost))
          "and nothing in it was read: the entry is not in the catalog")
      (is (= :alpha (:provider (providers/effective-provider "t-provfile")))
          "the config.edn catalog is untouched by it"))))

(deftest a-user-entry-overrides-the-built-in-field-by-field
  ;; A partial patch is legitimate: saying only what you mean to change and
  ;; borrowing the rest is what makes the built-in table worth having rather than
  ;; a thing to copy and then drift from.
  (with-home (cfg :openrouter)
             (pr-str {:openrouter {:base-url "https://my-proxy/v1"}})
    (fn []
      (let [p (providers/effective-provider "p-ovr")]
        (is (= "https://my-proxy/v1" (:base-url p)) "the field named here wins")
        (is (= :openai-completions (:protocol p)) "the rest is borrowed")
        (is (= "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free" (:model p))
            "including the default model")))))

(deftest adding-a-model-to-a-built-in-vendor-keeps-the-ones-it-already-had
  ;; Models merge BY ID. Replacing the table wholesale would make "add one id"
  ;; require retyping every id the built-in entry carried -- and the retyped list
  ;; would then drift, which is the duplication the built-in table removes.
  (with-home (cfg :openrouter)
             (pr-str {:openrouter {:models {"my-provider/custom" {:input #{:text}
                                                                  :output #{:text}}}}})
    (fn []
      (let [c (providers/catalog)]
        (is (contains? (get-in c [:openrouter :models]) "my-provider/custom")
            "the added id is there")
        (is (contains? (get-in c [:openrouter :models]) "anthropic/claude-sonnet-4.5")
            "and the built-in ids are still there")))))

(deftest a-user-may-add-a-whole-provider-beside-the-built-in-ones
  (with-home (cfg :mine)
             (pr-str {:mine {:protocol :openai-completions :base-url "https://mine/v1"
                             :model "mine-1"
                             :models {"mine-1" {:input #{:text} :output #{:text}}}}})
    (fn []
      (let [c (providers/catalog)]
        (is (= "https://mine/v1" (:base-url (get c :mine))))
        (is (contains? c :openrouter) "and the built-ins are untouched"))
      (is (= "mine-1" (:model (providers/effective-provider "p-added")))))))

(deftest an-unknown-provider-fails-and-lists-what-is-known
  (with-home (cfg :nope) nil
    (fn []
      (let [e (try (providers/effective-provider "p-unknown") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "nope"))
        (is (str/includes? (ex-message e) "openrouter")
            "the known list includes the built-ins, so the reader sees the options")))))

;; ------------------------------------------------------------------- shape

(deftest a-provider-names-a-vendor-and-lists-its-models
  (with-home (cfg :alpha) reg
    (fn []
      (let [c (providers/catalog)]
        (testing "the catalog is keyed by provider name"
          (is (every? #(contains? c %) [:alpha :beta :gamma])))
        (testing "each entry knows its endpoint, its default model, and every model it serves"
          (let [a (get c :alpha)]
            (is (= "https://alpha/v1" (:base-url a)))
            (is (= "alpha-large" (:model a)))
            (is (= #{"alpha-large" "alpha-small" "alpha-vision-free"}
                   (set (keys (:models a)))))))
        (testing "and each model declares what it takes in and gives out"
          (is (= #{:text :image} (get-in c [:alpha :models "alpha-large" :input])))
          (is (= #{:text} (get-in c [:beta :models "beta-plain" :input]))))))))

(deftest the-old-flat-shape-fails-by-name-and-says-what-to-write
  ;; A provider entry that still has a :model string and no :models table is the
  ;; shape this feature replaced. It is NOT read and NOT migrated -- it stops the
  ;; run, and the message says which shape to write instead.
  (with-home (cfg :cheap)
             (pr-str {:cheap {:protocol :openai-completions :base-url "https://a/v1"
                              :model "small"}})
    (fn []
      (let [e (try (providers/catalog) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "the old shape is a hard failure, not a quiet upgrade")
        (is (str/includes? (ex-message e) ":models")
            "and the message names the table it is missing")
        (is (str/includes? (ex-message e) ":input")
            "and shows a model entry, so the reader has the new shape in hand")))))

(deftest a-provider-with-no-models-lists-nothing-to-serve
  (with-home (cfg :alpha) (pr-str {:alpha {:protocol :p :base-url "https://a/v1"
                                            :model "m" :models {}}})
    (fn []
      (let [e (try (providers/catalog) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (str/includes? (ex-message e) "lists no models"))))))

(deftest a-model-must-declare-both-directions
  (testing "declaring neither is a failure -- a model silent about its modalities
            would make the guard vacuous"
    (with-home (cfg :alpha)
               (pr-str {:alpha {:protocol :p :base-url "https://a/v1" :model "m"
                                :models {"m" {}}}})
      (fn []
        (let [e (try (providers/catalog) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (str/includes? (ex-message e) ":input"))
          (is (str/includes? (ex-message e) ":output"))))))
  (testing "declaring only one is also a failure -- half a capability is not a statement"
    (with-home (cfg :alpha)
               (pr-str {:alpha {:protocol :p :base-url "https://a/v1" :model "m"
                                :models {"m" {:input #{:text}}}}})
      (fn []
        (let [e (try (providers/catalog) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (str/includes? (ex-message e) ":output")))))))

(deftest a-modality-this-harness-cannot-carry-fails-by-name
  (testing "input"
    (with-home (cfg :alpha)
               (pr-str {:alpha {:protocol :p :base-url "https://a/v1" :model "m"
                                :models {"m" {:input #{:text :audio} :output #{:text}}}}})
      (fn []
        (let [e (try (providers/catalog) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (str/includes? (ex-message e) "audio") "names the offending type")
          (is (str/includes? (ex-message e) "image") "and what it could have carried")))))
  (testing "output -- the vocabulary is narrower here, because the harness drops
            everything but text on the way back"
    (with-home (cfg :alpha)
               (pr-str {:alpha {:protocol :p :base-url "https://a/v1" :model "m"
                                :models {"m" {:input #{:text} :output #{:image}}}}})
      (fn []
        (let [e (try (providers/catalog) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (str/includes? (ex-message e) "image")))))))

(deftest the-default-model-must-be-one-the-provider-declares
  (with-home (cfg :alpha)
             (pr-str {:alpha {:protocol :p :base-url "https://a/v1" :model "ghost"
                              :models {"real" {:input #{:text} :output #{:text}}}}})
    (fn []
      (let [e (try (providers/catalog) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (str/includes? (ex-message e) "ghost") "names the id it could not find")
        (is (str/includes? (ex-message e) "real") "and lists what is declared")))))

;; ---------------------------------------------------- the two counts

(def ^:private counted
  "A catalog whose models declare the two counts -- and one that declares none,
  so 'silence' has a model to attach to."
  (pr-str {:alpha {:protocol :openai-completions :base-url "https://alpha/v1"
                   :model "alpha-large"
                   :models {"alpha-large" {:input #{:text :image} :output #{:text}
                                           :context-window 200000 :max-output-tokens 8192}
                            "alpha-quiet" {:input #{:text} :output #{:text}}}}}))

(deftest a-model-may-declare-its-context-and-output-counts
  (with-home (cfg :alpha) counted
    (fn []
      (let [p (providers/effective-provider "p-counts")]
        (is (= 200000 (:context-window p)) "the declared context window")
        (is (= 8192 (:max-output-tokens p)) "and the declared output ceiling"))
      (testing "both are plain integers in-process and on the wire -- never strings"
        (let [a (providers/active-provider "p-counts")]
          (is (= 200000 (:context-window a)))
          (is (integer? (:context-window a))))
        (let [w (providers/wire (providers/active-provider "p-counts"))]
          (is (= 200000 (:context-window w)))
          (is (= 8192 (:max-output-tokens w)))))
      (testing "and they follow the MODEL, not the provider"
        (providers/set-override! "p-counts" {:model "alpha-quiet"})
        (let [p (providers/effective-provider "p-counts")]
          (is (not (contains? p :context-window))
              "the chosen model declared none, so nothing is claimed")
          (is (not (contains? p :max-output-tokens))))
        (providers/set-override! "p-counts" nil)))))

(deftest a-model-silent-about-its-counts-says-nothing-about-them
  ;; Absent, not zero and not null: this harness does not know the number, which
  ;; is a different fact from knowing it is nothing. The same discipline :output
  ;; had to pass -- declare only what is actually stated.
  (with-home (cfg :alpha) counted
    (fn []
      (providers/set-override! "p-silent" {:model "alpha-quiet"})
      (let [p (providers/effective-provider "p-silent")
            w (providers/wire p)]
        (is (not (contains? p :context-window)))
        (is (not (contains? p :max-output-tokens)))
        (is (not (contains? w :context-window)) "nor on the wire")
        (is (not (contains? w :max-output-tokens))))
      (providers/set-override! "p-silent" nil))))

(deftest a-count-that-is-not-a-positive-integer-fails-by-name
  (doseq [[k v] [[:context-window 0]
                 [:context-window -1]
                 [:context-window 1.5]
                 [:context-window "8192"]
                 [:max-output-tokens 0]]]
    (with-home (cfg :alpha)
               (pr-str {:alpha {:protocol :p :base-url "https://a/v1"
                                :model "m"
                                :models {"m" {:input #{:text} :output #{:text}
                                              k v}}}})
      (fn []
        (let [e (try (providers/catalog) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) (str k " " (pr-str v) " is not a count"))
          (is (str/includes? (ex-message e) (name k))
              "the message names the field")
          (is (str/includes? (ex-message e) "alpha")
              "and the provider it is in")
          (is (str/includes? (ex-message e) "\"m\"")
              "and the model id, so the reader knows which entry to fix"))))))

(deftest an-output-ceiling-above-the-context-window-fails-and-names-both
  ;; A model claiming to give back more than it can hold has a stale number in
  ;; it. The failure reports BOTH rather than picking which one to believe: this
  ;; harness cannot tell which is wrong, and guessing would be the lie.
  (with-home (cfg :alpha)
             (pr-str {:alpha {:protocol :p :base-url "https://a/v1" :model "m"
                              :models {"m" {:input #{:text} :output #{:text}
                                            :context-window 8000
                                            :max-output-tokens 16000}}}})
    (fn []
      (let [e (try (providers/catalog) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "16000") "names the output ceiling")
        (is (str/includes? (ex-message e) "8000") "and the context window")
        (is (= 8000 (:context-window (ex-data e))) "and carries both in the data"))))
  (testing "equal is fine -- some vendors allow the ceiling to reach the window"
    (with-home (cfg :alpha)
               (pr-str {:alpha {:protocol :p :base-url "https://a/v1" :model "m"
                                :models {"m" {:input #{:text} :output #{:text}
                                              :context-window 8000
                                              :max-output-tokens 8000}}}})
      (fn [] (is (= 8000 (:max-output-tokens (providers/effective-provider "p-eq"))))))))

(deftest a-misspelled-count-key-fails-by-name
  ;; model-keys was defined and nobody read it, so :context_window -- the typo
  ;; every Clojure programmer makes once -- was silently dropped and the entry
  ;; looked like it had declared nothing. The entry is the one place a model
  ;; speaks about itself; a silent drop there is the failure this shape kills.
  (with-home (cfg :alpha)
             (pr-str {:alpha {:protocol :p :base-url "https://a/v1" :model "m"
                              :models {"m" {:input #{:text} :output #{:text}
                                            :context_window 200000}}}})
    (fn []
      (let [e (try (providers/catalog) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a key nobody reads is a failure, not a shrug")
        (is (str/includes? (ex-message e) "context_window") "the stray key is named")
        (is (str/includes? (ex-message e) "context-window")
            "and the key it probably meant is listed among the ones it knows")))))

(deftest a-user-may-correct-one-count-without-retyping-the-modalities
  ;; Model-level merge, field by field. Replacing the entry wholesale would make
  ;; "this vendor's window is actually 131072" require restating :input/:output --
  ;; and the restated pair is exactly the copy that goes stale.
  (with-home (cfg :openrouter)
             (pr-str {:openrouter {:models {"anthropic/claude-sonnet-4.5"
                                            {:context-window 131072}}}})
    (fn []
      (let [m (get-in (providers/catalog) [:openrouter :models "anthropic/claude-sonnet-4.5"])]
        (is (= 131072 (:context-window m)) "the corrected count wins")
        (is (= #{:text :image} (:input m)) "and the built-in modalities are still there")
        (is (= 64000 (:max-output-tokens m)) "along with the count it did not touch"))
      (providers/set-override! "p-patch" {:model "anthropic/claude-sonnet-4.5"})
      (is (= 131072 (:context-window (providers/effective-provider "p-patch"))))
      (providers/set-override! "p-patch" nil))))

(deftest an-inline-provider-may-declare-the-two-counts
  (with-home (pr-str {:protocol :fake :base-url "https://inline/v1" :model "flat"
                      :context-window 128000 :max-output-tokens 4096})
             nil
    (fn []
      (let [p (providers/effective-provider "p-inl")]
        (is (= 128000 (:context-window p)))
        (is (= 4096 (:max-output-tokens p)))))))

(deftest an-inline-provider-declaring-only-a-count-still-declares-something
  ;; The counts are the reason the inline branch still builds a model entry when
  ;; no modality is declared: an entry that states only a context window HAS
  ;; stated something, and dropping it for saying nothing about modalities would
  ;; be the silent drop this catalog refuses to perform.
  (with-home (pr-str {:protocol :fake :base-url "https://inline/v1" :model "flat"
                      :context-window 128000})
             nil
    (fn []
      (let [p (providers/effective-provider "p-only-ctx")]
        (is (= 128000 (:context-window p)))
        (is (not (contains? p :input)) "and nothing was claimed about modalities")))))

(deftest an-inline-count-still-has-to-be-a-count
  (with-home (pr-str {:protocol :fake :base-url "https://inline/v1" :model "flat"
                      :context-window "big"})
             nil
    (fn []
      (let [e (try (providers/effective-provider "p-inl-bad") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "context-window"))))))
(deftest a-key-nobody-reads-is-reported-not-ignored
  ;; This is the failure mode the whole feature exists to kill: a tier naming
  ;; something the resolution never looks at, and the run succeeding anyway.
  (with-home (cfg :alpha)
             (pr-str {:alpha {:protocol :p :base-url "https://a/v1" :model "m"
                              :reasoning-effort "high"
                              :models {"m" {:input #{:text} :output #{:text}}}}})
    (fn []
      (let [e (try (providers/catalog) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "reasoning-effort")
            "the stray key is named rather than silently dropped")))))

;; -------------------------------------------------------------------- tiers

(deftest the-catalog-supplies-the-endpoint-and-the-modalities
  (with-home (cfg :alpha) reg
    (fn []
      (let [p (providers/effective-provider "p-tier")]
        (is (= :openai-completions (:protocol p)))
        (is (= "https://alpha/v1" (:base-url p)))
        (is (= "alpha-large" (:model p)) "the provider's default model")
        (is (= #{:text :image} (:input p)) "and that model's declared modalities")
        (testing "a knob no tier named is simply absent -- not an error"
          (is (not (contains? p :reasoning-effort))))))))

(deftest the-default-tier-moves-knobs-without-a-new-provider
  (with-home (cfg :alpha :reasoning-effort "low") reg
    (fn []
      (let [p (providers/effective-provider "p-default")]
        (is (= "alpha-large" (:model p)) "the provider's default still stands")
        (is (= "low" (:reasoning-effort p)) "and the default tier tuned one knob")))))

(deftest a-tier-that-names-a-catalog-field-fails-by-name
  ;; The counts (and the endpoint, and the modalities) are the catalog's to
  ;; declare, not a tier's to choose. select-keys would drop them quietly and the
  ;; run would succeed having done nothing -- the exact failure this shape was
  ;; built to kill. The config tier is one of the three entries; the other two are
  ;; exercised further down (the run request) and in the configure-tool section.
  (doseq [[k v] [[:context-window 200000] [:max-output-tokens 8192]]]
    (with-home (pr-str {:provider :alpha :model "alpha-small" k v}) reg
      (fn []
        (let [e (try (providers/effective-provider "p-tier-bad") nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) (str k " is not a tier's to name"))
          (is (str/includes? (ex-message e) (name k))
              "the message names the field")
          (is (str/includes? (ex-message e) ":providers")
              "and says where it belongs instead"))))))

(deftest switching-provider-alone-really-switches-vendors
  ;; THE regression this feature exists for. Under the old field-by-field merge,
  ;; :provider was not a field and a tier naming one was silently dropped: the
  ;; session kept the old endpoint while reporting success. Every part of the
  ;; resolution moves together now, because the endpoint FOLLOWS the selection.
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (testing "the default tier's vendor"
          (let [p (providers/effective-provider "p-switch")]
            (is (= "https://alpha/v1" (:base-url p)))
            (is (= "alpha-large" (:model p)))))
        (testing "a session override naming ONLY a provider lands on that vendor's
                  endpoint AND its default model -- no :model named, none carried over"
          (providers/set-override! "p-switch" {:provider :beta})
          (let [p (providers/effective-provider "p-switch")]
            (is (= "https://beta/v1" (:base-url p)))
            (is (= "beta-plain" (:model p)) "beta's default, NOT alpha's alpha-large")
            (is (= #{:text} (:input p)) "and beta's model's modalities, not alpha's")))
        (testing "and the default tier's other knobs survive the vendor switch"
          (providers/set-override! "p-switch" {:provider :gamma})
          (is (= "http://localhost:11434/v1" (:base-url (providers/effective-provider "p-switch")))))
        (finally (providers/set-override! "p-switch" nil))))))

(deftest a-session-override-sits-above-the-default-tier
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (providers/set-override! "p-sess" {:model "alpha-small"})
        (let [p (providers/effective-provider "p-sess")]
          (is (= "alpha-small" (:model p)))
          (is (= :openai-completions (:protocol p)) "untouched knobs inherit below")
          (testing "and the modalities follow the MODEL, not the provider"
            (is (= #{:text :image} (:input p)))))
        (testing "and another session is untouched"
          (is (= "alpha-large" (:model (providers/effective-provider "p-sess-other")))))
        (finally (providers/set-override! "p-sess" nil))))))

(deftest a-session-can-pick-a-text-only-model-and-then-says-so
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (providers/set-override! "p-mod" {:provider :beta :model "beta-big"})
        (let [p (providers/effective-provider "p-mod")]
          (is (= "beta-big" (:model p)))
          (is (= #{:text} (:input p))
              "the modalities are the chosen model's, so the guard has something to enforce"))
        (finally (providers/set-override! "p-mod" nil))))))

(deftest a-run-request-sits-on-top-of-everything
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (providers/set-override! "p-req" {:model "alpha-small"})
        (let [{:keys [provider source]} (providers/resolve-provider "p-req" {:model "alpha-vision-free"})]
          (is (= "alpha-vision-free" (:model provider)))
          (is (= :request source)))
        (testing "with no request, the default tier is where the resolution started"
          (is (= :default (:source (providers/resolve-provider "p-req")))))
        (testing "and a request can switch the vendor too"
          (let [{:keys [provider]} (providers/resolve-provider "p-req" {:provider :gamma})]
            (is (= "http://localhost:11434/v1" (:base-url provider)))
            (is (= "qwen3" (:model provider)) "the vendor's default, not the session's model")))
        (finally (providers/set-override! "p-req" nil))))))

(deftest a-run-request-that-names-a-catalog-field-fails-by-name
  ;; The third entry: a run's request. It goes through the same selection
  ;; boundary, so naming a count here is refused by name rather than folded away.
  (with-home (cfg :alpha) reg
    (fn []
      (let [e (try (providers/resolve-provider "p-req-bad" {:model "alpha-small"
                                                      :context-window 200000})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "context-window"))
        (is (str/includes? (ex-message e) ":providers"))))))

(deftest an-inline-provider-needs-no-catalog-at-all
  ;; The escape hatch: describe one endpoint in config.edn and go. Kept because
  ;; registering a provider to try it once is friction, and because the built-in
  ;; catalog is a starting point rather than a gate.
  (with-home "{:protocol :fake :base-url \"https://inline/v1\" :model \"flat\" :input #{:text} :output #{:text}}\n"
             nil
    (fn []
      (let [p (providers/effective-provider "p-flat")]
        (is (= "https://inline/v1" (:base-url p)))
        (is (= "flat" (:model p)))
        (is (= #{:text} (:input p)))
        (is (= :inline (:source (providers/resolve-provider "p-flat"))))
        (testing "and it carries no :provider -- nothing was named"
          (is (not (contains? p :provider))))))))

(deftest an-inline-provider-may-declare-nothing
  ;; Modalities are optional in the inline form. Declaring nothing is a
  ;; statement ('this entry promises nothing'), not an error -- which is what
  ;; lets a bare {:protocol :fake} config work in tests and in a minimal setup.
  (with-home "{:protocol :fake :base-url \"https://bare/v1\" :model \"m\"}\n" nil
    (fn []
      (let [p (providers/effective-provider "p-bare")]
        (is (= "https://bare/v1" (:base-url p)))
        (is (not (contains? p :input)) "nothing was declared, so nothing is claimed")
        (is (not (contains? p :output)))))))

(deftest an-inline-provider-declaring-half-a-capability-is-refused
  (with-home "{:protocol :fake :base-url \"https://half/v1\" :model \"m\" :input #{:text}}\n" nil
    (fn []
      (let [e (try (providers/effective-provider "p-half") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) ":output"))))))

(deftest a-named-but-missing-provider-fails-naming-what-it-looked-for
  (with-home (cfg :nope) reg
    (fn []
      (let [e (try (providers/effective-provider "p-bad") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a name that is not in the catalog is a hard failure")
        (is (str/includes? (ex-message e) "nope") "the message names the missing provider")
        (is (str/includes? (ex-message e) "alpha") "and lists what IS defined")))))

(deftest a-model-id-from-another-vendor-fails-and-lists-this-vendors-models
  (with-home (cfg :beta) reg
    (fn []
      (try
        (providers/set-override! "p-x" {:model "alpha-large"})
        (let [e (try (providers/effective-provider "p-x") nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) "alpha's model id is not beta's to serve")
          (is (str/includes? (ex-message e) "alpha-large"))
          (is (str/includes? (ex-message e) "beta-plain") "and beta's ids are listed")
          (is (not (str/includes? (ex-message e) "alpha-small"))
              "while another vendor's ids are NOT -- the list is this provider's"))
        (finally (providers/set-override! "p-x" nil))))))

(deftest a-config-that-names-nothing-and-describes-nothing-says-so
  (with-home "{:reasoning-effort \"high\"}\n" reg
    (fn []
      (let [e (try (providers/effective-provider "p-none") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) ":provider") "names the first shape")
        (is (str/includes? (ex-message e) ":protocol") "and the second")))))

(deftest the-catalog-is-a-map-of-names-to-providers
  (with-home (cfg :alpha) "[{:protocol :p}]\n"
    (fn []
      (let [e (try (providers/catalog) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a :providers section that is not a map fails rather than iterating")
        (is (str/includes? (ex-message e) ":providers"))))))

(deftest providers-are-named-by-keyword-string-or-symbol
  ;; Names arrive from EDN (keyword), from JSON (string) and from a tool call
  ;; (either). All three spell the same provider; none of them mints a second.
  (with-home (pr-str {:provider "alpha"}) reg
    (fn []
      (is (= "https://alpha/v1" (:base-url (providers/effective-provider "p-name"))))
      (providers/set-override! "p-name" {:provider 'alpha})
      (is (= "https://alpha/v1" (:base-url (providers/effective-provider "p-name"))))
      (providers/set-override! "p-name" nil))))

(deftest the-api-key-is-attached-last-and-only-in-the-resolver
  (with-home (cfg :alpha) reg
    (fn []
      (let [p (providers/effective-provider "p-key")]
        (is (contains? p :api-key) "the assembled provider carries the key slot")
        (testing "but the introspectable answer deliberately does not"
          (let [a (providers/active-provider "p-key")]
            (is (not (contains? a :api-key)))
            (is (not-any? #(str/includes? (str %) "api-key")
                          (tree-seq coll? seq a)))))))))

;; ------------------------------------------------------------- display names

(deftest a-display-name-is-a-label-and-never-an-identity
  ;; The reference form asks for a Provider ID AND a display name. The ID stays
  ;; the identity everywhere that matters -- the credential name, the :provider
  ;; knob, the log lines, what the picker SENDS -- and the display name is what a
  ;; person reads. So the tests here are about which of the two each surface
  ;; carries, because 'the label leaked into the identity' is the failure that
  ;; would show up as a log line nobody can match to a config entry.
  (with-home (cfg :alpha) (pr-str {:alpha (assoc (:alpha (read-string reg)) :display-name "Alpha Corp")})
    (fn []
      (testing "the resolution carries the label next to the name"
        (let [p (providers/active-provider "t-label")]
          (is (= :alpha (:provider p)) "the ID is still the identity")
          (is (= "Alpha Corp" (:display-name p)) "and the label rides along")))

      (testing "the picker is handed BOTH, as two keys"
        ;; Not one already-decided string: what to show is the client's business,
        ;; what to send is the id.
        (let [rows (:providers (providers/choices "t-label"))
              row  (first (filter #(= "alpha" (:name %)) rows))]
          (is (= "alpha" (:name row)))
          (is (= "Alpha Corp" (:display-name row)))))

      (testing "a tier may not name it -- it is the catalog's answer"
        ;; THE TRAP THIS GUARDS: catalog-fields used to be a literal list, so a
        ;; field added to resolved-fields without being added there was a field a
        ;; tier could name and have SILENTLY DROPPED -- the exact failure this
        ;; catalog exists to kill. It is derived now; this is the test that says so.
        (let [e (try (providers/resolve-provider "t-label"
                                                 {:display-name "Beta Corp"})
                     nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) "naming the label in a tier fails rather than being dropped")
          (is (str/includes? (ex-message e) "display-name"))))

    (testing "and an entry without one says nothing rather than saying nil"
      (with-home (cfg :alpha) reg
        (fn []
          (is (not (contains? (providers/active-provider "t-label2") :display-name)))
          (let [rows (:providers (providers/choices "t-label2"))
                row  (first (filter #(= "alpha" (:name %)) rows))]
            (is (some? row) "the provider is in the picker's list at all")
            (is (not (contains? row :display-name))
                "and the row carries no label -- absence, not nil")))))))

(deftest a-display-name-is-patched-and-validated-like-any-other-field
  (testing "a one-line patch that adds only a label is legitimate"
    ;; The merge is field by field, which is what makes 'give the built-in ollama
    ;; a nicer name' one line rather than a retyped entry.
    (with-home (cfg :ollama) (pr-str {:ollama {:display-name "Local ollama"}})
      (fn []
        (let [p (providers/active-provider "t-patch-label")]
          (is (= "Local ollama" (:display-name p)))
          (is (= "http://localhost:11434/v1" (:base-url p)) "and the rest is borrowed")
          (is (= "qwen3" (:model p)))))))

  (testing "but a label that is not a label is a named failure"
    (doseq [bad ["" "   " 42 :alpha ["Alpha"]]]
      (with-home (cfg :alpha)
                 (pr-str {:alpha (assoc (:alpha (read-string reg)) :display-name bad)})
        (fn []
          (let [e (try (providers/catalog) nil (catch clojure.lang.ExceptionInfo e e))]
            (is (some? e) (str "a display name of " (pr-str bad) " is refused"))
            (is (str/includes? (ex-message e) "display-name")
                "and the field is named")))))))

;; ------------------------------------------------------------ credential names(deftest a-providers-credential-name-is-derived-from-its-id
  ;; The rule the reference form's help text states in words ("the id ... is used
  ;; to derive the credential name"), written down as the one function both sides
  ;; call: the reader here, and whatever writes the .env line.
  (is (= "ACME_GATEWAY_API_KEY" (providers/credential-name :acme-gateway)))
  (is (= "OPENROUTER_API_KEY"   (providers/credential-name :openrouter))
      "the built-in vendors derive names too -- one rule, not a special case")
  (is (= "ACME_GATEWAY_API_KEY" (providers/credential-name "acme.gateway"))
      "a string id derives the same name a keyword does")
  (is (= "MY_VENDOR_API_KEY"    (providers/credential-name :My_Vendor))
      "and a hand-written id that breaks the form's rule still resolves a key
       rather than failing over punctuation nobody chose")

  (testing "and the rule is NOT injective -- which is why the form's id rule exists"
    ;; Not a defect to be fixed: no shell-friendly name can distinguish every id.
    ;; What keeps it from mattering is that the settings form accepts one spelling
    ;; (^[a-z][a-z0-9-]*$), so two ids that differ only in punctuation cannot both
    ;; come from it. Stated here as a fact with a test, not a claim in a comment.
    (is (= (providers/credential-name :a-b)
           (providers/credential-name :a_b)
           (providers/credential-name :a.b)))))

(deftest the-key-comes-from-the-providers-own-name-then-the-global-one
  ;; One key per provider, with the global one as the floor: that is what lets this
  ;; land on a home that only ever had HARNESS_API_KEY (every home, before this).
  (with-home (cfg :alpha) reg
    (fn []
      (with-dotenv (str "HARNESS_API_KEY=the-global\n"
                        "ALPHA_API_KEY=alphas-own\n")
        (fn []
          (is (= "alphas-own" (:api-key (providers/effective-provider "t-cred")))
              "the provider's own name wins")
          (testing "a provider with no line of its own falls back to the global one"
            (providers/set-override! "t-cred" {:provider :beta})
            (is (= "the-global" (:api-key (providers/effective-provider "t-cred"))))
            (providers/set-override! "t-cred" nil))))
      (testing "and with neither name present the slot is nil rather than a guess"
        (with-dotenv nil
          (fn []
            (is (nil? (:api-key (providers/effective-provider "t-cred")))
                "no key configured is a normal state: offline tools run without one")))))))

(deftest an-inline-provider-has-only-the-global-key
  ;; A description has no NAME, so there is no credential name to derive -- and
  ;; inventing one from the endpoint would be a line nobody could have known to
  ;; write. The global fallback is what serves it, which is also what served every
  ;; inline description before this rule existed.
  (with-home (pr-str {:protocol :fake :base-url "https://inline/v1" :model "flat"}) nil
    (fn []
      (with-dotenv (str "HARNESS_API_KEY=the-global\n"
                        "INLINE_API_KEY=nobody-writes-this\n")
        (fn []
          (is (= "the-global" (:api-key (providers/effective-provider "t-inline")))))))))

(deftest api-key-source-names-the-line-either-way
  ;; The panel's answer: presence, origin, AND the name -- so the sentence a person
  ;; reads is "edit this line", not "derive this line yourself".
  (with-home (cfg :alpha) reg
    (fn []
      (with-dotenv "ALPHA_API_KEY=alphas-own\n"
        (fn []
          (is (= {:present? true :source :env-file :name "ALPHA_API_KEY"}
                 (providers/api-key-source :alpha))
              "the name that WON")))
      (testing "no key at all still names the line that would be read first"
        (with-dotenv nil
          (fn []
            (is (= {:present? false :source nil :name "ALPHA_API_KEY"}
                   (providers/api-key-source :alpha)))
            (is (= {:present? false :source nil :name "HARNESS_API_KEY"}
                   (providers/api-key-source nil))
                "an inline provider can only ever name the global one"))))
      (testing "and the name is a NAME -- never a value, a length or a prefix"
        (with-dotenv (str "ALPHA_API_KEY=" sentinel "\n")
          (fn []
            (let [body (json/write-str (providers/api-key-source :alpha))]
              (is (not (str/includes? body sentinel)))
              (is (not (str/includes? body (subs sentinel 0 12))))
              (is (not (str/includes? body (str (count sentinel))))))))))))

;; ----------------------------------------------------------- wire rendering

(deftest modalities-render-as-sorted-string-vectors-on-the-wire
  ;; JSON has no sets. Sorting is not cosmetic: an unsorted set would make two
  ;; identical runs produce log lines that differ, and a reader diffing a
  ;; timeline would see changes that never happened.
  (let [m {:provider :alpha :model "m" :input #{:text :image} :output #{:text}}]
    (is (= ["image" "text"] (:input (providers/wire m))) "a set becomes a sorted vector of names")
    (is (= ["text"] (:output (providers/wire m))))
    (testing "and naming fields renders only those fields"
      (is (= {:input ["image" "text"]} (providers/wire m [:input])))
      (is (= #{:provider :model} (set (keys (providers/wire m [:provider :model]))))))))

(deftest a-value-no-tier-named-stays-absent-on-the-wire
  ;; Absent is a fact -- 'nothing chose this'. null would read as 'chose nothing',
  ;; which is a different thing and a worse one.
  (let [w (providers/wire {:provider :alpha :model "m"})]
    (is (not (contains? w :reasoning-effort)))
    (is (not (contains? w :input)))))

(deftest the-wire-shape-cannot-name-the-key
  ;; wire is the boundary every provider shape crosses on its way to a log, an
  ;; HTTP body or a tool result. Naming :api-key in the fields still produces a
  ;; shape without it, so a future call site -- or a helper rendering
  ;; 'everything' -- cannot leak one by accident.
  (let [w (providers/wire {:provider :alpha :model "m" :api-key "SECRET" :protocol :p}
                       [:provider :model :protocol :api-key])]
    (is (not (contains? w :api-key)))
    (is (not (str/includes? (pr-str w) "SECRET")))
    (is (= :p (:protocol w)) "and the fields that WERE asked for are still there")))

;; ------------------------------------------------------------- introspection

(deftest log-path-names-the-file-the-writer-writes
  ;; The DIRECTORY is the caller's now -- which workspace a session belongs in
  ;; follows from its project, which harness.infra.home deliberately does not know. So
  ;; the claim here is the part home still owns: one rule turns an id into a
  ;; filename, and log-path and log-file agree about it.
  (let [d (io/file (home/projects-dir) "a-workspace")]
    (testing "log-path names exactly the file log-file points at"
      (is (= (str (home/log-file d "t-logpath")) (home/log-path d "t-logpath")))
      (is (str/ends-with? (home/log-path d "t-logpath") "t-logpath.jsonl")))
    (testing "and it lands inside the directory it was handed, not under the root"
      (is (= (.getCanonicalPath d)
             (.getCanonicalPath (.getParentFile (home/log-file d "t-logpath"))))))
    (testing "the sanitize rule is shared, so the reader and writer agree"
      (is (= "a_b_c.jsonl" (str/replace (home/log-path d "a/b c") #".*[\\/]" ""))))))

(deftest active-provider-answers-both-what-was-chosen-and-what-it-resolved-to
  (with-home (cfg :alpha :reasoning-effort "high") reg
    (fn []
      (let [a (providers/active-provider "t-act")]
        (is (= :alpha (:provider a)) "the name that was chosen")
        (is (= "alpha-large" (:model a)) "the model id that was chosen")
        (is (= "high" (:reasoning-effort a)) "and the effort that was chosen")
        (is (= :openai-completions (:protocol a)) "what the catalog resolved it to")
        (is (= "https://alpha/v1" (:base-url a)))
        (is (= #{:text :image} (:input a)) "the model's modalities, as sets in-process")
        (is (= #{:text} (:output a))))
      (testing "a change is visible to the very next call -- nothing is cached"
        (providers/set-override! "t-act" {:provider :beta})
        (let [a (providers/active-provider "t-act")]
          (is (= :beta (:provider a)))
          (is (= "https://beta/v1" (:base-url a)) "the endpoint followed the vendor")
          (is (= "beta-plain" (:model a)))
          (is (= #{:text} (:input a))))
        (providers/set-override! "t-act" nil))
      (testing "and no api-key key exists, at any nesting depth"
        (is (not-any? #(str/includes? (str %) "api-key")
                      (tree-seq coll? seq (providers/active-provider "t-act"))))))))

;; ------------------------------------------------- the outbox the edge drains
;;
;; The `session-configure` tool that used to feed this is gone, so nothing records a
;; change in production today. The mechanism stays -- `provider/changed` is a line the
;; prompt context reads back (harness.edge.context), and the edge's drain is live code --
;; so its contract is pinned here, driving the recorder directly instead of through a
;; tool that no longer exists.

(deftest a-recorded-change-is-drained-once-and-carries-what-it-was-given
  ;; The recorder is a plain function now: what the caller hands it is what the edge
  ;; writes down, once. :before/:after are the session's tier before and after (the whole
  ;; tier, not just the patch), :override is that same tier afterwards so a reader can
  ;; reconstruct the session from the line alone, and :resolved is that tier ASSEMBLED --
  ;; the catalog moves under an old log, so a reader must not re-resolve.
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (providers/set-override! "t-outbox" {:reasoning-effort "low"})
        (let [{:keys [before after resolved]}
              (providers/swap-override! "t-outbox" {:model "alpha-small"})]
          (providers/record-provider-change! "t-outbox" before after "a-path" after resolved)
          (let [[c & more] (providers/take-provider-changes! "t-outbox")]
            (is (some? c))
            (is (empty? more) "exactly one change was queued")
            (is (= "a-path" (:trigger c))
                "the trigger is the caller's string, verbatim -- a path, not a tool")
            (is (= {:reasoning-effort "low"} (:before c))
                "the before side is the tier that stood")
            (is (= {:model "alpha-small" :reasoning-effort "low"} (:after c))
                "and the after side is the whole tier, not just what moved")
            (is (= (:after c) (:override c))
                "the override is the whole tier, so the line stands on its own")
            (is (= "https://alpha/v1" (get-in c [:resolved :base-url])))
            (is (= "alpha-small" (get-in c [:resolved :model])))
            (testing "and it is drained for one thread at a time, exactly once"
              (providers/record-provider-change! "t-other" before after "a-path" after resolved)
              (is (empty? (providers/take-provider-changes! "t-outbox"))
                  "the outbox is not read twice")
              (is (= 1 (count (providers/take-provider-changes! "t-other")))
                  "and another session's change was not swept up by it"))))
        (finally (providers/set-override! "t-outbox" nil))))))

;; ------------------------------------------------------------------ settings

(defn- fresh-home
  "A home of this test's OWN, containing exactly the one config.edn these two
  sections make up and nothing else -- then point harness.infra.home at it for the
  duration of F.

  The shared runner home will not do for these tests: the settings answer reports
  WHICH FILES EXIST, and by the time this namespace runs, the store and whatever
  else earlier namespaces left are sitting in it. A test whose claim is 'this home
  has no store' has to own its home."
  [config providers f]
  (let [dir (io/file (support/temp-dir "settings"))]
    (when (or config providers)
      (spit (io/file dir "config.edn") (support/config-text config providers) :encoding "UTF-8"))
    (with-redefs [home/root (constantly (str dir))]
      (f dir))))

(defn- settings->json
  "providers/settings rendered exactly the way the http edge renders it, parsed
  back -- so a test reads the same bytes a browser would. Keywords arrive as
  strings here, because that is what crosses the wire."
  [thread-id]
  (let [s (providers/settings thread-id)]
    (json/read-str (json/write-str (providers/wire s (keys s))) :key-fn keyword)))

(deftest settings-answers-the-live-configuration-without-ever-the-key
  ;; The read-only panel's whole contract: what is in force, where each choice
  ;; came from, where the key WOULD be read from -- and the key itself, absent
  ;; from every byte of the rendered answer.
  (fresh-home (cfg :alpha :reasoning-effort "low") reg
    (fn [_]
      (with-dotenv (str "HARNESS_API_KEY=" sentinel "\n")
        (fn []
          (try
            (let [s      (providers/settings "st-1")
                  body   (json/write-str (providers/wire s (keys s)))
                  parsed (json/read-str body :key-fn keyword)]

              (testing "the resolved configuration is in the answer"
                (is (= "alpha-large" (:model parsed)) "the provider's own default model")
                (is (= "alpha" (:provider parsed)))
                (is (= "https://alpha/v1" (:base-url parsed)))
                (is (= "low" (:reasoning-effort parsed)))
                (is (= ["image" "text"] (:input parsed))
                    "modality sets render as sorted string vectors, like every other wire shape"))

              (testing "and every knob says which tier chose it"
                (is (= {:provider "config" :model "catalog" :reasoning-effort "config"}
                       (:tiers parsed))
                    ":catalog is the honest answer for a model nobody named"))

              (testing "the session's own tier is credited when it is the one that moved"
                (providers/set-override! "st-1" {:model "alpha-small"})
                (let [s2 (settings->json "st-1")]
                  (is (= "alpha-small" (:model s2)))
                  (is (= "session" (:model (:tiers s2))))
                  (is (= "config" (:provider (:tiers s2))) "and the vendor is still config's")))

              (testing "a vendor switch credits the session AND re-attributes the model"
                ;; The model a tier named is dropped when it switches vendor (see
                ;; fold-selection), so what ends up in force is the new entry's
                ;; default -- chosen by nobody, which the panel has to say rather
                ;; than credit to a tier that no longer has a say.
                (providers/set-override! "st-1" {:provider :beta})
                (let [s3 (providers/settings "st-1")]
                  (is (= "beta-plain" (:model s3)))
                  (is (= :session (:provider (:tiers s3))))
                  (is (= :catalog (:model (:tiers s3))))))

              (testing "the key is reported as presence and origin, and NOTHING else"
                (is (= {:present? true :source "env-file" :name "HARNESS_API_KEY"}
                       (:key parsed))
                    "the .env wins over the environment, which is api-key's own
                     precedence -- and the name says WHICH line did it: alpha has no
                     ALPHA_API_KEY here, so the global one is what this session reads")
                (is (not (contains? parsed :api-key))
                    "and no field is named after the secret at all"))

              (testing "a string search of the WHOLE body does not find it"
                (is (not (str/includes? body sentinel)))
                (is (not (str/includes? body (subs sentinel 0 12)))
                    "not even the prefix: a partially redacted key is still a leak"))
              (testing "and not its length either -- as a VALUE anywhere in the answer"
                ;; AS A VALUE, not as a substring, and the difference is a bug this
                ;; assertion used to have. The body always carries this home's
                ;; ABSOLUTE PATH, whose millisecond stamp is an arbitrary digit
                ;; run -- so a run whose stamp happened to contain "39" (the
                ;; sentinel's length) failed the substring form for a reason that
                ;; had nothing to do with the key, roughly one run in fifty. A path
                ;; is a string, so it can never be `=` to a number, and the claim
                ;; -- the length does not appear in the answer -- survives intact.
                (is (not-any? #(= (count sentinel) %)
                              (remove coll? (tree-seq coll? seq parsed))))
                (is (not (contains? parsed (keyword (str (count sentinel))))))))
              (finally
                (providers/set-override! "st-1" nil))))))))

(deftest settings-answers-what-this-home-is-made-of-and-writes-nothing
  ;; The rest of the panel: the root, which rule produced it, and which files are
  ;; there. "Read-only" is held by looking -- no store appears, and every file's
  ;; bytes and mtime are what they were.
  (fresh-home (cfg :alpha) reg
    (fn [dir]
      (let [facts  (fn []
                     (into {} (for [f (file-seq dir) :when (.isFile ^java.io.File f)]
                                [(.getName ^java.io.File f)
                                 [(.length ^java.io.File f) (.lastModified ^java.io.File f)]])))
            before (facts)]
        (dotimes [_ 3] (providers/settings "st-ro"))
        (testing "asking wrote nothing"
          (is (= before (facts)))
          (is (not (.exists (home/db-file)))
              "and asking the configuration a question did not create the store"))
        (let [parsed (settings->json "st-ro")
              rows   (:files (:home parsed))
              row    (fn [n] (first (filter (fn [f] (= n (:name f))) rows)))]
          (testing "the root is named, with the rule that produced it"
            (is (= (str dir) (:path (:home parsed))))
            (is (= "override" (:origin (:home parsed)))
                "this suite runs under the runner's root override, and the panel
                says so rather than calling it a default -- which is exactly the
                honesty the field exists for. The other two rules are covered by
                the fresh-JVM test below, where neither is bound."))
          (testing "every file this home is made of is listed, present or not"
            (is (= ["config.edn" "hooks.edn" ".env" "harness.db"]
                   (mapv :name rows))
                "four files, and providers.edn is not one of them any more: it is
                not a thing a person edits in this home, so the panel does not
                pretend otherwise -- a home still holding one is refused by the
                catalog instead, with a sentence saying to move it into config.edn")
            (is (true?  (:present? (row "config.edn"))) "this home has the one config file")
            (is (false? (:present? (row "hooks.edn"))) "and no hooks file")
            (is (false? (:present? (row ".env"))))
            (is (false? (:present? (row "harness.db")))
                "the store is a file this home MAY have, not one it does")))))))

(deftest settings-rereads-the-config-every-time
  ;; The one place a person can SEE the "configuration is files, read fresh"
  ;; discipline. It is the same question the store asks from the other end:
  ;; opening harness.db must not read config.edn, and this must not read the store.
  ;;
  ;; Each edit rewrites the WHOLE file, :providers section included, because that
  ;; is what an edit is now: one file holds the vendor catalog and the default
  ;; tier, and a rewrite that dropped the catalog would be a different test.
  (fresh-home (cfg :alpha) reg
    (fn [_]
      (is (= "alpha-large" (:model (providers/settings "st-r"))))
      (spit (home/config-file) (support/config-text (cfg :beta) reg) :encoding "UTF-8")
      (let [after (providers/settings "st-r")]
        (is (= :beta (:provider after)) "the file's new content is what is in force")
        (is (= "beta-plain" (:model after)) "including the model it brought with it"))
      (spit (home/config-file)
            (support/config-text (pr-str {:provider :alpha :reasoning-effort "high"}) reg)
            :encoding "UTF-8")
      (is (= "high" (:reasoning-effort (providers/settings "st-r")))
          "and a second edit is visible too -- nothing is cached between calls")
      (testing "the panel's own answer changes with it, tiers and all"
        (let [parsed (settings->json "st-r")]
          (is (= "alpha" (:provider parsed)))
          (is (= "config" (:reasoning-effort (:tiers parsed)))
              "the knob the newer file names is still config's doing"))))))

(deftest settings-refuses-a-configuration-it-cannot-resolve-by-name
  ;; A half-edited config.edn is the ordinary way a person meets this, and the
  ;; reason is what the panel shows: "no provider named :nope; the registry
  ;; defines [...]" is worth more than an empty panel.
  (fresh-home (cfg :nope) reg
    (fn [_]
      (let [e (try (providers/settings "st-bad") nil (catch Exception ex ex))]
        (is (some? e) "an unresolvable configuration fails rather than answering")
        (is (str/includes? (ex-message e) "no provider named")))
      (testing "and a config.edn that is not there at all is the SAME ANSWER, not a
                different error: absence and emptiness are one fact about a home"
        (io/delete-file (home/config-file) true)
        (let [e (try (providers/settings "st-bad") nil (catch Exception ex ex))]
          (is (some? e))
          (is (str/includes? (ex-message e) "no provider")
              "the sentence a run with no default tier meets")
          (is (str/includes? (ex-message e) "config.edn")
              "and it names the file, absolutely -- which is the useful half of the
               refusal that used to carry the path")
          (is (not (.exists (home/config-file)))
              "and asking did not create it: reads leave the home as they found it"))))))

(defn- spawn-child
  "Run FORM in a NEW JVM and return its combined output. DIR is handed over as
  CLJ_HARNESS_HOME when non-nil, and USER-HOME as the -Duser.home property --
  which is the only way to exercise the DEFAULT root rule without reading or
  writing the developer's real ~/.clj-harness.

  ENV is a {NAME value} map put INTO the child's environment (a NAME mapped to nil
  is REMOVED from it). A map rather than one value because the key lookup now
  considers more than one name -- a provider's own derived name and the global
  fallback -- and a test about precedence between sources has to be able to put
  different names in each source.

  A real second process is required for both facts these children report: a JVM
  reads its environment once, at startup, so System/getenv cannot be moved from
  inside a test."
  [dir user-home env form]
  (let [args (cond-> ["clojure"]
               user-home (into [(str "-J-Duser.home=" user-home)])
               true      (into ["-M" "-e" form]))
        pb   (doto (ProcessBuilder. ^java.util.List (vec args))
               (.directory (io/file (System/getProperty "user.dir")))
               (.redirectErrorStream true))]
    (when dir (.put (.environment pb) "CLJ_HARNESS_HOME" (.getAbsolutePath (io/file dir))))
    (when (nil? dir) (.remove (.environment pb) "CLJ_HARNESS_HOME"))
    ;; The two names this repository reads for a provider key, always cleared
    ;; first: a nil value REMOVES the name, so a child whose environment this test
    ;; does not control cannot leak a key into the precedence it is measuring.
    (doseq [n ["HARNESS_API_KEY" "ALPHA_API_KEY"]]
      (if-some [v (get env n)]
        (.put (.environment pb) n v)
        (.remove (.environment pb) n)))
    (let [p   (.start pb)
          out (slurp (.getInputStream p) :encoding "UTF-8")]
      (when-not (.waitFor p 120 java.util.concurrent.TimeUnit/SECONDS)
        (.destroyForcibly p))
      out)))

(def ^:private report
  "(require (quote [harness.infra.home :as h]) (quote [harness.cap.providers :as p]))
   (let [s (p/settings nil)] (prn {:root (h/root) :origin (:origin (:home s)) :key (:key s)}))")

(defn- printed
  "PATH the way the child's `prn` carries it: Clojure's printed form of the string.

  THE CHILD ANSWERS A MAP, so the root arrives as a PRINTED string, and a Windows
  path is printed with its backslashes escaped -- `C:\\\\Users\\\\..`. Comparing that
  against `.getAbsolutePath` fails here and would pass on POSIX, where there is
  nothing to escape: a red suite that names nothing about the harness. Printing the
  expected value the same way is the one comparison that holds on both."
  [p]
  (pr-str (str p)))

(deftest the-root-rule-and-the-key-source-are-each-reported-truthfully
  ;; Two facts, and the second source of each needs a process whose environment
  ;; and user.home this JVM cannot change: the environment variable for the root
  ;; and the key, and the DEFAULT root -- which is only reachable when
  ;; CLJ_HARNESS_HOME is absent, so the child is given a user.home of its own
  ;; rather than being pointed at the developer's real home.
  (let [env-home (io/file (support/temp-dir "settings-env"))
        def-user (io/file (support/temp-dir "settings-user"))
        def-home (io/file def-user ".clj-harness")]
    (doseq [d [def-home]] (.mkdirs d))
    (doseq [d [env-home def-home]]
      (spit (io/file d "config.edn") (support/config-text (cfg :alpha) reg) :encoding "UTF-8"))

    (testing "with CLJ_HARNESS_HOME set, that is the rule named -- and the key
              comes from the environment because that home has no .env"
      (let [out (spawn-child env-home nil {"HARNESS_API_KEY" sentinel} report)]
        (is (str/includes? out ":environment") out)
        (is (str/includes? out (printed (.getAbsolutePath env-home))) out)
        (is (not (str/includes? out sentinel)) "and the key's VALUE is printed nowhere")
        (is (not (str/includes? out (subs sentinel 0 12))) out)))

    (testing "and the provider's OWN name is what the environment is asked for"
      ;; The name-major half of the rule, in the one place it can be measured: a
      ;; real environment has a provider's derived name and the home's .env has
      ;; nothing -- so the answer has to say WHICH name supplied it.
      (let [out (spawn-child env-home nil {"ALPHA_API_KEY" sentinel} report)]
        (is (str/includes? out ":name \"ALPHA_API_KEY\"") out)
        (is (str/includes? out ":source :environment") out)
        (is (not (str/includes? out sentinel)) "and the value is still nowhere")))

    (testing "the .env is consulted for EVERY name before the environment is asked
              about any -- so a file's global key is not overridden by an exported
              provider name"
      ;; The source-major half, and it is the promise env-value has always made
      ;; ("a shell variable does not override the file"), extended to the case this
      ;; feature created: two names, two sources.
      (let [dotenv (io/file env-home ".env")]
        (spit dotenv "HARNESS_API_KEY=from-the-file\n" :encoding "UTF-8")
        (try
          (let [out (spawn-child env-home nil {"ALPHA_API_KEY" sentinel} report)]
            (is (str/includes? out ":name \"HARNESS_API_KEY\"") out)
            (is (str/includes? out ":source :env-file") out))
          (finally (io/delete-file dotenv true)))))

    (testing "with no CLJ_HARNESS_HOME, the default rule is named"
      (let [out (spawn-child nil (.getAbsolutePath def-user) nil report)]
        (is (str/includes? out ":default") out)
        ;; The rule's OWN spelling, separators and all: `root` answers the JVM
        ;; property with `/.clj-harness` appended, so the child prints a mixed
        ;; `..\\harness-settings-user-X/.clj-harness` and a path built by `io/file`
        ;; would not be the string it is being compared with.
        (is (str/includes? out (printed (str (.getAbsolutePath def-user) "/.clj-harness"))) out)
        (is (str/includes? out ":present? false")
            "and a home with neither .env nor the variable reports no key at all")
        (is (str/includes? out "ALPHA") 
            "naming the line it looked for, so the panel can say which one to add")))))

;; ------------------------------------------- the form's two halves: read and write

(defn- file-facts
  "The home's config.edn as a fact that can be compared: its bytes and its mtime.
  A rewrite that changes nothing must move NEITHER, and a refusal must move
  neither -- the two claims the writer makes about a file it does not own."
  []
  (let [f (home/config-file)]
    (when (.exists f) [(.length f) (.lastModified f) (slurp f :encoding "UTF-8")])))

(defn- form-entry
  "A form submission: one provider, one model, as the route hands it over (keyword
  keys, because that is what the JSON parse produces)."
  [& {:as over}]
  (merge {:protocol "openai-completions"
          :base-url "https://gateway.example/v1"
          :model    "gpt-x"
          :models   [{:id "gpt-x" :input ["text"] :output ["text"]}]}
         over))

(deftest the-registry-report-says-what-this-home-has
  (with-home (cfg :alpha) reg
    (fn []
      (let [r    (providers/registry-report)
            row  (fn [n] (first (filter #(= n (:name %)) (:providers r))))
            seen (mapv :name (:providers r))]
        (testing "every provider the catalog serves is listed, once, sorted"
          (is (= (sort (map name (keys (providers/catalog)))) seen))
          (is (= (vec (sort seen)) seen) "and the menu has one order"))

        (testing "with where each one came from"
          (is (= :builtin (:origin (row "openrouter"))) "a built-in is the table's")
          (is (= :user (:origin (row "alpha"))) "a config.edn entry is yours"))

        (testing "with the fact each row needs to be edited"
          (is (= "https://alpha/v1" (:base-url (row "alpha"))))
          (is (= "alpha-large" (:model (row "alpha"))) "the default model's id")
          (is (= [{:id "alpha-large" :input ["image" "text"] :output ["text"]}
                  {:id "alpha-small" :input ["image" "text"] :output ["text"]}
                  {:id "alpha-vision-free" :input ["image" "text"] :output ["text"]}]
                 (:models (row "alpha")))
              "model rows are sorted by id, and their modalities are wire strings")
          (is (= "ALPHA_API_KEY" (:credential (row "alpha"))))
          (is (= {:present? false :source nil :name "ALPHA_API_KEY"} (:key (row "alpha")))
              "and the key facts never carry a value"))

        (testing "with the protocols a form may offer, read off the implementation"
          ;; THE SET IS THE MULTIMETHOD'S, read at CALL time -- so this asserts the
          ;; derivation rather than a list, and a suite where other namespaces have
          ;; defined more protocols is a suite where the form truthfully offers them.
          (is (= (sort (map name (keys (methods llm/stream!)))) (:protocols r)))
          (is (some #{"openai-completions"} (:protocols r))))

        (testing "with the default tier as the file writes it"
          ;; In-process the provider stays a KEYWORD: `wire` renders modality sets,
          ;; and it is the JSON encoder that turns a keyword into a string. So this
          ;; is the same value a client reads as "alpha".
          (is (= {:provider :alpha} (:default r))))

        (testing "and with no api-key VALUE anywhere in it"
          (with-dotenv (str "ALPHA_API_KEY=" sentinel "\n")
            (fn []
              (let [body (json/write-str (providers/registry-report))]
                (is (not (str/includes? body sentinel)))
                (is (not (str/includes? body (subs sentinel 0 12))))
                (is (not (contains? (providers/registry-report) :api-key)))))))))))

(deftest a-built-in-patch-and-a-catalog-entry-are-different-origins
  ;; The distinction the page draws, tested at the source: same name, three
  ;; meanings, and only two of them are the person's to remove.
  (with-home (cfg :alpha) reg
    (fn []
      (let [row (fn [n] (first (filter #(= n (:name %)) (:providers (providers/registry-report)))))]
        (is (= :user (:origin (row "alpha"))))
        (is (= "https://alpha/v1" (:base-url (row "alpha")))))))
  (with-home (cfg :openrouter) (pr-str {:openrouter {:base-url "https://my-proxy/v1"}})
    (fn []
      (let [row (first (filter #(= "openrouter" (:name %)) (:providers (providers/registry-report))))]
        (is (= :builtin-patched (:origin row)))
        (is (= "https://my-proxy/v1" (:base-url row)) "the patch is in force")
        (is (<= 4 (count (:models row)))
            "and the models are the built-in's -- a patch borrows what it does not state")))))

(deftest writing-a-provider-lands-in-the-one-file-and-is-served-at-once
  (with-home (cfg :alpha) reg
    (fn []
      (let [before (file-facts)
            written (providers/put-provider! "acme-gateway"
                                             (form-entry :display-name "Acme Gateway")
                                             nil)
            after  (slurp (home/config-file))]
        (testing "the entry the catalog serves is what was written"
          (is (= :openai-completions (:protocol written)))
          (is (= "https://gateway.example/v1" (:base-url written)))
          (is (= "Acme Gateway" (:display-name written))))

        (testing "and it lands NORMALIZED, not as the wire handed it over"
          ;; The strings JSON carries become the keywords the catalog speaks. Writing
          ;; the raw row would put a shape in the file that the next read normalizes
          ;; differently from what was validated -- and the modality guard compares
          ;; against the catalog's spelling.
          (is (str/includes? after ":input #{:text}"))
          (is (not (str/includes? after "[\"text\"]"))))

        (testing "in ONE file: the :default section and the rest survive untouched"
          (is (str/includes? after ":provider :alpha") "the default tier is still there")
          (is (str/includes? after ":gamma") "and so is a provider nobody touched")
          (is (str/includes? after "acme-gateway")))

        (testing "and the file says what wrote it and what happened to the comments"
          (is (str/includes? after "WRITTEN BY THE SETTINGS FORM")))

        (testing "the previous version is one file away"
          (is (str/includes? (slurp (home/config-backup-file)) ":provider :alpha")
              "the backup is what the file held a moment ago")
          (is (not= before (file-facts)) "and the live file really did change"))

        (testing "and the next resolution serves it with no restart"
          (providers/set-override! "t-write" {:provider :acme-gateway})
          (let [p (providers/effective-provider "t-write")]
            (is (= "https://gateway.example/v1" (:base-url p)))
            (is (= #{:text} (:input p)) "with the modalities the form declared"))
          (providers/set-override! "t-write" nil))))))

(deftest a-refused-write-leaves-the-file-exactly-as-it-was
  (with-home (cfg :alpha) reg
    (fn []
      (doseq [[what entry] {"a new id that is not one"      (form-entry)
                            "a protocol nobody implements"  (form-entry :protocol "anthropic-messages")
                            "no models at all"              (form-entry :models [])
                            "a model row with no id"        (form-entry :models [{:input ["text"] :output ["text"]}])
                            "the same model id twice"       (form-entry :models [{:id "a" :input ["text"] :output ["text"]}
                                                                                  {:id "a" :input ["text"] :output ["text"]}])
                            "a modality nobody carries"     (form-entry :models [{:id "gpt-x" :input ["audio"] :output ["text"]}])
                            "a default model that is not declared" (form-entry :model "nope")
                            "a field nobody reads"          (form-entry :reasoning-effort "high")}]
        (let [before (file-facts)
              e (try (providers/put-provider! (if (= what "a new id that is not one") "Acme" "acme-gateway")
                                              entry nil)
                     nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) (str what " is refused"))
          (is (= before (file-facts)) (str what " wrote nothing"))
          (is (not (.exists (home/config-backup-file)))
              "and not even a backup: a refusal is not a write that happened"))))))

(deftest an-update-keeps-the-id-the-file-already-uses
  ;; The id rule is the FORM's, for ids it creates. An entry already in the file --
  ;; hand-written, possibly in a spelling that rule would refuse -- is edited, not
  ;; renamed: the id is the entry's identity and its credential name.
  (with-home (cfg :alpha) (pr-str {:My_Vendor {:protocol :openai-completions
                                               :base-url "https://old/v1"
                                               :model "m"
                                               :models {"m" {:input #{:text} :output #{:text}}}}})
    (fn []
      (providers/put-provider! "My_Vendor" (form-entry :base-url "https://new/v1") nil)
      (let [after (slurp (home/config-file))]
        (is (str/includes? after "My_Vendor") "the file's own spelling survived")
        (is (str/includes? after "https://new/v1") "and the edit landed")
        (is (= "MY_VENDOR_API_KEY" (providers/credential-name :My_Vendor)))))))

(deftest the-key-is-written-as-one-line-and-only-that-line-moves
  (with-home (cfg :alpha) reg
    (fn []
      (with-dotenv (str "# the provider keys live here\n"
                        "HARNESS_API_KEY=the-global\n"
                        "export  BRAVE_API_KEY = \"brave-value\"\n"
                        "\n"
                        "# a note about the next line\n")
        (fn []
          (let [before (slurp (home/dotenv-file))]
            (providers/put-provider! "acme-gateway" (form-entry) "sk-acme")
            (let [after (slurp (home/dotenv-file))]
              (is (str/includes? after "ACME_GATEWAY_API_KEY=sk-acme")
                  "the line is appended when it is not there")
              (is (str/includes? after "HARNESS_API_KEY=the-global") "other variables stay")
              (is (str/includes? after "export  BRAVE_API_KEY = \"brave-value\"")
                  "including one with an export prefix, spaces and quotes")
              (is (str/includes? after "# a note about the next line") "and every comment")
              (is (str/starts-with? after "# the provider keys live here")
                  "nothing was reordered")

              (testing "and replacing it later touches nothing else either"
                (providers/put-provider! "acme-gateway" (form-entry :base-url "https://second/v1") "sk-acme-2")
                (let [third (slurp (home/dotenv-file))]
                  (is (str/includes? third "ACME_GATEWAY_API_KEY=sk-acme-2"))
                  (is (not (str/includes? third "sk-acme\n")) "the old value is gone, not appended to")
                  (is (= (count (str/split-lines after)) (count (str/split-lines third)))
                      "and the file did not grow a second line for the same name")))
              (is (not= before after)))))))))

(deftest writing-a-key-that-cannot-be-a-line-is-refused
  (with-home (cfg :alpha) reg
    (fn []
      (let [before (file-facts)]
        (let [e (try (providers/put-provider! "acme-gateway" (form-entry) "sk-a\nsk-b")
                     nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) "a value with a newline cannot be one line of .env")
          (is (str/includes? (ex-message e) "span lines"))
          (is (= before (file-facts))
              "and it is refused BEFORE the config write -- an entry whose key could not
               be stored must not be left behind"))))))

(deftest removing-a-provider-takes-back-only-what-the-file-holds
  (with-home (cfg :alpha) (pr-str {:alpha (:alpha (read-string reg))
                                   :mine {:protocol :openai-completions
                                          :base-url "https://mine/v1"
                                          :model "m"
                                          :models {"m" {:input #{:text} :output #{:text}}}}})
    (fn []
      (testing "a built-in is not the file's to remove, and the sentence says so"
        (let [e (try (providers/remove-provider! "openrouter") nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (str/includes? (ex-message e) "nothing to remove"))
          (is (str/includes? (ex-message e) "built-in"))))

      (testing "your own entry goes, and the catalog stops serving it"
        (is (= {:name "mine" :remaining 1} (providers/remove-provider! "mine")))
        (is (not (contains? (providers/catalog) :mine)))
        (is (contains? (providers/catalog) :alpha) "and the rest of the catalog is untouched"))

      (testing "a provider the DEFAULT TIER names cannot be removed out from under it"
        ;; Two individually-valid actions whose combination is a home where every run
        ;; fails to resolve. The refusal says which other step to take, and does not
        ;; quietly edit the tier it was not asked about.
        (providers/put-provider! "mine" (form-entry :base-url "https://mine/v1") nil)
        (providers/put-defaults! {:provider :mine})
        (let [e (try (providers/remove-provider! "mine") nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) "the removal is refused")
          (is (str/includes? (ex-message e) ":default") "the sentence names the section")
          (is (str/includes? (ex-message e) "General") "and where to change it")
          (is (contains? (providers/catalog) :mine) "and the entry is still there"))
        (providers/put-defaults! {:provider :alpha})
        (is (= "mine" (:name (providers/remove-provider! "mine")))
            "once the tier points elsewhere it comes out"))

      (testing "and a PATCH of a built-in can be taken back, which restores the built-in"
        (providers/put-provider! "openrouter" (form-entry :base-url "https://proxy/v1") nil)
        (is (= "https://proxy/v1" (:base-url (get (providers/catalog) :openrouter))))
        (providers/remove-provider! "openrouter")
        (is (= "https://openrouter.ai/api/v1" (:base-url (get (providers/catalog) :openrouter)))
            "the merge simply stops happening")))))

(deftest a-dead-api-key-line-is-not-a-casualty-of-removal
  ;; Deleting a secret is not a side effect anybody asked for -- the line may be one
  ;; this route wrote or one somebody typed, and a line for a provider that no longer
  ;; exists is inert.
  (with-home (cfg :alpha) reg
    (fn []
      (with-dotenv "ALPHA_API_KEY=keep-me\n"
        (fn []
          (providers/put-provider! "acme-gateway" (form-entry) "sk-acme")
          (providers/remove-provider! "acme-gateway")
          (is (str/includes? (slurp (home/dotenv-file)) "ACME_GATEWAY_API_KEY=sk-acme")))))))

(deftest the-default-tier-is-set-from-the-three-knobs
  (with-home (cfg :alpha) reg
    (fn []
      (testing "naming a provider makes the tier a named one, with the model it was given"
        (is (= {:provider :beta :model "beta-big"}
               (providers/put-defaults! {:provider "beta" :model "beta-big"})))
        (is (str/includes? (slurp (home/config-file)) ":beta")))

      (testing "and naming a provider WITHOUT a model does not carry the old one over"
        ;; 'switch vendor' is one knob at every tier, and a model id means 'an id this
        ;; vendor serves' -- carrying `beta-big` to alpha would either fail the run or
        ;; be silently replaced later.
        (is (= {:provider :alpha}
               (providers/put-defaults! {:provider "alpha"}))
            "the model is dropped, so the tier lands on alpha's own default"))

      (testing "a knob the body does not mention is not touched"
        (is (= {:provider :alpha :reasoning-effort "high"}
               (providers/put-defaults! {:provider "alpha" :reasoning-effort "high"})))
        (is (= {:provider :alpha :reasoning-effort "low"}
               (providers/put-defaults! {:reasoning-effort "low"}))
            "and a body with no provider at all PATCHES the tier rather than replacing
             it -- the provider it did not mention is still there, and the knob it did
             mention moved"))

      (testing "an explicit null removes the key, which is a different request"
        (is (= {:provider :alpha}
               (providers/put-defaults! {:reasoning-effort nil}))
            "no reasoning effort is a state: nothing is sent to the vendor")
        (is (= {} (providers/put-defaults! {:provider nil}))
            "and no provider at all is a state too -- the one a fresh home is in,
             and a session override can still pick one"))

      (testing "a default nobody can be served from is refused, and nothing is written"
        (providers/put-defaults! {:provider :alpha :model "alpha-small"})
        (let [before (file-facts)]
          (doseq [[what knobs] {"a vendor that is not in the catalog" {:provider "nope"}
                                "a model that vendor does not declare" {:provider "alpha" :model "no-such-model"}}]
            (let [e (try (providers/put-defaults! knobs) nil
                         (catch clojure.lang.ExceptionInfo e e))]
              (is (some? e) (str what " is refused"))
              (is (= before (file-facts)) (str what " wrote nothing")))))
        (is (= "alpha-small" (:model (providers/put-defaults! {:reasoning-effort "high"})))
            "and the tier still holds what it was refused a change away from"))

      (testing "naming a provider is the way out of an INLINE description"
        (with-home (pr-str {:protocol :fake :base-url "https://inline/v1" :model "flat"}) reg
          (fn []
            (is (= {:provider :alpha :model "alpha-small"}
                   (providers/put-defaults! {:provider "alpha" :model "alpha-small"}))
                "the name arrives as a string and lands as the keyword the file speaks")
            (is (not (str/includes? (slurp (home/config-file)) "https://inline/v1"))
                "the description is replaced, not merged with")))))))

;; ------------------------------------------- the tier, written from an http-kit thread
;;
;; The model endpoint is the ONLY writer now, and it runs on an http-kit thread: two
;; presses of the picker -- or a press while the previous one is still resolving -- can
;; write the SAME session tier at once, so read-then-write loses one of the two changes
;; -- and both audit lines then claim a transition that never happened.

(deftest a-change-that-lands-while-another-is-in-flight-is-not-lost
  ;; THE WINDOW IS BETWEEN READING THE TIER AND WRITING IT BACK. Two callers land in it
  ;; in ordinary use -- both of them the model endpoint, on two http-kit threads -- and
  ;; the one that writes second erases the other's change, while both audit lines go on
  ;; to claim a transition that never happened.
  ;;
  ;; THE GATE HOLDS THE FIRST CALLER INSIDE THAT WINDOW: `:selection` is called on the way
  ;; from the read to the write, and gating it (for the first call only, or the second
  ;; caller would be held too) makes the second change land in the window EVERY time.
  ;; What is under test is the order two operations run in, not how fast the machine is.
  (with-home (cfg :alpha) reg
    (fn []
      (let [tid "p-window"]
        (try
          (providers/swap-override! tid {:model "alpha-small"})   ; something to keep
          (let [gate (support/window-gate #'providers/selection 30000 1)
                held (future (providers/swap-override! tid {:reasoning-effort "low"}))]
            (try
              (is (support/holds-within? #(= 1 ((:entered gate))) 5000)
                  "the first change is inside the window: read, not yet written")
              (let [landed (providers/swap-override! tid {:model "alpha-large"})]
                (is (= "alpha-large" (:model (:after landed)))
                    "the second change lands while the first one is in flight"))
              ((:release gate))
              (is (not= ::timeout (deref held 10000 ::timeout)) "the held change finishes")
              (let [final (providers/override-for tid)]
                (is (= "alpha-large" (:model final)) "what landed is what is stored")
                (is (= "low" (:reasoning-effort final))
                    (str "and the change that was in flight was NOT lost: "
                         (pr-str final))))
            (finally
              ((:release gate))
              ((:restore gate))
              (providers/set-override! tid nil)))))))))

(deftest the-transition-it-answers-with-is-the-one-it-made
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (let [first- (providers/swap-override! "p-pair" {:model "alpha-small"})
              second- (providers/swap-override! "p-pair" {:reasoning-effort "low"})]
          (is (= nil (:before first-)) "nothing was there before")
          (is (= {:model "alpha-small"} (:after first-)))
          (is (= (:after first-) (:before second-))
              "the second call starts where the first left -- no stale read")
          (is (= {:model "alpha-small" :reasoning-effort "low"} (:after second-))
              "and folding a second knob keeps the first")
          (is (= (:after second-) (providers/override-for "p-pair"))
              "what it answers with is what is stored")
          (is (map? (:resolved second-))
              "and it carries what the catalog assembled, so no caller re-resolves")
          (testing "clearing answers the transition too"
            (is (= {:before (:after second-) :after nil :resolved nil}
                   (providers/swap-override! "p-pair" nil)))))
        (finally (providers/set-override! "p-pair" nil))))))

(deftest a-change-that-cannot-be-served-writes-nothing
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (providers/swap-override! "p-bad" {:model "alpha-small"})
        (is (thrown? Exception
                     (providers/swap-override! "p-bad" {:model "no-such-model"}))
            "a model the provider does not declare is refused at the moment it is proposed")
        (is (= {:model "alpha-small"} (providers/override-for "p-bad"))
            "and the session keeps exactly what it had")
        (finally (providers/set-override! "p-bad" nil))))))

;; ------------------------------------------- what a provider says it serves

(deftest a-providers-listing-is-read-off-its-own-shape
  ;; THE LAYER BELOW THE PROBE'S SEAM. `*list-models*` is stubbed in the edge suite,
  ;; and that stub replaced the PARSING along with the outbound call -- so every case
  ;; there asked "did the stub's answer come back", and not one of them ever fed a
  ;; provider-shaped body. The parse is a function of the body now, and this is the test
  ;; that feeds it: no stub, no network, just the shape a real provider answers with.
  (testing "the ids the provider lists, in the order it listed them"
    (is (= ["gpt-x" "gpt-y"]
           (providers/listed-models "https://gateway.example/v1"
                                    "{\"data\":[{\"id\":\"gpt-x\"},{\"id\":\"gpt-y\"}]}"))))

  (testing "a row whose id is not a string is skipped rather than failing the lot"
    (is (= ["gpt-x" "gpt-y"]
           (providers/listed-models
            "https://x/v1"
            (str "{\"data\":[{\"id\":\"gpt-x\"},{\"id\":7},{\"id\":null},"
                 "{\"no-id\":true},\"junk\",{\"id\":\"gpt-y\"}]}")))))

  (testing "and 'the provider listed nothing' is an ordinary answer, not a failure"
    ;; Empty, absent, the wrong type -- one answer, and none of them an error: a
    ;; provider that lists nothing is a provider that lists nothing. The MAP case is the
    ;; one worth spelling out: `data` as an object iterates its ENTRIES, so a parser
    ;; that simply walked it would answer ["id" "gpt-x"] for a body with no list in it.
    (doseq [body ["{\"data\":[]}"
                  "{}"
                  "{\"data\":null}"
                  "{\"data\":\"nope\"}"
                  "{\"data\":{\"id\":\"gpt-x\"}}"
                  "{\"data\":[1,2,3]}"]]
      (is (= [] (providers/listed-models "https://x/v1" body))
          (str "no ids in " body))))

  (testing "but a body that is not JSON at all is its own refusal, naming the address"
    (let [e (try (providers/listed-models "https://x/v1" "<html>not json</html>") nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (str/includes? (ex-message e) "not JSON"))
      (is (str/includes? (ex-message e) "https://x/v1")
          "the address it came from, because a half-filled form holds several"))))

;; ------------------------------------------------------- the picker's key fact

(deftest the-pickers-list-says-which-providers-this-home-holds-a-key-for
  ;; TICKET 03's SERVER HALF. The picker offers a provider's models only when this home
  ;; holds a key pointing at it, and that fact has to arrive WITH the list it filters
  ;; -- the picker reads /api/choices, not /api/providers, and a fact it has to fetch
  ;; from somewhere else is a fact that can be out of date by the time it draws.
  ;;
  ;; IT IS THE SAME `api-key-source` THE SETTINGS ROWS CARRY, deliberately: 'has a
  ;; key' has ONE answer in this codebase (.env before the environment, the provider's
  ;; own credential name before the global one), and a second derivation here would be
  ;; a second answer free to disagree with the page beside it. FACT ONLY -- that
  ;; function reports where a key would come from and never a value -- so the value is
  ;; searched for at no depth below.
  ;;
  ;; THIS SITS AT THE END OF THE FILE RATHER THAN BESIDE THE OTHER PICKER CASES, and
  ;; that is not tidiness. `a-display-name-is-a-label-and-never-an-identity` (above)
  ;; does not close where its indentation says it does, so everything between it and
  ;; `the-key-comes-from-the-providers-own-name-then-the-global-one` is swallowed into
  ;; its body -- and a `deftest` in there comes out NESTED: its `def` runs only when the
  ;; ENCLOSING test body runs, so the var does not exist yet at the moment this
  ;; namespace's vars are collected for the pass. It is missing from the run and from
  ;; the count while looking exactly like a case that passed: 98 `(deftest` forms in
  ;; this file, 97 tests reported. A second pass in the same JVM would pick it up, which
  ;; is the tell that this is collection order and not a lost var. (Found while adding
  ;; this one; the defect is pre-existing, so it is reported rather than fixed here.)
  (with-home (cfg :alpha) reg
    (fn []
      (let [rows (fn [] (:providers (providers/choices "t-key")))
            row  (fn [n] (first (filter #(= n (:name %)) (rows))))]
        (testing "a provider with no key says so, and still names the line a key would go on"
          ;; The NAME is the useful half either way: it is what a person has to add.
          (is (= {:present? false :source nil :name "ALPHA_API_KEY"} (:key (row "alpha")))))

        (testing "a key in this home's .env is the picker's answer too -- no second rule"
          (with-dotenv (str "ALPHA_API_KEY=" sentinel "\n")
            (fn []
              (is (= true (get-in (row "alpha") [:key :present?])))
              (is (= :env-file (get-in (row "alpha") [:key :source]))
                  ".env first, exactly as a run resolves it"))))

        (testing "and the value is in the answer at NO depth"
          (with-dotenv (str "ALPHA_API_KEY=" sentinel "\n")
            (fn []
              (let [body (json/write-str (providers/choices "t-key"))]
                (is (not (str/includes? body sentinel)))
                (is (not (str/includes? body (subs sentinel 0 12))))
                (is (not (contains? (:providers (providers/choices "t-key")) :api-key)))))))

        (testing "every row carries the fact, so a client never has to guess by omission"
          (is (every? #(contains? % :key) (rows))))))))

;; --------------------------------------------------------- the :ui section

(defn- with-config-text
  "Run F with config.edn's exact bytes, restoring what was there afterwards -- the
  shared temp home means a file left behind is a file the next case reads."
  [text f]
  (let [file (home/config-file)
        old  (when (.exists file) (slurp file :encoding "UTF-8"))]
    (try (spit file text :encoding "UTF-8") (f)
         (finally (spit file (or old "{:default {:protocol :fake}}\n") :encoding "UTF-8")))))

(deftest the-ui-section-is-this-homes-language-setting
  ;; A language is not a knob a session starts from, so it does not belong in
  ;; :default -- the closed top level is opened for a section of its own rather than
  ;; letting that section's description start to lie.
  (with-config-text "{:default {:provider :openrouter} :ui {:language :zh}}\n"
    (fn []
      (testing "a :ui section is read as configuration, not refused"
        (is (= :zh (get-in (providers/config) [:ui :language]))))
      (testing "and the knobs beside it are untouched"
        (is (= :openrouter (get-in (providers/config) [:default :provider]))))))
  (with-config-text "{:default {:provider :openrouter}}\n"
    (fn []
      (is (nil? (:ui (providers/config)))
          "an absent :ui section is the everyday case, not a failure"))))

(deftest a-ui-key-nobody-declared-is-a-named-failure
  (with-config-text "{:ui {:langauge :zh}}\n"
    (fn []
      (let [e (try (providers/config) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a :ui typo fails by name rather than sitting there doing nothing")
        (is (str/includes? (ex-message e) ":langauge") "the sentence names the typo")
        (is (str/includes? (ex-message e) ":language") "and the key it meant")))))

(deftest a-language-nobody-speaks-is-a-named-failure
  (with-config-text "{:ui {:language :fr}}\n"
    (fn []
      (let [e (try (providers/config) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) ":fr") "the sentence names the value")
        (is (str/includes? (ex-message e) ":en") "and a language that may be written")))))

(deftest the-top-level-sentence-names-the-ui-section-too
  (with-config-text "{:oops 1}\n"
    (fn []
      (let [e (try (providers/config) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) ":ui")
            "the closed-top-level sentence says where the language goes")))))

(deftest set-language-writes-the-choice-and-refuses-one-it-cannot-speak
  (with-config-text "{:default {:provider :openrouter}}\n"
    (fn []
      (testing "a language this harness speaks is written into :ui :language"
        (providers/set-language! "zh")
        (is (= :zh (get-in (providers/config) [:ui :language])))
        (is (= :zh (language/config-language))
            "and the resolver reads it back out of the file"))
      (testing "a value nobody speaks is refused by name and writes nothing"
        (let [before (slurp (home/config-file) :encoding "UTF-8")
              e      (try (providers/set-language! "fr") nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (str/includes? (ex-message e) ":fr") "the sentence names the value")
          (is (= before (slurp (home/config-file) :encoding "UTF-8"))
              "the file still holds the last good choice"))))))

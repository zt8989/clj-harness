(ns harness.provider-test
  "The model catalog, the tier fold, the session-state introspection surface, and
  the authorised session-configure tool.

  Everything here runs under the runner's isolated config root, so the fixtures
  write their own config.edn / providers.edn into a temp home rather than
  touching the developer's real ~/.clj-harness."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.home :as home]
            [harness.memory :as mem]
            [harness.models :as models]
            [harness.tools :as tools]))

;; ------------------------------------------------------------------ fixtures

(defn- write-home! [config providers]
  (.mkdirs (io/file (home/root)))
  (spit (home/config-file) config :encoding "UTF-8")
  (if providers
    (spit (home/providers-file) providers :encoding "UTF-8")
    (io/delete-file (home/providers-file) true)))

(defn- with-home [config providers f]
  (let [old-config (when (.exists (home/config-file))
                     (slurp (home/config-file) :encoding "UTF-8"))
        old-prov    (when (.exists (home/providers-file))
                      (slurp (home/providers-file) :encoding "UTF-8"))]
    (try
      (write-home! config providers)
      (f)
      (finally
        (write-home! (or old-config "{:protocol :fake}\n") old-prov)))))

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
  "A config.edn naming PROVIDER, plus any extra knobs."
  [provider & {:as extra}]
  (pr-str (merge {:provider provider} extra)))

;; --------------------------------------------------------------- the built-ins

(deftest the-built-in-table-stands-on-its-own
  ;; No providers.edn at all. This is the shape's headline claim: the three knobs
  ;; in config.edn are enough to start, so a fresh install runs without anyone
  ;; copying a template first.
  (with-home (cfg :openrouter) nil
    (fn []
      (let [p (mem/effective-provider "p-builtin")]
        (is (= "https://openrouter.ai/api/v1" (:base-url p)))
        (is (= "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free" (:model p))
            "the built-in entry's default model")
        (is (= #{:text :image} (:input p)) "with its declared modalities"))
      (testing "and a built-in provider's model can be chosen by name"
        (mem/set-override! "p-builtin" {:model "deepseek/deepseek-v4-pro"})
        (let [p (mem/effective-provider "p-builtin")]
          (is (= "deepseek/deepseek-v4-pro" (:model p)))
          (is (= #{:text} (:input p)) "whose modalities are its own, not the default's"))
        (mem/set-override! "p-builtin" nil)))))

(deftest the-built-in-table-covers-more-than-one-vendor
  (with-home (cfg :deepseek) nil
    (fn []
      (is (= "https://api.deepseek.com/v1" (:base-url (mem/effective-provider "p-ds"))))
      (is (= "deepseek-flash" (:model (mem/effective-provider "p-ds"))))
      (testing "each vendor's own endpoint, not a shared one"
        (mem/set-override! "p-ds" {:provider :ollama})
        (is (= "http://localhost:11434/v1" (:base-url (mem/effective-provider "p-ds"))))
        (is (= "qwen3" (:model (mem/effective-provider "p-ds"))))
        (mem/set-override! "p-ds" nil)))))

(deftest a-user-entry-overrides-the-built-in-field-by-field
  ;; A partial patch is legitimate: saying only what you mean to change and
  ;; borrowing the rest is what makes the built-in table worth having rather than
  ;; a thing to copy and then drift from.
  (with-home (cfg :openrouter)
             (pr-str {:openrouter {:base-url "https://my-proxy/v1"}})
    (fn []
      (let [p (mem/effective-provider "p-ovr")]
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
      (let [c (mem/providers)]
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
      (let [c (mem/providers)]
        (is (= "https://mine/v1" (:base-url (get c :mine))))
        (is (contains? c :openrouter) "and the built-ins are untouched"))
      (is (= "mine-1" (:model (mem/effective-provider "p-added")))))))

(deftest an-unknown-provider-fails-and-lists-what-is-known
  (with-home (cfg :nope) nil
    (fn []
      (let [e (try (mem/effective-provider "p-unknown") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "nope"))
        (is (str/includes? (ex-message e) "openrouter")
            "the known list includes the built-ins, so the reader sees the options")))))

;; ------------------------------------------------------------------- shape

(deftest a-provider-names-a-vendor-and-lists-its-models
  (with-home (cfg :alpha) reg
    (fn []
      (let [c (mem/providers)]
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
      (let [e (try (mem/providers) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "the old shape is a hard failure, not a quiet upgrade")
        (is (str/includes? (ex-message e) ":models")
            "and the message names the table it is missing")
        (is (str/includes? (ex-message e) ":input")
            "and shows a model entry, so the reader has the new shape in hand")))))

(deftest a-provider-with-no-models-lists-nothing-to-serve
  (with-home (cfg :alpha) (pr-str {:alpha {:protocol :p :base-url "https://a/v1"
                                            :model "m" :models {}}})
    (fn []
      (let [e (try (mem/providers) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (str/includes? (ex-message e) "lists no models"))))))

(deftest a-model-must-declare-both-directions
  (testing "declaring neither is a failure -- a model silent about its modalities
            would make the guard vacuous"
    (with-home (cfg :alpha)
               (pr-str {:alpha {:protocol :p :base-url "https://a/v1" :model "m"
                                :models {"m" {}}}})
      (fn []
        (let [e (try (mem/providers) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (str/includes? (ex-message e) ":input"))
          (is (str/includes? (ex-message e) ":output"))))))
  (testing "declaring only one is also a failure -- half a capability is not a statement"
    (with-home (cfg :alpha)
               (pr-str {:alpha {:protocol :p :base-url "https://a/v1" :model "m"
                                :models {"m" {:input #{:text}}}}})
      (fn []
        (let [e (try (mem/providers) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (str/includes? (ex-message e) ":output")))))))

(deftest a-modality-this-harness-cannot-carry-fails-by-name
  (testing "input"
    (with-home (cfg :alpha)
               (pr-str {:alpha {:protocol :p :base-url "https://a/v1" :model "m"
                                :models {"m" {:input #{:text :audio} :output #{:text}}}}})
      (fn []
        (let [e (try (mem/providers) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (str/includes? (ex-message e) "audio") "names the offending type")
          (is (str/includes? (ex-message e) "image") "and what it could have carried")))))
  (testing "output -- the vocabulary is narrower here, because the harness drops
            everything but text on the way back"
    (with-home (cfg :alpha)
               (pr-str {:alpha {:protocol :p :base-url "https://a/v1" :model "m"
                                :models {"m" {:input #{:text} :output #{:image}}}}})
      (fn []
        (let [e (try (mem/providers) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (str/includes? (ex-message e) "image")))))))

(deftest the-default-model-must-be-one-the-provider-declares
  (with-home (cfg :alpha)
             (pr-str {:alpha {:protocol :p :base-url "https://a/v1" :model "ghost"
                              :models {"real" {:input #{:text} :output #{:text}}}}})
    (fn []
      (let [e (try (mem/providers) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (str/includes? (ex-message e) "ghost") "names the id it could not find")
        (is (str/includes? (ex-message e) "real") "and lists what is declared")))))

(deftest a-key-nobody-reads-is-reported-not-ignored
  ;; This is the failure mode the whole feature exists to kill: a tier naming
  ;; something the resolution never looks at, and the run succeeding anyway.
  (with-home (cfg :alpha)
             (pr-str {:alpha {:protocol :p :base-url "https://a/v1" :model "m"
                              :reasoning-effort "high"
                              :models {"m" {:input #{:text} :output #{:text}}}}})
    (fn []
      (let [e (try (mem/providers) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "reasoning-effort")
            "the stray key is named rather than silently dropped")))))

;; -------------------------------------------------------------------- tiers

(deftest the-catalog-supplies-the-endpoint-and-the-modalities
  (with-home (cfg :alpha) reg
    (fn []
      (let [p (mem/effective-provider "p-tier")]
        (is (= :openai-completions (:protocol p)))
        (is (= "https://alpha/v1" (:base-url p)))
        (is (= "alpha-large" (:model p)) "the provider's default model")
        (is (= #{:text :image} (:input p)) "and that model's declared modalities")
        (testing "a knob no tier named is simply absent -- not an error"
          (is (not (contains? p :reasoning-effort))))))))

(deftest the-default-tier-moves-knobs-without-a-new-provider
  (with-home (cfg :alpha :reasoning-effort "low") reg
    (fn []
      (let [p (mem/effective-provider "p-default")]
        (is (= "alpha-large" (:model p)) "the provider's default still stands")
        (is (= "low" (:reasoning-effort p)) "and the default tier tuned one knob")))))

(deftest switching-provider-alone-really-switches-vendors
  ;; THE regression this feature exists for. Under the old field-by-field merge,
  ;; :provider was not a field and a tier naming one was silently dropped: the
  ;; session kept the old endpoint while reporting success. Every part of the
  ;; resolution moves together now, because the endpoint FOLLOWS the selection.
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (testing "the default tier's vendor"
          (let [p (mem/effective-provider "p-switch")]
            (is (= "https://alpha/v1" (:base-url p)))
            (is (= "alpha-large" (:model p)))))
        (testing "a session override naming ONLY a provider lands on that vendor's
                  endpoint AND its default model -- no :model named, none carried over"
          (mem/set-override! "p-switch" {:provider :beta})
          (let [p (mem/effective-provider "p-switch")]
            (is (= "https://beta/v1" (:base-url p)))
            (is (= "beta-plain" (:model p)) "beta's default, NOT alpha's alpha-large")
            (is (= #{:text} (:input p)) "and beta's model's modalities, not alpha's")))
        (testing "and the default tier's other knobs survive the vendor switch"
          (mem/set-override! "p-switch" {:provider :gamma})
          (is (= "http://localhost:11434/v1" (:base-url (mem/effective-provider "p-switch")))))
        (finally (mem/set-override! "p-switch" nil))))))

(deftest a-session-override-sits-above-the-default-tier
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (mem/set-override! "p-sess" {:model "alpha-small"})
        (let [p (mem/effective-provider "p-sess")]
          (is (= "alpha-small" (:model p)))
          (is (= :openai-completions (:protocol p)) "untouched knobs inherit below")
          (testing "and the modalities follow the MODEL, not the provider"
            (is (= #{:text :image} (:input p)))))
        (testing "and another session is untouched"
          (is (= "alpha-large" (:model (mem/effective-provider "p-sess-other")))))
        (finally (mem/set-override! "p-sess" nil))))))

(deftest a-session-can-pick-a-text-only-model-and-then-says-so
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (mem/set-override! "p-mod" {:provider :beta :model "beta-big"})
        (let [p (mem/effective-provider "p-mod")]
          (is (= "beta-big" (:model p)))
          (is (= #{:text} (:input p))
              "the modalities are the chosen model's, so the guard has something to enforce"))
        (finally (mem/set-override! "p-mod" nil))))))

(deftest a-run-request-sits-on-top-of-everything
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (mem/set-override! "p-req" {:model "alpha-small"})
        (let [{:keys [provider source]} (mem/resolve-provider "p-req" {:model "alpha-vision-free"})]
          (is (= "alpha-vision-free" (:model provider)))
          (is (= :request source)))
        (testing "with no request, the default tier is where the resolution started"
          (is (= :default (:source (mem/resolve-provider "p-req")))))
        (testing "and a request can switch the vendor too"
          (let [{:keys [provider]} (mem/resolve-provider "p-req" {:provider :gamma})]
            (is (= "http://localhost:11434/v1" (:base-url provider)))
            (is (= "qwen3" (:model provider)) "the vendor's default, not the session's model")))
        (finally (mem/set-override! "p-req" nil))))))

(deftest an-inline-provider-needs-no-catalog-at-all
  ;; The escape hatch: describe one endpoint in config.edn and go. Kept because
  ;; registering a provider to try it once is friction, and because the built-in
  ;; catalog is a starting point rather than a gate.
  (with-home "{:protocol :fake :base-url \"https://inline/v1\" :model \"flat\" :input #{:text} :output #{:text}}\n"
             nil
    (fn []
      (let [p (mem/effective-provider "p-flat")]
        (is (= "https://inline/v1" (:base-url p)))
        (is (= "flat" (:model p)))
        (is (= #{:text} (:input p)))
        (is (= :inline (:source (mem/resolve-provider "p-flat"))))
        (testing "and it carries no :provider -- nothing was named"
          (is (not (contains? p :provider))))))))

(deftest an-inline-provider-may-declare-nothing
  ;; Modalities are optional in the inline form. Declaring nothing is a
  ;; statement ('this entry promises nothing'), not an error -- which is what
  ;; lets a bare {:protocol :fake} config work in tests and in a minimal setup.
  (with-home "{:protocol :fake :base-url \"https://bare/v1\" :model \"m\"}\n" nil
    (fn []
      (let [p (mem/effective-provider "p-bare")]
        (is (= "https://bare/v1" (:base-url p)))
        (is (not (contains? p :input)) "nothing was declared, so nothing is claimed")
        (is (not (contains? p :output)))))))

(deftest an-inline-provider-declaring-half-a-capability-is-refused
  (with-home "{:protocol :fake :base-url \"https://half/v1\" :model \"m\" :input #{:text}}\n" nil
    (fn []
      (let [e (try (mem/effective-provider "p-half") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) ":output"))))))

(deftest a-named-but-missing-provider-fails-naming-what-it-looked-for
  (with-home (cfg :nope) reg
    (fn []
      (let [e (try (mem/effective-provider "p-bad") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a name that is not in the catalog is a hard failure")
        (is (str/includes? (ex-message e) "nope") "the message names the missing provider")
        (is (str/includes? (ex-message e) "alpha") "and lists what IS defined")))))

(deftest a-model-id-from-another-vendor-fails-and-lists-this-vendors-models
  (with-home (cfg :beta) reg
    (fn []
      (try
        (mem/set-override! "p-x" {:model "alpha-large"})
        (let [e (try (mem/effective-provider "p-x") nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) "alpha's model id is not beta's to serve")
          (is (str/includes? (ex-message e) "alpha-large"))
          (is (str/includes? (ex-message e) "beta-plain") "and beta's ids are listed")
          (is (not (str/includes? (ex-message e) "alpha-small"))
              "while another vendor's ids are NOT -- the list is this provider's"))
        (finally (mem/set-override! "p-x" nil))))))

(deftest a-config-that-names-nothing-and-describes-nothing-says-so
  (with-home "{:reasoning-effort \"high\"}\n" reg
    (fn []
      (let [e (try (mem/effective-provider "p-none") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) ":provider") "names the first shape")
        (is (str/includes? (ex-message e) ":protocol") "and the second")))))

(deftest the-catalog-is-a-map-of-names-to-providers
  (with-home (cfg :alpha) "[{:protocol :p}]\n"
    (fn []
      (let [e (try (mem/providers) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "providers.edn that is not a map fails rather than iterating")
        (is (str/includes? (ex-message e) "providers.edn"))))))

(deftest providers-are-named-by-keyword-string-or-symbol
  ;; Names arrive from EDN (keyword), from JSON (string) and from a tool call
  ;; (either). All three spell the same provider; none of them mints a second.
  (with-home (pr-str {:provider "alpha"}) reg
    (fn []
      (is (= "https://alpha/v1" (:base-url (mem/effective-provider "p-name"))))
      (mem/set-override! "p-name" {:provider 'alpha})
      (is (= "https://alpha/v1" (:base-url (mem/effective-provider "p-name"))))
      (mem/set-override! "p-name" nil))))

(deftest the-api-key-is-attached-last-and-only-in-the-resolver
  (with-home (cfg :alpha) reg
    (fn []
      (let [p (mem/effective-provider "p-key")]
        (is (contains? p :api-key) "the assembled provider carries the key slot")
        (testing "but the introspectable answer deliberately does not"
          (let [a (mem/active-provider "p-key")]
            (is (not (contains? a :api-key)))
            (is (not-any? #(str/includes? (str %) "api-key")
                          (tree-seq coll? seq a)))))))))

;; ----------------------------------------------------------- wire rendering

(deftest modalities-render-as-sorted-string-vectors-on-the-wire
  ;; JSON has no sets. Sorting is not cosmetic: an unsorted set would make two
  ;; identical runs produce log lines that differ, and a reader diffing a
  ;; timeline would see changes that never happened.
  (let [m {:provider :alpha :model "m" :input #{:text :image} :output #{:text}}]
    (is (= ["image" "text"] (:input (models/wire m))) "a set becomes a sorted vector of names")
    (is (= ["text"] (:output (models/wire m))))
    (testing "and naming fields renders only those fields"
      (is (= {:input ["image" "text"]} (models/wire m [:input])))
      (is (= #{:provider :model} (set (keys (models/wire m [:provider :model]))))))))

(deftest a-value-no-tier-named-stays-absent-on-the-wire
  ;; Absent is a fact -- 'nothing chose this'. null would read as 'chose nothing',
  ;; which is a different thing and a worse one.
  (let [w (models/wire {:provider :alpha :model "m"})]
    (is (not (contains? w :reasoning-effort)))
    (is (not (contains? w :input)))))

(deftest the-wire-shape-cannot-name-the-key
  ;; wire is the boundary every provider shape crosses on its way to a log, an
  ;; HTTP body or a tool result. Naming :api-key in the fields still produces a
  ;; shape without it, so a future call site -- or a helper rendering
  ;; 'everything' -- cannot leak one by accident.
  (let [w (models/wire {:provider :alpha :model "m" :api-key "SECRET" :protocol :p}
                       [:provider :model :protocol :api-key])]
    (is (not (contains? w :api-key)))
    (is (not (str/includes? (pr-str w) "SECRET")))
    (is (= :p (:protocol w)) "and the fields that WERE asked for are still there")))

;; ------------------------------------------------------------- introspection

(deftest log-path-names-the-file-the-writer-writes
  (testing "and it is derived from harness.home, so a relocated root follows"
    (is (= (str (home/log-file "t-logpath")) (mem/log-path "t-logpath")))
    (is (str/ends-with? (mem/log-path "t-logpath") "t-logpath.jsonl")))
  (testing "the sanitize rule is shared, so the reader and writer agree"
    (is (= "a_b_c.jsonl" (str/replace (mem/log-path "a/b c") #".*[\\/]" "")))))

(deftest active-provider-answers-both-what-was-chosen-and-what-it-resolved-to
  (with-home (cfg :alpha :reasoning-effort "high") reg
    (fn []
      (let [a (mem/active-provider "t-act")]
        (is (= :alpha (:provider a)) "the name that was chosen")
        (is (= "alpha-large" (:model a)) "the model id that was chosen")
        (is (= "high" (:reasoning-effort a)) "and the effort that was chosen")
        (is (= :openai-completions (:protocol a)) "what the catalog resolved it to")
        (is (= "https://alpha/v1" (:base-url a)))
        (is (= #{:text :image} (:input a)) "the model's modalities, as sets in-process")
        (is (= #{:text} (:output a))))
      (testing "a change is visible to the very next call -- nothing is cached"
        (mem/set-override! "t-act" {:provider :beta})
        (let [a (mem/active-provider "t-act")]
          (is (= :beta (:provider a)))
          (is (= "https://beta/v1" (:base-url a)) "the endpoint followed the vendor")
          (is (= "beta-plain" (:model a)))
          (is (= #{:text} (:input a))))
        (mem/set-override! "t-act" nil))
      (testing "and no api-key key exists, at any nesting depth"
        (is (not-any? #(str/includes? (str %) "api-key")
                      (tree-seq coll? seq (mem/active-provider "t-act"))))))))

;; ------------------------------------------------------- the configure tool

(defn- configure!
  "Run the session-configure tool through the real seam, as the agent would,
  with THREAD-ID's session in scope. Returns the seam's result map."
  [thread-id args]
  (tools/run! {:id "sc1" :type "function"
               :function {:name "session-configure"
                          :arguments (json/write-str args)}}
              thread-id))

(defn- approve!
  "Drive a parked session-configure to APPROVED the way a resume does: park it,
  hand the human's verdict to the seam's memory, then call again -- the second
  call consumes the decision and runs the body. Returns the second call's map."
  [thread-id id args]
  (let [call (fn [] (tools/run! {:id id :type "function"
                                 :function {:name "session-configure"
                                            :arguments (json/write-str args)}}
                                thread-id))
        {:keys [parked]} (call)
        _ (mem/decide-approval! (:interrupt-id parked) :approved {})]
    (call)))

(defn- veto!
  "Drive a parked session-configure to VETOED. The body never runs."
  [thread-id id args]
  (let [call (fn [] (tools/run! {:id id :type "function"
                                 :function {:name "session-configure"
                                            :arguments (json/write-str args)}}
                                thread-id))
        {:keys [parked]} (call)]
    (mem/decide-approval! (:interrupt-id parked) :vetoed {:reason "no"})
    (call)))

(deftest session-configure-parks-rather-than-writing
  (with-home (cfg :alpha) reg
    (fn []
      (let [{:keys [parked]} (configure! "t-conf" {:model "alpha-small"})]
        (testing "with no decision yet, the call parks and nothing is written"
          (is (some? parked) "the seam reports the call as parked")
          (is (nil? (mem/override-for "t-conf")) "the session override is untouched"))))))

(deftest an-approved-configure-writes-only-what-it-names
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (mem/set-override! "t-ok" {:reasoning-effort "low"})
        (approve! "t-ok" "sc-ok" {:model "alpha-small"})
        (let [ov (mem/override-for "t-ok")]
          (is (= "alpha-small" (:model ov)))
          (is (= "low" (:reasoning-effort ov))
              "the knob it did not name is left exactly as it was")
          (is (not (contains? ov :base-url))
              "and nothing it could not know about was invented"))
        (finally (mem/set-override! "t-ok" nil))))))

(deftest an-approved-vendor-switch-moves-the-endpoint
  ;; The session-configure half of the feature: an agent naming a provider gets
  ;; that vendor, and the change is real rather than recorded-but-inert.
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (approve! "t-vendor" "sc-vend" {:provider "beta"})
        (is (= :beta (:provider (mem/override-for "t-vendor"))))
        (let [p (mem/effective-provider "t-vendor")]
          (is (= "https://beta/v1" (:base-url p)))
          (is (= "beta-plain" (:model p))))
        (finally (mem/set-override! "t-vendor" nil))))))

(deftest a-vendor-switch-with-no-model-lands-on-the-new-vendors-default
  (with-home (cfg :alpha :model "alpha-small") reg
    (fn []
      (try
        (is (= "alpha-small" (:model (mem/effective-provider "t-dflt"))))
        (approve! "t-dflt" "sc-dflt" {:provider "beta"})
        (is (= "beta-plain" (:model (mem/effective-provider "t-dflt")))
            "the old model id was alpha's, so it is gone -- not carried into beta")
        (finally (mem/set-override! "t-dflt" nil))))))

(deftest a-configure-with-nothing-to-change-is-refused
  (with-home (cfg :alpha) reg
    (fn []
      ;; The refusal lives in the body, so it only surfaces on the approved
      ;; transit -- which is also the only transit that could ever write.
      (let [{:keys [content error]} (approve! "t-empty" "sc-empty" {})]
        (is (true? error))
        (is (str/includes? content "nothing to change"))
        (is (nil? (mem/override-for "t-empty")) "and nothing was written")))))

(deftest a-configure-naming-a-model-the-provider-cannot-serve-is-refused
  ;; Validated BEFORE the write. A change that cannot be served must not become
  ;; the session's configuration: the next run would fail, far from this call.
  (with-home (cfg :beta) reg
    (fn []
      (testing "a model id belonging to another vendor"
        (let [{:keys [content error]} (approve! "t-badmodel" "sc-bm" {:model "alpha-large"})]
          (is (true? error))
          (is (str/includes? content "alpha-large"))
          (is (nil? (mem/override-for "t-badmodel")) "nothing was written")))
      (testing "and no change was queued for the writer"
        (is (empty? (mem/take-provider-changes! "t-badmodel")))))))

(deftest a-configure-naming-a-provider-that-does-not-exist-is-refused
  (with-home (cfg :alpha) reg
    (fn []
      (let [{:keys [content error]} (approve! "t-badprov" "sc-bp" {:provider "ghost"})]
        (is (true? error))
        (is (str/includes? content "ghost"))
        (is (nil? (mem/override-for "t-badprov")))
        (is (empty? (mem/take-provider-changes! "t-badprov")))))))

(deftest a-vetoed-configure-never-writes
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (veto! "t-veto" "sc-veto" {:model "alpha-small"})
        (is (nil? (mem/override-for "t-veto")))
        (testing "and no change was queued for the writer"
          (is (empty? (mem/take-provider-changes! "t-veto"))))
        (finally (mem/set-override! "t-veto" nil))))))

(deftest an-approved-configure-queues-one-change-for-the-writer
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (approve! "t-queue" "sc-q" {:reasoning-effort "high"})
        (let [[c & more] (mem/take-provider-changes! "t-queue")]
          (is (some? c))
          (is (empty? more) "exactly one change was queued")
          (is (= "high" (:reasoning-effort (:after c)))
              "the after side shows the new value")
          (testing "and the change carries what it resolved to, not just what was asked"
            (is (= "https://alpha/v1" (get-in c [:resolved :base-url])))
            (is (= "alpha-large" (get-in c [:resolved :model])))))
        (testing "and draining clears it -- the outbox is not read twice"
          (is (empty? (mem/take-provider-changes! "t-queue"))))
        (finally (mem/set-override! "t-queue" nil))))))

(deftest consecutive-changes-chain-before-and-after
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (approve! "t-chain" "sc-c1" {:model "alpha-small"})
        (approve! "t-chain" "sc-c2" {:model "alpha-vision-free"})
        (let [[a b] (mem/take-provider-changes! "t-chain")]
          (is (= "alpha-small" (:model (:after a))))
          (is (= "alpha-small" (:model (:before b)))
              "the second change starts where the first ended")
          (is (= "alpha-vision-free" (:model (:after b)))))
        (finally (mem/set-override! "t-chain" nil))))))

(deftest a-chained-vendor-switch-resolves-each-step-against-its-own-vendor
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (approve! "t-vchain" "sc-v1" {:provider "beta" :model "beta-big"})
        (approve! "t-vchain" "sc-v2" {:provider "alpha" :model "alpha-small"})
        (let [[a b] (mem/take-provider-changes! "t-vchain")]
          (is (= "https://beta/v1" (get-in a [:resolved :base-url]))
              "each step resolves against the vendor it names, not the one before it")
          (is (= "beta-big" (get-in a [:resolved :model])))
          (is (= "https://alpha/v1" (get-in b [:resolved :base-url])))
          (is (= "alpha-small" (get-in b [:resolved :model])))
          (is (= :beta (:provider (:before b))))
          (is (= :alpha (:provider (:after b)))))
        (finally (mem/set-override! "t-vchain" nil))))))

(deftest the-change-is-scoped-to-its-own-thread
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (approve! "t-a" "sc-a" {:model "alpha-small"})
        (is (= "alpha-small" (:model (mem/effective-provider "t-a"))))
        (is (= "alpha-large" (:model (mem/effective-provider "t-b")))
            "another session serves from the untouched default")
        (finally (mem/set-override! "t-a" nil))))))

;; -- provider/changed line shape: :trigger, :override, :resolved ----------

(deftest an-approved-configure-tags-the-change-with-trigger-and-override
  "Every approved change carries :trigger (the path that pressed it -- currently
  always session-configure) and :override (the FULL session tier after this
  change, so a reader can reconstruct post-change session state without asking
  the resolution)."
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (mem/set-override! "t-tag" {:reasoning-effort "low"})
        (approve! "t-tag" "sc-tag" {:model "alpha-small"})
        (let [[c] (mem/take-provider-changes! "t-tag")]
          (is (some? c))
          (is (= "session-configure" (:trigger c))
              "the trigger names the path that pressed the change")
          (is (= {:model "alpha-small" :reasoning-effort "low"} (:override c))
              "the override is the full session slice after the change -- every
              knob the session owns, not just what this call touched"))
        (finally (mem/set-override! "t-tag" nil))))))

(deftest a-vendor-switch-is-not-recorded-as-an-empty-change
  ;; The specific way the old shape failed as a RECORD: :provider was not in the
  ;; audit slice, so switching vendors wrote {:before {} :after {}} -- a change
  ;; line that documents nothing. The slice is the three knobs now.
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (approve! "t-notempty" "sc-ne" {:provider "beta"})
        (let [[c] (mem/take-provider-changes! "t-notempty")]
          (is (= :beta (:provider (:after c)))
              "the change line names the vendor that was selected")
          (is (not= {} (:after c)))
          (is (not= {} (:before c)) "and the one it moved away from"))
        (finally (mem/set-override! "t-notempty" nil))))))

(deftest consecutive-changes-pin-trigger-and-override-throughout
  "Chained changes all carry the same trigger, and each :override is the previous
  :override plus the new patch -- so a reader stepping through the timeline sees
  the session evolving without consulting the resolution."
  (with-home (cfg :alpha) reg
    (fn []
      (try
        (approve! "t-ch2" "sc-1" {:model "alpha-small"})
        (approve! "t-ch2" "sc-2" {:model "alpha-vision-free" :reasoning-effort "high"})
        (let [[a b] (mem/take-provider-changes! "t-ch2")]
          (is (every? #(= "session-configure" (:trigger %)) [a b])
              "every change names its trigger")
          (is (= {:model "alpha-small"} (:override a))
              "the first change's override is just what it set")
          (is (= (:override b)
                 (merge (:override a)
                        {:model "alpha-vision-free" :reasoning-effort "high"}))
              "the second change's override is the first one plus the new patch"))
        (finally (mem/set-override! "t-ch2" nil))))))

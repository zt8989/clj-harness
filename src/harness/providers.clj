(ns harness.providers
  "Providers: which vendors this process can reach, which models each one
  serves, and what every model can take in and give out.

  SHAPE
  -----
  A provider names a VENDOR -- one endpoint, one protocol -- and lists the models
  reachable there:

    {:openrouter {:protocol :openai-completions
                  :base-url \"https://openrouter.ai/api/v1\"
                  :model    \"anthropic/claude-sonnet-4.5\"   ; the default model id
                  :models   {\"anthropic/claude-sonnet-4.5\" {:input #{:text :image}
                                                             :output #{:text}
                                                             :context-window 1000000
                                                             :max-output-tokens 64000}
                             \"deepseek/deepseek-v4-pro\"    {:input #{:text}
                                                             :output #{:text}}}}
     :ollama     {:protocol :openai-completions
                  :base-url \"http://localhost:11434/v1\"
                  :model    \"qwen3\"
                  :models   {\"qwen3\" {:input #{:text} :output #{:text}}}}}

  :model is the id a provider serves unless a tier picks another one, and it must
  be a key of :models. :input / :output are required of every model entry and
  speak the vocabulary this harness actually carries (input-types / output-types).
  :context-window / :max-output-tokens are optional counts every model MAY state;
  neither is enforced -- see `counts` for what they are for.

  WHAT THIS NAMESPACE OWNS
  ------------------------
  Two halves that are one question -- what is this process, this session, served
  from:

    THE CATALOG  which vendors exist, which models each serves, what every model
                 takes in and gives out, and what a selection assembles to
                 (`builtin`, `catalog`, the `check-*` validators, `assemble`,
                 `wire`).
    WHO WINS     which of config / session / request supplies each knob, and
                 where the secret comes from (`resolve-provider` and the tier
                 fold, the `scripted-pins` / `session-overrides` slots,
                 `api-key`).

  The two were once separate namespaces, and merging them is deliberate: a
  reader asking what this session is on needs both, and a boundary that only one
  of them knows about is a boundary that has to be written down twice.

  THE API-KEY RULE, and it is a DISCIPLINE rather than a wall: the key is
  resolved in exactly one place (`api-key`, private) and attached in exactly one
  place (`resolve-provider`'s return). Every other return path names its fields
  one by one instead of passing a map through -- `active-provider` above all,
  which is what a session is told about itself. Nothing about this is enforced by
  the language: eval's var-quote and resolve reach any var in this file, exactly
  as they reach any other, so the enforcement is prompt.md's secrets clause and
  the tests that assert an `:api-key` never appears at any depth. `wire` refuses
  to render the field at all, which is the one mechanical guard there is.

  WHY A PROVIDER IS NOT A MODEL. The old shape made one entry do both: :cheap was
  an endpoint AND a model, so the endpoint and the model id moved together and
  'switch vendor' had no expression at all -- a tier could name a provider and
  have the name silently dropped, because it was not one of the fields being
  merged. Splitting vendor from model is what makes three knobs enough to describe
  every run, and it is what gives a model somewhere to declare what it can receive.

  The old flat shape is neither read nor migrated: it fails by name, saying what
  to write instead."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [harness.home :as home]))

;; ------------------------------------------------------------- the vocabulary

(def input-types
  "What a message may carry IN, as far as this harness can move it. A model
  declaring anything else is claiming a capability the wire cannot deliver."
  #{:text :image})

(def output-types
  "What a model may give back, as far as this harness can carry it.

  Only :text -- narrower than INPUT-TYPES on purpose. harness.llm folds text,
  reasoning and tool calls out of a response and drops everything else, so a
  model declaring image output would be declaring something this harness cannot
  deliver."
  #{:text})

(def counts
  "What a model may say about SIZE: how much context it holds, and how much it
  may give back. Both are OPTIONAL and both are COUNTS OF TOKENS -- a positive
  integer, or a named failure.

  NEITHER IS ENFORCED, and that is the honest half of this design. Nothing here
  counts tokens, so measuring a run against :context-window would mean estimating,
  and a refusal built on an estimate is an estimate dressed up as a fact.
  :max-output-tokens is not sent to the vendor either: the vendor's own default
  stands, and this harness does not second-guess it.

  They are facts for whoever is CHOOSING a model -- the capability endpoint and
  the provider lines in the log -- which is a reader, and a real one. That is the
  same test :output had to pass: declare a thing only when someone reads it."
  #{:context-window :max-output-tokens})

(def model-keys
  "Everything a model entry may carry: the two modality sets it MUST declare and
  the two counts it MAY. A key outside this fails by name -- a stray
  :context_window would otherwise be silently dropped, and the entry would look
  like it declared nothing."
  (into #{:input :output} counts))

(def knobs
  "The three things a tier may choose. Everything else about a provider is the
  catalog's business -- which is what makes 'switch vendor' one knob instead of a
  set of endpoint fields that have to move in lockstep."
  [:provider :model :reasoning-effort])

(def resolved-fields
  "What the catalog answers with, once a selection has been assembled. The two
  counts ride along with the modalities: they are answers about the model that
  was selected, and a reader asking 'what is this session on' wants them."
  [:protocol :base-url :model :input :output :context-window :max-output-tokens])

(def catalog-fields
  "What a TIER may never name: everything the catalog answers about a model once
  it has been selected -- its endpoint, its modalities, its two counts. A tier
  chooses provider, model and reasoning effort and nothing else, so a tier
  naming one of these is either a mistake or a description (the inline form,
  which is a different shape and not a tier at all).

  NAMING ONE FAILS BY NAME, in `selection` below. select-keys alone would drop
  them quietly, which is the silent no-op this catalog exists to kill: the caller
  wrote something, the run succeeded, and nothing happened."
  (into #{:protocol :base-url :input :output} counts))

(def inline-fields
  "The fields config.edn may use to DESCRIBE a provider instead of naming one --
  the escape hatch, for trying one endpoint without registering it. Exactly
  resolved-fields: an inline provider is a resolved provider, written by hand."
  resolved-fields)

;; ------------------------------------------------------------ named failures
;;
;; Every failure here says what was wrong, where, and what the shape should have
;; been. A catalog that fails anonymously is worse than no catalog: the run dies
;; somewhere downstream with the model id smuggled into an HTTP error body.

(defn- fail [msg data] (throw (ex-info msg data)))

(defn- ->kw
  "NAME as a keyword, or a named failure. Keywords, strings and symbols are all
  accepted spellings of a name; anything else (a number, a vector) is a typo
  worth stopping for, not something to silently stringify."
  [x what where]
  (if (or (keyword? x) (string? x) (symbol? x))
    (keyword (name x))
    (fail (str where " has " what " " (pr-str x) ", which is not a name")
          {:where where :value x})))

(defn- sortable [xs] (vec (sort-by str xs)))

(defn- unknown-keys!
  "Fail if M carries keys outside ALLOWED. A key nobody reads is a silent no-op
  -- the exact failure this catalog exists to stop -- so a stray :reasoning-effort
  left over from the old shape is reported, not ignored."
  [where m allowed]
  (let [bad (sortable (remove (set allowed) (keys m)))]
    (when (seq bad)
      (fail (str where " carries " (pr-str bad) ", which it does not understand;"
                 " it knows " (pr-str (sortable allowed)))
            {:where where :unknown bad}))))

(defn- modalities
  "The declared modality set for one direction of a model entry, or nil when the
  entry does not declare it. Accepts a set or a sequence; a type outside CARRIED
  fails by name, naming what this harness does carry."
  [entry k carried where]
  (when-let [v (get entry k)]
    (let [vs  (cond (set? v) v (sequential? v) (set v) :else #{v})
          ks  (set (map #(->kw % (str k " entry") where) vs))
          bad (sortable (remove carried ks))]
      (when (seq bad)
        (fail (str where " declares " (pr-str k) " " (pr-str (mapv name bad))
                   ", which this harness cannot carry; it carries "
                   (pr-str (sortable (map name carried))))
              {:where where :declared (mapv name bad) :carried carried}))
      ks)))

(defn- count-of
  "ENTRY's declared value for K -- a count of tokens -- or nil when the entry is
  silent about it. Silence is allowed and means something specific: this harness
  knows nothing about that number, which is not the same as knowing it is zero.

  A value that is not a positive integer fails BY NAME. Every other reading is
  worse: \"8192\" would be carried around as a string that sorts and prints like a
  number, 0 would read as 'declared, and it is nothing', and a float would be
  rounded somewhere far from here."
  [entry k where]
  (let [v (get entry k)]
    (when (some? v)
      (when-not (and (integer? v) (pos? v))
        (fail (str where " declares " (pr-str k) " " (pr-str v)
                   ", which is not a count of tokens; write a positive integer,"
                   " e.g. " (pr-str k) " 200000")
              {:where where :declared v}))
      v)))

(defn- limits
  "The two optional counts a model entry states, as a map -- empty when it states
  neither. When it states both, the output cannot exceed the context: a model
  claiming to give back more than it can hold has a stale number in it, and the
  failure names both rather than picking which one to believe."
  [entry where]
  (let [ctx (count-of entry :context-window where)
        out (count-of entry :max-output-tokens where)]
    (when (and ctx out (> out ctx))
      (fail (str where " declares :max-output-tokens " out
                 " but a context window of only " ctx
                 "; a model cannot give back more than it can hold -- one of the"
                 " two numbers is stale, and this harness will not guess which")
            {:where where :context-window ctx :max-output-tokens out}))
    (cond-> {}
      ctx (assoc :context-window ctx)
      out (assoc :max-output-tokens out))))

(defn- modalities-of
  "The :input / :output pair for one model, BOTH required. A model that declares
  neither would make the guard vacuous and leave the catalog silent about the one
  thing it exists to state; a model that declares one has stated half a
  capability, which is worse than stating nothing because it reads as complete.

  The inline form is the one caller that may omit both -- see check-inline, which
  only asks for the pair when the entry mentions one of them."
  [entry where]
  (let [missing (remove #(contains? entry %) [:input :output])]
    (when (seq missing)
      (fail (str where " does not declare " (pr-str (vec missing))
                 "; every model says what it takes in and gives out, e.g."
                 " {:input #{:text :image} :output #{:text}}")
            {:where where :missing (vec missing)}))
    {:input  (modalities entry :input input-types where)
     :output (modalities entry :output output-types where)}))

(defn- check-model
  "One model entry -> what it declares: the two required modality sets, plus
  whichever of the two counts it states.

  A key the entry is not understood to carry fails by name (model-keys): this is
  the one place a model speaks about itself, and a silent drop here would turn a
  typo into a model that quietly declares nothing."
  [entry where]
  (unknown-keys! where entry model-keys)
  (merge (modalities-of entry where) (limits entry where)))

;; --------------------------------------------------------- one provider entry

(def ^:private provider-keys #{:protocol :base-url :model :models})

(def ^:private inline-keys
  "What the inline form may carry: its endpoint, its one model id, and then
  everything a model entry may say -- built from model-keys rather than listed
  again, so a field added to a model entry is accepted here without a second
  edit that someone will forget."
  (into #{:protocol :base-url :model} model-keys))

(defn- check-provider
  "A named registry entry -> its normalized form
  {:protocol .. :base-url .. :model <default id> :models {id {:input .. :output ..}}}.

  An entry with no :models table is the OLD flat shape -- a provider that WAS a
  model -- and says so, in those words, with the new shape spelled out: this
  harness does not read two shapes and does not migrate between them."
  [name entry]
  (let [where (str "provider " (pr-str name))]
    (when-not (map? entry)
      (fail (str where " is " (pr-str entry) ", not a map") {:provider name}))
    (let [models (:models entry)]
      (when-not (map? models)
        (fail (str where
                   (when (contains? entry :model)
                     (str " has :model " (pr-str (:model entry)) " but no :models table"
                          " -- that is the old flat shape, where a provider WAS a model"))
                   "; a provider now names the vendor and lists the models reachable"
                   " there: {:protocol :openai-completions :base-url \"https://…\""
                   " :model \"the-default-id\""
                   " :models {\"the-default-id\" {:input #{:text} :output #{:text}}}}")
              {:provider name}))
      (when (empty? models)
        (fail (str where " lists no models") {:provider name}))
      (unknown-keys! where entry provider-keys)
      (doseq [k [:protocol :base-url :model]]
        (when-not (contains? entry k)
          (fail (str where " names no " (pr-str k)
                     "; a provider needs an endpoint to reach and a default model to serve")
                {:provider name :missing k})))
      (let [norm (into {} (map (fn [[id m]]
                                 [(str id)
                                  (check-model m (str "model " (pr-str id)
                                                      " of provider " (pr-str name)))]))
                       models)
            dflt (str (:model entry))]
        (when-not (contains? norm dflt)
          (fail (str where " names default model " (pr-str (:model entry))
                     " but does not declare it; it declares " (pr-str (sortable (keys norm))))
                {:provider name :model (:model entry) :known (sortable (keys norm))}))
        {:protocol (:protocol entry)
         :base-url (:base-url entry)
         :model    dflt
         :models   norm}))))

(defn- check-inline
  "A config.edn that DESCRIBES its provider instead of naming one -> the same
  normalized form as a registry entry.

  Two spellings, both the escape hatch: a flat map of fields, or a map carrying
  its own :models table (which is just a registry entry without a name). With no
  :models table there is nothing to validate a model id against -- the entry IS
  the one model -- so :model is the id and :input/:output are optional; when they
  are declared, both must be, so the catalog never states half a capability.

  The two counts are optional here for the same reason, and they are the reason
  this branch still builds a :models table when NO modality is declared: an
  inline entry that says only {:context-window 128000} has declared something,
  and dropping it because it said nothing about modalities would be the silent
  drop this catalog refuses to perform."
  [entry]
  (let [where "the provider described inline in config.edn"]
    (when-not (map? entry)
      (fail (str where " is " (pr-str entry) ", not a map") {}))
    (if (contains? entry :models)
      (check-provider :inline entry)
      (do
        (when (empty? entry)
          (fail (str "config.edn names no provider and describes none: give a name"
                     " ({:provider :openrouter}) or the inline form"
                     " ({:protocol … :base-url … :model …})")
                {}))
        (unknown-keys! where entry inline-keys)
        (doseq [k [:protocol :base-url :model]]
          (when-not (contains? entry k)
            (fail (str where " names no " (pr-str k)
                       "; the inline form is {:protocol … :base-url … :model …}")
                  {:missing k})))
        (let [id    (str (:model entry))
              half? (or (contains? entry :input) (contains? entry :output))
              dirs  (cond-> (limits entry where)
                      half? (merge (modalities-of entry where)))]
          (cond-> {:protocol (:protocol entry)
                   :base-url (:base-url entry)
                   :model    id}
            (seq dirs) (assoc :models {id dirs})))))))

;; ------------------------------------------------------------------ the file

(def builtin-raw
  "The providers this harness knows out of the box, so a config.edn naming one
  needs no providers.edn at all.

  :as-of 2026-09-14. EVERY ID BELOW WAS READ OFF THE VENDOR'S OWN LIVE LISTING
  that day, not written from memory -- and the exercise earned its keep, because
  the ids that would have been written from memory are gone (DeepSeek's direct
  API now answers to deepseek-flash / deepseek-v4-pro, not the deepseek-chat that
  every example still shows). Re-checking this table is a maintenance task, not a
  formality: a stale id here fails as 'this provider declares no model', which is
  a confusing way to learn that a vendor renamed something.

  THE TWO COUNTS ARE READ THE SAME WAY, OR LEFT OUT. :context-window and
  :max-output-tokens come from the vendor's own listing -- OpenRouter's
  /api/v1/models publishes context_length and top_provider.max_completion_tokens,
  DeepSeek's pricing page states 1M context and a 384K maximum output, ollama's
  library page states the context window per tag. Where no vendor publishes a
  number, the entry says nothing rather than carrying a plausible one: ollama
  publishes no output limit (the server's own default decides), so :ollama's
  models declare :context-window only. A count nobody verified is the same class
  of lie as an id nobody verified.

  THE NUMBERS ARE NOT ROUNDED INTO AGREEMENT. Two models from one vendor differ
  because the vendor says so: OpenRouter's listing gives deepseek-v4.1-flash a
  maximum completion of 384000 and deepseek-v4-pro 393216 -- neither is 384K
  spelled two ways, and a reader who 'corrects' the smaller one to match is
  writing from memory, which is how this table was wrong the first time. A count
  here is a quotation, not a calculation.

  ONLY OPENAI-COMPATIBLE VENDORS. Anthropic's and Google's own APIs are not
  chat-completions, so a built-in :anthropic here would be a lie -- they arrive
  through :openrouter, whose table lists them. openai, xai and groq are absent for
  a different and more mundane reason: their model listings answered 403 to an
  unauthenticated check today, and this table does not carry ids nobody verified.
  Adding one is a single providers.edn entry, which is also where that vendor's
  list should be consulted.

  A model that ALSO accepts :audio or :video at the vendor is recorded as
  :text/:image only. The declaration says what a run may send, and those are the
  types this harness can carry."
   {:openrouter
    {:protocol :openai-completions
     :base-url "https://openrouter.ai/api/v1"
     :model    "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"
     :models   {"nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"
                {:input #{:text :image} :output #{:text}
                 :context-window 256000 :max-output-tokens 65536}
                "anthropic/claude-sonnet-4.5" {:input #{:text :image} :output #{:text}
                                               :context-window 1000000
                                               :max-output-tokens 64000}
                "anthropic/claude-haiku-4.5"  {:input #{:text :image} :output #{:text}
                                               :context-window 200000
                                               :max-output-tokens 64000}
                "deepseek/deepseek-v4.1-flash" {:input #{:text :image} :output #{:text}
                                                :context-window 1048576
                                                :max-output-tokens 384000}
                "deepseek/deepseek-v4-pro"     {:input #{:text}       :output #{:text}
                                                :context-window 1048576
                                                :max-output-tokens 393216}
                "openai/gpt-4o-mini"           {:input #{:text :image} :output #{:text}
                                                :context-window 128000
                                                :max-output-tokens 16384}}}

    :deepseek
    {:protocol :openai-completions
     :base-url "https://api.deepseek.com/v1"
     :model    "deepseek-flash"
     :models   {"deepseek-flash"   {:input #{:text :image} :output #{:text}
                                    :context-window 1048576 :max-output-tokens 393216}
                "deepseek-v4-pro"  {:input #{:text}       :output #{:text}
                                    :context-window 1048576 :max-output-tokens 393216}}}

    ;; ollama publishes a context window per tag and no output limit at all: the
    ;; server's num_predict default decides, and a number here would be a guess.
    ;; :qwen3's 40K is the `latest` tag's; a tag like :30b carries 256K, which is
    ;; what providers.edn is for.
    :ollama
    {:protocol :openai-completions
     :base-url "http://localhost:11434/v1"
     :model    "qwen3"
     :models   {"qwen3"    {:input #{:text}       :output #{:text}
                            :context-window 40960}
                "qwen3-vl" {:input #{:text :image} :output #{:text}
                            :context-window 262144}}}})

(def ^:private builtin
  "BUILTIN-RAW validated once, at load. Compiled-in data cannot be edited into a
  bad state at runtime, so paying for the checks on every read would buy nothing
  -- while a typo in the table SHOULD stop the process at load rather than surface
  on the first run that happens to name that provider. Validating here also means
  the merge below only ever validates the user's half."
  (into {} (map (fn [[n e]] [(->kw n "a provider name" "harness.builtin-raw")
                             (check-provider n e)]))
        builtin-raw))

(defn- over
  "The user's entry for NAME laid over the built-in one, field by field, with
  :models merged MODEL BY MODEL rather than replaced.

  Replacing the model table wholesale would make 'add one model to a vendor this
  harness already knows' require retyping every id it already had -- and the
  retyped list would then drift from the built-in one, which is precisely the
  duplication the built-in table exists to remove. Merging by id means an entry
  here says only what it means to say.

  An individual model merges FIELD BY FIELD as well, for the same reason: an
  entry correcting one built-in model's :context-window should not have to
  restate that model's modalities to do it, and a restated pair is exactly the
  copy that goes stale while the built-in moves on.

  The result is re-validated as a whole (see catalog), so a partial patch --
  {:base-url \"…\"} and nothing else -- is legitimate: it borrows the built-in
  provider's protocol, default and model table."
  [builtin-entry user-entry]
  (if (nil? user-entry)
    builtin-entry
    (merge builtin-entry user-entry
           {:models (merge-with merge (:models builtin-entry) (:models user-entry))})))

(defn catalog
  "The provider registry this process resolves against: {name provider}, with the
  user's providers.edn laid over the built-in table. Re-read on every call,
  matching config.edn's rule.

  A missing providers.edn is NOT an error and does not even mean an empty catalog:
  the built-in table stands on its own, so a config.edn naming :openrouter or
  :deepseek or :ollama works with no file at all. That is the point of shipping
  one -- the three knobs in config.edn should be enough to start.

  Every merged entry is validated eagerly, including ones this run will not use: a
  malformed catalog is a configuration mistake, and meeting it on the run that
  happens to name it turns one clear failure into an intermittent one."
  []
  (let [f (home/providers-file)]
    (if-not (.exists f)
      builtin
      (let [raw  (edn/read-string (slurp f :encoding "UTF-8"))
            path (.getAbsolutePath f)]
        (when-not (map? raw)
          (fail (str path " must be a map of provider name -> provider, not "
                     (pr-str (type raw)))
                {:path path}))
        (let [user (into {}
                         (map (fn [[n e]]
                                [(->kw n "a provider name" path) e]))
                         raw)]
          (into {}
                (map (fn [[n e]]
                       [n (check-provider n (over (get builtin n) e))]))
                (merge builtin user)))))))

;; ------------------------------------------------------------- the fold
;;
;; A SELECTION is folded from the tiers, low to high. Everything else about a
;; provider is the catalog's answer; these three knobs are the only things a tier
;; gets to choose.

(defn- name-of
  "A provider name, canonicalized. JSON hands over \"beta\", EDN hands over :beta,
  a tool call may hand over either -- all three spell one provider, and folding
  them into three different values would mint three providers. A MAP is returned
  untouched: an inline provider is not a name, and two inline maps are equal when
  they say the same thing."
  [p]
  (if (map? p) p (->kw p "a provider name" "a selection")))

(defn- describing?
  "M describes a provider rather than selecting one -- the inline form, which is
  allowed to carry everything the catalog would otherwise have answered. It is
  recognized by naming an endpoint: :protocol or :base-url belongs to no tier."
  [m]
  (or (contains? m :protocol) (contains? m :base-url)))

(defn selection
  "M -> the knobs M names, with a provider name canonicalized. Anything else M
  carries -- an endpoint, a modality, a model's counts -- is not a selection and
  is not folded: those are what the catalog ANSWERS, never something a tier
  chooses.

  NAMING ONE OF THEM IS A FAILURE, NOT A SILENT DROP. select-keys would discard
  them quietly, and that is precisely the failure this shape was built to kill:
  the caller wrote something, the run reported success, and nothing happened. A
  tier that wants to describe a provider rather than name one has the inline form
  (see check-inline), which is a different shape and declares itself by naming
  its endpoint.

  Canonicalizing the name is not cosmetic. JSON hands over \"beta\" and EDN hands
  over :beta; folding them as two values would mint two providers, and a reader
  diffing a session's timeline would see a vendor switch where nothing switched."
  [m]
  (when-not (describing? m)
    (let [bad (sortable (filter catalog-fields (keys m)))]
      (when (seq bad)
        (fail (str "a tier may not carry " (pr-str bad)
                   "; those are the catalog's answers about the model a tier"
                   " selected, and a tier chooses only "
                   (pr-str (mapv str knobs))
                   " -- declare them on the model entry in providers.edn"
                   " (or describe the whole provider inline in config.edn)")
              {:unknown bad :knobs knobs}))))
  (let [s (select-keys m knobs)]
    (if (contains? s :provider)
      (update s :provider name-of)
      s)))

(defn fold-selection
  "TIERS, lowest first -> one SELECTION. A knob a tier does not name falls
  through from below, which is what lets the three move independently.

  THE ONE EXCEPTION, and it is deliberate: :model is scoped to :provider. A tier
  that names a DIFFERENT provider than the fold currently holds, and does not
  name a model alongside it, drops the model -- because a model id means 'an id
  this provider serves', and an id from the old vendor names nothing at the new
  one. Carrying it over would either fail the run ('beta declares no model
  alpha-small') or, worse, be silently replaced later; dropping it lands on the
  new vendor's default model, which is a stated fact the resolution records.

  That is what makes 'switch vendor' expressible as ONE knob at any tier, which
  is the point of the split. It is not the silent-drop failure this shape
  replaced: that one dropped a knob the caller DID name, and reported success
  while serving the old endpoint."
  [& tiers]
  (reduce
   (fn [acc tier]
     (let [t (selection tier)]
       (if (empty? t)
         acc
         (let [switch? (and (contains? t :provider)
                            (not= (:provider t) (:provider acc)))
               model   (cond
                         (contains? t :model) (:model t)
                         switch?              nil
                         :else                (:model acc))]
           (cond-> acc
             (contains? t :provider)         (assoc :provider (:provider t))
             (contains? t :reasoning-effort) (assoc :reasoning-effort (:reasoning-effort t))
             (some? model)                   (assoc :model model)
             (and switch? (nil? model))      (dissoc :model))))))
   {}
   tiers))

;; ----------------------------------------------------------------- assembly

(defn- named
  "Look SRC up in REGISTRY, by name. A name that is not there is a hard, NAMED
  failure: falling back to nil would serve the run from nowhere and report the
  problem as a confusing downstream error instead of 'no such provider'."
  [registry src]
  (let [k (->kw src "a provider name" "the selection")]
    (if-let [entry (get registry k)]
      {:name k :entry entry}
      (fail (str "no provider named " (pr-str src)
                 "; the registry defines "
                 (if (empty? registry)
                   "nothing"
                   (pr-str (sortable (map name (keys registry))))))
            {:name src :known (sortable (map name (keys registry)))}))))

(defn assemble
  "A SELECTION -> the provider a run is served by.

  SELECTION names the three knobs. :provider is a registry name, a map (the
  inline form), or absent when the selection came from an inline config.

  THE MODEL ID IS FOLDED, NOT DEFAULTED EARLY. A tier that names no :model does
  not pin one, so changing only :provider lands on the NEW vendor's default model
  with its endpoint. An id some tier DID name must be declared by the selected
  provider, or this fails by name and lists what that provider declares -- it
  never quietly falls back, because a fallback here means the run reports success
  while being served by a model nobody asked for.

  Returns the resolved fields, plus :provider (the name; absent when inline) and
  :reasoning-effort (absent when no tier named one -- the resolution is a
  description of what was chosen, not a fill-in-the-blanks)."
  [registry selection]
  (let [src    (:provider selection)
        inline (map? src)
        found  (cond
                 inline     {:name nil :entry (check-inline src)}
                 (nil? src) (fail (str "no provider: name one in config.edn"
                                       " (e.g. {:provider :openrouter}) or describe one inline"
                                       " ({:protocol … :base-url … :model …})")
                                  {})
                 :else      (named registry src))
        entry  (:entry found)
        chosen (:model selection)
        models (:models entry)
        id     (str (or chosen (:model entry)))]
    (when (and (map? models) (not (contains? models id)))
      (fail (str "provider "
                 (if inline "described inline in config.edn" (pr-str (:name found)))
                 " declares no model " (pr-str id)
                 "; it declares " (pr-str (sortable (keys models))))
            {:provider (:name found) :model id :known (sortable (keys models))}))
    (let [m (get models id)]
      (cond-> (merge {:protocol (:protocol entry)
                      :base-url (:base-url entry)
                      :model    id}
                     ;; The model's own declaration travels as ONE unit: whatever
                     ;; check-model validated, keyed by model-keys -- modalities
                     ;; and counts alike. Copying them one at a time is how a
                     ;; third count would get declared, validated, and then
                     ;; silently left out of every resolution.
                     (select-keys m model-keys))
        (:name found)                         (assoc :provider (:name found))
        (some? (:reasoning-effort selection)) (assoc :reasoning-effort (:reasoning-effort selection))))))

;; ------------------------------------------------------- provider outbox
;;
;; A PENDING-OUTBOX, not a copy of state: the tool body records that the session
;; changed and what it changed from and to, and the http edge drains it to the
;; jsonl. It exists for exactly the reason parked-registry does -- to carry a
;; fact across the seam from the code that knows it (the tool) to the code that
;; writes it down (the edge -- the only writer). Once drained it is gone, and
;; nothing reads it back.

(defonce ^:private provider-changes
  (atom []))
;; [{:thread-id .. :before <knob slice> :after <knob slice>
;;   :trigger "session-configure" :override <session tier afterwards>
;;   :resolved <what the catalog assembled from that tier>}]

(defn record-provider-change!
  "Note that THREAD-ID's provider moved from BEFORE to AFTER, by an APPROVED
  change of TRIGGER (a string identifying the path that pressed the change --
  currently always \"session-configure\"). The body only runs on an approval --
  a vetoed call never reaches it -- so landing here means the human said yes;
  a veto leaves no change line at all, and the reader tells the two apart by
  the presence of this line (paired with its approval/decided row).

  OVERRIDE is the session's OWN tier after the change -- the partial the next
  resolve-provider would consult. :before / :after are slices (only the knobs
  this call moved); :override is the whole tier, so a reader can reconstruct the
  post-change session from this line alone.

  RESOLVED is that tier again, assembled -- the endpoint and modalities the
  session is now served by. Recorded here rather than left to a reader to
  re-derive, because the catalog moves underneath the log: a built-in table
  gains a model, a base-url changes, and re-resolving an old line would answer
  with today's catalog instead of that day's. Drained, not read: the writer
  empties this after every run."
  [thread-id before after trigger override resolved]
  (swap! provider-changes conj {:thread-id thread-id
                                :before before :after after
                                :trigger trigger :override override
                                :resolved resolved}))

(defn take-provider-changes!
  "Every pending provider change recorded for THREAD-ID, in order, clearing them.
  Called by the http edge after a run -- the one writer."
  [thread-id]
  (let [[taken _] (swap-vals! provider-changes
                              (fn [vs] (into [] (remove #(= thread-id (:thread-id %))) vs)))]
    (filterv #(= thread-id (:thread-id %)) taken)))

;; ------------------------------------------------------------------- config

(defn config
  "config.edn, re-read every time so it can be edited while the process runs.
  This is the EDN half only (:protocol/:base-url/:model...) -- there is no
  api-key here and never will be: the key is resolved from .env/environment in
  the provider-resolution section below, which is where the effective provider
  is assembled.

  The path comes from harness.home; a missing file is a named failure there."
  []
  (edn/read-string (home/config)))

;; -------------------------------------------------------- provider resolution
;;
;; Merged in from the former harness.opaque, unchanged in behaviour. This is
;; where the effective provider is ASSEMBLED, because that is the one place the
;; api-key is legitimate -- and the one place it must never leak from. The
;; resolution has four levels, each overriding the one before it, field by
;; field (see resolve-provider). The boundary this section marks is prompt.md's
;; written discipline, not the language: eval can reach every var here, and the
;; rule it must follow is spelled out in prompt.md, not in visibility.

;; TWO slots, on purpose -- they answer different questions and must not collide:
;;
;;   scripted-pins     a WHOLE provider, installed by tests (and replay) to take
;;                     the place of config resolution entirely. It is the seam
;;                     that drives the edge offline.
;;   session-overrides a PARTIAL {field value}, the session's own configuration
;;                     change (tier 3 of resolve-provider).
;;
;; Both are keyed by thread-id: one session reconfiguring itself must not
;; silently reconfigure every other session in the process. A nil thread-id
;; addresses the process-wide slot -- replay and offline tools run outside a
;; session and use that one.
(defonce ^:private scripted-pins (atom {}))
(defonce ^:private session-overrides (atom {}))

(defn use-provider!
  "Pin THREAD-ID's session to a whole PROVIDER, bypassing the config files (and
  the session override). Pass nil to drop the pin. This is how the edge is
  exercised offline, against a scripted provider, without an api-key or a
  network."
  ([provider] (use-provider! nil provider))
  ([thread-id provider]
   (if (nil? provider)
     (swap! scripted-pins dissoc thread-id)
     (swap! scripted-pins assoc thread-id provider))))

(defn pinned-provider
  "THREAD-ID's scripted pin, or nil. A pin may carry a key, though a scripted
  one does not -- which is one more reason introspection never returns a whole
  provider map."
  [thread-id]
  (get @scripted-pins thread-id))

(defn override-for
  "THREAD-ID's session configuration override -- a partial SELECTION ({:provider
  :model :reasoning-effort}) -- or nil. This is the session's own tier of
  resolve-provider: the session's own change, not a test seam."
  [thread-id]
  (get @session-overrides thread-id))

(defn set-override!
  "Replace THREAD-ID's session override with OV, or drop it entirely when OV is
  nil. Separate from use-provider! because a session override is PARTIAL --
  naming only what changes -- while a pin is a whole provider.

  OV is canonicalized into a selection on the way in (see selection), so a
  provider named by a JSON string and one named by an EDN keyword are the SAME
  override. Storing the raw spelling would let one provider be selected twice and
  show up in a timeline as a change that changed nothing.

  Only the three knobs are kept, and that is the shape, not a lossy filter: a
  session chooses WHICH provider and model, never an endpoint -- the endpoint is
  what the catalog answers for the provider it chose. An override naming one
  therefore has no effect, which is the correct outcome rather than a silent
  failure: there is nothing for it to mean.

  RETURNS what was stored, canonicalized (nil when cleared). A caller recording
  the change it just made should record THAT rather than its own copy of the
  arguments: the stored value is the one the next run will fold, and a caller
  keeping a parallel version of it is how the log and the session drift apart."
  [thread-id ov]
  (if (nil? ov)
    (do (swap! session-overrides dissoc thread-id) nil)
    (let [sel (selection ov)]
      (swap! session-overrides assoc thread-id sel)
      sel)))

(defn- parse-dotenv
  "A .env file's contents -> a {name value} map. Handles the shapes the format
  actually uses: `export` prefixes, surrounding single or double quotes,
  `#` comments, blank lines, and values that themselves contain `=` (only the
  first `=` splits).

  We parse it ourselves rather than lean on the dotenv library because that
  library resolves `.env` from the CURRENT DIRECTORY at namespace-load time and
  caches it in a def -- so it cannot be pointed at harness.home, and it would
  miss an edit made while the process runs. Both of those matter here."
  [raw]
  (into {}
        (->> (str/split-lines raw)
             (map str/trim)
             (remove #(or (empty? %) (str/starts-with? % "#")))
             (map #(str/split % #"=" 2))
             (filter #(= 2 (count %)))
             (map (fn [[k v]]
                    [(str/replace (str/trim k) #"^export\s+" "")
                     (let [v (str/trim v)]
                       (if (and (>= (count v) 2)
                                (or (and (str/starts-with? v "\"") (str/ends-with? v "\""))
                                    (and (str/starts-with? v "'") (str/ends-with? v "'"))))
                         (subs v 1 (dec (count v)))
                         v))])))))

(defn- api-key
  "The API key, following the dotenv library's documented precedence: a value in
  .env wins over a real environment variable. So .env is the single place that
  decides, and setting a shell variable will NOT override it.

  The file is harness.home's .env -- and is re-read every time, like config.edn,
  so editing it takes effect without a restart. PRIVATE, and doubly so by
  discipline: the key flows ONLY into resolve-provider's result, and prompt.md
  forbids reaching for it any other way."
  []
  (let [f (home/dotenv-file)
        from-file (when (.exists f) (get (parse-dotenv (slurp f :encoding "UTF-8"))
                                         "HARNESS_API_KEY"))]
    (or from-file (System/getenv "HARNESS_API_KEY"))))

;; ---------------------------------------------- the selection and its tiers
;;
;; A provider is no longer described field by field across the tiers. It is
;; SELECTED: the three knobs in harness.knobs (:provider / :model /
;; :reasoning-effort) are folded down the tiers, and the selected provider's
;; entry in the catalog ASSEMBLES the endpoint and the model's modalities.
;;
;; That is the whole reason the old four-field merge went away. Under a
;; field-by-field merge, "which vendor" was expressed by moving :protocol and
;; :base-url, so a tier could name :provider and have it silently dropped -- the
;; name was not a field, so nothing carried it. A knob that is folded and then
;; resolved cannot be dropped without a loud failure: an unknown provider name
;; and an undeclared model id both stop the run by name.

(defn- default-selection
  "config.edn, the default tier. Two shapes:

    {:provider :openrouter :model \"…\" :reasoning-effort \"high\"}
        name a provider; the other two knobs are optional
    {:protocol … :base-url … :model … :input #{…} :output #{…}}
        DESCRIBE a provider instead of naming one -- the escape hatch, for trying
        one endpoint without registering it. Modalities may be omitted here,
        which is a statement that this entry declares nothing (nothing then
        guards its input), not an error.

  A config that does neither fails by name in assemble, saying which of
  the two shapes to write."
  [cfg]
  (if (contains? cfg :provider)
    (selection cfg)
    (let [desc (select-keys cfg inline-fields)]
      (when (seq desc)
        (assoc (selection cfg) :provider desc)))))

(defn- fold-and-assemble
  "A folded SELECTION -> the provider it names, assembled from the catalog. The
  one place a selection becomes a provider, so every path that resolves -- a run,
  an offline tool, a session's own pending change -- agrees on what a selection
  means and on which failures it can raise.

  Deliberately WITHOUT the api-key: resolution and key-attachment are two steps,
  and only resolve-provider performs the second. Validation has no business
  reading a secret to answer a question about a model id."
  [folded]
  (assemble (catalog) folded))

(defn- resolve-tiers
  "The three tiers resolve-provider folds, LOWEST first, as the raw maps they
  arrive as: config's default tier (or an inline description), this session's
  override, and this run's request.

  ONE DEFINITION, so the fold and anything that has to say WHERE a knob came
  from cannot disagree about which tiers were in play or in what order. The
  request tier is `{}` when there is no run in hand -- an offline tool, or the
  read-only settings panel, which is asking about the configuration rather than
  about any one run."
  [thread-id request]
  [(default-selection (config))
   (or (override-for thread-id) {})
   (or request {})])

(defn resolve-provider
  "The effective provider for THREAD-ID, plus where it came from.

  Three tiers, each overriding the one before it KNOB BY KNOB:

    1. config.edn's default tier       (or a provider described inline)
    2. this session's override         (the session-configure tool)
    3. this run's request              (REQUEST, from the input map)

  The fold produces a SELECTION, and the catalog assembles it: the selected
  provider supplies :protocol / :base-url and the model's :input / :output,
  and a model id no tier named resolves to the provider's default. Because the
  endpoint follows the provider, a tier that names only :provider really does
  switch vendors -- under the old field-by-field merge it silently did not.

  Returns {:provider {.. :api-key ..} :selection {..} :source :default|:inline|:request},
  where the api-key is attached LAST and only here. :source names where the
  resolution STARTED -- config's default tier, a provider config described
  inline, or this run's request -- not which tier last moved a knob; a session's
  own changes are the provider/changed timeline's business, not this field's. A
  THREAD-ID of nil resolves the default tier with no session in play, which is
  what an offline tool wants.

  REQUEST must not be routed through the prompt context -- it would become a
  trailing user message and poison the provider's prefix cache."
  ([thread-id] (resolve-provider thread-id nil))
  ([thread-id request]
   (let [[base ses run] (resolve-tiers thread-id request)
         folded (fold-selection base ses run)
         source (cond
                  (seq (selection run)) :request
                  (map? (:provider base))      :inline
                  :else                        :default)]
     {:provider  (assoc (fold-and-assemble folded) :api-key (api-key))
      :selection folded
      :source    source})))

(defn resolve-override
  "What THREAD-ID's session would be served by if its own tier WERE OV -- the
  question session-configure asks before it writes anything.

  A change that cannot be served is not a change: a provider name that is not in
  the catalog, or a model id the selected provider does not declare, has to fail
  at the moment it is proposed. Writing it first and failing later would leave
  the override holding a configuration every subsequent run fails on -- and the
  failure would surface on the NEXT run, nowhere near the call that caused it.

  OV is the session's whole tier afterwards (not just the knobs this call moved),
  because that is what the next run will fold. Returns
  {:selection <the tier as folded> :resolved <what the catalog assembled>}, with
  no api-key: this answers a question about a configuration, and a secret is not
  part of that answer."
  [ov]
  (let [folded (fold-selection (default-selection (config)) ov)]
    {:selection folded
     :resolved  (fold-and-assemble folded)}))

(defn effective-provider
  "The provider for THREAD-ID, without a run request: the default and session
  tiers plus the ENV-sourced api-key. Offline tools and replay use this -- they
  run outside a run, so there is no request to layer on top."
  ([] (effective-provider nil))
  ([thread-id] (:provider (resolve-provider thread-id))))

(defn current-provider
  "What the http edge serves from for THREAD-ID on this run: an explicit scripted
  pin wins outright -- it is the test seam, and the tests that use it are not
  exercising provider resolution. Otherwise the four-tier resolution runs, with
  REQUEST layered on top."
  ([thread-id] (current-provider thread-id nil))
  ([thread-id request]
   (or (pinned-provider thread-id)
       (:provider (resolve-provider thread-id request)))))

(defn active-provider
  "What THREAD-ID's session is serving from right now -- the SELECTION and what
  it resolved to:

    {:provider :openrouter :model \"anthropic/claude-sonnet-4.5\"
     :reasoning-effort \"high\" :protocol :openai-completions
     :base-url \"https://openrouter.ai/api/v1\" :input #{:text :image} :output #{:text}
     :context-window 1000000 :max-output-tokens 64000}

  The three knobs are what was CHOSEN; the rest is what the catalog answered.
  Both are worth reporting: :model is the id, and a reader asking 'what is this
  session on' wants the name, not just the endpoint it happens to reach. The two
  counts are the model's, and are absent when the catalog says nothing about
  them -- which is a fact about the entry, not a zero.

  Resolved live through the tiers above -- the session override included -- but
  NEVER the api-key: the fields are named one by one rather than the map being
  passed through, so a future change to the resolver cannot leak a key through
  here.

  A nil THREAD-ID answers for the process-wide slot (no session in play), which
  is what an offline tool wants. Sets, not wire strings: this is the in-process
  answer, and wire is what renders it for a log, an HTTP body or
  a tool result."
  [thread-id]
  (let [p (effective-provider thread-id)]
    (select-keys p [:provider :model :reasoning-effort
                    :protocol :base-url :input :output
                    :context-window :max-output-tokens])))

(def ^:private never-rendered
  "Fields this shape refuses to carry, whatever a caller asks for. :api-key is
  here so that naming it -- by a future call site, by a mistake, by a helper that
  renders 'everything' -- still produces a shape without it. That is a guardrail
  against an accident, not a security boundary: the rule that the key is resolved
  in one place and never surfaces through a self-inspection answer is prompt.md's
  discipline, and this only means the serializer is not a place it can leak from.

  IT IS ALSO THE ONE LIST `settings` SUBTRACTS (see `unsecret`), and that reuse
  is the point: a field that may not be RENDERED and a field that may not be
  INSPECTED are the same set of fields, and two lists would be two places to
  forget."
  #{:api-key})

;; ------------------------------------------------------------------ settings
;;
;; The read-only half of this namespace: what configuration is in force RIGHT
;; NOW, where each choice came from, where the key would be read from, and which
;; files make up this home. It is the one answer meant to be shown to a person,
;; which is why the key's treatment is spelled out at every step below.

(def ^:private tier-names
  "The three tiers a selection is folded down, LOWEST first, named as the panel
  shows them. The vec is the order; the keyword is what an answer carries."
  [:config :session :request])

(defn- tier-sources
  "Which tier supplied each of the three knobs, as RESOLVED sees them --
  {:provider :config :model :session :reasoning-effort :request}, plus :catalog
  for a knob no tier named.

  DERIVED FROM THE FOLD ITSELF rather than by re-reading the tiers: for each
  knob, the winner is the LAST tier that named it whose prefix still folds to the
  final value. Reimplementing the fold here would be a second copy of its one
  special rule (:model is scoped to :provider -- a tier that switches vendor and
  names no model drops it), and a second copy is how the panel comes to explain a
  choice the resolution did not make.

  :catalog is a real answer and not a shrug, and it covers two cases that look
  different and are not: a provider's DEFAULT model (nobody named one, the
  catalog's entry does), and the vendor switch above (the model a tier named was
  dropped on purpose, so what is in force is again the new entry's default). Both
  are 'no tier chose this', which is exactly what a person debugging 'why am I
  talking to this model' needs to hear. The knobs are read off RESOLVED as well
  as off the fold for that reason: a dropped model is absent from the fold and
  present in what the run will actually use."
  [tiers resolved]
  (let [folded (apply fold-selection tiers)
        named  (vec (map vector tier-names tiers))
        ;; A knob can be in force without being in the fold -- see the vendor
        ;; switch above -- so the knob set comes from both sides.
        ks     (into #{} (concat (keys folded) (filter #(contains? resolved %) knobs)))]
    (into {}
          (keep (fn [k]
                  (when (contains? resolved k)
                    [k (if-not (contains? folded k)
                         :catalog
                         (or (last (for [[i [_ m]] (map-indexed vector named)
                                         :when (contains? (selection m) k)
                                         :let  [upto (map second (subvec named 0 (inc i)))]
                                         :when (= (get (apply fold-selection upto) k)
                                                  (get folded k))]
                                     (first (nth named i))))
                             :catalog))])))
          ks)))

(defn api-key-source
  "Where the api-key WOULD be read from, as facts that carry no value:

    {:present? true|false :source :env-file|:environment|nil}

  THE PRECEDENCE IS `api-key`'s, not a guess: a value in the home's .env wins
  over a real environment variable, so a home with both is a home whose key comes
  from the file. Reporting the other one would send a person to edit a variable
  that is being ignored.

  THE VALUE, ITS LENGTH AND ITS PREFIX ARE ALL ABSENT, and this is a separate
  function from `api-key` rather than a wrapper over it: a wrapper would have the
  secret in hand and would have to remember not to return it, while this one
  never reads the value out of the map it parses. A nil :source means nobody
  supplies one, which is a normal state -- offline tools run without a key, and a
  run that needs one fails by name at the vendor."
  []
  (let [f (home/dotenv-file)
        from-file (when (.exists f)
                    (get (parse-dotenv (slurp f :encoding "UTF-8")) "HARNESS_API_KEY"))
        from-env  (System/getenv "HARNESS_API_KEY")]
    (cond
      (some? from-file) {:present? true  :source :env-file}
      (some? from-env)  {:present? true  :source :environment}
      :else             {:present? false :source nil})))(defn home-origin
  "Which of harness.home's three rules produced the config root:

    :environment  CLJ_HARNESS_HOME said so
    :override     a test bound *root-override* (normally invisible, and truthful
                  if it ever is not)
    :default      ~/.clj-harness

  WHICH RULE WON IS THE POINT of showing the path at all: a home the environment
  moved and a home nobody moved are debugged in completely different places, and
  a bare path does not say which one this is."
  []
  (cond
    (some? home/*root-override*)               :override
    (some? (System/getenv "CLJ_HARNESS_HOME")) :environment
    :else                                      :default))

(defn- home-files
  "Every FILE this home is made of, named as a person would say it, in the order
  harness.home declares them. The log tree is a directory and is not listed: the
  panel answers 'which of the things I edit are here', and a tree of conversations
  is not one of them."
  []
  (mapv (fn [[nm ^java.io.File f]]
          {:name nm :path (.getAbsolutePath f) :present? (.exists f)})
        [["config.edn"    (home/config-file)]
         ["providers.edn" (home/providers-file)]
         ["hooks.edn"     (home/hooks-file)]
         [".env"          (home/dotenv-file)]
         ["harness.db"    (home/db-file)]]))

(defn- unsecret
  "M with every field in `never-rendered` removed -- AT EVERY DEPTH.

  THE DIFFERENCE RATHER THAN A LIST OF WHAT MAY BE SHOWN, and that is the shape
  the ticket asks for: a whitelist has to be extended every time the resolution
  learns a new fact, so the failure it invites is 'a field was added and nobody
  judged it', which surfaces as a panel quietly missing something. Subtracting a
  short, named set of SECRETS inverts that: a new fact appears by itself, and the
  only way to leak is to add a secret to the resolution without adding it to
  `never-rendered`.

  Walking all the way down matters because a resolution carries nested maps -- the
  inline form's provider description lives inside :selection -- and a secret one
  level deeper is exactly as secret."
  [m]
  (walk/postwalk (fn [x] (if (map? x) (apply dissoc x never-rendered) x)) m))

(defn settings
  "The read-only settings panel's whole answer for THREAD-ID: what is in force,
  where each choice came from, where the key would come from, and which files make
  up this home.

    {:protocol :openai-completions :base-url \"https://…\" :model \"…\"
     :reasoning-effort \"low\" :input [\"image\" \"text\"] :output [\"text\"]
     :context-window 256000 :max-output-tokens 65536
     :selection {:provider \"openrouter\" :model \"…\"}
     :source :default|:inline|:request
     :tiers {:provider :config :model :catalog :reasoning-effort :session}
     :key {:present? true :source :env-file}
     :home {:path \"/Users/…/.clj-harness\" :origin :default
            :files [{:name \"config.edn\" :path \"…\" :present? true} …]}}

  EVERYTHING COMES FROM FILES AND LIVE MEMORY, NEVER FROM THE STORE, and
  re-reading is the whole contract: config.edn, providers.edn, .env and the
  session's own override are each read at call time, so editing one and asking
  again shows the new answer with no restart and nothing written. That is the
  discipline this namespace follows everywhere, and this is the one place a
  person can SEE it.

  READ-ONLY IN EVERY DIRECTION: nothing here writes a file, touches the store, or
  moves a session -- asking what the configuration is must not be able to change
  it, including while a run is in flight, which this is safe to call during
  because it resolves and returns rather than registering anything.

  A configuration that cannot be resolved FAILS BY NAME (an unknown provider, an
  undeclared model, a missing config.edn), and that failure is the ANSWER's
  replacement rather than a bug: the caller shows the reason, which is of far
  more use than an empty panel. See the http route for how it is carried.

  Sets, not wire strings, exactly like `active-provider`; `wire` is what renders
  this for a body. The KEY is subtracted rather than omitted field by field (see
  `unsecret`), and its presence and origin are reported instead -- under :key,
  NOT under :api-key, so that the report cannot be confused with the secret at
  any layer and `never-rendered` stays a guard instead of a naming hazard: were a
  raw resolution ever merged into this answer, `wire` would strip its :api-key
  and leave the report standing."
  [thread-id]
  (let [[base session request] (resolve-tiers thread-id nil)
        {:keys [provider selection source]} (resolve-provider thread-id)]
    (merge (unsecret provider)
           {:selection (unsecret selection)
            :source    source
            :tiers     (tier-sources [base session request] provider)
            :key       (api-key-source)
            :home      {:path   (home/root)
                        :origin (home-origin)
                        :files  (home-files)}})))

;; -------------------------------------------------------------------- wire

(defn wire
  "M -> the shape that may leave this process: the fields in KS, with modality
  sets rendered as SORTED STRING vectors.

  Sorted, because JSON has no sets: an unsorted set would make two otherwise
  identical log lines differ run to run, and a reader diffing a timeline would
  see changes that never happened. Strings, because this shape is written to
  logs, HTTP bodies and tool results, where a keyword is only ever a string with
  different clothes on.

  Absent values stay absent rather than becoming null: a field no tier named is a
  fact about the resolution ('nothing chose this'), and null would read as
  'chose nothing'.

  Only fields in KS are rendered -- the call names what it means to publish, so a
  caller cannot accidentally publish more than it asked for. never-rendered
  removes the rest."
  ([m] (wire m (into knobs resolved-fields)))
  ([m ks]
   (let [ks  (remove never-rendered ks)
         sel (into {} (remove (comp nil? val)) (select-keys m ks))]
     (reduce (fn [acc k]
               (if (contains? acc k)
                 (update acc k #(mapv name (sort-by name %)))
                 acc))
             sel
             [:input :output]))))

;; ------------------------------------------------------- what a picker offers

(def reasoning-efforts
  "The reasoning efforts a session may pick, as the composer's picker offers
  them.

  A CLOSED LIST HERE AND NOWHERE ELSE, and that asymmetry is deliberate: nothing
  in this namespace validates :reasoning-effort -- the value travels to the wire
  as `reasoning_effort` and each vendor decides what it means, so refusing a name
  here would refuse one a vendor accepts. The list is therefore not a guard, it is
  an OFFER: the three OpenAI-compatible values, which is what the providers in the
  built-in table speak. A session that wants something else can still be given it
  by `session-configure` or by config.edn; the picker just does not put it on the
  menu."
  ["low" "medium" "high"])

(defn choices
  "What a session may be switched to, for the composer's model picker:

    {:provider \"openrouter\" :model \"…\" :reasoning-effort \"high\"
     :reasoning-efforts [\"low\" \"medium\" \"high\"]
     :providers [{:name \"deepseek\" :models [\"deepseek-flash\" \"…\"]} …]}

  THE THREE CURRENT VALUES ARE SCALARS PICKED BY NAME, and the provider list is
  built here rather than passed through `wire`. That is not a shortcut around the
  secret rule -- it is the same rule the other way round: `wire` renders a
  resolution by SELECTING FIELDS, and handing it a list of maps would run its
  modality step over them (it sorts and `name`s every value of the keys it is
  asked about) and mangle a nested shape. So nothing here merges a resolution in;
  every value is one this function chose, and :api-key is not among them at any
  depth.

  THE LIST IS THE CATALOG, NOT THE RESOLUTION: every provider that declares at
  least one model, sorted by name so the menu has one order. A provider with no
  :models cannot be switched TO (naming it would fail in `assemble`), so offering
  it would be offering a refusal."
  [thread-id]
  (let [current (wire (active-provider thread-id))]
    {:provider (:provider current)
     :model (:model current)
     :reasoning-effort (:reasoning-effort current)
     :reasoning-efforts reasoning-efforts
     :providers (->> (catalog)
                     (keep (fn [[n entry]]
                             (let [models (keys (:models entry))]
                               (when (seq models)
                                 {:name (name n) :models (sortable models)}))))
                     (sort-by :name)
                     vec)}))

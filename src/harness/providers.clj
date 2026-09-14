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
  The catalog: the built-in table, reading and validating providers.edn, merging
  the two, and turning a SELECTION into a resolved provider.

  It does NOT own which tier wins, and it never touches the api-key. Both of
  those are the neighbouring half of 'what is this session served from' -- which
  provider the config, the session and the request agree on, and where the secret
  comes from -- and they do not live here yet. This namespace answers about
  providers in general; it does not know what a SESSION is.

  WHY A PROVIDER IS NOT A MODEL. The old shape made one entry do both: :cheap was
  an endpoint AND a model, so the endpoint and the model id moved together and
  'switch vendor' had no expression at all -- a tier could name a provider and
  have the name silently dropped, because it was not one of the fields being
  merged. Splitting vendor from model is what makes three knobs enough to describe
  every run, and it is what gives a model somewhere to declare what it can receive.

  The old flat shape is neither read nor migrated: it fails by name, saying what
  to write instead."
  (:require [clojure.edn :as edn]
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
  (into {} (map (fn [[n e]] [(->kw n "a provider name" "harness.providers/builtin-raw")
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

;; -------------------------------------------------------------------- wire

(def ^:private never-rendered
  "Fields this shape refuses to carry, whatever a caller asks for. :api-key is
  here so that naming it -- by a future call site, by a mistake, by a helper that
  renders 'everything' -- still produces a shape without it. That is a guardrail
  against an accident, not a security boundary: the rule that the key is resolved
  in one place and never surfaces through a self-inspection answer is prompt.md's
  discipline, and this only means the serializer is not a place it can leak from."
  #{:api-key})

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

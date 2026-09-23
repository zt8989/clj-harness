(ns harness.cap.providers
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
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [harness.infra.home :as home]
            ;; For the implemented-protocol set, READ off the multimethod
            ;; rather than keeping a list that could disagree with it. cap -> kernel
            ;; is the allowed direction, and kernel.llm does not require this
            ;; namespace, so there is no cycle to worry about.
            [harness.kernel.llm :as llm])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

;; ------------------------------------------------------------- the vocabulary

(def input-types
  "What a message may carry IN, as far as this harness can move it. A model
  declaring anything else is claiming a capability the wire cannot deliver."
  #{:text :image})

(def output-types
  "What a model may give back, as far as this harness can carry it.

  Only :text -- narrower than INPUT-TYPES on purpose. harness.kernel.llm folds text,
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
  was selected, and a reader asking 'what is this session on' wants them. The
  display name is an answer about the PROVIDER rather than the model, and it
  rides along for the same reason: it is the catalog's to say, never a tier's."
  [:protocol :base-url :model :display-name :input :output
   :context-window :max-output-tokens])

(def catalog-fields
  "What a TIER may never name: everything the catalog answers once a selection has
  been assembled -- the endpoint, the display name, the model's modalities and its
  two counts. A tier chooses provider, model and reasoning effort and nothing
  else, so a tier naming one of these is either a mistake or a description (the
  inline form, which is a different shape and not a tier at all).

  NAMING ONE FAILS BY NAME, in `selection` below. select-keys alone would drop
  them quietly, which is the silent no-op this catalog exists to kill: the caller
  wrote something, the run succeeded, and nothing happened.

  DERIVED FROM `resolved-fields` RATHER THAN LISTED AGAIN, and that is the fix for
  a trap this list used to be: a field added to the vocabulary and not to this
  literal set was a field a tier could name and have silently dropped -- the exact
  failure the paragraph above is about. :model is the one resolved field a tier
  DOES choose, so it is the one subtraction."
  (disj (set resolved-fields) :model))

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

(defn- set->wire
  "A modality set -> the SORTED STRING vector that may leave this process. One
  definition, because `wire` and the registry report both render these and a second
  copy would be a second answer to 'what order do they come in' -- which is the
  whole point of sorting them (two otherwise identical answers must not differ)."
  [s]
  (mapv name (sort-by name s)))

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

(def ^:private provider-keys
  "What a named entry may carry. :display-name is the one field here that is not
  about reaching the vendor: it is what a person calls this vendor, and it exists
  because the form that creates one asks for it (its reference asks for a Provider
  ID AND a display name). Everything else -- the credential name, the log lines,
  the :provider knob -- stays the ID: a display name is a second name for the
  screen, not a second identity."
  #{:protocol :base-url :model :models :display-name})

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
      (when (contains? entry :display-name)
        (let [label (:display-name entry)]
          (when-not (and (string? label) (not (str/blank? label)))
            (fail (str where " has :display-name " (pr-str label)
                       "; it is what a person calls this vendor, so it is a"
                       " non-empty string -- the ID is the identity, and this is"
                       " only the label on screen")
                  {:provider name :display-name label}))))
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
        (cond-> {:protocol (:protocol entry)
                 :base-url (:base-url entry)
                 :model    dflt
                 :models   norm}
          ;; ABSENT STAYS ABSENT, like every other optional field: an entry with no
          ;; display name has none, and a nil here would read as 'named nothing'.
          (some? (:display-name entry)) (assoc :display-name (:display-name entry)))))))

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
          (fail (str "config.edn's :default names no provider and describes none: give a"
                     " name ({:default {:provider :openrouter}}) or describe one there"
                     " ({:default {:protocol … :base-url … :model …}})")
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
;;
;; ONE FILE, TWO SECTIONS. config.edn is read here (below), the built-in table is
;; the floor under it, and `catalog` is the two combined -- in that order, because
;; that is the order a reader asking "which vendors can this process reach" wants
;; to meet them in.

(def ^:private config-sections
  "The two sections config.edn is made of, and the whole of its top level:

    :default    the three knobs a session starts from (:provider / :model /
                :reasoning-effort), or a provider DESCRIBED inline
    :providers  the vendor catalog, {name entry}, laid over the built-in table

  TWO SECTIONS IN ONE FILE, which is the point of the shape: which vendors this
  process can reach and which one it starts on are one question asked twice, so a
  person answering either opens one file. What used to be providers.edn is the
  :providers section now -- see `catalog`."
  #{:default :providers})

(defn- check-config
  "A parsed config.edn -> the same map, or a named failure about its SHAPE.

  SEPARATE FROM THE READ, because `migrate-config!` has to ask the same question about
  a map that is not on disk yet: 'would this pass when it is read back'. One checker,
  so the answer cannot differ between the two."
  [raw path]
  (when-not (map? raw)
    (fail (str path " must be a map of the two sections (:default and :providers), not "
               (pr-str (type raw)))
          {:path path}))
  (let [unknown (sortable (remove config-sections (keys raw)))]
    (when (seq unknown)
      (fail (str path " carries " (pr-str unknown) " at its top level; it is made of two"
                 " sections -- :default (the three knobs a session starts from:"
                 " :provider / :model / :reasoning-effort) and :providers (the vendors,"
                 " {name entry}). The knobs used to sit at the top level themselves:"
                 " start the server once and it moves them under :default for you,"
                 " or move them yourself.")
            {:path path :unknown unknown})))
  (doseq [k (sortable (keys raw))]
    (when-not (map? (get raw k))
      (fail (str path "'s " (pr-str k) " must be a map, not " (pr-str (get raw k)))
            {:path path :section k})))
  raw)

(defn config
  "config.edn, re-read every time so it can be edited while the process runs ->
  the two sections it is made of, checked as such.

  THE TOP LEVEL IS CLOSED, and that check IS the migration: this file used to BE
  the default tier, with :provider / :model / :reasoning-effort at the top. Those
  keys are a named failure now, saying to put them under :default -- rather than
  a second shape being read, which is the rule this catalog keeps everywhere
  (see `check-provider` on the far older flat provider shape).

  There is no api-key here and never will be: the key is resolved from
  .env/environment in the provider-resolution section below, which is where the
  effective provider is assembled.

  AN EMPTY FILE IS A FILE THAT SAYS NOTHING, which this shape spells `{}`. `touch
  config.edn` is the ordinary way to meet this -- somebody following the missing-file
  failure's own advice -- and answering it with 'must be a map ... not nil' told them
  their empty file was malformed when what it was is silent: the built-in catalog
  still stands, no default tier is named, and the failure a run meets is the one that
  says which shape to write. A file that SAYS something which is not a map (a vector,
  a string, a number) is a different matter and stays a named failure.

  A previously-working file is not refused, either: the top level the knobs used to
  live at is UPGRADED at boot see `migrate-config!`.

  The path comes from harness.infra.home."
  []
  (check-config (or (edn/read-string (home/config)) {})
                (.getAbsolutePath (home/config-file))))

(def builtin-raw
  "The providers this harness knows out of the box, so a config.edn naming one
  needs no :providers entry at all.

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
  Adding one is a single :providers entry in config.edn, which is also where that
  vendor's list should be consulted.

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
    ;; what an entry in config.edn's :providers is for.
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

(defn- config-path [] (.getAbsolutePath (home/config-file)))

(defn- user-catalog
  "RAW -- a parsed :providers section -- -> the validated catalog, laid over the
  built-in table.

  ONE DEFINITION, and for the writer it is the whole discipline: the file a form is
  about to write is validated by exactly the code that will validate it on the next
  read, so 'it validated' cannot come to mean two different things over time."
  [raw]
  (if (empty? raw)
    builtin
    (let [path (config-path)
          user (into {}
                     (map (fn [[n e]]
                            [(->kw n "a provider name" path) e]))
                     raw)]
      (into {}
            (map (fn [[n e]]
                   [n (check-provider n (over (get builtin n) e))]))
            (merge builtin user)))))

(defn catalog
  "The provider registry this process resolves against: {name provider}, with the
  :providers section of config.edn laid over the built-in table. Re-read on every
  call, matching config.edn's rule.

  A config.edn with NO :providers section is not an error and does not even mean an
  empty catalog: the built-in table stands on its own, so a :default naming
  :openrouter or :deepseek or :ollama works with no entry anywhere. That is the
  point of shipping one -- the three knobs should be enough to start.

  A providers.edn FILE IS A NAMED FAILURE, not a quiet ignore. It held this section
  until the catalog moved into config.edn, and a file that goes on looking
  authoritative while none of its entries does anything is exactly the silent
  no-op this catalog exists to kill. So the reader says which file to empty into
  :providers and delete, rather than leaving a person to wonder why an edit to it
  changed nothing.

  Every merged entry is validated eagerly, including ones this run will not use: a
  malformed catalog is a configuration mistake, and meeting it on the run that
  happens to name it turns one clear failure into an intermittent one."
  []
  (let [f (home/providers-file)]
    (when (.exists f)
      (fail (str (.getAbsolutePath f)
                 " is no longer read: the provider catalog is the :providers section of"
                 " config.edn now. Move its entries there and delete this file.")
            {:path (.getAbsolutePath f)}))
    (user-catalog (:providers (config)))))

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
                   " -- declare them on the model entry in config.edn's :providers"
                   " (or describe the whole provider inline in :default)")
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
                 ;; THE SHAPE THIS SENTENCE TEACHES IS THE FILE'S OWN, sections and all:
                 ;; it is the one a person meets on a fresh home, and an example written
                 ;; against the file's previous shape was advice to write something that
                 ;; no longer parses.
                 ;; THE ABSOLUTE PATH IS IN THE SENTENCE, because the file that used to
                 ;; be missing *was* named by a refusal that no longer fires: 'which
                 ;; file, and where' is the half of it that was worth keeping.
                 (nil? src) (fail (str "no provider: name one in " (config-path)
                                       "'s :default (e.g. {:default {:provider :openrouter}})"
                                       " or describe one inline there"
                                       " ({:default {:protocol … :base-url … :model …}})")
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
        ;; The PROVIDER's label, not the model's: it travels from the entry for the
        ;; same reason the model's declaration travels from the model entry -- a
        ;; reader answering 'what is this session on' should read the catalog's
        ;; answer, not a second lookup that could disagree with it.
        (some? (:display-name entry))         (assoc :display-name (:display-name entry))
        (:name found)                         (assoc :provider (:name found))
        (some? (:reasoning-effort selection)) (assoc :reasoning-effort (:reasoning-effort selection))))))

;; ------------------------------------------------------- provider outbox
;;
;; A PENDING-OUTBOX, not a copy of state: whoever knows the session changed
;; records what it changed from and to, and the http edge drains it to the jsonl.
;; It exists for exactly the reason parked-registry does -- to carry a fact
;; across the seam from the code that knows it to the code that writes it down
;; (the edge -- the only writer). Once drained it is gone, and nothing reads it
;; back.
;;
;; NOTHING FEEDS IT TODAY, and that is the honest state rather than an oversight:
;; its only producer was the `session-configure` tool, and that tool is gone. The
;; KIND stays -- `provider/changed` is read back by the prompt context
;; (harness.edge.context) and by the trajectory, and a kind is vocabulary rather
;; than a producer -- so the drain stays too: should a tool ever move the
;; selection again, the line, its `:verdict` and its `:resolved` are already wired
;; end to end. `POST /api/model` deliberately does NOT come through here -- it IS
;; the edge, so it writes its own line at the moment of the change.

(defonce ^:private provider-changes
  (atom []))
;; [{:thread-id .. :before <knob slice> :after <knob slice>
;;   :trigger <the path that pressed it> :override <session tier afterwards>
;;   :resolved <what the catalog assembled from that tier>}]

(defn record-provider-change!
  "Note that THREAD-ID's provider moved from BEFORE to AFTER, by an APPROVED
  change of TRIGGER (a string identifying the path that pressed the change).
  The body only runs on an approval -- a vetoed call never reaches it -- so
  landing here means the human said yes; a veto leaves no change line at all,
  and the reader tells the two apart by the presence of this line (paired with
  its approval/decided row).

  NO CALLER TODAY: the `session-configure` tool was the only one, and it has been
  removed -- this and the drain are kept as the shape a tool-made change travels
  in, and the tests drive them directly (see the outbox note above).

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

(defn credential-name
  "The `.env` name a provider's api-key lives under: its id uppercased, every
  character that is not a letter or a digit turned into an underscore, and _API_KEY
  appended.

    :acme-gateway   ->  ACME_GATEWAY_API_KEY
    :acme.gateway   ->  ACME_GATEWAY_API_KEY
    \"My_Vendor\"    ->  MY_VENDOR_API_KEY

  PUBLIC, AND THAT IS THE POINT: the reader here and whatever WRITES that line -- a
  person editing .env by hand now, the settings form's route later -- have to agree
  on the name, and two derivations of one name eventually differ. The precedent is
  harness.infra.home/sanitize, for exactly this reason.

  TOTAL AND NOT INJECTIVE, both deliberately. Total, because a provider id is a
  keyword and keywords are not restricted to letters: :My_Vendor resolves a key
  rather than failing over punctuation nobody chose. Not injective, because
  uppercasing and collapsing punctuation maps :a-b, :a_b and :a.b onto ONE name --
  so two providers whose ids differ only in punctuation share a credential. That is
  a property of any rule that produces a shell-friendly name, not a bug in this one;
  what keeps it from biting is the ID RULE the settings form enforces
  (^[a-z][a-z0-9-]*$, one spelling per provider). A hand-written config.edn that
  spells two ids nearly the same way is told so here rather than left to discover it
  from a request that goes out with the wrong key.

  ONE NAME, ONE KEY, which is the shape the reference form asks for: the form's own
  help text says the id derives the credential name, and this is that sentence
  written down as code."
  [provider]
  (str (str/upper-case (str/replace (name provider) #"[^A-Za-z0-9]" "_")) "_API_KEY"))

(defn- credential-names
  "The names PROVIDER's api-key may live under, in the order they are tried: the
  provider's own derived name first, then the global HARNESS_API_KEY as the
  fallback.

  THE FALLBACK IS WHAT MAKES THIS LAND: every home configured before this change has
  one HARNESS_API_KEY, and the three built-in vendors and any inline description
  keep working off it with nothing to edit. A provider that says its own name wins;
  a provider that says nothing still has a key.

  AN INLINE PROVIDER HAS NO NAME (it is a description, not an entry in the catalog),
  so it has only the global one -- not a derived name invented from its endpoint,
  which would be a credential nobody could have known to write."
  [provider]
  (if (some? provider)
    [(credential-name provider) "HARNESS_API_KEY"]
    ["HARNESS_API_KEY"]))

(defn- api-key
  "PROVIDER's API key: the provider's own derived name first, then the global
  HARNESS_API_KEY -- and within each name, the home's .env before the environment
  (harness.infra.home/env-source owns that order; prompt.md owns the discipline that
  this is the only place a key is ever resolved).

  PROVIDER is the catalog NAME of a provider, or nil for an inline description. A
  key attached to the wrong provider is indistinguishable from a working one until
  the vendor answers 401, so the name is asked for rather than guessed from
  whatever happens to be in the environment.

  PRIVATE, and doubly so by discipline: the key flows ONLY into resolve-provider's
  result, and prompt.md forbids reaching for it any other way."
  [provider]
  (some-> (home/env-source (credential-names provider)) :name (home/env-value)))

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
  "config.edn's :default section, the default tier. Two shapes:

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
  arrive as: config.edn's :default section (or an inline description), this
  session's override, and this run's request.

  ONE DEFINITION, so the fold and anything that has to say WHERE a knob came
  from cannot disagree about which tiers were in play or in what order. The
  request tier is `{}` when there is no run in hand -- an offline tool, or the
  read-only settings panel, which is asking about the configuration rather than
  about any one run."
  [thread-id request]
  [(default-selection (:default (config)))
   (or (override-for thread-id) {})
   (or request {})])

(defn resolve-provider
  "The effective provider for THREAD-ID, plus where it came from.

  Three tiers, each overriding the one before it KNOB BY KNOB:

    1. config.edn's default tier       (or a provider described inline)
    2. this session's override         (written by POST /api/model -- the picker)
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
         resolved (fold-and-assemble folded)
         source (cond
                  (seq (selection run)) :request
                  (map? (:provider base))      :inline
                  :else                        :default)]
     {:provider  (assoc resolved :api-key (api-key (:provider resolved)))
      :selection folded
      :source    source})))

(defn resolve-override
  "What THREAD-ID's session would be served by if its own tier WERE OV -- the
  question `POST /api/model` asks before it writes anything.

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
  (let [folded (fold-selection (default-selection (:default (config))) ov)]
    {:selection folded
     :resolved  (fold-and-assemble folded)}))

(defn swap-override!
  "Fold CHANGE into THREAD-ID's session override, and answer the transition it made:

    {:before <the override as it was> :after <what was stored, canonicalized, or nil>
     :resolved <what the catalog assembled for :after>}

  ONE ATOM OPERATION, and that is the whole of it. Reading the override, folding the
  change in and writing it back is three steps, so two callers who do that LOSE one of
  the two changes -- and, worse, both then record a before->after pair that never
  happened. THE TWO CALLERS ARE ONE CALLER TWICE, which is the ordinary case rather
  than a rare one: this route runs on an http-kit thread, so two presses of the picker
  -- or a press while the previous one is still resolving -- land here at once. Before
  `session-configure` was removed the second caller was the tool, on a tool thread; the
  discipline is unchanged, and the test that pins it drives two swaps at once. Here the
  write is a compare-and-set on the map itself, retried until it lands, and the pair it
  answers with is the pair it made.

  RESOLVE RUNS BEFORE THE WRITE AND OUTSIDE THE RETRY. `resolve-override` is what proves
  the change can be served at all, and it throws when it cannot -- which is why it must
  not live inside the `swap!` function: that function is re-run under contention, and a
  throw there would abandon a write that had nothing wrong with it. A change that cannot
  be served writes NOTHING, and the session keeps what it had.

  CHANGE nil clears the override, and the answer is then {:after nil}. `set-override!` is
  the same store without the transition, and stays for the callers that only want to set
  a value."
  [thread-id change]
  (loop []
    (let [m      @session-overrides
          before (get m thread-id)
          sel    (when change (selection (merge before change)))
          ;; Throws when the change cannot be served -- before anything is written.
          served (when sel (resolve-override sel))]
      (if (compare-and-set! session-overrides m
                            (if sel (assoc m thread-id sel) (dissoc m thread-id)))
        {:before before :after sel :resolved (:resolved served)}
        (recur)))))

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
    (select-keys p [:provider :model :reasoning-effort :display-name
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
  "Where the api-key for PROVIDER would be read from, as facts that carry no value:

    {:present? true|false :source :env-file|:environment|nil :name \"ACME_GATEWAY_API_KEY\"}

  :name IS THE USEFUL HALF OF THIS ANSWER, and it is present either way: the name
  that WON when a key is set, and the name that would be read first when none is --
  which is the line a person has to add. Reporting only presence would leave them to
  derive the name from the provider id in their heads, which is the one step this
  feature exists to remove.

  THE PRECEDENCE IS `api-key`'s, not a guess: the provider's own name before the
  global one, and within a name the home's .env before the environment. So a home
  with both is a home whose key comes from the file. Reporting the other one would
  send a person to edit a variable that is being ignored.

  THE VALUE, ITS LENGTH AND ITS PREFIX ARE ALL ABSENT, and this is a separate
  function from `api-key` rather than a wrapper over it: a wrapper would have the
  secret in hand and would have to remember not to return it, while this one never
  reads a value out of the map it parses. A nil :source means nobody supplies one,
  which is a normal state -- offline tools run without a key, and a run that needs
  one fails by name at the vendor."
  [provider]
  (let [{:keys [name source]} (home/env-source (credential-names provider))]
    {:present? (some? name)
     :source   source
     :name     (or name (first (credential-names provider)))}))(defn home-origin
  "Which of harness.infra.home's three rules produced the config root:

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
  harness.infra.home declares them. The log tree is a directory and is not listed: the
  panel answers 'which of the things I edit are here', and a tree of conversations
  is not one of them."
  []
  (mapv (fn [[nm ^java.io.File f]]
          {:name nm :path (.getAbsolutePath f) :present? (.exists f)})
        [["config.edn"    (home/config-file)]
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
  re-reading is the whole contract: config.edn (both of its sections) and the
  session's own override are read at call time, so editing one and asking
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
            :key       (api-key-source (:provider provider))
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
                 (update acc k set->wire)
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
  by `POST /api/model` or by config.edn; the picker just does not put it on the
  menu."
  ["low" "medium" "high"])

(defn choices
  "What a session may be switched to, for the composer's model picker:

    {:provider \"openrouter\" :model \"…\" :reasoning-effort \"high\"
     :reasoning-efforts [\"low\" \"medium\" \"high\"]
     :providers [{:name \"deepseek\" :models [\"deepseek-flash\" \"…\"]
                  :key {:present? true :source :env-file :name \"DEEPSEEK_API_KEY\"}} …]}

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
  it would be offering a refusal.

  EACH ROW ALSO CARRIES WHETHER THIS HOME HOLDS A KEY FOR THAT VENDOR, and the rule
  the picker applies to it is the settings page's rule rather than a second one: a
  vendor is shown when this home has a key pointing at it, because putting a vendor
  that will certainly refuse in front of a person leads them to a run that cannot
  work. It arrives WITH the list instead of being fetched beside it, so the two can
  never be out of step. `api-key-source` is the one place that question is answered
  -- the same function the settings rows carry -- and it is a FACT, NOT A VALUE: it
  never reads a key out of the map it reports on, which is why :api-key stays absent
  here at every depth, exactly as everywhere else in this answer.

  :name IS THE ID AND :display-name IS THE LABEL, and the two are deliberately
  different keys rather than one already-decided string: what to SHOW is the
  client's business (falling back to the id when there is no label, putting it in
  a title, dropping it), while what to SEND is the id -- the picker sends `name`
  and nothing else. Handing over a pre-chosen label would make the fallback rule a
  server detail no client could see, and a client that showed only the label would
  have no id to send."
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
                                 (cond-> {:name (name n) :models (sortable models)
                                          :key  (api-key-source n)}
                                   (some? (:display-name entry))
                                   (assoc :display-name (:display-name entry)))))))
                     (sort-by :name)
                     vec)}))

;; --------------------------------------------- what the settings form reads and writes
;;
;; Two halves in one section because they are one conversation: the reader answers
;; "what is in this home", the writer answers "make this entry so", and both speak
;; to the SAME validator the file is read through (`user-catalog`). That is what
;; makes the form's promises true -- "a rejected change writes nothing" is only
;; checkable if the check and the read are one function.

(defn- implemented-protocols
  "The protocols this process can actually SPEAK, read off `llm/stream!` itself: the
  multimethod's dispatch values ARE the set of implemented protocols.

  DERIVED RATHER THAN LISTED, for the reason the whole catalog exists: a second
  implementation must not need a second edit to become offerable, and a name with no
  method behind it must not be offerable at all. A hand-written list would go stale
  in the direction that matters -- the form would offer a protocol nothing can
  serve, and the run would fail somewhere far from the form.

  A FUNCTION RATHER THAN A def, which is what makes that claim true instead of
  nearly true: a def would freeze the set at LOAD time, so a method added later --
  by a test, by a plugin, by anything that runs after this namespace -- would be
  speakable and unofferable at once. Asking at call time costs one small map lookup
  and answers about the process as it is now."
  []
  (set (keys (methods llm/stream!))))

(defn protocol-names
  "The protocols as a client spells them: names, sorted."
  []
  (set->wire (implemented-protocols)))

(defn- origin-of
  "Which of the three things this catalog entry is: the built-in table's (nothing in
  config.edn mentions it), yours alone, or your PATCH of a built-in.

  The distinction is the difference between two edits a person makes in the same
  form -- 'add my vendor' and 'point openrouter at my proxy' -- and getting it wrong
  would make the second look like the first, so the panel would offer to delete a
  built-in provider."
  [user n]
  (let [mine?  (contains? user n)
        built? (contains? builtin n)]
    (cond
      (and mine? built?) :builtin-patched
      built?             :builtin
      :else              :user)))

(defn- model-row
  "One model entry -> the row a form edits: the id, its two modality sets as wire
  strings, and whichever counts it states."
  [id m]
  (cond-> {:id     id
           :input  (set->wire (:input m))
           :output (set->wire (:output m))}
    (some? (:context-window m))    (assoc :context-window (:context-window m))
    (some? (:max-output-tokens m)) (assoc :max-output-tokens (:max-output-tokens m))))

(defn registry-report
  "The catalog as the settings page needs it:

    {:providers [{:name \"acme-gateway\" :display-name \"Acme Gateway\" :origin :user
                  :protocol \"openai-completions\" :base-url \"https://…\"
                  :model \"gpt-x\"
                  :models [{:id \"gpt-x\" :input [\"text\"] :output [\"text\"]} …]
                  :credential \"ACME_GATEWAY_API_KEY\"
                  :key {:present? true :source :env-file :name \"ACME_GATEWAY_API_KEY\"}}
                 …]
     :protocols [\"openai-completions\"]
     :reasoning-efforts [\"low\" \"medium\" \"high\"]
     :default   {:provider \"acme-gateway\" :model \"gpt-x\"}}

  EVERY FIELD IS ONE THIS FUNCTION CHOSE -- it does not merge a resolution in -- and
  :api-key is therefore not in it at any depth, at any nesting the rows might grow.
  The `:key` facts come from `api-key-source`, which never reads a value.

  `:default` IS THE SECTION AS WRITTEN, rendered by `wire`: the three knobs when it
  names a provider, the endpoint fields when it DESCRIBES one. Which of the two it is
  is visible the same way the server decides it -- whether :provider is there -- so
  a client does not need a second rule for a distinction the file already makes.

  `:origin` is what lets the page say 'yours', 'built-in', or 'your patch of a
  built-in' rather than showing three different things identically.

  READ-ONLY: no file is written, nothing is registered, and no key value is read."
  []
  (let [raw  (config)
        user (:providers raw)]
    {:providers (->> (catalog)
                     (map (fn [[n entry]]
                            (cond-> {:name       (name n)
                                     :origin     (origin-of user n)
                                     :protocol   (name (:protocol entry))
                                     :base-url   (:base-url entry)
                                     :model      (:model entry)
                                     :models     (mapv (fn [[id m]] (model-row id m))
                                                       (sort-by (comp str key) (:models entry)))
                                     :credential (credential-name n)
                                     :key        (api-key-source n)}
                              (some? (:display-name entry))
                              (assoc :display-name (:display-name entry)))))
                     (sort-by :name)
                     vec)
     :protocols (protocol-names)
     :reasoning-efforts reasoning-efforts
     :default   (wire (:default raw))}))

;; ------------------------------------------------------------------ the writer
;;
;; Three rules, and every one of them is a promise the form makes to a person:
;;
;;   VALIDATE THE WHOLE PROSPECTIVE FILE FIRST   a change that cannot be served is
;;                                               not a change, and the check is the
;;                                               reader's own (`user-catalog`).
;;   WRITE ATOMICALLY, KEEP ONE BACKUP          a half-written config.edn is a home
;;                                               that cannot start, and the previous
;;                                               version is one file away.
;;   TOUCH NOTHING ELSE                          :default and any future section
;;                                               survive a provider edit untouched.

(def ^:private provider-id-pattern
  "The id rule the FORM enforces -- and only the form. A hand-written config.edn may
  name a provider however a keyword can be spelled (see `credential-name`: the
  derivation is total, so it still resolves a key); what this rule adds is ONE
  SPELLING PER PROVIDER for the ids a person types into a form, because the id
  derives the credential name and `:a-b` / `:a_b` would otherwise share one key."
  #"^[a-z][a-z0-9-]*$")

(defn- check-new-id!
  "An id the form is CREATING -> a keyword, or a named failure."
  [id]
  (let [s (some-> id str/trim)]
    (when-not (and (string? s) (seq s) (re-matches provider-id-pattern s))
      (fail (str "a provider id must start with a lowercase letter and hold only"
                 " lowercase letters, digits and dashes (" (pr-str (or id "nothing"))
                 " does not): it identifies this vendor in requests AND derives the"
                 " credential name in .env, so one spelling per provider is what keeps"
                 " the two in step")
            {:id id}))
    (keyword s)))

(defn- existing-key
  "The key CONFIG.EDN already uses for this id, or nil. Looked up by NAME rather
  than by keyword so a file that spells its keys as strings is still recognized: an
  update must not rename an entry that is already there."
  [user id]
  (let [want (str/trim (str id))]
    (some (fn [[n _]] (when (= want (name n)) n)) user)))

(defn- kw-keys
  "M with string keys turned into keywords, one level deep.

  JSON hands over strings and EDN hands over keywords, and this is the same
  canonicalization `->kw` performs for a provider NAME -- for the same reason: two
  spellings of one entry would otherwise fail validation against each other, and the
  failure would name the very keys the caller wrote ('carries [\"base-url\"], which
  it does not understand; it knows [:base-url]') which reads as nonsense."
  [m]
  (if (map? m)
    (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v])) m)
    m))

(defn entry-from-wire
  "The form's JSON provider entry -> a config.edn :providers entry, or a named
  failure.

  THE INVERSE OF `wire` FOR ONE ENTRY, and it converts only what has to be
  converted: the protocol comes as a name and must be one this harness implements
  (`implemented-protocols`), a model row's modalities arrive as vectors and are left for
  `check-model` to convert and validate -- handing them over unchanged is what keeps
  ONE validator rather than a second, weaker copy here. Keys may arrive as strings
  (JSON) or keywords (EDN): see `kw-keys`.

  What it does check is the SHAPE OF THE LIST, because a list is not a table: every
  row names an :id, and no id appears twice (a duplicate would silently keep the last
  row, which is a form mistake reported as success)."
  [entry]
  (let [m (kw-keys entry)]
    (when-not (map? m)
      (fail (str "a provider entry must be a map of fields, not " (pr-str (type entry))) {}))
    (unknown-keys! "the provider the form submitted" m
                   #{:display-name :protocol :base-url :model :models})
    (let [p (:protocol m)
          proto (cond
                  (nil? p)
                  (fail (str "the provider the form submitted names no :protocol; it speaks"
                             " one of " (pr-str (protocol-names)))
                        {:protocol nil})
                  (contains? (implemented-protocols) (keyword p)) (keyword p)
                  :else
                  (fail (str "the provider the form submitted speaks " (pr-str p)
                             ", which this harness has no implementation for; it speaks "
                             (pr-str (protocol-names)))
                        {:protocol p}))
          rows  (:models m)]
      (when-not (sequential? rows)
        (fail (str "the provider the form submitted has :models " (pr-str rows)
                   "; it is a list of rows, each with an :id and its modalities")
              {:models rows}))
      (let [models (mapv (fn [row]
                           (let [row (kw-keys row)]
                             (when-not (map? row)
                               (fail (str "a model row is " (pr-str row) ", not a map")
                                     {:row row}))
                             (let [id (:id row)]
                               (when-not (and (string? id) (seq (str/trim id)))
                                 (fail (str "a model row names no :id: " (pr-str row))
                                       {:row row}))
                               [(str/trim id) (dissoc row :id)])))
                         rows)
            dupes  (->> (map first models) frequencies
                        (keep (fn [[id n]] (when (> n 1) id)))
                        sortable)]
        (when (seq dupes)
          (fail (str "the form listed " (pr-str dupes) " more than once; a model id is"
                     " one row, and a repeated one would silently be the last")
                {:duplicates dupes}))
        (cond-> {:protocol proto
                 :base-url (:base-url m)
                 :model    (:model m)
                 :models   (into {} models)}
          (contains? m :display-name) (assoc :display-name (:display-name m)))))))

(def ^:private written-header
  "The first lines of a config.edn this process WROTE. It is here because the write
  is a rewrite: EDN has no way to keep a person's comments through one, and no
  comment-preserving writer is worth a dependency for a file this small. So the file
  says so, and the version it replaced is beside it.

  WORDED FOR EVERY PREVIOUS FILE, INCLUDING AN EMPTY ONE: `touch config.edn` writes
  this same header, and a sentence that presumed comments had been lost would have
  the file telling its reader about something that never happened."
  (str ";; WRITTEN BY THE SETTINGS FORM. This file is rewritten whole, so comments do not\n"
       ";; survive a write from the form. What it held before this write is beside it as\n"
       ";; config.edn.bak, when there was anything to keep. Editing by hand is still fine\n"
       ";; -- the form reads this file back -- and the next write will reorder it again.\n\n"))

(def ^:private skeleton
  "What a home that has never been configured gets, the first time a process starts
  in it: a few lines saying which file this is and where the rest is written down,
  and an empty map -- which is how this shape says 'says nothing'.

  A COMMENTED LITTLE, NOT A TEMPLATE: the full explanation is documentation and
  belongs in docs/ (see docs/architecture/providers.md); what a person needs at the
  moment they open this file is which sections exist and that leaving them empty is a
  working state. A write from the settings form replaces these comments with its own
  header, which the file then says out loud."
  (str ";; The two sections of this file, both of which the settings panel writes:\n"
       ";;   :default    the three knobs a session starts from\n"
       ";;   :providers  the vendors, {name entry}\n"
       ";; Empty is a working state: the built-in vendors still stand, and a run with no\n"
       ";; default tier says which shape to write. See docs/architecture/providers.md.\n"
       "\n{}\n"))

(defn ensure-config!
  "Give this home a config.edn if it has none, and leave one that exists ALONE.

  CALLED BY THE COMPOSITION ROOT AT BOOT (`harness.edge.http/start!`), not by the
  reader: `home/config` reads a missing file as an empty one and creates nothing, so a
  read-only caller leaves the home as it found it. A process that is going to SERVE
  from a home is the one that should hand a person a file to edit -- and doing it here
  rather than in `infra` keeps the shape in the namespace the shape belongs to.

  Returns the file, and whether it had to create it."
  []
  (let [f (home/config-file)]
    (if (.exists f)
      {:file f :created? false}
      (do (when-let [dir (.getParentFile f)] (.mkdirs dir))
          (spit f skeleton :encoding "UTF-8")
          {:file f :created? true}))))

(defn- write-config!
  "M -> config.edn, written atomically, with ONE GENERATION of backup.

  The backup is taken only when the file exists and its text actually changes, so a
  no-op write does not destroy the last interesting version."
  [m]
  (let [f    (home/config-file)
        old  (when (.exists f) (slurp f :encoding "UTF-8"))
        text (str written-header (with-out-str (pprint/pprint m)))]
    ;; A BLANK PREVIOUS FILE IS NOT A BACKUP: it holds nothing a person could want
    ;; back, and a zero-byte config.edn.bak would suggest otherwise.
    (when (and old (not (str/blank? old)) (not= old text))
      (spit (home/config-backup-file) old :encoding "UTF-8"))
    (home/spit-atomically! f text)))

(defn- legacy-top-level?
  "Is RAW the shape config.edn had BEFORE it was sectioned -- the three knobs, or a
  whole provider described inline, written at the top level?

  Recognized by ABSENCE PLUS ONE LEGACY KEY: a file that names neither :default nor
  :providers is a file from before those existed, and it has to look like a
  configuration (a knob, an endpoint, a model) rather than a map of something else. A
  file that names one of the sections and something else is a DIFFERENT case -- a typo
  in a section name -- and stays the named failure it already is."
  [raw]
  (and (map? raw)
       (seq raw)
       (not (contains? raw :default))
       (not (contains? raw :providers))
       (some #(contains? raw %) #{:provider :model :reasoning-effort :protocol :base-url})))

(defn migrate-config!
  "Bring an older config.edn forward, ONCE, and leave every other file alone.

  THE FILE THIS MOVES AWAY FROM IS ONE SOMEBODY WAS USING. `config.edn` used to BE the
  default tier -- the knobs at the top level, or a whole provider described inline --
  and the sectioned shape refused that outright. For a home that already existed,
  'refused outright' meant a working configuration stopped working with no path back:
  the first real home this met ended up an EMPTY file, its owner chasing one failure
  into the next.

  So the boot upgrades it instead: the whole old top level becomes the :default
  section, and the result is checked with the reader's own shape check (`check-config`)
  before anything is written -- a file that was broken for some other reason is left
  exactly as it was, for the reader's sentence to explain. `write-config!` leaves the
  previous contents in config.edn.bak on the way past.

  A file that already has its sections is not touched; neither is a missing or empty
  one (`ensure-config!` handles those). Returns {:migrated? .. :file ..}."
  []
  (let [f (home/config-file)]
    (if-not (.exists f)
      {:file f :migrated? false}
      (let [raw (edn/read-string (slurp f :encoding "UTF-8"))]
        (if-not (legacy-top-level? raw)
          {:file f :migrated? false}
          (try
            (let [forwarded (check-config {:default raw} (.getAbsolutePath f))]
              (write-config! forwarded)
              {:file f :migrated? true})
            (catch Throwable _ {:file f :migrated? false})))))))

(defn- change-providers!
  "CHANGE -- a function of the parsed config map -> the config to write -- validated
  as a whole before anything is written. Returns the new config map."
  [change]
  (let [raw  (config)
        next (change raw)]
    (user-catalog (:providers next))
    (write-config! next)
    next))

(defn put-provider!
  "ID + ENTRY (the form's shape, see `entry-from-wire`) + optional API-KEY -> the
  catalog entry that is now in config.edn's :providers.

  CREATE OR REPLACE, one route for both: the form knows which it is doing (its id
  field is read-only when it is editing), and the file does not care -- an entry is
  an entry.

  THE ID RULE APPLIES TO A NEW ID ONLY. An entry already in the file keeps the
  spelling the file gave it, so an update never renames anything: the id is the
  entry's identity (and its credential name), and 'edit' does not mean 'rename'.

  THE KEY, when given, is written as its own line in .env under the credential name
  the id derives -- AFTER the config write has succeeded, so a rejected entry cannot
  leave a key behind for a provider that does not exist. Validated first for the one
  thing .env cannot hold: a newline.

  VALIDATION IS THE WHOLE FILE'S: a patch of a built-in is checked against what the
  built-in provides, and an entry this run will not use is checked too.

  THE ENTRY IS STORED AS `check-provider` NORMALIZED IT, not as the wire handed it
  over. That is not tidiness: `entry-from-wire` accepts a row's modalities as the
  strings JSON has (`[\"text\"]`), and the catalog speaks keywords in sets
  (`#{:text}`) -- so writing the raw row would put a shape in the file that the next
  read normalizes differently from what was validated, and the modality guard (which
  compares against the catalog's spelling) would read a set of strings as a model
  that declares nothing."
  [id entry key]
  (let [raw      (config)
        user     (:providers raw)
        id       (or (existing-key user id) (check-new-id! id))
        validated (check-provider id (entry-from-wire entry))]
    (when (some? key)
      (when (re-find #"[\r\n]" (str key))
        (fail "an api-key cannot span lines" {:id (name id)})))
    (let [next (change-providers! (fn [cfg] (assoc-in cfg [:providers id] validated)))]
      (when (some? key)
        (home/write-env-line! (credential-name id) key))
      (get (user-catalog (:providers next)) id))))

(defn remove-provider!
  "ID -> {:name .. :remaining ..}, after config.edn is written without that entry.

  ONLY WHAT THE FILE HOLDS CAN BE REMOVED, and that is said rather than done
  silently: a built-in provider is not in config.edn, so there is nothing to remove
  -- what a person CAN remove is their PATCH of it, and the sentence says so instead
  of answering 'removed' while the provider is still there.

  Removing a patch restores the built-in, because the merge simply stops happening.
  Removing the last entry leaves `:providers {}`, which is the same catalog the
  built-in table alone gives.

  THE DEFAULT TIER IS NOT TOUCHED, AND THAT IS WHY THIS REFUSES WHEN IT NAMES THIS
  PROVIDER. Clearing the reference as a side effect would be this function writing
  a section it was not asked about; leaving it would turn one click into a home
  whose every run fails to resolve. So the refusal names the other step (change the
  default tier first), and the panel that shows the failure is the same panel that
  can take it."
  [id]
  (let [raw  (config)
        user (:providers raw)
        k    (existing-key user id)]
    (when (nil? k)
      (fail (str "no provider named " (pr-str id) " is in config.edn's :providers, so"
                 " there is nothing to remove; a built-in vendor is the built-in"
                 " table's, and only a patch of one is yours to take back")
            {:id id :known (sortable (map name (keys user)))}))
    (when (= k (:provider (:default raw)))
      (fail (str "config.edn's :default names " (pr-str (name k)) ", so removing it"
                 " would leave every new session unable to resolve. Point the default"
                 " tier at another provider first (General), then remove this one.")
            {:id id :default true}))
    (let [next (change-providers! (fn [cfg] (update cfg :providers dissoc k)))]
      {:name      (name k)
       :remaining (count (:providers next))})))

;; ------------------------------------------------- asking a vendor what it serves

(defn listed-models
  "A provider's own 2xx body -> the model ids it lists, in the order it listed them.

  THE LAYER BELOW `*list-models*`, and a function of the body on purpose. The probe's
  outbound call is stubbed in tests, and while the parse lived inside that same
  function the stub replaced the parse too -- which is how a body read with STRING
  keys and looked up with a KEYWORD key shipped: `(:data parsed)` was nil for every
  provider, so every 2xx answer came back empty, a provider listing thirty models
  included. A stub cannot be wrong, so the seam could not see it.

  BASE IS CARRIED ONLY SO A REFUSAL CAN NAME THE ADDRESS it came from: a person with
  a half-filled form holds several endpoints, and 'answered something that is not
  JSON' without one sends them to all of them. Both arguments are data -- no request
  is made here, the body is the only thing read, and nothing comes back but ids.

  THE SHAPE IS THE PROVIDER'S, WHICH MEANS STRING KEYS: `json/read-str`'s default
  `:key-fn` is `identity`, so `{\"data\": …}` arrives as `{\"data\" …}`.

  AND 'IT LISTED NOTHING' IS AN ANSWER, NOT AN ERROR: an absent `data`, one that is
  not an array, rows that are not maps, ids that are not strings -- each is an empty
  list, because a provider with nothing to offer is an ordinary provider. Only a body
  that is not JSON at all is a refusal, and it is a different sentence from the
  vendor's own 4xx: this one says the answer could not be READ, not that it said no."
  [base body]
  (let [parsed (try (json/read-str body)
                    (catch Throwable _
                      (fail (str "the vendor at " base " answered something that is not JSON")
                            {:base-url base})))]
    ;; `vector?` rather than `coll?`: `data` as a JSON OBJECT decodes to a map, and a
    ;; map walked as a sequence yields its ENTRIES -- which would answer ["id" "gpt-x"]
    ;; for a body that listed no array at all.
    (if (vector? (get parsed "data"))
      (->> (get parsed "data")
           (keep (fn [row]
                   (when (map? row)
                     (let [id (get row "id")]
                       (when (string? id) id)))))
           vec)
      [])))

(defn- openai-models
  "GET <base-url>/models with the key as a bearer token -> the ids it lists.

  THIS IS THE REQUEST, NOT THE PARSE: the body is handed to `listed-models`, which is
  a function of the body alone and therefore the thing a test can feed. The two were
  one function, and a stub at `*list-models*` replaced both -- see `listed-models`.

  The OpenAI-compatible listing shape (`{\"data\": [{\"id\": …}, …]}`), which is
  what the one protocol this harness implements speaks. ONE PROTOCOL TODAY, so one
  implementation; a second vendor shape is a second function and a dispatch on
  `:protocol`, exactly as `llm/stream!` is dispatched -- not a special case bolted
  into this one.

  A TIMEOUT OF ITS OWN: a form waiting on a vendor that never answers must not hold
  a request thread open, and the sentence says which half failed (the vendor was
  reached and refused, versus nobody answered) because those send a person to
  different places."
  [provider]
  (let [base  (str/replace (str (:base-url provider)) #"/+$" "")
        req   (-> (HttpRequest/newBuilder (URI/create (str base "/models")))
                  (.header "Authorization" (str "Bearer " (:api-key provider)))
                  (.header "Accept" "application/json")
                  (.timeout (java.time.Duration/ofSeconds 15))
                  (.GET)
                  (.build))
        resp  (try
                (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))
                (catch java.net.http.HttpTimeoutException _
                  (fail (str "the vendor at " base " did not answer within 15 seconds")
                        {:base-url base :timeout true}))
                (catch java.io.IOException e
                  ;; NAMING THE ADDRESS IT COULD NOT REACH, because a person with a
                  ;; half-filled form has several of them and the JVM's own message
                  ;; ("Remote host terminated the handshake") does not say which.
                  (fail (str "could not reach " base " — "
                             (or (ex-message e) "the connection failed"))
                        {:base-url base :unreachable true})))]
    (if (<= 200 (.statusCode resp) 299)
      (listed-models base (.body resp))
      ;; THE VENDOR'S OWN WORDS, trimmed: a 401 that says "invalid api key" is worth
      ;; more to a person than this harness' paraphrase of it, and the status is
      ;; carried so a form can tell 'wrong key' from 'wrong address'.
      (fail (str "the vendor at " base " answered " (.statusCode resp)
                 (let [b (str/trim (str (.body resp)))]
                   (if (seq b) (str ": " b) "")))
            {:base-url base :status (.statusCode resp)}))))

(def ^:dynamic *list-models*
  "The vendor probe, as a seam: a function of one RESOLVED provider -> a vector of
  model ids.

  TESTS ALTER-VAR-ROOT THIS to a stub, because the real one makes an outbound HTTP
  request and no test run may depend on a vendor answering. Production leaves it at
  the real implementation -- a var holding the default, exactly like
  `harness.edge.http/*directory-chooser*` and `harness.infra.home/*root-override*`:
  alter-var-root rather than binding, because the server runs on another thread.

  ONE SHAPE TODAY: `openai-models` speaks the listing shape of the one protocol
  `implemented-protocols` contains. When a second vendor needs a different shape, this becomes a
  multimethod dispatched on `:protocol` -- the seam stays a seam, the dispatch
  arrives underneath it.

  AND THE PARSE IS NOT BEHIND THIS SEAM ANY MORE: a stub here replaces the REQUEST,
  while `listed-models` -- the body -> ids half -- stays real and is fed directly.
  Stubbing this var used to replace both, which is what let a nil `:data` lookup pass
  for a vendor that lists nothing."
  (fn [provider] (openai-models provider)))

(defn probe-models
  "What a vendor serves, asked of the vendor itself: {:models [id …]}, or a named
  failure carrying the vendor's own answer.

  ENDPOINT AND PROTOCOL COME FROM THE CALLER, or from the catalog when the caller
  names an :id it already holds -- a form editing an unsaved vendor has them in
  hand, and a form asking about a saved one should not have to repeat them.

  THE KEY IS RESOLVED THE WAY A RUN RESOLVES IT -- the provider's derived credential
  name, then the global one -- unless the caller has one in hand, which is the case
  that matters for a form: somebody typing a key into a field means to try THAT key
  before it is written anywhere.

  THIS IS THE SECOND PLACE A KEY IS EVER ATTACHED, and it is written down rather
  than left to be noticed: `resolve-provider` attaches one to a resolution, and this
  attaches one to an outbound request, both through the same private `api-key`.
  Nothing here returns a key, logs one, or keeps one past the call.

  AND IT IS THE ONE CALL IN THIS FEATURE THAT LEAVES THE MACHINE -- which is the
  point: an id typed from memory is the mistake the built-in table's own comment
  records having made once already. It reads nothing it does not need and writes
  nothing anywhere: no file, no store, no log line."
  [{:keys [id base-url protocol] :as asked}]
  (let [k     (when (some? id) (keyword (str/trim (str id))))
        saved (when (some? k) (get (catalog) k))
        url   (or base-url (:base-url saved))
        ;; NOT destructured as `api-key`: that name is the private lookup this
        ;; falls back to, and a local of the same name would shadow it -- which
        ;; fails as a null dereference inside an HTTP call rather than as anything
        ;; readable.
        proto (or (some-> protocol keyword) (:protocol saved))]
    (when-not (and (string? url) (seq url))
      (fail (str "no endpoint to ask: give an :id this home's catalog knows, or a"
                 " :base-url to ask directly")
            {:id id}))
    (when-not (contains? (implemented-protocols) proto)
      (fail (str "cannot ask a " (pr-str proto) " vendor: this harness speaks "
                 (pr-str (protocol-names)))
            {:protocol proto}))
    {:models (*list-models* {:protocol proto
                             :base-url url
                             :api-key  (or (:api-key asked)
                                           (when (some? k) (api-key k)))})
     :asked    url}))

(defn put-defaults!
  "KNOBS (a map over the three knobs) -> the default tier now in config.edn's
  :default, after validating it by RESOLVING it.

  ABSENT MEANS 'DO NOT TOUCH THAT KNOB'; AN EXPLICIT nil MEANS 'REMOVE THE KEY'.
  The difference is the same one `POST /api/model` draws for a session, pushed one
  tier down: 'leave my model alone' and 'stop choosing a model' are different
  requests, and the second one has a meaning -- the provider's own default model,
  or no reasoning effort sent at all.

  NAMING A PROVIDER REPLACES THE TIER rather than patching it, and that is the one
  way out of an INLINE description: the three controls cannot express a described
  endpoint, so choosing a vendor is a statement that the tier is now a named one.
  It also makes 'switch vendor' the same one-knob move it is at every other tier --
  a model the caller did not restate is dropped rather than carried onto a vendor
  that may not declare it.

  VALIDATED BY RESOLVING, which is `POST /api/model`'s stance one tier down: a
  default nobody can be served from is not a change, and writing it first would
  leave config.edn holding a configuration every NEW session fails on.

  Writes config.edn atomically with a backup, touches no other section, and returns
  the section as written."
  [knobs]
  (unknown-keys! "the default tier the form submitted" knobs #{:provider :model :reasoning-effort})
  (doseq [k [:provider :model :reasoning-effort]]
    (let [v (get knobs k)]
      (when (and (contains? knobs k) (some? v) (not (string? v)) (not (keyword? v)) (not (symbol? v)))
        (fail (str "the default tier's " (pr-str k) " is " (pr-str v)
                   ", which is not a name; a knob is a name or nothing at all")
              {k v}))))
  (let [raw     (config)
        before  (or (:default raw) {})
        named?  (some? (:provider knobs))
        cleared (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) m)) before knobs)
        given   (into {} (remove (comp nil? val)) (select-keys knobs [:provider :model :reasoning-effort]))
        proposed (if named?
                   (fold-selection given)
                   (merge cleared given))]
    ;; Resolving is the check: an unknown provider or an undeclared model fails here,
    ;; by name, with nothing written.
    (when (seq proposed)
      (fold-and-assemble proposed))
    (write-config! (assoc raw :default proposed))
    proposed))

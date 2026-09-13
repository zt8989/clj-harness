(ns harness.opaque
  "The non-introspectable counterpart of harness.memory: everything that carries
  a secret or could carry one. The api-key is resolved from .env/environment
  here and never surfaces through a memory-surface return value; the raw
  provider override may carry a key (an offline scripted provider need not, but
  nothing forbids one), so it lives here too. Vars are private wherever the
  language allows -- eval is not invited to this namespace.

  This is also where the effective provider is ASSEMBLED, because that is the
  one place the api-key is legitimate. The resolution has four levels, each
  overriding the one before it, field by field (see resolve-provider).

  Deliberately does NOT require harness.memory: memory's active-provider asks
  THIS namespace what the provider is, so a dependency the other way would be a
  cycle. The config.edn read below goes straight to harness.home instead --
  memory/config remains the introspectable accessor for everyone else."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [harness.home :as home]))

;; ---------------------------------------------------------------- overrides
;;
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
  "THREAD-ID's scripted pin, or nil. Part of the non-introspectable surface: a
  pin may carry a key, though a scripted one does not."
  [thread-id]
  (get @scripted-pins thread-id))

(defn override-for
  "THREAD-ID's session configuration override -- a PARTIAL {field value} -- or
  nil. This is tier 3 of resolve-provider: the session's own change, not a test
  seam."
  [thread-id]
  (get @session-overrides thread-id))

(defn set-override!
  "Replace THREAD-ID's session override with OV (a partial {field value} map), or
  drop it entirely when OV is nil. Separate from use-provider! because a session
  override is PARTIAL -- naming only what changes -- while a pin is a whole
  provider."
  [thread-id ov]
  (if (nil? ov)
    (swap! session-overrides dissoc thread-id)
    (swap! session-overrides assoc thread-id ov)))

;; ---------------------------------------------------------------- the files

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
  so editing it takes effect without a restart."
  []
  (let [f (home/dotenv-file)
        from-file (when (.exists f) (get (parse-dotenv (slurp f :encoding "UTF-8"))
                                         "HARNESS_API_KEY"))]
    (or from-file (System/getenv "HARNESS_API_KEY"))))

(def ^:private fields
  "The four fields a provider is described by. Nothing else is inherited, so a
  partial provider can never silently pick up a stray key from another level."
  [:protocol :base-url :model :reasoning-effort])

(defn- config
  "config.edn, parsed. Read straight from harness.home rather than through
  harness.memory -- memory depends on this namespace for active-provider, so
  asking it back would be a cycle."
  []
  (edn/read-string (home/config)))

(defn providers
  "providers.edn -> {name provider-map}. Re-read every time, like config.edn.

  A MISSING file is an EMPTY registry, not an error: a config.edn that describes
  its provider inline (the escape hatch) needs no registry at all. The named
  failure belongs where a name is actually used -- see named."
  []
  (let [f (home/providers-file)]
    (if (.exists f)
      (edn/read-string (slurp f :encoding "UTF-8"))
      {})))

(defn- named
  "Look NAME up in the registry. A name that is not there is a hard, NAMED
  failure: falling back to nil would serve the run from nowhere and report the
  problem as a confusing downstream error instead of 'no such provider'."
  [registry name]
  (or (get registry name)
      (throw (ex-info (str "no provider named " (pr-str name) " in providers.edn"
                           "; it defines " (pr-str (vec (sort (keys registry)))))
                      {:name name :known (vec (sort (keys registry)))}))))

(defn- default-base
  "config.edn, the default tier. Three shapes are accepted, in this order:

    {:provider :cheap}       a registry name; looked up in providers.edn
    {:provider {..provider}} an inline map -- the escape hatch (try one endpoint
                             once without registering it)
    {:protocol .. :model ..} a FLAT provider, every field at the top level

  The flat form is last and needs no registry at all: a config that describes
  its provider directly is complete on its own, and is what a lone-provider
  deployment naturally writes. Only when :provider is present and names a
  missing entry does resolution fail -- and it fails NAMING the entry."
  [registry cfg]
  (let [base (cond
               (map? (:provider cfg))  (:provider cfg)
               (some? (:provider cfg)) (named registry (:provider cfg))
               :else                   (select-keys cfg fields))]
    ;; Whatever the shape, the default tier may ALSO override individual fields
    ;; at the top level. That is why config.edn stays a tier instead of
    ;; collapsing into the registry: you can tune one knob (usually
    ;; :reasoning-effort) without minting a new registry entry.
    (merge base (select-keys cfg fields))))

(defn- overlay
  "Apply a partial override: only the fields actually present win. A field left
  out falls back to the tier below, which is what lets the three knobs move
  independently."
  [base over]
  (merge base (select-keys over fields)))

(defn resolve-provider
  "The effective provider for THREAD-ID, plus where it came from.

  Four tiers, each overriding the one before it FIELD BY FIELD (a tier that
  names no value for a field leaves the one below it standing):

    1. the named entry in providers.edn          (or an inline map in config.edn)
    2. config.edn's default-tier field overrides  (:model / :reasoning-effort)
    3. this session's override                    (memory/session-configure)
    4. this run's request                         (REQUEST, from the input map)

  Returns {:provider {.. :api-key ..} :source :default|:request|:inline}, where
  the api-key is attached LAST and only here. The source is :inline when
  config.edn describes its provider directly (an inline map or a flat set of
  fields) rather than naming a registry entry -- the two are worth telling
  apart in the audit trail. A THREAD-ID of nil resolves tiers 1-2 with no
  session in play, which is what an offline tool wants.

  REQUEST must not be routed through the prompt context -- it would become a
  trailing user message and poison the provider's prefix cache."
  ([thread-id] (resolve-provider thread-id nil))
  ([thread-id request]
   (let [registry (providers)
         cfg      (config)
         inline?  (not (keyword? (:provider cfg)))
         base     (default-base registry cfg)
         with-ses (overlay base (or (override-for thread-id) {}))
         with-run (overlay with-ses (or (select-keys request fields) {}))
         source   (cond
                    (seq (select-keys request fields)) :request
                    inline?                            :inline
                    :else                              :default)]
     {:provider (assoc with-run :api-key (api-key))
      :source   source})))

(defn effective-provider
  "The provider for THREAD-ID, without a run request: tiers 1-3 plus the
  ENV-sourced api-key. Offline tools and replay use this -- they run outside a
  run, so there is no request to layer on top."
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

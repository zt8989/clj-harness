(ns harness.cap.model-data
  "TWO OUTSIDE FACTS ABOUT A MODEL, AND THE CACHE EACH ONE LIVES IN.

  A model entry in config.edn may say as little as its id. What the harness knows
  ABOUT that id does not have to be written down by a person, because two public
  facts answer most of it:

    THE DATABASE    https://models.dev/api.json -- an open database of models with
                    their modalities and their two token counts. Keyless, public,
                    one document covering every vendor it knows.
    THE VENDOR      <base-url>/models -- the listing a provider publishes about
                    itself, which is the only thing that says WHICH ids it serves.

  THEY ARE NOT THE SAME KIND OF FACT, and the difference decides when each may
  block:

    THE VENDOR'S LISTING IS A REQUIREMENT. A provider that lists no models cannot
    be served at all -- there is no id to run -- so the first time an entry needs
    it and nothing is cached, this namespace FETCHES IT AND WAITS. A person who
    wrote an endpoint and a name gets a working provider, not an error about a
    fetch they never asked for. A failure there is answered with nil, and the
    catalog decides what 'no table' means.

    THE DATABASE IS AN ENRICHMENT. Nothing needs it to run: a count it knows is
    better than silence, and silence is already an answer. So it is NEVER fetched
    on the calling thread -- a reader asks what it says NOW, gets whatever is
    cached, and an absent or aged-out cache is refreshed BEHIND the call.

  BOTH ARE CACHED ON DISK FOR SEVEN DAYS (a def, not a knob: a week-old listing is
  not what breaks a run). The cache is a MIRROR, not configuration -- written whole
  and atomically, safe to delete, and re-read when the file moves under a running
  process, because two harnesses can share one home. EACH LINE CARRIES ITS OWN
  FETCH TIME, so refreshing one provider's listing does not make every other
  provider's line look fresh.

  A FAILURE TO REFRESH IS NOT A FAILURE TO ANSWER: the cached copy keeps being
  served, the failure is logged, and the next attempt waits out a cooldown so a
  machine with no network does not retry a five-megabyte download on every read.

  NOTHING HERE KNOWS WHAT THIS HARNESS CAN CARRY. A database row's modalities come
  back as keywords (`:text`, `:image`, `:video`, `:pdf`, `:audio`) and it is the
  CATALOG that intersects them with its own vocabulary -- this namespace reports
  what the world says about a model, not what this process can deliver."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [harness.infra.home :as home]
            [harness.infra.log :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

;; ---------------------------------------------------------------- the clock

(def stale-after-ms
  "How long a cached answer is trusted, in millis -- SEVEN DAYS. The listing a
  vendor publishes and the database's rows change on the order of releases, and a
  run that goes out on a week-old window is a run that would have gone out anyway.
  A def rather than a config knob because there is no decision in it a person has
  to make: a home that wants a fresh answer deletes the cache file."
  (* 7 24 60 60 1000))

(def retry-after-ms
  "How long a FAILED refresh is remembered, in millis -- one hour. Long enough that
  a machine with no network does not retry the download on every read; short enough
  that a laptop which just came back online heals within the hour. The cached answer
  keeps being served the whole time."
  (* 60 60 1000))

(def modelsdev-url
  "The one public document this harness reads for model facts. KEYLESS on purpose:
  nothing here needs an account, so a home with no keys at all still gets counts."
  "https://models.dev/api.json")

(def ^:private modelsdev-timeout-seconds 30)

(defn- now [] (System/currentTimeMillis))

;; ------------------------------------------------------------- the network

(defn- http-get
  "URL + TIMEOUT-SECONDS -> the body as a string, or a thrown ex-info saying which
  half failed -- the same discipline `providers/openai-models` keeps, because 'the
  host was reached and refused' and 'nobody answered' send a reader to different
  places."
  [url timeout-seconds]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.header "Accept" "application/json")
                (.timeout (java.time.Duration/ofSeconds (long timeout-seconds)))
                (.GET)
                (.build))]
    (try
      (let [resp (.send (HttpClient/newHttpClient) req
                        (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))]
        (if (<= 200 (.statusCode resp) 299)
          (.body resp)
          (throw (ex-info (str url " answered " (.statusCode resp))
                          {:url url :status (.statusCode resp)}))))
      (catch java.net.http.HttpTimeoutException _
        (throw (ex-info (str url " did not answer within " timeout-seconds " seconds")
                        {:url url :timeout true})))
      (catch java.io.IOException e
        (throw (ex-info (str "could not reach " url " — "
                             (or (ex-message e) "the connection failed"))
                        {:url url :unreachable true}))))))

(def ^:dynamic *http-get*
  "The one outbound call, as a seam: URL + TIMEOUT-SECONDS -> body string. TESTS
  ALTER-VAR-ROOT THIS, because no test run may depend on a public host answering --
  the same seam `providers/*list-models*` is, for the same reason. Production leaves
  it at the real implementation."
  http-get)

;; --------------------------------------------------------------- the files

(defn- read-json-file
  "F -> the parsed document, or nil when the file is absent or unreadable. A cache
  that cannot be read is NOT a failure: it is a line that is not there yet, and the
  next refresh writes a good one."
  [f]
  (when (.exists f)
    (try
      (json/read-str (slurp f :encoding "UTF-8") :key-fn keyword)
      (catch Throwable _ nil))))

(defn- write-json-file! [f payload]
  (home/spit-atomically! f (json/write-str payload)))

(defn- hydrate-payload
  "A payload read back from the cache file -> the shape a fresh fetch produces.
  
  THE INDEX IS STORED AS PAIRS, not as a JSON object, and that is not a style
  choice: the file is read with `:key-fn keyword`, which would turn a model id into
  a keyword (`:cn:glm-5.3-flash`) -- and an id is a STRING this harness compares
  against what a vendor publishes. A pair keeps it one.
  
  THE MODALITY VECTORS come back as strings and are put back here; everything else
  is already numbers, strings and maps."
  [payload]
  (update payload :models
          (fn [stored]
            (when (sequential? stored)
              (into {}
                    (map (fn [[id facts]]
                           [id (cond-> facts
                                 (sequential? (:input facts))  (update :input #(mapv keyword %))
                                 (sequential? (:output facts)) (update :output #(mapv keyword %)))])
                         stored))))))

;; ------------------------------------------------------------ the database

(defn- modality-vector
  "A JSON modality list -> the keywords it names, lowercased and sorted. This
  DELIBERATELY does not filter: what this harness can carry is the catalog's
  vocabulary, not this namespace's."
  [xs]
  (when (sequential? xs)
    (->> xs
         (keep (fn [x] (when (string? x) (keyword (str/lower-case x)))))
         distinct
         (sort-by str)
         vec)))

(defn- positive [n] (when (and (integer? n) (pos? n)) n))

(defn- limit-of
  "One database row -> the two counts it states, or {} when it states neither. The
  document has rows carrying ZEROES, which mean 'not stated' rather than zero."
  [row]
  (let [l   (get row "limit")
        ctx (positive (get l "context"))
        out (positive (get l "output"))]
    (cond-> {}
      ctx (assoc :context-window ctx)
      out (assoc :max-output-tokens out))))

(defn- facts-of
  "One database row -> what this namespace reports about that spelling of a model:
  its two counts, the modalities it takes and gives, and the name a person reads.
  A KEY WITH NOTHING TO SAY IS ABSENT, never nil."
  [row]
  (let [m   (get row "modalities")
        n   (get row "name")
        in  (modality-vector (get m "input"))
        out (modality-vector (get m "output"))]
    (cond-> (limit-of row)
      (seq in)  (assoc :input in)
      (seq out) (assoc :output out)
      (and (string? n) (not (str/blank? n))) (assoc :name n))))

(defn- bare-id
  "A database id -> the FAMILY it is filed under: everything after its LAST slash,
  so `tencent/Hy3` and `@cf/zai-org/glm-5.3-flash` are reachable by the id a person
  would type. A COLON IS NOT A SEPARATOR HERE, and this is the one place the rule
  differs from the catalog's: the database spells model TAGS with a colon
  (`gpt-oss:120b`, `deepseek-v4-flash:0731`), so stripping one would file a tag as
  if it were a model."
  [id]
  (let [s  (str/lower-case (str id))
        at (str/last-index-of s "/")]
    (if at (subs s (inc at)) s)))

(defn- owner-of
  "A :canonical_model_id -> the vendor that owns the model, or nil. That field names
  the model's real home (`zhipuai/glm-5.3-flash`), which is how a reseller's row is
  told apart from the owner's. IT TAKES THE STRING, not a row: both the raw document
  (string keys) and this namespace's own maps reach it."
  [canon]
  (let [c (str canon)]
    (when-not (str/blank? c)
      (first (str/split c #"/" 2)))))

(defn- mode
  "The most common value in XS; a tie goes to the LARGER value. Measured on the real
  document (2026-10-03): 2567 families carry rows from several relays and 508 of
  them disagree -- OpenRouter advertises a 943717-token glm reply where the vendor's
  own row says 131072. What most relays say wins; when they are evenly split, the
  larger window is the one that does not cut a conversation short."
  [xs]
  (let [xs (remove nil? xs)]
    (when (seq xs)
      ;; SORTED RATHER THAN max-key: the key is a PAIR, and max-key compares with
      ;; `<`, which numbers satisfy and vectors do not. The last element is the
      ;; one with the most votes, and among equals the larger value.
      (key (last (sort-by (fn [[v n]] [n v]) (frequencies xs)))))))

(defn- canonical-id
  "The id a family is filed under by its own owner, when any row names one: the
  most common :canonical_model_id among ROWS. nil when the database does not say."
  [rows]
  (mode (keep #(get % "canonical_model_id") rows)))

(defn- picked-rows
  "The rows whose word counts for one family: THE OWNER'S OWN ROW when the family has
  one, else the rows agreeing with the family's canonical id, else every row.
  
  THE OWNER'S ROW IS LOOKED FOR ACROSS THE WHOLE FAMILY, not only among the rows
  that name a canonical id -- and that is the one thing this function gets wrong if
  written the obvious way. The owner's own row is usually the one WITHOUT a
  :canonical_model_id (it does not have to name itself), so restricting the search
  to the agreeing rows finds only the RESELLERS' rows and reads their numbers as the
  model's: measured 2026-10-03, that turned glm-5.3-flash's 1000000/131072 into
  OpenRouter's 1048576/943717."
  [rows]
  (let [canon      (canonical-id rows)
        owner      (owner-of canon)
        owner-rows (when owner (filter #(= owner (name (:provider %))) rows))
        agreeing   (if canon (filter #(= canon (get % "canonical_model_id")) rows) [])]
    (cond (seq owner-rows) owner-rows
          (seq agreeing)   agreeing
          :else            rows)))

(defn- spokesman
  "One row out of ROWS (the WHOLE family, not the picked rows) to read a NAME and
  MODALITIES from. THE OWNER WINS: the row filed under the provider the canonical id
  names (`zhipuai`), then the row whose id IS the canonical id, then the most plainly
  spelled id. IT HAS TO LOOK ACROSS THE WHOLE FAMILY because the owner's own row is
  usually the one WITHOUT a :canonical_model_id -- it does not have to name itself --
  so it is exactly the row `picked-rows` leaves out. Without this, a reseller renames
  the model: `TEE/glm-5.3-flash` calls it \"GLM 5.3 Flash TEE\" and its id sorts first."
  [rows canon]
  (let [owner  (owner-of canon)
        by-own (when owner (first (filter #(= owner (name (:provider %))) rows)))
        by-id  (when canon (first (filter #(= canon (str/lower-case (:mid %))) rows)))]
    (or by-own by-id
        (first (sort-by (fn [r] [(count (str (:id r))) (str (:id r))]) rows)))))

(defn index-of
  "A parsed api.json -> the lookup this harness reads: {key FACTS}, keyed BOTH by
  each row's FULL id (lowercased) and by its FAMILY name. The family key is what
  makes a relay's spelling reachable -- a catalog id `cn:glm-5.3-flash` is looked
  up by `glm-5.3-flash` -- and the full-id key keeps a namespaced row's own answer
  available when a catalog id spells it exactly.

  THE FAMILY ANSWER WINS A COLLISION, deliberately: `deepseek-v4.1-flash` may exist
  as a full id under a relay AND as a family across every provider that resells it,
  and 'what is this model' is the family question. A family is resolved once, from
  all its rows (see picked-rows and mode)."
  [parsed]
  (let [rows (for [[provider entry] (sort-by (comp str key) parsed)
                   [mid row]       (get entry "models")]
               (assoc row :provider provider :mid (str mid)))
        full (into {} (map (fn [row] [(str/lower-case (:mid row)) (facts-of row)])) rows)
        fam  (reduce (fn [acc [family rs]]
                       (let [canon  (canonical-id rs)
                             picked (picked-rows rs)
                             ;; THE COUNTS ARE THE FAMILY'S, not one row's: several
                             ;; relays may carry the same model with different ceilings,
                             ;; and the chosen rows are those whose word counts (see
                             ;; picked-rows) -- most of them wins, ties go up.
                             ctx    (mode (keep (comp :context-window limit-of) picked))
                             out    (mode (keep (comp :max-output-tokens limit-of) picked))]
                         (assoc acc family
                                (merge (facts-of (spokesman rs canon))
                                       (cond-> {}
                                         ctx (assoc :context-window ctx)
                                         ;; AN OUTPUT CEILING ABOVE THE CONTEXT IS NOT
                                         ;; HANDED OUT: the catalog refuses that pair by
                                         ;; name, and remote data must never be why a home
                                         ;; stops reading (see providers/limits).
                                         (and out ctx)        (assoc :max-output-tokens (min out ctx))
                                         (and out (nil? ctx)) (assoc :max-output-tokens out))))))
                     {}
                     (group-by (comp bare-id :mid) rows))]
    (merge full fam)))

;; ---------------------------------------------------------- the database io

(defonce ^:private db-state (atom nil))        ;; {:mtime ms :payload {..}}
(defonce ^:private db-in-flight? (atom false))
(defonce ^:private db-last-attempt (atom 0))

(defn- current-db
  "The cached payload as it stands, re-read when the FILE moved under us (another
  process refreshed it). nil when nothing has been cached yet."
  []
  (let [f     (home/modelsdev-cache-file)
        mtime (when (.exists f) (.lastModified f))]
    (if (and @db-state (= mtime (:mtime @db-state)))
      (:payload @db-state)
      (let [payload (hydrate-payload (read-json-file f))]
        (reset! db-state {:mtime mtime :payload payload})
        payload))))

(defn- refresh-db!
  "Fetch the document, index it and cache it. Throws -- the caller is a background
  thread whose job is to log it."
  []
  ;; THE DOCUMENT KEEPS ITS STRING KEYS -- the parse a `listed-models` body gets. Its
  ;; ids are DATA: forcing them through `keyword` would intern thousands of keywords
  ;; and hand back a spelling the vendor never published (`(str :a/b)` has a colon).
  (let [index   (index-of (json/read-str (*http-get* modelsdev-url modelsdev-timeout-seconds)))
        payload {:fetched-at (now) :models index}
        f       (home/modelsdev-cache-file)]
    ;; THE INDEX IS WRITTEN AS PAIRS (see hydrate-payload): a JSON object's keys come
    ;; back through `:key-fn keyword`, and a model id is not a keyword.
    (write-json-file! f (update payload :models vec))
    (reset! db-state {:mtime (.lastModified f) :payload payload})
    index))

(defn- kick-db-refresh!
  "Start a refresh behind the caller, if the cache is absent or aged out and no
  attempt is in flight or cooling down. NEVER BLOCKS."
  [payload]
  (let [at (get payload :fetched-at 0)
        t  (now)]
    (when (and (or (zero? at) (< (+ at stale-after-ms) t))
               (< (+ @db-last-attempt retry-after-ms) t)
               (compare-and-set! db-in-flight? false true))
      (reset! db-last-attempt t)
      (future
        (try
          (refresh-db!)
          (catch Throwable e
            (log/warn! :model-data/modelsdev-refresh-failed
                       {:url modelsdev-url :reason (ex-message e)}))
          (finally (reset! db-in-flight? false)))))))

(defn lookup-keys
  "MODEL-ID -> the keys to try in the index, most specific first: the id itself,
  then the family a relay's spelling hides it behind -- everything after the LAST
  of `/` or `:`, which is where a gateway writes its own namespace
  (`cn:glm-5.3-flash` is `glm-5.3-flash`)."
  [id]
  (let [s   (str/lower-case (str id))
        cut (max (or (str/last-index-of s "/") -1)
                 (or (str/last-index-of s ":") -1))]
    (if (pos? cut) [s (subs s (inc cut))] [s])))


(defn warm!
  "Start the database refresh when the cache is absent or aged out, and answer
  nothing. The CATALOG calls this: it is the process's first reader of model facts,
  and a person opening a session should not have to wait for a `describe` to happen
  before the download the whole feature depends on is even started."
  []
  (kick-db-refresh! (current-db))
  nil)
(defn describe
  "MODEL-ID -> what the database says about it, or {} when it says nothing. This
  NEVER BLOCKS: the answer is whatever is cached, and a cache that is absent or
  aged out starts a refresh behind the call."
  [id]
  (let [payload (current-db)]
    (kick-db-refresh! payload)
    (if (str/blank? (str id))
      {}
      (let [index (or (:models payload) {})]
        (or (some #(get index %) (lookup-keys id)) {})))))

;; -------------------------------------------------- the vendor's own listing

(defonce ^:private listings-state (atom nil))  ;; {:mtime ms :payload {..}}
(defonce ^:private listings-in-flight (atom #{}))
(defonce ^:private listings-last-attempt (atom {}))
(def ^:private listings-lock (Object.))


(defn forget!
  "FORGET EVERYTHING THIS PROCESS REMEMBERS about the two sources: the parsed copies
  of the cache files, which refreshes are in flight, and the retry clocks that keep a
  failed fetch from being retried for an hour.
  
  THE FILES ARE NOT TOUCHED -- a caller that wants them gone deletes them (see
  harness.infra.home's two accessors).
  
  A TEST'S DOOR, and an honest one rather than a test reaching into privates: the
  retry clock is process-wide and an hour long, so a case that stubs the network AFTER
  something else already failed a fetch would otherwise wait out somebody else's
  cooldown. A running process has no reason to call this."
  []
  (reset! db-state nil)
  (reset! db-in-flight? false)
  (reset! db-last-attempt 0)
  (reset! listings-state nil)
  (reset! listings-in-flight #{})
  (reset! listings-last-attempt {})
  nil)
(defn- current-listings
  "The cached provider listings as they stand, re-read when the file moved under us."
  []
  (let [f     (home/provider-models-cache-file)
        mtime (when (.exists f) (.lastModified f))]
    (if (and @listings-state (= mtime (:mtime @listings-state)))
      (:payload @listings-state)
      (let [payload (read-json-file f)]
        (reset! listings-state {:mtime mtime :payload payload})
        payload))))

(defn- store-listing!
  "NAME's ids into the cache, keeping every other provider's line. The lock is
  around a rare whole-file rewrite (once per provider per seven days), not around a
  read path."
  [name ids]
  (locking listings-lock
    (let [payload (current-listings)
          next    (assoc-in payload [:providers (keyword name)]
                            {:fetched-at (now) :models (vec ids)})
          f       (home/provider-models-cache-file)]
      (write-json-file! f next)
      (reset! listings-state {:mtime (.lastModified f) :payload next}))))

(defn- kick-listing-refresh!
  "Refresh NAME's line behind the caller. Deduplicated per provider and cooled down,
  so a listing that keeps failing is not fetched on every read."
  [name fetch]
  (let [t (now)]
    (when (and (< (+ (get @listings-last-attempt name 0) retry-after-ms) t)
               (not (contains? @listings-in-flight name)))
      (swap! listings-in-flight conj name)
      (swap! listings-last-attempt assoc name t)
      (future
        (try
          (store-listing! name (vec (distinct (fetch))))
          (catch Throwable e
            (log/warn! :model-data/listing-refresh-failed
                       {:provider name :reason (ex-message e)}))
          (finally (swap! listings-in-flight disj name)))))))

(defn listing
  "NAME + FETCH (a thunk answering a vector of model ids) -> the ids that provider
  serves, IN THE VENDOR'S OWN ORDER (the first one is the default model), or nil
  when nothing is known.

  THE THREE CASES ARE THE POINT:

    NOTHING CACHED      FETCH NOW AND WAIT. A provider with no table cannot serve a
                        run at all, and the person who wrote an endpoint and a name
                        should get a working one rather than a message about a fetch.
                        A failure is answered with nil -- the catalog says what that
                        means, and it is not this namespace's sentence to write.
    AGED OUT            answer with the cached line and refresh it behind the call.
    FRESH               answer with it.

  FETCH IS THE CALLER'S, and that is deliberate: what 'the vendor's listing' costs
  (which URL, which key, which protocol) is the catalog's business, and this
  namespace owns only when it may be called and where the answer is kept."
  [name fetch]
  (let [payload (current-listings)
        line    (get-in payload [:providers (keyword name)])
        at      (get line :fetched-at 0)
        t       (now)]
    (cond
      (nil? line)
      (let [ids (try (vec (distinct (fetch)))
                     (catch Throwable e
                       (log/warn! :model-data/listing-fetch-failed
                                  {:provider name :reason (ex-message e)})
                       nil))]
        (when ids (store-listing! name ids))
        ids)

      (< (+ at stale-after-ms) t)
      (do (kick-listing-refresh! name fetch)
          (:models line))

      :else
      (:models line))))

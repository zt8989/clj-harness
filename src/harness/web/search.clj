(ns harness.web.search
  "`web_search`: ONE search vendor's wire, in one place.

  WHY ONE VENDOR AND NOT A PLUGIN SYSTEM. What a search API needs from a caller is
  a request shape and a response shape, and those are a vendor's, not a protocol's
  -- so this namespace is where that vendor's two shapes live, and a second vendor
  is a second section here rather than an abstraction nobody has needed yet. The
  transport below it (harness.web) knows nothing about any of this.

  THE KEY IS NOT CONFIGURATION. It is a secret the person running this put outside
  the repository, so it comes from harness.home/env-value -- the same lookup the
  provider's api-key goes through -- and its NAME is fixed rather than configurable,
  exactly like HARNESS_API_KEY's. There is no harness.edn key for it: a knob whose
  only legal value is 'the name of the env var' is a knob that buys nothing.

  NOTHING HERE IS A SECURITY BOUNDARY. The query goes to a fixed endpoint chosen by
  whoever wrote this file, not by the model, which is why this tool is not marked
  for approval while `web_fetch` (whose destination the MODEL picks, per call) is
  also not marked: `bash` reaches the network ungated either way. A session that
  wants a gate installs one."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [harness.home :as home]
            [harness.web :as web])
  (:import [java.net URLEncoder]))

(def api-key-env
  "The environment variable (or .env entry) the search key is read from. Named here
  and spelled nowhere else, so the refusal below can name it and a reader has one
  place to look."
  "HARNESS_SEARCH_API_KEY")

(def endpoint
  "Where a search goes. A VAR rather than an inlined string for one reason: the test
  suite points it at a server it runs itself, so this feature's tests never touch
  the network."
  "https://api.search.brave.com/res/v1/web/search")

(def default-count
  "How many results a call gets when it does not say."
  5)

(def max-count
  "The most results one call may ask for. The answer is read by a model, and past a
  handful of results the rest are noise that costs context."
  20)

;; ------------------------------------------------------------------ validation

(defn- check-query!
  [query]
  (when-not (and (string? query) (not (str/blank? query)))
    (throw (ex-info (str "`query` is required: what to search for. Got " (pr-str query) ".")
                    {:argument :query :value query :reason :missing}))))

(defn- check-count!
  "COUNT as a legal result count. Returns the count to use."
  [count]
  (cond
    (nil? count) default-count
    (not (integer? count))
    (throw (ex-info (str "`count` must be a whole number of results; got " (pr-str count) ".")
                    {:argument :count :value count :reason :not-an-integer}))
    (or (< count 1) (> count max-count))
    (throw (ex-info (str "`count` must be between 1 and " max-count "; got " count ".")
                    {:argument :count :value count :reason :out-of-range}))
    :else count))

;; ------------------------------------------------------------------- the key

(defn- key-of
  "The search key, or a NAMED refusal that says where to put it.

  Raised BEFORE the request is built, so an unconfigured harness makes no network
  call at all -- a refusal that still went out to the vendor would be a leak of the
  query for an answer it already knows it cannot get."
  []
  (or (home/env-value api-key-env)
      (throw (ex-info (str "web_search is not configured: " api-key-env " is not set."
                           " Put it in the configuration home's .env ("
                           (str (home/dotenv-file)) ") or in the environment, then"
                           " call again. Nothing else needs it -- `web_fetch` still"
                           " reads a URL you already have, and a session with no key"
                           " can still search nothing.")
                      {:reason :not-configured :env api-key-env}))))

;; ------------------------------------------------------------------ the wire

(defn- request-url
  "ENDPOINT as a request URL carrying QUERY and COUNT.

  The query is encoded explicitly (and explicitly as UTF-8) rather than pasted in:
  a search for `clojure & java` has to arrive as one parameter, and this machine's
  default charset is not something to leave a byte boundary to (deps.edn's rule)."
  [query count]
  (str endpoint
       "?q=" (URLEncoder/encode (str query) "UTF-8")
       "&count=" count))

(defn- results-of
  "The vendor's body as {:results [{:title :url :description} ..] :more? boolean}.

  A VENDOR ANSWER THIS DOES NOT RECOGNISE IS A REFUSAL, never an empty list. 'The
  vendor answered in a shape I do not know' and 'there is nothing on the web about
  this' are different facts, and reporting the first as the second is a lie the
  model cannot detect -- it will simply believe the subject does not exist."
  [body]
  (let [parsed (try (json/read-str body :key-fn keyword)
                    (catch Exception e
                      (throw (ex-info (str "the search vendor answered with something that"
                                           " is not JSON (" (ex-message e) "), so there is"
                                           " nothing to read. This is a bug or an outage on"
                                           " the vendor's side, not a search with no results.")
                                      {:reason :unparseable :cause (ex-message e)}))))]
    (cond
      (map? (:web parsed))
      {:results (vec (keep (fn [r]
                             (when (map? r)
                               {:title       (:title r)
                                :url         (:url r)
                                :description (:description r)}))
                           (or (:results (:web parsed)) [])))
       :more?   (boolean (get-in parsed [:query :more_results_available]))}

      ;; A recognised answer that simply carries no results block: the vendor's way
      ;; of saying there were none.
      (map? (:query parsed)) {:results [] :more? false}

      :else
      (throw (ex-info (str "the search vendor answered in a shape this tool does not"
                           " speak: no `web` and no `query` in the response. The wire"
                           " below this tool has changed, or something between here and"
                           " the vendor answered instead of it.")
                      {:reason :unknown-shape :keys (vec (keys parsed))})))))

(defn- check-status!
  [status url]
  (when (>= (long status) 400)
    (throw (ex-info (cond
                      (= 401 status)
                      (str "the search vendor rejected the key (HTTP 401). " api-key-env
                           " is set but not accepted -- check the value in the"
                           " configuration home's .env.")

                      (= 403 status)
                      (str "the search vendor refused the request (HTTP 403). The key may"
                           " not be allowed to search, or the plan may have lapsed.")

                      (= 429 status)
                      (str "the search vendor is rate-limiting this key (HTTP 429). Wait"
                           " and try again, or search less.")

                      :else
                      (str "the search vendor answered HTTP " status "."))
                    {:url url :status status :reason :search-http-error}))))

;; ------------------------------------------------------------------ the answer

(defn- render
  [query {:keys [results more?]}]
  (if (empty? results)
    (str "no results for " (pr-str query) ".")
    (str (count results) " result" (when (not= 1 (count results)) "s")
         " for " (pr-str query) ":\n"
         (str/join "\n"
                   (map-indexed
                    (fn [i {:keys [title url description]}]
                      (str (inc i) ". " (if (str/blank? (str title)) "(untitled)" title)
                           "\n   " (or url "")
                           (when-not (str/blank? (str description))
                             (str "\n   " (str/trim (str/replace (str description)
                                                                 #"\s+" " "))))))
                    results))
         (when more?
           (str "\n\nmore results are available -- narrow the query, or raise `count`"
                " (at most " max-count ").")))))

(defn perform
  "Run one `web_search` for ARGS ({:query, :count?}) and answer with the results as
  text, or throw a named refusal.

  Nothing is fetched when the key is missing (see `key-of`), and a status the vendor
  refused is a refusal by number rather than an empty result page."
  [{:keys [query count]}]
  (check-query! query)
  (let [n       (check-count! count)
        key-val (key-of)
        url     (request-url query n)
        {:keys [status text]} (web/get-text url {"Accept"              "application/json"
                                                 "X-Subscription-Token" key-val})]
    (check-status! status url)
    (render query (results-of text))))

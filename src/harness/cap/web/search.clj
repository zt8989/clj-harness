(ns harness.cap.web.search
  "`web_search`: THREE search vendors' wires, and the rule that picks one.

  THE VENDOR IS CHOSEN BY WHICH KEY IS PRESENT -- the first of Brave, Exa, Tavily whose
  key the home has. There is no knob for it, deliberately: a session that wants a
  different vendor already has the way to say so (which key is set), and a knob whose
  only job is to disagree with the answer to \"which key is set\" would be a second
  source of truth about one fact. The ORDER is the only policy, and it lives in
  `vendors` below, in one place, where the refusal can read it out loud.

  EACH VENDOR IS ITS OWN PAIR OF SHAPES -- a request and a parse -- because that is
  what a vendor IS. They happen to agree on the three fields the answer shows
  (title, URL, a snippet) and on nothing else: one wants the query in the URL, two
  want a JSON body, the key goes in three different headers, and the snippet is called
  `description`, `text` and `content` respectively. Normalising that difference HERE,
  once, is what lets the tool and the tests above stay vendor-blind.

  THE KEY IS NOT CONFIGURATION. Each vendor's variable has a fixed name (no
  `harness.edn` entry, no configurable env-var name) and is read through
  harness.infra.home/env-value -- the home's .env first, then the environment, the same
  lookup the provider's HARNESS_API_KEY goes through. A key is a secret somebody put
  outside the repository; where it is looked for is not a per-session preference.

  NOTHING HERE IS A SECURITY BOUNDARY. The query goes to an endpoint chosen by this
  file, not by the model, which is why this tool is not marked for approval while
  `web_fetch` -- whose destination the model picks per call -- is not marked either:
  `bash` reaches the network ungated either way. A session that wants a gate installs
  one."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [harness.infra.home :as home]
            [harness.cap.web :as web])
  (:import [java.net URLEncoder]))

(def default-count
  "How many results a call gets when it does not say."
  5)

(def max-count
  "The most results one call may ask for. The answer is read by a model, and past a
  handful of results the rest are noise that costs context."
  20)

(def snippet-chars
  "How long one result's snippet may be, in characters. `anchor_grep` bounds its answer
  in bytes and this bounds part of one: it exists because Exa's snippet is a slice of
  the PAGE rather than a summary, and a model that wants the page has `web_fetch` for
  it. Brave and Tavily send real snippets and are under this anyway."
  600)

;; --------------------------------------------------------------- what a vendor is

(defn- url-encode [^String s] (URLEncoder/encode (str s) "UTF-8"))

(defn- clip-snippet
  "SNIPPET as at most `snippet-chars` characters, with the whitespace runs inside it
  squeezed to single spaces -- a vendor's snippet often arrives with newlines and
  indentation from the page it was cut out of, and the answer is a list of lines."
  [snippet]
  (let [s (str/trim (str/replace (str snippet) #"\s+" " "))]
    (if (> (count s) snippet-chars)
      (str (subs s 0 (dec snippet-chars)) "\u2026")
      s)))

(defn- take-results
  "RAW as [{:title :url :description}] -- each vendor's three fields read from the
  names THAT vendor uses.

  A result that is not an object is dropped rather than refused: the answer is a list,
  and one malformed item in it is not a reason to lose the others."
  [raw snippet-key]
  (vec (keep (fn [r]
               (when (map? r)
                 {:title       (:title r)
                  :url         (:url r)
                  :description (clip-snippet (get r snippet-key ""))}))
             (or raw []))))

(defn- unknown-shape
  "The refusal for an answer this tool does not speak. 'I do not understand the vendor'
  and 'the web has nothing about this' are different facts, and reporting the first as
  the second is a lie the model cannot detect -- it will simply believe the subject
  does not exist."
  [vendor parsed]
  (ex-info (str "the search vendor (" (name vendor) ") answered in a shape this tool"
                " does not speak: the results array is not there (or is not an array)."
                " The wire below this tool has changed, or something between here and"
                " the vendor answered instead of it.")
           {:reason :unknown-shape :vendor vendor
            :keys (vec (keys (when (map? parsed) parsed)))}))

(defn- results-array
  "The shape check shared by the vendors whose `results` IS the answer: a vector, and
  nothing else, because an empty vector means 'no results' and a MISSING one means
  this is not their answer."
  [vendor parsed snippet-key]
  (if (vector? (:results parsed))
    {:results (take-results (:results parsed) snippet-key) :more? false}
    (throw (unknown-shape vendor parsed))))

;; ----------------------------------------------------------------- the three

(def endpoints
  "Where each vendor's search lives -- a VAR rather than a string inlined in the `send`
  fns below, for one reason: the test suite points these at a server it runs itself, so
  this feature's tests never touch the network.

  NOT CONFIGURATION, all the same: nothing reads them from a file. One vendor's
  endpoint is not a per-session preference, and a config key that allowed a different
  host would silently keep parsing that vendor's shape."
  {:brave  "https://api.search.brave.com/res/v1/web/search"
   :exa    "https://api.exa.ai/search"
   :tavily "https://api.tavily.com/search"})

(def vendors
  "The vendors this harness speaks, IN PREFERENCE ORDER -- the first whose key is set
  is the one that answers. Each entry is

    {:name  :brave                      ; the vendor, and what the answer names
     :env   \"BRAVE_API_KEY\"            ; where its key is looked for
     :label \"brave\"                    ; how the answer spells it
     :send  (fn [query count key] ...)  ; -> {:method :url :headers :body}
     :read  (fn [parsed] ...)}          ; -> {:results [{:title :url :description}] :more? bool}

  A FOURTH VENDOR IS A FOURTH ENTRY HERE, and that is the whole extension story: no
  registry, no plugin, no config. What a vendor is, is a request shape and a response
  shape, and both are the vendor's."

  [{:name  :brave
    :env   "BRAVE_API_KEY"
    :label "brave"
    ;; The query rides in the URL and the key in its own header; Brave is the only one
    ;; of the three that takes a GET.
    :send  (fn [query count key]
             {:method  :get
              :url     (str (:brave endpoints)
                            "?q=" (url-encode query) "&count=" count)
              :headers {"Accept"              "application/json"
                        "X-Subscription-Token" key}})
    :read  (fn [parsed]
             (cond
               ;; Brave wraps the results in `web`, and says whether it has more.
               (map? (:web parsed))
               {:results (take-results (get-in parsed [:web :results]) :description)
                :more?   (boolean (get-in parsed [:query :more_results_available]))}

               ;; A recognised answer carrying no results block: Brave's way of saying
               ;; there were none.
               (map? (:query parsed)) {:results [] :more? false}

               :else (throw (unknown-shape :brave parsed))))}

   {:name  :exa
    :env   "EXA_API_KEY"
    :label "exa"
    ;; JSON body, key in `x-api-key`. Exa returns a result's TITLE and URL without
    ;; being asked, but its page text only if `contents` asks for it -- and a result
    ;; with no snippet is a bare link, so we ask (bounded, see `snippet-chars`).
    :send  (fn [query count key]
             {:method  :post
              :url     (:exa endpoints)
              :headers {"Accept"       "application/json"
                        "Content-Type" "application/json"
                        "x-api-key"    key}
              :body    (json/write-str {:query      query
                                        :numResults count
                                        :contents   {:text {:maxCharacters snippet-chars}}})})
    :read  (fn [parsed] (results-array :exa parsed :text))}

   {:name  :tavily
    :env   "TAVILY_API_KEY"
    :label "tavily"
    ;; JSON body, key as a bearer token (Tavily's own schema says bearerAuth; the key
    ;; is NOT a body field, which is the shape an older version of their API used).
    :send  (fn [query count key]
             {:method  :post
              :url     (:tavily endpoints)
              :headers {"Accept"        "application/json"
                        "Content-Type"  "application/json"
                        "Authorization" (str "Bearer " key)}
              :body    (json/write-str {:query       query
                                        :max_results count})})
    :read  (fn [parsed] (results-array :tavily parsed :content))}])

(def key-vars
  "Every key this tool looks for, in the order it looks. The refusal and the tool's
  description both read it from here, so 'which variables' is answered once."
  (mapv :env vendors))

(defn- set-key
  "NAME's key from the home's .env or the environment -- nil when neither has one, and
  nil too when the value is blank: `EXA_API_KEY=` in a .env is somebody having not
  decided yet, not a key that will not work."
  [name]
  (let [v (home/env-value name)]
    (when-not (str/blank? (str v)) v)))

(defn chosen
  "The vendor that will answer: the first of `vendors` whose key is set, as
  {:vendor .. :key ..}. Throws a NAMED refusal naming every variable and where to put
  one when none is set."
  []
  (or (some (fn [v] (when-let [k (set-key (:env v))] {:vendor v :key k})) vendors)
      (throw (ex-info (str "web_search is not configured: none of " (str/join ", " key-vars)
                           " is set. Put ONE of them in the configuration home's .env ("
                           (str (home/dotenv-file)) ") or in the environment -- the first"
                           " one found in that order is the one used, so " (first key-vars)
                           " wins if several are set. Nothing else needs a search key:"
                           " `web_fetch` still reads a URL you already have.")
                      {:reason :not-configured :env key-vars}))))

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

(defn- check-status!
  "Refuse a status the vendor did not like, naming WHICH vendor and -- for the two
  statuses that are about the key -- the variable that was set. VENDOR is the entry
  from `vendors`, so the name is `(:name vendor)` and not `vendor`."
  [vendor status url]
  (when (>= (long status) 400)
    (let [who (name (:name vendor))]
      (throw (ex-info (cond
                        (= 401 status)
                        (str "the search vendor (" who ") rejected the key (HTTP 401). "
                             (:env vendor) " is set but not accepted -- check the value"
                             " in the configuration home's .env.")

                        (= 403 status)
                        (str "the search vendor (" who ") refused the request (HTTP 403)."
                             " The key may not be allowed to search, or the plan may have"
                             " lapsed.")

                        (= 429 status)
                        (str "the search vendor (" who ") is rate-limiting this key"
                             " (HTTP 429). Wait and try again, or search less.")

                        :else
                        (str "the search vendor (" who ") answered HTTP " status "."))
                      {:url url :status status :vendor (:name vendor)
                       :reason :search-http-error})))))

(defn- read-json-or-refuse
  "TEXT as JSON, or a named refusal -- an answer that is not JSON at all is refused for
  the same reason an unrecognised shape is, and it names the vendor either way."
  [vendor text]
  (try (json/read-str text :key-fn keyword)
       (catch Exception e
         (throw (ex-info (str "the search vendor (" (name (:name vendor)) ") answered"
                              " with something that is not JSON (" (ex-message e) "), so"
                              " there is nothing to read. That is a bug or an outage on"
                              " the vendor's side, not a search with no results.")
                         {:reason :unparseable :vendor (:name vendor)
                          :cause (ex-message e)})))))

;; ------------------------------------------------------------------ the answer

(defn- render
  [vendor query {:keys [results more?]}]
  (let [via (str " (via " (:label vendor) ")")]
    (if (empty? results)
      (str "no results for " (pr-str query) via ".")
      (str (count results) " result" (when (not= 1 (count results)) "s")
           " for " (pr-str query) via ":\n"
           (str/join "\n"
                     (map-indexed
                      (fn [i {:keys [title url description]}]
                        (str (inc i) ". " (if (str/blank? (str title)) "(untitled)" title)
                             "\n   " (or url "")
                             (when-not (str/blank? (str description))
                               (str "\n   " description))))
                      results))
           (when more?
             (str "\n\nmore results are available -- narrow the query, or raise `count`"
                  " (at most " max-count ")."))))))

(defn perform
  "Run one `web_search` for ARGS ({:query, :count?}) and answer with the results as
  text, or throw a named refusal.

  Nothing is sent when no key is set (`chosen` refuses first), and a status the vendor
  refused is a refusal by number rather than an empty result page."
  [{:keys [query count]}]
  (check-query! query)
  (let [n               (check-count! count)
        {:keys [vendor key]} (chosen)
        {:keys [method url headers body]} ((:send vendor) query n key)
        {:keys [status text]} (web/call method url {:headers headers :body body})]
    (check-status! vendor status url)
    (render vendor query ((:read vendor) (read-json-or-refuse vendor text)))))

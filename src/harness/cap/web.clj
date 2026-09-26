(ns harness.cap.web
  "Outbound HTTP: the one place this harness fetches a URL, and the lossy reading
  of what came back.

  WHY IT IS ITS OWN NAMESPACE. The transport is a property of the MACHINE, not of
  any one tool: the timeout, the redirect rule, the size bound and the way a byte
  array becomes text all have to agree between whoever fetches. Written inside a
  tool, a second fetcher would eventually be written beside it and disagree about
  one of the four. (The provider clients are deliberately NOT here: they talk a
  protocol, they are configured per session, and harness.cap.providers owns them.)

  IT KNOWS NOTHING ABOUT ANY VENDOR. `web_search` has its own namespace for the
  search wire; this one only knows how to get bytes off the network and how to
  turn them into something readable.

  THE EXTRACTION IS LOSSY AND SAYS SO. `text-of` is not a renderer: it drops
  `<script>` and `<style>` bodies, turns block-level tags into newlines, removes
  the rest of the markup and undoes the handful of entities that actually appear.
  A page built by JavaScript comes back as an empty shell, which is why
  `fetch-text` says exactly that rather than answering with nothing.

  NOTHING HERE IS A SECURITY BOUNDARY. There is no host blocklist: the harness's
  own tests fetch a server on loopback, and a list of 'looks dangerous' addresses
  would break that long before it stopped anybody. A session that wants a gate on
  outbound calls installs one (session-require-approval!, or a PreToolUse hook) --
  and note that `bash` reaches the network without any gate at all, so a rule here
  is a speed bump rather than a wall."
  (:require [clojure.string :as str]
            [org.httpkit.client :as http])
  (:import [java.net URI]
           [java.nio.charset Charset StandardCharsets]))

(def timeout-ms
  "How long one request may take, in milliseconds. The same order as a search's
  bound (harness.infra.rg): long enough for a slow page, short enough that a host which
  never answers does not hold the run."
  10000)

(def max-redirects
  "How many redirects to follow before giving up. A chain longer than this is a
  loop or a trap, and either way the answer is a named refusal rather than a
  request that never returns."
  10)

(def max-bytes
  "How much of a page one answer may carry, in bytes of text. THE SAME NUMBER as
  `grep`'s max-bytes, deliberately: it bounds the same thing (how much of a
  document goes into one tool answer), and two different budgets for one idea
  would be a difference nobody asked for and nobody would remember."
  51200)

(def ^:private redirect-statuses #{301 302 303 307 308})

;; --------------------------------------------------------------------- transport

(defn- uri-of
  "URL as a URI, or a NAMED refusal. A URL without a scheme is the mistake a model
  actually makes (`example.com/x`), so it is refused with the fix in the message
  rather than resolved against nothing."
  [url]
  (when-not (and (string? url) (not (str/blank? url)))
    (throw (ex-info "`url` is required: the full http(s) address to fetch."
                    {:argument :url :value url :reason :missing})))
  (let [uri (try (URI. (str/trim url))
                 (catch Exception e
                   (throw (ex-info (str "`url` is not a URL: " (pr-str url)
                                        " (" (ex-message e) ").")
                                   {:argument :url :value url :reason :malformed-by-uri}))))]
    (when-not (contains? #{"http" "https"} (some-> (.getScheme uri) str/lower-case))
      (throw (ex-info (str "`url` must be an http or https address, but this one's scheme"
                           " is " (pr-str (.getScheme uri)) ". Give the whole address,"
                           " including the https:// prefix.")
                      {:argument :url :value url :scheme (.getScheme uri)
                       :reason :unsupported-scheme})))
    uri))

(defn- header
  "HDRS' value for WANTED, case-insensitively -- or nil. http-kit hands headers back
  as a map, and a header name is case-insensitive by definition, so looking one up
  by an exact key would work until some server spelled it differently.

  The parameter is WANTED and not `name`, because `name` is clojure.core's and
  binding it here would make the lookup below call a STRING as a function."
  [hdrs wanted]
  (some (fn [[k v]] (when (= (str/lower-case (name k)) wanted) v)) hdrs))

(defn- one-call
  "One request -- METHOD, URL, REQUEST-HEADERS, REQUEST-BODY -- with no redirect
  following. Returns {:status :headers :body} with the body as BYTES: decoding is the
  caller's step, so the charset comes from one place (see `charset-of`) rather than
  from a client default that is neither."
  [method ^String url request-headers request-body]
  (let [{:keys [status headers body error]}
        @(http/request {:url              url
                        :method           method
                        :headers          (or request-headers {})
                        :body             request-body
                        :timeout          timeout-ms
                        :follow-redirects false
                        ;; `:byte-array` rather than `:raw-byte-array`: the former
                        ;; has http-kit undo a gzip/deflate body first, so what comes
                        ;; back is the page rather than its compressed self.
                        :as               :byte-array})]
    (when error
      ;; A TIMEOUT IS ITS OWN SENTENCE. The two failures read the same to a caller
      ;; but not to a model: 'it never answered in 150ms' is worth retrying, 'the
      ;; connection was refused' is a wrong address. Reporting the first one's
      ;; millisecond count on a refused connection -- as the first version of this
      ;; did -- reads as 'it took ten seconds', which is a different fact.
      ;;
      ;; Detected by the MESSAGE as well as the class: the client reports its
      ;; timeout as an idle-timeout exception rather than a TimeoutException, and
      ;; the sentence the model reads is worth more than a tidy `instance?`.
      (let [why        (str (or (ex-message error) error))
            timed-out? (or (instance? java.util.concurrent.TimeoutException error)
                           (boolean (re-find #"(?i)timeout" why)))]
        (throw (ex-info (if timed-out?
                          (str url " did not answer within " timeout-ms "ms, so the"
                               " request was stopped. Try again, or fetch a smaller"
                               " page from the same site.")
                          (str "could not reach " url ": " why "."))
                        {:url url
                         :reason (if timed-out? :timeout :unreachable)
                         :timeout-ms (when timed-out? timeout-ms)
                         :cause (ex-message error)}))))
    {:status status :headers headers :body body}))

(defn- fetch
  "Send METHOD + REQUEST-HEADERS + REQUEST-BODY to URL, following redirects BY HAND --
  which is what makes the answer able to say where it ended up, and what bounds the
  chain.

  A redirect is followed by resolving its Location against the URL that produced it,
  so a relative Location (`/next`, `../x`) works; only http(s) is followed, so a page
  cannot redirect the fetch into `file:`. The chain's length is capped by
  `max-redirects`, and hitting the cap is a refusal rather than a hang. The headers
  ride along on every hop: a caller that needs a key -- or a version -- needs it at
  the end of the chain too.

  307/308 MEAN 'REPEAT WHAT YOU SENT, ELSEWHERE' AND KEEP THE BODY; 301/302/303 MEAN
  'GO AND GET THAT' AND DO NOT. That is the standard, and the difference is not
  bookkeeping: preserving a POST's body across a 303 is how a query ends up at an
  address that only meant to point at the answer. (The headers -- including a key --
  stay either way, because it IS the same request aimed somewhere else.)"
  [method ^String url request-headers request-body]
  (loop [method method, url url, body request-body, hops 0]
    (let [{:keys [status headers body]} (one-call method url request-headers body)
          location (header headers "location")]
      (if (contains? redirect-statuses status)
        (do
          (when (str/blank? (str location))
            (throw (ex-info (str url " answered HTTP " status
                                 " (a redirect) without a Location header, so there is"
                                 " nowhere to go.")
                            {:url url :status status :reason :redirect-without-location})))
          (when (>= hops max-redirects)
            (throw (ex-info (str url " redirected more than " max-redirects
                                 " times; giving up rather than following it further."
                                 " This is usually a loop.")
                            {:url url :status status :hops hops
                             :reason :too-many-redirects})))
          (let [next-url  (str (.resolve (URI. url) (str/trim (str location))))
                keep-body? (contains? #{307 308} status)]
            (uri-of next-url)                    ; refuses a non-http(s) target
            (recur (if keep-body? method :get)
                   next-url
                   (when keep-body? body)
                   (inc hops))))
        {:url url :status status :headers headers :body body}))))

;; ---------------------------------------------------------------------- reading

(defn- charset-of
  "The charset to decode BODY with: the one the response DECLARED, or UTF-8.

  Never the JVM's default, which is the fault deps.edn's comment block warns about
  -- on a Chinese Windows install that is GBK, and every page would come back
  mangled without anything saying so. A declared charset this JVM does not have is
  ignored rather than fatal: the page is worth reading in UTF-8."
  [content-type]
  (or (when-let [declared (some->> content-type
                                   (re-find #"(?i)charset\s*=\s*\"?([A-Za-z0-9_.:+-]+)")
                                   second)]
        (try (Charset/forName declared) (catch Exception _ nil)))
      StandardCharsets/UTF_8))

(defn- decode
  "BODY as text, at most `max-bytes` of it. Returns [TEXT truncated?].

  THE CUT LANDS ON A CHARACTER BOUNDARY. Cutting at an arbitrary byte can split a
  multi-byte character, which decodes to a replacement character -- a stray `?` at
  the end of every truncated page. Backing off over continuation bytes (0b10xxxxxx)
  is three lines and removes it."
  [^bytes body ^Charset cs]
  (if (<= (alength body) max-bytes)
    [(String. body cs) false]
    (let [n (loop [n max-bytes]
              (if (and (pos? n) (= 0x80 (bit-and (aget body n) 0xC0)))
                (recur (dec n))
                n))]
      [(String. body 0 n cs) true])))

(def ^:private kind-rules
  "Content type prefix -> how to read the body. `:html` is extracted; `:text` is
  passed through untouched, because it is already what the model asked for.

  A response that declares NOTHING is read as text: a server that sends no
  Content-Type is almost always sending text, and refusing it would make the tool
  useless against exactly the simple endpoints a session is most likely to fetch."
  [[#{"text/html" "application/xhtml"} :html]
   [#{"text/" "application/json" "application/xml"} :text]])

(defn- kind-of
  [content-type]
  (let [t (str/lower-case (str/trim (or content-type "")))]
    (or (some (fn [[prefixes k]]
                (when (some #(str/starts-with? t %) prefixes) k))
              kind-rules)
        (when (str/blank? t) :text)
        :other)))

;; --------------------------------------------------------------- the extractor

(def ^:private dropped
  "Regions whose TEXT is not prose: `<script>` and `<style>` bodies, which a plain
  tag-stripper would leave behind as source code, and comments, which a page leaves
  in for other machines. Non-greedy and DOTALL, so a body spanning lines is one
  region rather than a partial match of two."
  #"(?is)<(script|style)\b[^>]*>.*?</\1\s*>|<!--.*?-->")

(def ^:private breaks
  "Tags that END a line of prose. Replaced by a newline, which is the one thing a
  lossy extractor has to get right: without it a whole page is a single line and
  the structure that made it readable is gone."
  #"(?is)</?(?:p|div|br|li|ul|ol|tr|table|h[1-6]|section|article|header|footer|nav|blockquote|pre|hr|dl|dt|dd)\b[^>]*>")

(def ^:private any-tag
  "Whatever markup is left. Deliberately naive: a `>` inside a quoted attribute ends
  the match early, which loses at most the tail of one attribute -- and the
  alternative is an HTML parser, which this repo has decided against (see the
  namespace docstring)."
  #"(?s)<[^>]*>")

(def ^:private entities
  "The named entities that actually turn up in prose. `nbsp` becomes an ordinary
  space on purpose: it is a space to the reader, and leaving it as U+00A0 would
  defeat the whitespace collapsing below."
  {"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'" "nbsp" " "
   "mdash" "\u2014" "ndash" "\u2013" "hellip" "\u2026"
   "lsquo" "\u2018" "rsquo" "\u2019" "ldquo" "\u201c" "rdquo" "\u201d"
   "copy" "\u00a9" "reg" "\u00ae" "times" "\u00d7" "middot" "\u00b7"})

(defn decode-entities
  "S with character references undone: the named ones in `entities`, and numeric
  ones in both spellings (`&#39;`, `&#x27;`). An entity nobody knows is left as it
  was written -- guessing would turn a typo into content."
  [^String s]
  (str/replace s #"(?i)&(#x[0-9a-f]+|#\d+|[a-z][a-z0-9]*);"
               (fn [[written kind]]
                 (cond
                   (str/starts-with? kind "#x")
                   (try (String. (Character/toChars (Integer/parseInt (subs kind 2) 16)))
                        (catch Exception _ written))

                   (str/starts-with? kind "#")
                   (try (String. (Character/toChars (Integer/parseInt (subs kind 1))))
                        (catch Exception _ written))

                   :else (get entities (str/lower-case kind) written)))))

(defn- tidy-lines
  "TEXT with each line's runs of spaces collapsed and its blank lines dropped. The
  last step of reading markup: without it every extracted page keeps the indentation
  of its source and the answer is mostly whitespace. It is NOT applied to
  text/plain, which is already what the model asked for."
  [^String text]
  (->> (str/split-lines text)
       (map #(str/trim (str/replace % #"[ \t\u00a0\f]+" " ")))
       (remove str/blank?)
       (str/join "\n")))

(defn text-of
  "HTML as readable text. PURE -- a string in, a string out, no network -- so the
  extraction rules can be tested as what they are rather than through a socket.

  LOSSY BY CONSTRUCTION, and the order matters: regions whose text is code are
  dropped FIRST (a tag-stripper would leave `<script>` bodies behind as prose),
  then block tags become newlines, then the remaining markup goes, and only THEN
  are entities decoded -- decoding earlier would turn an escaped `&lt;div&gt;` into
  something the tag-stripper would eat."
  [html]
  (-> (str html)
      (str/replace dropped "")
      (str/replace breaks "\n")
      (str/replace any-tag "")
      (decode-entities)
      (str/replace "\r\n" "\n")
      (str/replace "\r" "\n")
      (tidy-lines)))

(defn title-of
  "HTML's `<title>`, or nil. Reported because it is the fastest way for a model to
  tell which page it actually got -- the URL alone often does not say."
  [html]
  (some-> (re-find #"(?is)<title[^>]*>(.*?)</title>" (str html))
          second
          decode-entities
          (str/replace #"\s+" " ")
          str/trim
          not-empty))

;; ------------------------------------------------------------------- the answer

(defn- render
  "The whole answer: what was fetched, what it was, and its text."
  [{:keys [url original-url status content-type total cut? text title]}]
  (str "fetched " url
       " (HTTP " status ", " (if (str/blank? (str content-type)) "no content type" content-type)
       ", " total " bytes)"
       (when (and original-url (not= original-url url))
         (str "\nredirected from " original-url))
       (when cut?
         (str "\nthis is the first " max-bytes " bytes of " total
              "; the rest is not shown."))
       (when title (str "\ntitle: " title))
       "\n\n"
       (if (str/blank? text)
         (str "(no readable text: whatever this page shows is not in its markup --"
              " it may be built by JavaScript, which this does not run)")
         text)))

(defn call
  "One outbound call -- METHOD (:get or :post), URL, and REQUEST as {:headers :body} --
  answered as {:url :status :content-type :text}.

  THIS IS THE TRANSPORT WITHOUT THE READING, and it exists for a caller that already
  knows what it asked for -- an API that answers JSON -- where the page rules below
  (extract the markup, cut at `max-bytes`) would be the wrong reading of the answer.
  A status of 400 or worse is RETURNED rather than refused here: what a 401 means
  depends on what the caller was asking, so the caller says it.

  GET and POST go through one door because they share everything that decides what a
  call IS: the timeout, the hand-followed capped chain, the charset rule, the named
  transport refusals. Only the method, the body and the caller's headers differ."
  [method url request]
  (let [resp         (fetch method (str (uri-of url)) (:headers request) (:body request))
        content-type (header (:headers resp) "content-type")]
    {:url          (:url resp)
     :status       (:status resp)
     :content-type content-type
     :text         (String. ^bytes (:body resp) (charset-of content-type))}))

(defn get-text
  "GET URL with REQUEST-HEADERS and answer the whole body as text. The shape
  `harness.cap.web.search` needs for the vendor that puts its query in the URL."
  [url request-headers]
  (call :get url {:headers request-headers}))

(defn fetch-text
  "Fetch URL and answer with its text, or throw a named refusal.

  URL must be http(s); the redirect chain is followed by hand and capped; a status
  of 400 or worse is refused BY NUMBER rather than returned as a page; a body whose
  content type is not text is refused by type. Each refusal is something the model
  can act on, which is why none of them is an empty string."
  [url]
  (let [requested (str (uri-of url))
        {:keys [url status headers body]} (fetch :get requested nil nil)
        content-type (header headers "content-type")]
    (when (>= (long status) 400)
      (throw (ex-info (str url " answered HTTP " status ". Nothing was read -- an error"
                           " page is not the page you asked for.")
                      {:url url :status status :reason :http-error})))
    (let [kind (kind-of content-type)]
      (when (= :other kind)
        (throw (ex-info (str url " answered with content type " (pr-str (str content-type))
                             ", which is not text, so there is nothing to read."
                             " (This tool reads pages, not files.)")
                        {:url url :content-type content-type :reason :not-text})))
      (let [cs            (charset-of content-type)
            [decoded cut?] (decode body cs)
            html?         (= :html kind)
            text          (if html? (text-of decoded) decoded)]
        (render {:url url :original-url requested :status status
                 :content-type content-type :total (alength body) :cut? cut?
                 :text text :title (when html? (title-of decoded))})))))

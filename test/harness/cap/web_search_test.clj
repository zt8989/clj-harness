(ns harness.cap.web-search-test
  "`web_search`: three vendors' wires, the rule that picks one, and the refusals that
  keep the pick honest.

  NOTHING HERE TOUCHES THE NETWORK. `harness.cap.web.search/endpoints` is a var, and this
  suite points all three at a server it runs itself -- which is also the only way to
  assert what a REQUEST looked like (the method, the body, where the key went) and to
  answer with each vendor's shapes (an empty result set, a shape this tool does not
  speak, a 401) without three accounts.

  THE KEY IS A SENTINEL, and the cases below check all three rules of it: it is SENT
  where that vendor wants it, it decides WHICH vendor answers, and it appears NOWHERE
  the model or a log could read it -- not in an answer, and not in any refusal,
  including the 401 that a careless implementation would quote back."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.test-support :as support]
            [harness.infra.home :as home]
            [harness.kernel.tools :as tools]
            [harness.cap.web :as web]
            [harness.cap.web.search :as search]
            [org.httpkit.server :as hk]))

(def ^:private sentinel "search-key-SENTINEL-9f3a1c")

;; ------------------------------------------------------------------ the fake vendors

(def ^:private scenario
  "How the fake vendor answers next: {:status n :body \"..\"}."
  (atom {:status 200 :body ""}))

(def ^:private seen
  "Every request the fake vendor received: {:path .. :method .. :query-string ..
  :token .. :body ..}."
  (atom []))

(defn- header-of [req wanted]
  (some (fn [[k v]] (when (= (str/lower-case (name k)) wanted) v)) (:headers req)))

(defn- vendor
  [{:keys [uri request-method query-string body] :as req}]
  (swap! seen conj {:path         uri
                    :method       request-method
                    :query-string query-string
                    :body         (some-> body slurp)
                    :bearer       (header-of req "authorization")
                    :x-api-key    (header-of req "x-api-key")
                    :brave-token  (header-of req "x-subscription-token")})
  @scenario)

(def ^:private base (atom nil))

(defn- key! [& pairs]
  (io/delete-file (home/dotenv-file) true)
  (let [f (home/dotenv-file)]
    (.mkdirs (.getParentFile f))
    (spit f (str (str/join "\n" (map (fn [[name value]] (str name "=" value)) pairs)) "\n")
          :encoding "UTF-8")))

(use-fixtures :once support/with-builtins)

(use-fixtures :each
  (fn [f]
    (let [stop (hk/run-server vendor {:port 0})
          real search/endpoints]
      (reset! base (str "http://127.0.0.1:" (:local-port (meta stop))))
      (alter-var-root #'search/endpoints
                      (constantly {:brave  (str @base "/brave")
                                   :exa    (str @base "/exa")
                                   :tavily (str @base "/tavily")}))
      (reset! seen [])
      (reset! scenario {:status 200 :body ""})
      (key! ["BRAVE_API_KEY" sentinel])
      (try (f)
           (finally
             (alter-var-root #'search/endpoints (constantly real))
             (io/delete-file (home/dotenv-file) true)
             (stop))))))

;; ------------------------------------------------------------------- the answers

(def ^:private three
  [{:title "First" :url "https://a.example/1" :description-or-text "about the first"}
   {:title "Second" :url "https://b.example/2" :description-or-text "about the second"}
   {:title "Third" :url "https://c.example/3" :description-or-text "about the third"}])

(defn- brave-body [& {:keys [more?]}]
  (json/write-str {:query {:original "q" :more_results_available (boolean more?)}
                   :web   {:results (mapv #(hash-map :title (:title %)
                                                     :url (:url %)
                                                     :description (:description-or-text %))
                                          three)}}))

(defn- exa-body []
  (json/write-str {:requestId "r1"
                   :results (mapv #(hash-map :title (:title %)
                                             :url (:url %)
                                             :text (:description-or-text %))
                                  three)}))

(defn- tavily-body []
  (json/write-str {:query "q"
                   :results (mapv #(hash-map :title (:title %)
                                             :url (:url %)
                                             :content (:description-or-text %)
                                             :score 0.9)
                                  three)}))

(defn- search!
  [args]
  (tools/run! {:id "c" :type "function"
               :function {:name "web_search" :arguments (json/write-str args)}}
              "web-search-test"))

(def ^:private per-vendor
  "The three vendors as [env-var path answer-body] -- what a case that has to run
  against EACH vendor iterates over. The key matters: with only one set the others
  are never chosen, which is the selection rule working rather than a case passing."
  [["BRAVE_API_KEY" "/brave" (brave-body)]
   ["EXA_API_KEY" "/exa" (exa-body)]
   ["TAVILY_API_KEY" "/tavily" (tavily-body)]])

;; ------------------------------------------------------- which vendor answers

(deftest the-vendor-is-the-first-one-whose-key-is-set
  (reset! scenario {:status 200 :body (brave-body)})
  (testing "the default: only a Brave key"
    (search! {:query "x"})
    (is (= "/brave" (:path (last @seen))))
    (is (str/includes? (:content (search! {:query "x"})) "via brave")))
  (testing "with Brave and Tavily set, Brave is the first in the order"
    (key! ["BRAVE_API_KEY" sentinel] ["TAVILY_API_KEY" "another-key"])
    (reset! seen [])
    (search! {:query "x"})
    (is (= "/brave" (:path (last @seen))) "the FIRST key in the order wins"))
  (testing "Exa is found when Brave's key is absent"
    (key! ["EXA_API_KEY" sentinel])
    (reset! seen [])
    (reset! scenario {:status 200 :body (exa-body)})
    (let [{:keys [content error]} (search! {:query "x"})]
      (is (false? error) content)
      (is (= "/exa" (:path (last @seen))))
      (is (str/includes? content "via exa"))))
  (testing "and Tavily when it is the only one"
    (key! ["TAVILY_API_KEY" sentinel])
    (reset! seen [])
    (reset! scenario {:status 200 :body (tavily-body)})
    (let [{:keys [content error]} (search! {:query "x"})]
      (is (false? error) content)
      (is (= "/tavily" (:path (last @seen))))
      (is (str/includes? content "via tavily")))))

(deftest a-blank-key-in-the-dotenv-is-not-a-key
  ;; `EXA_API_KEY=` is somebody having not decided yet. Treating the empty string as a
  ;; configured vendor would make the tool announce a vendor it cannot use.
  (key! ["EXA_API_KEY" ""] ["TAVILY_API_KEY" sentinel])
  (reset! scenario {:status 200 :body (tavily-body)})
  (reset! seen [])
  (let [{:keys [content error]} (search! {:query "x"})]
    (is (false? error) content)
    (is (= "/tavily" (:path (last @seen))) "the blank key was passed over, not chosen")))

;; ------------------------------------------------------ each vendor's own wire

(deftest the-results-come-back-in-order-with-their-links
  (doseq [[env path body] per-vendor]
    (testing (str path " (each vendor's own three field names)")
      (key! [env sentinel])
      (reset! scenario {:status 200 :body body})
      (let [{:keys [content error]} (search! {:query "clojure agents"})]
        (is (false? error) content)
        (is (str/includes? content "3 results"))
        (is (< (.indexOf content "1. First") (.indexOf content "2. Second")))
        (is (< (.indexOf content "2. Second") (.indexOf content "3. Third")))
        (doseq [r three]
          (is (str/includes? content (:title r)) (:title r))
          (is (str/includes? content (:url r)) (:url r))
          (is (str/includes? content (:description-or-text r)) (:title r)))))))

(deftest each-vendor-gets-the-request-it-expects
  (testing "brave: GET, the query in the URL, the key in its own header"
    (reset! scenario {:status 200 :body (brave-body)})
    (reset! seen [])
    (search! {:query "clojure & java" :count 7})
    (let [{:keys [method query-string brave-token body]} (last @seen)]
      (is (= :get method))
      (is (str/includes? query-string "q=clojure"))
      (is (str/includes? query-string "%26") "an ampersand cannot start a new parameter")
      (is (str/includes? query-string "count=7"))
      (is (= sentinel brave-token) "the key is sent where this vendor wants it")
      (is (nil? body) "a GET carries no body")))
  (testing "exa: POST, JSON body, the key in x-api-key"
    (key! ["EXA_API_KEY" sentinel])
    (reset! scenario {:status 200 :body (exa-body)})
    (reset! seen [])
    (search! {:query "clojure" :count 7})
    (let [{:keys [method path body x-api-key]} (last @seen)]
      (is (= :post method))
      (is (= "/exa" path))
      (is (= sentinel x-api-key))
      (is (= 7 (:numResults (json/read-str body :key-fn keyword))))
      (is (contains? (:contents (json/read-str body :key-fn keyword)) :text)
          "Exa only sends page text if `contents` asks for it")))
  (testing "tavily: POST, JSON body, the key as a bearer token"
    (key! ["TAVILY_API_KEY" sentinel])
    (reset! scenario {:status 200 :body (tavily-body)})
    (reset! seen [])
    (search! {:query "clojure" :count 7})
    (let [{:keys [method path body bearer]} (last @seen)]
      (is (= :post method))
      (is (= "/tavily" path))
      (is (= (str "Bearer " sentinel) bearer)
          "the key is a header, not a body field -- which is the shape their old API used")
      (is (= 7 (:max_results (json/read-str body :key-fn keyword))))
      (is (not (str/includes? body sentinel)) "and it is not ALSO in the body"))))

(deftest a-search-with-no-results-says-so-rather-than-failing
  ;; Each vendor's own way of saying "none": Brave still wraps it in `web` (and keeps
  ;; its `query` block), the other two send an empty `results` array.
  (doseq [[env path empty-body]
          [["BRAVE_API_KEY" "/brave"
            (json/write-str {:query {:original "q"} :web {:results []}})]
           ["EXA_API_KEY" "/exa" (json/write-str {:results []})]
           ["TAVILY_API_KEY" "/tavily" (json/write-str {:results []})]]]
    (key! [env sentinel])
    (reset! scenario {:status 200 :body empty-body})
    (let [{:keys [content error]} (search! {:query "zzzz"})]
      (is (false? error) (str path " -> " content))
      (is (str/includes? content "no results") path))))

(deftest more-results-are-announced-when-the-vendor-says-so
  (reset! scenario {:status 200 :body (brave-body :more? true)})
  (is (str/includes? (:content (search! {:query "x"})) "more results")))

(deftest the-count-defaults-and-is-capped
  (reset! scenario {:status 200 :body (brave-body)})
  (search! {:query "x"})
  (is (str/includes? (:query-string (last @seen)) (str "count=" search/default-count)))
  (doseq [[label count] {"zero" 0 "negative" -1 "too many" (inc search/max-count)
                         "not a number" "3"}]
    (let [{:keys [content error]} (search! {:query "x" :count count})]
      (is (true? error) label)
      (is (str/includes? content "count") (str label " -> " content)))))

(deftest a-blank-query-is-refused
  (doseq [q ["" "   "]]
    (let [{:keys [content error]} (search! {:query q})]
      (is (true? error) (pr-str q))
      (is (str/includes? content "query"))))
  (is (str/includes? (:content (search! {})) "missing required argument")))

;; ------------------------------------------------------------------ the refusals

(deftest a-vendor-shape-this-does-not-speak-is-not-an-empty-result-set
  ;; The difference the whole namespace turns on: 'I do not understand the answer' and
  ;; 'the web has nothing about this' are different facts, and reporting the first as
  ;; the second is a lie the model has no way to detect.
  (doseq [[env path _] per-vendor]
    (key! [env sentinel])
    (reset! scenario {:status 200 :body (json/write-str {:something "else"})})
    (let [{:keys [content error]} (search! {:query "x"})]
      (is (true? error) path)
      (is (not (str/includes? content "no results")) path)
      (is (str/includes? content "shape") path)
      (is (str/includes? content (str "(" (subs path 1) ")"))
          (str path ": the refusal names the vendor that answered")))))

(deftest an-answer-that-is-not-json-is-refused-as-such
  (reset! scenario {:status 200 :body "<html><body>not json at all</body></html>"})
  (let [{:keys [content error]} (search! {:query "x"})]
    (is (true? error))
    (is (str/includes? content "JSON"))))

(deftest an-error-status-is-refused-by-number
  (doseq [[status fragment] {401 "401" 403 "403" 429 "429" 500 "500"}]
    (reset! scenario {:status status :body ""})
    (let [{:keys [content error]} (search! {:query "x"})]
      (is (true? error) (str status))
      (is (str/includes? content fragment) (str status " -> " content)))))

(deftest a-rejected-key-names-the-variable-that-was-used
  (doseq [[env path _] per-vendor]
    (key! [env sentinel])
    (reset! scenario {:status 401 :body ""})
    (let [{:keys [content]} (search! {:query "x"})]
      (is (str/includes? content env) path)
      (is (str/includes? content ".env") path))))

(deftest a-vendor-that-never-answers-is-refused-with-the-bound
  (let [real web/timeout-ms]
    (try
      (alter-var-root #'web/timeout-ms (constantly 150))
      (reset! scenario {:status 200 :body (brave-body)})
      (let [stop (hk/run-server (fn [_]
                                  ;; Interruptible: the finally below stops this
                                  ;; server while the handler is still in the sleep,
                                  ;; and an uncaught interrupt is an ERROR line for
                                  ;; something that is not a failure.
                                  (try (Thread/sleep 1500)
                                       (catch InterruptedException _ nil))
                                  {:status 200 :body ""})
                                {:port 0})
            real-endpoints search/endpoints]
        (try
          (alter-var-root #'search/endpoints
                          (constantly {:brave (str "http://127.0.0.1:"
                                                   (:local-port (meta stop)) "/search")}))
          (let [{:keys [content error]} (search! {:query "x"})]
            (is (true? error))
            (is (str/includes? content "150ms")))
          (finally
            (alter-var-root #'search/endpoints (constantly real-endpoints))
            (stop))))
      (finally
        (alter-var-root #'web/timeout-ms (constantly real))))))

;; --------------------------------------------------------------------- the key

(deftest without-any-key-the-tool-refuses-and-asks-nothing-of-any-vendor
  (io/delete-file (home/dotenv-file) true)
  (doseq [env search/key-vars]
    (is (nil? (System/getenv env))
        (str "this case needs " env " to be UNSET in this process's environment; it is"
             " set here, so the case cannot run as written")))
  (let [{:keys [content error]} (search! {:query "x"})]
    (is (true? error))
    (doseq [env search/key-vars]
      (is (str/includes? content env) (str env " must be named in the refusal")))
    (is (str/includes? content ".env") "and where to put one")
    (is (str/includes? content (first search/key-vars)) "and which one wins")
    (is (empty? @seen)
        (str "and NOTHING was asked of any vendor -- a refusal that still sent the query"
             " would leak it for an answer it knew it could not get"))))

(deftest the-key-is-never-in-what-the-model-or-a-reader-sees
  (doseq [[env path body] per-vendor]
    (key! [env sentinel])
    (reset! scenario {:status 200 :body body})
    (reset! seen [])
    (let [ok (:content (search! {:query "x"}))]
      (is (seq @seen) (str path " did go out"))
      (is (not (str/includes? ok sentinel)) (str path ": the answer does not quote the key"))))
  (testing "and not in any refusal either -- including the 401 that names the variable"
    (key! ["BRAVE_API_KEY" sentinel])
    (doseq [status [200 401 403 429 500]]
      (reset! scenario {:status status :body (brave-body)})
      (is (not (str/includes? (:content (search! {:query "x"})) sentinel))
          (str "status " status)))
    (reset! scenario {:status 200 :body "not json"})
    (is (not (str/includes? (:content (search! {:query "x"})) sentinel)))))

(deftest a-long-exa-snippet-is-clipped
  ;; Exa's snippet is a slice of the PAGE, so the answer would otherwise carry
  ;; thousands of characters per result; the model has `web_fetch` for the page.
  (key! ["EXA_API_KEY" sentinel])
  (reset! scenario {:status 200
                    :body (json/write-str
                           {:results [{:title "T" :url "https://e.example/"
                                       :text (apply str (repeat 5000 "x"))}]})})
  (let [content (:content (search! {:query "x"}))]
    (is (str/includes? content "T"))
    (is (< (count content) (+ search/snippet-chars 200))
        "the snippet was cut before it reached the answer")))

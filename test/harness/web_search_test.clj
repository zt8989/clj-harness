(ns harness.web-search-test
  "`web_search`: one vendor's wire, and the three refusals that keep it honest.

  NOTHING HERE TOUCHES THE NETWORK. The endpoint is a var, and this suite points it
  at a server it runs itself -- which is also the only way to assert what the REQUEST
  looked like, and to answer with the vendor's shapes (an empty result set, a shape
  this tool does not speak, a 401) without a vendor account.

  THE KEY IS A SENTINEL, and the cases below check all three of its rules: it is
  SENT where it must be (the vendor's header), and it appears NOWHERE the model or a
  log could read it -- not in an answer, and not in any refusal, including the 401
  that a careless implementation would quote back."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.home :as home]
            [harness.tools :as tools]
            [harness.web :as web]
            [harness.web.search :as search]
            [org.httpkit.server :as hk]))

(def ^:private sentinel "search-key-SENTINEL-9f3a1c")

(def ^:private scenario
  "What the fake vendor answers next -- written by each case."
  (atom {:status 200 :body ""}))

(def ^:private seen
  "Every request the fake vendor received: {:query-string .. :token ..}."
  (atom []))

(defn- header-of [req wanted]
  (some (fn [[k v]] (when (= (str/lower-case (name k)) wanted) v)) (:headers req)))

(defn- vendor
  [{:keys [query-string] :as req}]
  (swap! seen conj {:query-string query-string
                    :token (header-of req "x-subscription-token")})
  @scenario)

(defn- key! [^String value]
  (.mkdirs (.getParentFile (home/dotenv-file)))
  (spit (home/dotenv-file) (str search/api-key-env "=" value "\n") :encoding "UTF-8"))

(defn- no-key! []
  (io/delete-file (home/dotenv-file) true))

(use-fixtures :each
  (fn [f]
    (let [stop (hk/run-server vendor {:port 0})
          real search/endpoint]
      (alter-var-root #'search/endpoint
                      (constantly (str "http://127.0.0.1:" (:local-port (meta stop))
                                       "/res/v1/web/search")))
      (reset! seen [])
      (reset! scenario {:status 200 :body ""})
      (key! sentinel)
      (try (f)
           (finally
             (alter-var-root #'search/endpoint (constantly real))
             (no-key!)
             (stop))))))

(defn- search!
  [args]
  (tools/run! {:id "c" :type "function"
               :function {:name "web_search" :arguments (json/write-str args)}}
              "web-search-test"))

(defn- answer
  "The vendor's shape as a JSON string."
  [results & {:keys [more?]}]
  (json/write-str
   {:query {:original "q" :more_results_available (boolean more?)}
    :web   {:results (vec results)}}))

(def ^:private three
  [{:title "First" :url "https://a.example/1" :description "about the first thing"}
   {:title "Second" :url "https://b.example/2" :description "about the second"}
   {:title "Third" :url "https://c.example/3" :description "about the third"}])

;; ------------------------------------------------------------------ the happy path

(deftest results-come-back-in-order-with-their-links
  (reset! scenario {:status 200 :body (answer three)})
  (let [{:keys [content error]} (search! {:query "clojure agents"})]
    (is (false? error) content)
    (is (str/includes? content "3 results"))
    (testing "each result keeps the vendor's order and carries its own three fields"
      (is (< (.indexOf content "1. First") (.indexOf content "2. Second")))
      (is (< (.indexOf content "2. Second") (.indexOf content "3. Third")))
      (doseq [r three]
        (is (str/includes? content (:title r)))
        (is (str/includes? content (:url r)))
        (is (str/includes? content (:description r)))))))

(deftest the-request-carries-the-query-the-count-and-the-key
  (reset! scenario {:status 200 :body (answer three)})
  (search! {:query "clojure & java" :count 7})
  (let [{:keys [query-string token]} (last @seen)]
    (testing "one encoded parameter, whatever the query holds"
      (is (str/includes? query-string "q=clojure"))
      (is (str/includes? query-string "%26") "an ampersand cannot start a new parameter")
      (is (str/includes? query-string "count=7")))
    (testing "and the key rides in the vendor's own header"
      (is (= sentinel token) "the key is sent where it is supposed to be"))))

(deftest the-count-defaults-and-is-capped
  (reset! scenario {:status 200 :body (answer three)})
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

(deftest a-search-with-no-results-says-so-rather-than-failing
  (reset! scenario {:status 200 :body (answer [])})
  (let [{:keys [content error]} (search! {:query "zzzz"})]
    (is (false? error) content)
    (is (str/includes? content "no results"))))

(deftest more-results-are-announced
  (reset! scenario {:status 200 :body (answer three :more? true)})
  (is (str/includes? (:content (search! {:query "x"})) "more results")))

;; ------------------------------------------------------------------ the refusals

(deftest a-vendor-shape-this-does-not-speak-is-not-an-empty-result-set
  ;; The difference the whole namespace turns on: 'I do not understand the answer'
  ;; and 'the web has nothing about this' are different facts, and reporting the
  ;; first as the second is a lie the model has no way to detect.
  (reset! scenario {:status 200 :body (json/write-str {:something "else"})})
  (let [{:keys [content error]} (search! {:query "x"})]
    (is (true? error))
    (is (not (str/includes? content "no results")))
    (is (str/includes? content "shape"))))

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

(deftest a-rejected-key-says-which-variable-to-check
  (reset! scenario {:status 401 :body ""})
  (let [{:keys [content]} (search! {:query "x"})]
    (is (str/includes? content search/api-key-env))
    (is (str/includes? content ".env"))))

(deftest a-vendor-that-never-answers-is-refused-with-the-bound
  (let [real web/timeout-ms]
    (try
      (alter-var-root #'web/timeout-ms (constantly 150))
      (reset! scenario {:status 200 :body (answer three)})
      ;; The handler sleeps past the bound before answering.
      (let [stop (hk/run-server (fn [_]
                                  ;; Interruptible: the finally below stops this
                                  ;; server while the handler is still in the sleep,
                                  ;; and an uncaught interrupt is an ERROR line for
                                  ;; something that is not a failure.
                                  (try (Thread/sleep 1500)
                                       (catch InterruptedException _ nil))
                                  {:status 200 :body ""})
                                {:port 0})
            real-endpoint search/endpoint]
        (try
          (alter-var-root #'search/endpoint
                          (constantly (str "http://127.0.0.1:"
                                           (:local-port (meta stop)) "/search")))
          (let [{:keys [content error]} (search! {:query "x"})]
            (is (true? error))
            (is (str/includes? content "150ms")))
          (finally
            (alter-var-root #'search/endpoint (constantly real-endpoint))
            (stop))))
      (finally
        (alter-var-root #'web/timeout-ms (constantly real))))))

;; --------------------------------------------------------------------- the key

(deftest without-a-key-the-tool-refuses-and-asks-nothing-of-the-vendor
  (no-key!)
  (is (nil? (System/getenv search/api-key-env))
      (str "this case needs " search/api-key-env " to be UNSET in this process's"
           " environment; it is set here, so the case cannot run as written"))
  (let [{:keys [content error]} (search! {:query "x"})]
    (is (true? error))
    (is (str/includes? content search/api-key-env) "the refusal names the variable")
    (is (str/includes? content ".env") "and where to put it")
    (is (empty? @seen)
        (str "and NOTHING was asked of the vendor -- a refusal that still sent the"
             " query would leak it for an answer it knew it could not get"))))

(deftest the-key-is-never-in-what-the-model-or-a-reader-sees
  (reset! scenario {:status 200 :body (answer three)})
  (let [ok (:content (search! {:query "x"}))]
    (is (str/includes? (:query-string (last @seen)) "q=") "the request did go out")
    (is (not (str/includes? ok sentinel)) "the answer does not quote the key"))
  (testing "and not in any refusal either -- including the 401 that names the key"
    (doseq [status [200 401 403 429 500]]
      (reset! scenario {:status status :body (answer three)})
      (let [{:keys [content]} (search! {:query "x"})]
        (is (not (str/includes? content sentinel)) (str "status " status))))
    (reset! scenario {:status 200 :body "not json"})
    (is (not (str/includes? (:content (search! {:query "x"})) sentinel)))))

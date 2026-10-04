(ns harness.cap.model-data-test
  "The two outside facts about a model: the models.dev database and the vendor's own
  /models listing -- what each one is allowed to do, and what it may never do.

  NOTHING HERE LEAVES THE MACHINE. The runner's `isolate!` shuts both doors
  (`model-data/*http-get*` cannot download, `providers/*list-models*` lists nothing),
  so every case below either stubs a door itself or plants a cache file and reads it.
  That is also why the cache files are deleted per case: they belong to the HOME, not
  to a case, and one case's answer would otherwise be the next case's."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.model-data :as md]
            [harness.infra.home :as home]))


(defn- wait-until
  "Poll PRED every 50ms until it holds, up to ten seconds. THE REFRESHES IN THIS
  NAMESPACE ARE BACKGROUND THREADS (that is the design), so a case asserting what one
  produced has to wait for it -- and a fixed sleep would either be slow or flaky."
  [pred]
  (loop [n 0]
    (when (and (< n 200) (not (pred)))
      (Thread/sleep 50)
      (recur (inc n)))))
(defn- with-clean-caches [f]
  ;; THE FILES and THE PROCESS'S MEMORY OF THEM, both: the cache files belong to the
  ;; HOME, and the retry clocks to the PROCESS -- a fetch another case failed would
  ;; otherwise keep this one from trying at all (see harness.cap.model-data/forget!).
  (io/delete-file (home/modelsdev-cache-file) true)
  (io/delete-file (home/provider-models-cache-file) true)
  (md/forget!)
  (f))

(use-fixtures :each with-clean-caches)

;; --------------------------------------------------------------- a document

(def ^:private document
  "A small api.json: two providers carrying the SAME family, one of them the owner --
  and a second family nothing competes over. The numbers are deliberately different,
  because 'whose row wins' is the question."
  {"zhipuai" {"models" {"glm-5.3-flash" {"id" "glm-5.3-flash"
                                         "name" "GLM-5.3-Flash"
                                         "modalities" {"input" ["text" "image" "video"]
                                                       "output" ["text"]}
                                         "limit" {"context" 1000000 "output" 131072}}}}
   "openrouter" {"models" {"zai/glm-5.3-flash" {"id" "zai/glm-5.3-flash"
                                                "name" "GLM 5.3 Flash (Z AI)"
                                                "canonical_model_id" "zhipuai/glm-5.3-flash"
                                                "modalities" {"input" ["text"] "output" ["text"]}
                                                "limit" {"context" 1048576 "output" 943717}}
                           "TEE/glm-5.3-flash" {"id" "TEE/glm-5.3-flash"
                                                "name" "GLM 5.3 Flash TEE"
                                                "canonical_model_id" "zhipuai/glm-5.3-flash"
                                                "modalities" {"input" ["text"] "output" ["text"]}
                                                "limit" {"context" 1048576 "output" 131072}}
                           "acme/mystery" {"id" "acme/mystery"
                                           "name" "Mystery"
                                           "limit" {"context" 0 "output" 0}}}}})

(defn- write-db!
  "A cache file as harness.cap.model-data reads it: the model index -- and, when given,
  the provider table -- both as PAIRS, because an object's keys come back through
  `:key-fn keyword` and a model id is not a keyword."
  ([index] (write-db! (System/currentTimeMillis) index))
  ([fetched-at index] (write-db! fetched-at index nil))
  ([fetched-at index providers]
   (spit (home/modelsdev-cache-file)
         (json/write-str (cond-> {:fetched-at fetched-at :models (vec index)}
                           (some? providers) (assoc :providers (vec providers))))
         :encoding "UTF-8")))

(def ^:private provider-document
  "A parsed api.json as far as VENDORS go: one reachable with this protocol, one that
  spells itself with another SDK, and one compatible vendor with no endpoint published."
  {"zai"      {"name" "Z.AI" "npm" "@ai-sdk/openai-compatible"
               "api" "https://api.z.ai/api/paas/v4"
               "models" {"glm-5.3-flash" {"id" "glm-5.3-flash"}}}
   "anthropic" {"name" "Anthropic" "npm" "@ai-sdk/anthropic"
                "api" "https://api.anthropic.com/v1" "models" {}}
   "no-endpoint" {"name" "Nowhere" "npm" "@ai-sdk/openai-compatible" "models" {}}})

;; ------------------------------------------------------------------ the index

(deftest a-family-is-resolved-once-and-the-owner-decides
  (let [idx (md/index-of document)]
    (testing "the family is reachable by the id a person would type"
      (is (some? (get idx "glm-5.3-flash"))))
    (testing "and the OWNER's row is what it says, not a relay's"
      ;; zhipuai owns `zhipuai/glm-5.3-flash`; TEE and OpenRouter resell it with
      ;; their own names and their own numbers.
      (is (= "GLM-5.3-Flash" (:name (get idx "glm-5.3-flash"))))
      (is (= 1000000 (:context-window (get idx "glm-5.3-flash"))))
      (is (= 131072 (:max-output-tokens (get idx "glm-5.3-flash"))))
      (is (= [:image :text :video] (:input (get idx "glm-5.3-flash")))
          "the owner's modalities, and the CATALOG is what narrows them"))))

(deftest a-row-that-states-nothing-is-not-a-row-that-states-zero
  (let [idx (md/index-of document)]
    ;; The document carries zeroes for counts it does not know. Zero is not a
    ;; window, and handing it out would refuse every run on that model.
    (is (nil? (:context-window (get idx "mystery"))))
    (is (nil? (:max-output-tokens (get idx "mystery"))))
    (is (= "Mystery" (:name (get idx "mystery"))) "the name it does state is kept")))

(deftest an-output-ceiling-above-the-context-is-never-handed-out
  ;; OpenRouter advertises a 943717-token reply for a model whose own row says the
  ;; window is 1M -- consistent -- but a row claiming more output than context would
  ;; be a pair this catalog refuses by name, and remote data must never be the
  ;; reason a home cannot read its own config.
  (let [idx (md/index-of {"p" {"models" {"m" {"id" "m"
                                              "limit" {"context" 1000 "output" 5000}}}}})]
    (is (= 1000 (:context-window (get idx "m"))))
    (is (= 1000 (:max-output-tokens (get idx "m"))) "capped by the context, not dropped")))

(deftest a-gateway-spelling-is-looked-up-by-the-model-behind-it
  (is (= ["cn:glm-5.3-flash" "glm-5.3-flash"] (md/lookup-keys "cn:glm-5.3-flash")))
  (is (= ["glm-5.3-flash"] (md/lookup-keys "glm-5.3-flash")))
  ;; A COLON IN THE MIDDLE OF AN ID IS A TAG, not a namespace: the id is tried whole
  ;; first, and only then the tail behind the last separator.
  (is (= ["gpt-oss:120b" "120b"] (md/lookup-keys "gpt-oss:120b"))))

;; -------------------------------------------------------------- the database

(deftest the-database-answers-never-blocks-and-is-cached-for-seven-days
  (let [writes (atom 0)]
    (alter-var-root #'md/*http-get*
                    (constantly (fn [_ _] (swap! writes inc) (json/write-str document))))
    (try
      (testing "nothing cached: the answer is silence, and the fetch starts behind it"
        (is (= {} (md/describe "glm-5.3-flash")))
        ;; AND THE FETCH GOES OUT BEHIND IT -- not proved by a counter here, because
        ;; the future may already have called the stub by the time this line runs,
        ;; which is exactly the point: nothing waits for it.
      )
      (testing "the refresh lands, the file is written, and the answer arrives"
        (wait-until #(not (empty? (md/describe "glm-5.3-flash"))))
        (is (str/includes? (slurp (home/modelsdev-cache-file)) "glm-5.3-flash")
            "the cache file holds what was fetched")
        (let [facts (md/describe "cn:glm-5.3-flash")]
          (is (= 1000000 (:context-window facts)) "and a gateway's spelling reaches it")
          (is (= "GLM-5.3-Flash" (:name facts)))))
      (testing "a fresh cache is not fetched again"
        (let [before @writes]
          (dotimes [_ 5] (md/describe "glm-5.3-flash"))
          (is (= before @writes) "nothing aged out, so nothing was asked again")))
    (finally (alter-var-root #'md/*http-get* (constantly (fn [_ _] (throw (ex-info "no network" {})))))))))

(deftest an-aged-out-cache-keeps-answering-while-it-refreshes
  ;; SEVEN DAYS is the trust window. Past it the cached answer is still served --
  ;; a stale fact beats no fact -- and the refresh happens behind the reader.
  (write-db! (- (System/currentTimeMillis) (* 8 24 60 60 1000))
             {"glm-5.3-flash" {:context-window 1 :max-output-tokens 1 :name "Old"}})
  (alter-var-root #'md/*http-get*
                  (constantly (fn [_ _] (json/write-str document))))
  (try
    (is (= "Old" (:name (md/describe "glm-5.3-flash")))
        "the stale copy answers the moment it is asked")
    (wait-until #(not= "Old" (:name (md/describe "glm-5.3-flash"))))
    (is (= "GLM-5.3-Flash" (:name (md/describe "glm-5.3-flash")))
        "and the refresh replaced it without anybody waiting")
    (finally (alter-var-root #'md/*http-get* (constantly (fn [_ _] (throw (ex-info "no network" {}))))))))

(deftest a-cache-that-cannot-be-read-is-silence-not-a-failure
  (spit (home/modelsdev-cache-file) "{not json at all" :encoding "UTF-8")
  (is (= {} (md/describe "glm-5.3-flash"))
      "a mirror nobody can parse is a line that is not there yet")
  (is (= {} (md/describe "cn:glm-5.3-flash"))))

(deftest an-id-nothing-knows-is-silence
  (write-db! (System/currentTimeMillis) {"glm-5.3-flash" {:name "GLM-5.3-Flash"}})
  (is (= {} (md/describe "no-such-model")))
  (is (= {} (md/describe nil)))
  (is (= {} (md/describe ""))))

;; ------------------------------------------------------- the vendor's listing

(deftest the-listing-waits-when-nothing-is-cached-and-caches-what-arrives
  (let [calls (atom 0)
        fetch (fn [] (swap! calls inc) ["zzz-first" "aaa-second"])]
    (is (= ["zzz-first" "aaa-second"] (md/listing "acme" fetch))
        "with nothing cached, the caller waits -- a provider with no table cannot serve")
    (is (= 1 @calls))
    (testing "and the answer is on disk, with this line's own fetch time"
      (let [stored (json/read-str (slurp (home/provider-models-cache-file)) :key-fn keyword)]
        (is (= ["zzz-first" "aaa-second"] (get-in stored [:providers :acme :models])))
        (is (number? (get-in stored [:providers :acme :fetched-at])))))
    (testing "a second read is served from the cache, not from the vendor"
      (is (= ["zzz-first" "aaa-second"] (md/listing "acme" fetch)))
      (is (= 1 @calls)))))

(deftest a-listing-that-cannot-be-fetched-is-not-cached
  ;; A FAILED FETCH AND AN EMPTY LISTING ARE DIFFERENT ANSWERS. Caching the failure
  ;; would freeze a typo in a base-url for a week; answering nil leaves the table
  ;; empty and lets the next read try again.
  (let [fails (atom 0)
        broken (fn [] (swap! fails inc) (throw (ex-info "could not reach acme" {})))]
    (is (nil? (md/listing "acme" broken)))
    (is (= 1 @fails))
    (is (not (.exists (home/provider-models-cache-file))) "nothing was written down")
    (is (= ["m"] (md/listing "acme" (fn [] ["m"])))
        "and the next read may still succeed")))

(deftest one-providers-listing-does-not-make-anothers-look-fresh
  ;; EACH LINE CARRIES ITS OWN FETCH TIME: refreshing acme must not make beta's
  ;; week-old line look like it was fetched just now.
  (md/listing "beta" (fn [] ["old"]))
  (let [stored (json/read-str (slurp (home/provider-models-cache-file)) :key-fn keyword)]
    (spit (home/provider-models-cache-file)
          (json/write-str (assoc-in stored [:providers :beta :fetched-at]
                                    (- (System/currentTimeMillis) (* 8 24 60 60 1000))))
          :encoding "UTF-8"))
  (let [calls (atom 0)]
    (is (= ["old"] (md/listing "beta" (fn [] (swap! calls inc) ["new"])))
        "the aged line answers while the refresh runs behind it")
    (wait-until #(= ["new"] (md/listing "beta" (fn [] ["new"]))))
    (is (= ["new"] (md/listing "beta" (fn [] ["new"]))))
  (let [stored (json/read-str (slurp (home/provider-models-cache-file)) :key-fn keyword)]
    (is (some? (get-in stored [:providers :beta]))))))

;; ---------------------------------------------------------- the vendor list

(deftest only-a-vendor-this-harness-can-reach-is-offered
  (let [idx (md/provider-index provider-document)]
    (is (= #{"zai"} (set (keys idx)))
        "one vendor speaks this protocol AND publishes an endpoint; the others do not")
    (is (= {:name "Z.AI" :base-url "https://api.z.ai/api/paas/v4" :model-count 1}
           (get idx "zai")))
    (testing "a vendor reached with another SDK is not this harness's to offer"
      (is (nil? (get idx "anthropic"))
          "its endpoint is a different wire, and a run there could not be sent"))
    (testing "and neither is one with no endpoint published"
      (is (nil? (get idx "no-endpoint"))
          "there would be no address to send anything to"))))

(deftest the-vendor-list-comes-out-sorted-and-carries-its-own-id
  (write-db! (System/currentTimeMillis)
             {"glm-5.3-flash" {:context-window 1000}}
             {"zai"      {:name "Z.AI" :base-url "https://api.z.ai/v4" :model-count 18}
              "abliteration-ai" {:name "abliteration.ai" :base-url "https://x/v1" :model-count 3}})
  (let [rows (md/known-providers)]
    (is (= ["abliteration-ai" "zai"] (mapv :id rows)) "sorted by the name a person reads")
    (is (= "Z.AI" (:name (second rows))))
    (is (= 18 (:model-count (second rows))))))

(deftest a-cache-without-the-vendor-table-asks-for-a-refresh-rather-than-waiting
  ;; A FILE WRITTEN BEFORE THIS NAMESPACE KEPT PROVIDERS IS NOT STALE -- it is fresh by
  ;; its timestamp and simply cannot answer this question. 'Come back in seven days' is
  ;; not an answer a feature may give.
  (write-db! (System/currentTimeMillis) {"glm-5.3-flash" {:context-window 1000}})
  (let [fetches (atom 0)]
    (alter-var-root #'md/*http-get*
                    (constantly (fn [_ _] (swap! fetches inc) "{}")))
    (try
      (is (= [] (md/known-providers)) "nothing is known from that file")
      (wait-until #(pos? @fetches))
      (is (pos? @fetches) "and the refresh went out anyway")
      (finally (alter-var-root #'md/*http-get*
                               (constantly (fn [_ _] (throw (ex-info "no network" {})))))))))

(deftest a-vendors-own-variable-names-come-back-for-the-home-to-check
  ;; WHAT THE VENDOR SAYS ITS KEY IS CALLED (models.dev's `env`). The harness asks this
  ;; so a person who already exported `ZHIPU_API_KEY` does not have to write the same
  ;; secret again under a name this harness derived from the id.
  ;; THREE ARGUMENTS: this file's helper is (fetched-at, index, providers) -- the other
  ;; one in providers_test is (index, providers), and mixing them up writes the vendor
  ;; table where the model index belongs.
  (write-db! (System/currentTimeMillis)
             {"m" {:context-window 1000}}
             {"zai"      {:name "Z.AI" :base-url "https://api.z.ai/v4" :model-count 18
                          :env ["ZHIPU_API_KEY"]}
              "cloudflare-workers-ai" {:name "Cloudflare" :base-url "https://cf/v1"
                                       :model-count 27
                                       :env ["CLOUDFLARE_ACCOUNT_ID" "CLOUDFLARE_API_KEY"]}})
  (is (= ["ZHIPU_API_KEY"] (md/provider-env-names "zai")))
  (is (= ["CLOUDFLARE_ACCOUNT_ID" "CLOUDFLARE_API_KEY"]
         (md/provider-env-names "cloudflare-workers-ai"))
      "a vendor whose key needs setup beside it carries BOTH names")
  (testing "and a vendor the document does not name answers nothing rather than something"
    (is (= [] (md/provider-env-names "my-own-gateway")))
    (is (= [] (md/provider-env-names nil)))
    (is (= [] (md/provider-env-names "")))))

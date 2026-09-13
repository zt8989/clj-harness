(ns harness.provider-test
  "Provider resolution (four tiers), the session-state introspection surface, and
  the authorised session-configure tool.

  Everything here runs under the runner's isolated config root, so the fixtures
  write their own config.edn / providers.edn into a temp home rather than
  touching the developer's real ~/.clj-harness."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.home :as home]
            [harness.memory :as mem]
            [harness.opaque :as opaque]
            [harness.tools :as tools]))

;; ------------------------------------------------------------------ fixtures

(defn- write-home! [config providers]
  (.mkdirs (io/file (home/root)))
  (spit (home/config-file) config :encoding "UTF-8")
  (if providers
    (spit (home/providers-file) providers :encoding "UTF-8")
    (io/delete-file (home/providers-file) true)))

(defn- with-home [config providers f]
  (let [old-config (when (.exists (home/config-file))
                     (slurp (home/config-file) :encoding "UTF-8"))
        old-prov    (when (.exists (home/providers-file))
                      (slurp (home/providers-file) :encoding "UTF-8"))]
    (try
      (write-home! config providers)
      (f)
      (finally
        (write-home! (or old-config "{:protocol :fake}\n") old-prov)))))

(def ^:private registry
  "{:cheap {:protocol :openai-completions :base-url \"https://a/v1\" :model \"small\"}
    :smart {:protocol :openai-completions :base-url \"https://a/v1\" :model \"big\" :reasoning-effort \"high\"}
    :local {:protocol :openai-completions :base-url \"http://localhost:11434/v1\" :model \"qwen3\"}}")

;; ------------------------------------------------------------------ tiers

(deftest the-registry-supplies-the-base-provider
  (with-home "{:provider :cheap}\n" registry
    (fn []
      (let [p (opaque/effective-provider "p-tier")]
        (is (= :openai-completions (:protocol p)))
        (is (= "https://a/v1" (:base-url p)))
        (is (= "small" (:model p)))
        (testing "a field no tier named is simply absent -- not an error"
          (is (not (contains? p :reasoning-effort))))))))

(deftest the-default-tier-overrides-fields-without-a-new-entry
  (with-home "{:provider :cheap :reasoning-effort \"low\"}\n" registry
    (fn []
      (let [p (opaque/effective-provider "p-default")]
        (is (= "small" (:model p)) "the registry's model still stands")
        (is (= "low" (:reasoning-effort p)) "and the default tier tuned one field")))))

(deftest a-session-override-sits-above-the-default-tier
  (with-home "{:provider :cheap}\n" registry
    (fn []
      (try
        (opaque/set-override! "p-sess" {:model "medium"})
        (let [p (opaque/effective-provider "p-sess")]
          (is (= "medium" (:model p)))
          (is (= :openai-completions (:protocol p)) "untouched fields inherit below"))
        (testing "and another session is untouched"
          (is (= "small" (:model (opaque/effective-provider "p-sess-other")))))
        (finally (opaque/set-override! "p-sess" nil))))))

(deftest a-run-request-sits-on-top-of-everything
  (with-home "{:provider :cheap}\n" registry
    (fn []
      (try
        (opaque/set-override! "p-req" {:model "medium"})
        (let [{:keys [provider source]} (opaque/resolve-provider "p-req" {:model "biggest"})]
          (is (= "biggest" (:model provider)))
          (is (= :request source)))
        (testing "with no request, the session's override is the top tier"
          (is (= :default (:source (opaque/resolve-provider "p-req")))))
        (finally (opaque/set-override! "p-req" nil))))))

(deftest a-missing-registry-is-empty-not-fatal
  ;; A config that describes its provider inline needs no registry at all.
  (with-home "{:protocol :fake :model \"flat\"}\n" nil
    (fn []
      (is (= "flat" (:model (opaque/effective-provider "p-flat"))))
      (is (= :inline (:source (opaque/resolve-provider "p-flat")))))))

(deftest a-named-but-missing-provider-fails-naming-what-it-looked-for
  (with-home "{:provider :nope}\n" registry
    (fn []
      (let [e (try (opaque/effective-provider "p-bad") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a name that is not in the registry is a hard failure")
        (is (str/includes? (ex-message e) "nope") "the message names the missing entry")
        (is (str/includes? (ex-message e) "cheap") "and lists what IS defined")))))

(deftest the-api-key-is-attached-last-and-only-in-the-resolver
  (with-home "{:provider :cheap}\n" registry
    (fn []
      (let [p (opaque/effective-provider "p-key")]
        (is (contains? p :api-key) "the assembled provider carries the key slot")
        (testing "but the introspectable answer deliberately does not"
          (let [a (mem/active-provider "p-key")]
            (is (= #{:protocol :base-url :model} (set (keys a))))
            (is (not (contains? a :api-key)))))))))

;; ------------------------------------------------------------- introspection

(deftest log-path-names-the-file-the-writer-writes
  (testing "and it is derived from harness.home, so a relocated root follows"
    (is (= (str (home/log-file "t-logpath")) (mem/log-path "t-logpath")))
    (is (str/ends-with? (mem/log-path "t-logpath") "t-logpath.jsonl")))
  (testing "the sanitize rule is shared, so the reader and writer agree"
    (is (= "a_b_c.jsonl" (str/replace (mem/log-path "a/b c") #".*[\\/]" "")))))

(deftest active-provider-reads-live-and-hides-the-key
  (with-home "{:provider :smart}\n" registry
    (fn []
      (let [a (mem/active-provider "t-act")]
        (is (= {:protocol :openai-completions :base-url "https://a/v1"
                :model "big" :reasoning-effort "high"}
               a)))
      (testing "a change is visible to the very next call -- nothing is cached"
        (opaque/set-override! "t-act" {:model "bigger"})
        (is (= "bigger" (:model (mem/active-provider "t-act"))))
        (opaque/set-override! "t-act" nil))
      (testing "and no api-key key exists, at any nesting depth"
        (is (not-any? #(str/includes? (str %) "api-key")
                      (tree-seq coll? seq (mem/active-provider "t-act"))))))))

;; ------------------------------------------------------- the configure tool

(defn- configure!
  "Run the session-configure tool through the real seam, as the agent would,
  with THREAD-ID's session in scope. Returns the seam's result map."
  [thread-id args]
  (tools/run! {:id "sc1" :type "function"
               :function {:name "session-configure"
                          :arguments (json/write-str args)}}
              thread-id))

(defn- approve!
  "Drive a parked session-configure to APPROVED the way a resume does: park it,
  hand the human's verdict to the seam's memory, then call again -- the second
  call consumes the decision and runs the body. Returns the second call's map."
  [thread-id id args]
  (let [call (fn [] (tools/run! {:id id :type "function"
                                 :function {:name "session-configure"
                                            :arguments (json/write-str args)}}
                                thread-id))
        {:keys [parked]} (call)
        _ (mem/decide-approval! (:interrupt-id parked) :approved {})]
    (call)))

(defn- veto!
  "Drive a parked session-configure to VETOED. The body never runs."
  [thread-id id args]
  (let [call (fn [] (tools/run! {:id id :type "function"
                                 :function {:name "session-configure"
                                            :arguments (json/write-str args)}}
                                thread-id))
        {:keys [parked]} (call)]
    (mem/decide-approval! (:interrupt-id parked) :vetoed {:reason "no"})
    (call)))

(deftest session-configure-parks-rather-than-writing
  (with-home "{:provider :cheap}\n" registry
    (fn []
      (let [{:keys [parked]} (configure! "t-conf" {:model "sneaky"})]
        (testing "with no decision yet, the call parks and nothing is written"
          (is (some? parked) "the seam reports the call as parked")
          (is (nil? (opaque/override-for "t-conf")) "the session override is untouched"))))))

(deftest an-approved-configure-writes-only-what-it-names
  (with-home "{:provider :cheap}\n" registry
    (fn []
      (try
        (opaque/set-override! "t-ok" {:reasoning-effort "low"})
        (approve! "t-ok" "sc-ok" {:model "bigger"})
        (let [ov (opaque/override-for "t-ok")]
          (is (= "bigger" (:model ov)))
          (is (= "low" (:reasoning-effort ov))
              "the field it did not name is left exactly as it was")
          (is (not (contains? ov :base-url))
              "and nothing it could not know about was invented"))
        (finally (opaque/set-override! "t-ok" nil))))))

(deftest a-configure-with-nothing-to-change-is-refused
  (with-home "{:provider :cheap}\n" registry
    (fn []
      ;; The refusal lives in the body, so it only surfaces on the approved
      ;; transit -- which is also the only transit that could ever write.
      (let [{:keys [content error]} (approve! "t-empty" "sc-empty" {})]
        (is (true? error))
        (is (str/includes? content "nothing to change"))
        (is (nil? (opaque/override-for "t-empty")) "and nothing was written")))))

(deftest a-vetoed-configure-never-writes
  (with-home "{:provider :cheap}\n" registry
    (fn []
      (try
        (veto! "t-veto" "sc-veto" {:model "rejected"})
        (is (nil? (opaque/override-for "t-veto")))
        (testing "and no change was queued for the writer"
          (is (empty? (mem/take-provider-changes! "t-veto"))))
        (finally (opaque/set-override! "t-veto" nil))))))

(deftest an-approved-configure-queues-one-change-for-the-writer
  (with-home "{:provider :cheap}\n" registry
    (fn []
      (try
        (approve! "t-queue" "sc-q" {:reasoning-effort "high"})
        (let [[c & more] (mem/take-provider-changes! "t-queue")]
          (is (some? c))
          (is (empty? more) "exactly one change was queued")
          (is (= "high" (:reasoning-effort (:after c)))
              "the after side shows the new value"))
        (testing "and draining clears it -- the outbox is not read twice"
          (is (empty? (mem/take-provider-changes! "t-queue"))))
        (finally (opaque/set-override! "t-queue" nil))))))

(deftest consecutive-changes-chain-before-and-after
  (with-home "{:provider :cheap}\n" registry
    (fn []
      (try
        (approve! "t-chain" "sc-c1" {:model "m1"})
        (approve! "t-chain" "sc-c2" {:model "m2"})
        (let [[a b] (mem/take-provider-changes! "t-chain")]
          (is (= "m1" (:model (:after a))))
          (is (= "m1" (:model (:before b)))
              "the second change starts where the first ended")
          (is (= "m2" (:model (:after b)))))
        (finally (opaque/set-override! "t-chain" nil))))))

(deftest the-change-is-scoped-to-its-own-thread
  (with-home "{:provider :cheap}\n" registry
    (fn []
      (try
        (approve! "t-a" "sc-a" {:model "for-a"})
        (is (= "for-a" (:model (opaque/effective-provider "t-a"))))
        (is (= "small" (:model (opaque/effective-provider "t-b")))
            "another session serves from the untouched default")
        (finally (opaque/set-override! "t-a" nil))))))

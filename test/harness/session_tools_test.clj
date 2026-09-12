(ns harness.session-tools-test
  "The session-scoped toolset: an immutable base plus a per-thread overlay.
  A thread's add/remove reaches its next run's tools array and tool dispatch --
  and never another thread's. The base registry is never mutated at runtime."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.event :as ev]
            [harness.fake :as fake]
            [harness.llm :as llm]
            [harness.loop :as loop]
            [harness.memory :as mem]
            [harness.tools :as tools]))

(defn- echo-tool
  [label]
  {:description label
   :parameters  {:type "object" :properties {} :required []}
   :required    []
   :run         (fn [_] label)})

(defn- spec-names
  ([thread-id] (mapv #(get-in % [:function :name]) (tools/specs thread-id))))
(defn- spec-description
  [thread-id name]
  (some #(when (= name (get-in % [:function :name]))
           (get-in % [:function :description]))
        (tools/specs thread-id)))

(defn- tool [tool-name run-fn required]
  {:description tool-name
   :parameters  {:type "object" :properties {} :required (mapv clojure.core/name required)}
   :required    required
   :run         run-fn})

(defn- base-names [] (spec-names nil))
(defn- base-count [] (count (base-names)))

(deftest session-add-and-remove-are-scoped-to-one-thread
  (mem/session-register! "t-scope" "echo" (echo-tool "session echo"))
  (testing "the added tool is visible to its thread and to nobody else"
    (is (contains? (set (spec-names "t-scope")) "echo"))
    (is (= (base-count) (count (spec-names "t-other")))))
  (testing "removing a base tool hides it for this session only"
    (mem/session-unregister! "t-scope" "read")
    (is (not (contains? (set (spec-names "t-scope")) "read")))
    (is (contains? (set (spec-names "t-other")) "read")))
  (testing "removing a name nobody knows is a no-op"
    (mem/session-unregister! "t-scope" "no-such-tool")
    ;; base minus the hidden read, plus the added echo.
    (is (= (count (base-names)) (count (spec-names "t-scope")))))
  (testing "removal is monotonic within a session: a hidden base tool stays hidden,
            retracting an addition is the only undo there is"
    (mem/session-unregister! "t-scope" "echo")
    (is (= (remove #{"read"} (base-names)) (spec-names "t-scope")))
    (is (= (base-names) (spec-names "t-other"))
        "and another thread was never touched")))

(deftest a-session-shadow-leaves-the-base-untouched
  (let [base-read (@mem/registry "read")]
    (mem/session-register! "t-shadow" "read" (echo-tool "shadow read"))
    (is (= "shadow read" (spec-description "t-shadow" "read")))
    (is (not= "shadow read" (:description (@mem/registry "read")))
        "the base registry is never mutated at runtime")
    (is (= (:description base-read) (:description (@mem/registry "read"))))
    (mem/session-unregister! "t-shadow" "read")
    (is (= (:description base-read) (spec-description "t-shadow" "read")))))

;; ------------------------------------------------------------------ integration

;; A provider that records what toolset it was offered. The scripted fake ignores
;; its input, so it cannot show that the thread's effective toolset actually
;; reached the model -- which is the only thing worth asserting here.
(defmethod llm/stream! :tool-spy
  [{:keys [seen reply]} _messages on-event thread-id]
  (swap! seen conj {:thread-id thread-id :tools (tools/specs thread-id)})
  (on-event (ev/text-delta reply))
  {:role "assistant" :content reply})

(defn- spy-run [thread-id]
  (let [seen (atom [])
        ch   (loop/run-chan {:protocol :tool-spy :seen seen :reply "ok"} []
                             {:thread-id thread-id})]
    (loop []
      (when-let [_ (async/<!! ch)]
        (recur)))
    @seen))

(deftest the-run-serves-the-threads-effective-toolset
  (is (= "t-spy-plain" (:thread-id (first (spy-run "t-spy-plain")))))
  (is (= (base-count) (count (:tools (first (spy-run "t-spy-plain"))))))
  (mem/session-register! "t-spy" "echo" (echo-tool "session echo"))
  (let [[plain with-echo other] [(last (spy-run "t-spy-plain"))
                                 (last (spy-run "t-spy"))
                                 (last (spy-run "t-spy-other"))]]
    (testing "the thread with an overlay sees it on the next run"
      (is (contains? (set (map #(get-in % [:function :name]) (:tools with-echo))) "echo")))
    (testing "and the thread-id rode through the kernel to the provider"
      (is (= "t-spy" (:thread-id with-echo))))
    (testing "another thread still sees the pure base"
      (is (= (base-count) (count (:tools other))))
      (is (= (base-count) (count (:tools plain)))))))

;; ------------------------------------------------------------------ lifecycle

(defn- drain-events [provider thread-id]
  (let [ch (loop/run-chan provider [] {:thread-id thread-id})]
    (loop [acc []]
      (if-let [ev (async/<!! ch)]
        (if (= :run/done (:type ev)) acc (recur (conj acc ev)))
        acc))))

(deftest the-seam-reports-a-lifetime-for-every-call
  (mem/session-register! "t-life" "ok-tool"
                         (tool "ok" (fn [_] "fine") []))
  (mem/session-register! "t-life" "boom"
                         (tool "boom" (fn [_] (throw (ex-info "kaboom" {}))) []))
  (mem/session-register! "t-life" "needs-arg"
                         (tool "needs-arg" (fn [_] "no") [:x]))
  (let [events (drain-events (fake/scripted
                              [{:content ""
                                :tool-calls [{:id "c1" :name "no-such-tool" :arguments {}}
                                             {:id "c2" :name "ok-tool" :arguments {}}
                                             {:id "c3" :name "boom" :arguments {}}
                                             {:id "c4" :name "needs-arg" :arguments {}}]}
                               {:content "done"}])
                             "t-life")
        by-id  (fn [type id]
                 (filterv #(and (= type (:type %)) (= id (:id %))) events))]
    (testing "an unknown tool: refused at pre-execute, no execute, post closes"
      (is (= :unknown-tool (:outcome (first (by-id :tool/pre-execute "c1")))))
      (is (empty? (by-id :tool/execute "c1")))
      (is (= 1 (count (by-id :tool/post-execute "c1")))))
    (testing "a clean call: pre pass, execute without error, post"
      (is (= :pass (:outcome (first (by-id :tool/pre-execute "c2")))))
      (is (nil? (:error (first (by-id :tool/execute "c2")))))
      (is (= 1 (count (by-id :tool/post-execute "c2")))))
    (testing "a throwing call: execute carries the error message"
      (is (= "kaboom" (:error (first (by-id :tool/execute "c3")))))
      (is (= 1 (count (by-id :tool/post-execute "c3")))))
    (testing "missing arguments: refused at pre-execute with the missing names,
              no execute phase, and the error still fed back as the result"
      (let [pre (first (by-id :tool/pre-execute "c4"))]
        (is (= :missing-args (:outcome pre)))
        (is (= [:x] (:missing pre))))
      (is (empty? (by-id :tool/execute "c4")))
      (is (= 1 (count (by-id :tool/post-execute "c4"))))
      ;; Results flow in completion order -- the first :tool/result is whoever
      ;; finished first, so key on c4's own id.
      (is (str/includes? (str (:content
                               (first (filter #(and (= :tool/result (:type %))
                                                    (= "c4" (:id %)))
                                              events))))
                         "missing required argument")))
    (mem/session-unregister! "t-life" "ok-tool")
    (mem/session-unregister! "t-life" "boom")
    (mem/session-unregister! "t-life" "needs-arg")))

(ns harness.session-tools-test
  "The session-scoped toolset: an immutable base plus a per-thread overlay.
  A thread's add/remove reaches its next run's tools array and tool dispatch --
  and never another thread's. The base registry is never mutated at runtime."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
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
  (testing "unregistering a base tool is a no-op -- base tools cannot be removed"
    (mem/session-unregister! "t-scope" "read")
    (is (contains? (set (spec-names "t-scope")) "read"))
    (is (contains? (set (spec-names "t-other")) "read")))
  (testing "unregistering a name nobody knows is a no-op"
    (mem/session-unregister! "t-scope" "no-such-tool")
    ;; base plus the added echo. Nothing was hidden.
    (is (= (inc (base-count)) (count (spec-names "t-scope")))))
  (testing "retracting an addition is the only undo for presence"
    (mem/session-unregister! "t-scope" "echo")
    (is (= (base-names) (spec-names "t-scope")))
    (is (= (base-names) (spec-names "t-other"))
        "and another thread was never touched")))

;; ----------------------------------------------------------------- toggles

(deftest disabling-a-tool-keeps-it-in-the-toolset
  (mem/session-disable! "t-off" "read")
  (testing "the disabled tool is still offered to the model"
    (is (contains? (set (spec-names "t-off")) "read"))
    (is (= (base-names) (spec-names "t-off")))
    (is (= (spec-description nil "read") (spec-description "t-off" "read"))
        "and its definition is untouched"))
  (testing "the session can see that it is off"
    (is (true? (mem/session-disabled? "t-off" "read")))
    (is (false? (mem/session-disabled? "t-off" "write"))))
  (testing "another session is unaffected"
    (is (false? (mem/session-disabled? "t-off-other" "read")))
    (is (not (mem/session-disabled? "t-off-other" "read"))))
  (testing "enabling brings it back, and the toolset never changed"
    (mem/session-enable! "t-off" "read")
    (is (false? (mem/session-disabled? "t-off" "read")))
    (is (= (base-names) (spec-names "t-off")))))

(deftest toggles-are-idempotent-and-never-invent-a-tool
  (testing "disabling twice is one mark"
    (mem/session-disable! "t-idem" "bash")
    (mem/session-disable! "t-idem" "bash")
    (is (true? (mem/session-disabled? "t-idem" "bash"))))
  (testing "enabling a name that was never disabled is a no-op"
    (mem/session-enable! "t-idem" "edit")
    (is (false? (mem/session-disabled? "t-idem" "edit"))))
  (testing "disabling a name the session cannot see never invents a mark"
    (mem/session-disable! "t-idem" "no-such-tool")
    (is (false? (mem/session-disabled? "t-idem" "no-such-tool")))
    (is (= (base-names) (spec-names "t-idem")))))

(deftest a-session-added-tool-can-be-disabled-too
  (mem/session-register! "t-own" "echo" (echo-tool "session echo"))
  (mem/session-disable! "t-own" "echo")
  (testing "disabling does not retract the definition"
    (is (contains? (set (spec-names "t-own")) "echo"))
    (is (true? (mem/session-disabled? "t-own" "echo"))))
  (testing "enabling it again leaves the definition in place"
    (mem/session-enable! "t-own" "echo")
    (is (contains? (set (spec-names "t-own")) "echo"))))

(deftest retracting-an-addition-clears-its-disabled-mark
  ;; Otherwise re-adding the same name would inherit a zombie: present, but
  ;; silently switched off by a state from a definition that no longer exists.
  (mem/session-register! "t-zombie" "echo" (echo-tool "first"))
  (mem/session-disable! "t-zombie" "echo")
  (is (true? (mem/session-disabled? "t-zombie" "echo")))
  (mem/session-unregister! "t-zombie" "echo")
  (is (false? (mem/session-disabled? "t-zombie" "echo"))
      "the mark went with the definition")
  (mem/session-register! "t-zombie" "echo" (echo-tool "second"))
  (is (false? (mem/session-disabled? "t-zombie" "echo"))
      "a freshly added tool starts enabled")
  (is (contains? (set (spec-names "t-zombie")) "echo")))

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

;; ---------------------------------------------------------------- introspection

(deftest eval-joins-the-session-across-the-real-tool-call-shape
  ;; The agent's own path: a full eval tool call, thread context bound, extends
  ;; the session -- and the next run's tools array and dispatch both see it.
  (let [eval!  (fn [thread-id code]
                 (tools/run! {:function {:name "eval"
                                         :arguments (json/write-str {:code code})}}
                             thread-id))
        {:keys [content error]}
        (eval! "t-e2e"
               "(do (harness.memory/session-register! harness.memory/*thread-id* \"note\"
                      {:description \"note\"
                       :parameters {:type \"object\" :properties {\"text\" {:type \"string\"}}}
                       :required [:text]
                       :run (fn [{:keys [text]}] (str \"noted \" text))})
                    :added)")]
    (is (false? error))
    (is (str/includes? content ":added"))
    (let [events (drain-events
                  (fake/scripted [{:content ""
                                   :tool-calls [{:id "c1" :name "note" :arguments {:text "hi"}}]}
                                  {:content "done"}])
                  "t-e2e")
          result (first (filter #(= :tool/result (:type %)) events))]
      (testing "the next run's dispatch runs the session-added tool"
        (is (false? (:error result)))
        (is (= "noted hi" (:content result)))))
    (testing "the recorded state is reachable through the same eval surface"
      (is (str/includes? (:content (eval! "t-e2e"
                                          "(keys (harness.memory/config))"))
                         ":protocol")))
    (testing "disabling a base tool is reported as disabled, not as unknown"
      (mem/session-disable! "t-e2e" "read")
      (let [events (drain-events
                    (fake/scripted [{:content ""
                                     :tool-calls [{:id "c2" :name "read" :arguments {:path "deps.edn"}}]}
                                    {:content "done"}])
                    "t-e2e")
            result (first (filter #(= :tool/result (:type %)) events))
            pre    (first (filter #(and (= :tool/pre-execute (:type %))
                                        (= "c2" (:id %)))
                                  events))]
        (is (= :disabled (:outcome pre)))
        (is (true? (:error result)))
        (is (str/includes? (str (:content result)) "disabled"))
        (is (not (str/includes? (str (:content result)) "unknown")))))
    (mem/session-enable! "t-e2e" "read")
    (testing "enabling it again restores dispatch in the same run shape"
      (let [events (drain-events
                    (fake/scripted [{:content ""
                                     :tool-calls [{:id "c3" :name "read" :arguments {:path "deps.edn"}}]}
                                    {:content "done"}])
                    "t-e2e")
            result (first (filter #(= :tool/result (:type %)) events))
            pre    (first (filter #(and (= :tool/pre-execute (:type %))
                                        (= "c3" (:id %)))
                                  events))]
        (is (= :pass (:outcome pre)))
        (is (false? (:error result)))
        ;; t-read returns the file's CONTENTS -- assert on something in them.
        (is (str/includes? (str (:content result)) ":deps"))))))

(deftest the-agent-toggles-a-tool-through-eval-and-reads-what-it-has
  ;; The whole point of the feature, end to end and through the real tool call
  ;; shape: the agent switches a tool off by hand, calls it and is told it is
  ;; disabled, then switches it back on -- without ever losing sight of it.
  (let [eval! (fn [thread-id code]
                (tools/run! {:function {:name "eval"
                                        :arguments (json/write-str {:code code})}}
                            thread-id))
        call  (fn [thread-id code]
                (let [{:keys [content error]}
                      (eval! thread-id code)]
                  (is (false? error) (str "eval failed: " content))
                  content))
        seen  (fn [thread-id]
                (call thread-id
                      "(sort (keys (harness.memory/effective-tools
                                     harness.memory/*thread-id*)))"))]
    (testing "the toolset the agent reads reflects its OWN session, not the base"
      (call "t-tog" "(harness.memory/session-register! harness.memory/*thread-id*
                       \"probe\" {:description \"probe\"
                                  :parameters {:type \"object\" :properties {}}
                                  :required [] :run (fn [_] \"pong\")})")
      (let [names (seen "t-tog")]
        (is (str/includes? names "probe") "the session's own addition shows up")
        (is (str/includes? names "bash") "and so do the base tools")
        (is (not (str/includes? (seen "t-tog-other") "probe"))
            "another session's read does not")))
    (testing "a disabled tool is still in the toolset the agent reads"
      (call "t-tog" "(harness.memory/session-disable! harness.memory/*thread-id* \"bash\")")
      (is (str/includes? (seen "t-tog") "bash")
          "disabling never removes it from what the agent sees"))
    (testing "calling it reports disabled, never unknown"
      (let [events (drain-events
                    (fake/scripted [{:content ""
                                     :tool-calls [{:id "d1" :name "bash"
                                                   :arguments {:command "echo hi"}}]}
                                    {:content "done"}])
                    "t-tog")
            result (first (filter #(= :tool/result (:type %)) events))]
        (is (true? (:error result)))
        (is (str/includes? (str (:content result)) "disabled"))
        (is (not (str/includes? (str (:content result)) "unknown")))))
    (testing "and the agent can turn it back on itself"
      (call "t-tog" "(harness.memory/session-enable! harness.memory/*thread-id* \"bash\")")
      (let [events (drain-events
                    (fake/scripted [{:content ""
                                     :tool-calls [{:id "d2" :name "bash"
                                                   :arguments {:command "echo back-on"}}]}
                                    {:content "done"}])
                    "t-tog")
            result (first (filter #(= :tool/result (:type %)) events))]
        (is (false? (:error result)))
        (is (str/includes? (str (:content result)) "back-on"))))))

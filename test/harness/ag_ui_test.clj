(ns harness.ag-ui-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.ag-ui :as ag]
            [harness.event :as ev]
            [harness.fake :as fake]
            [harness.loop :as loop]))

(defn- wire [events]
  (let [emit (ag/outbound "thr-1" "run-1")]
    (vec (mapcat emit events))))

(defn- types [frames] (mapv :type frames))

(defn- violations
  "Every structural rule an AG-UI client enforces, checked across a whole stream."
  [frames]
  (let [open (atom {})
        bad  (atom [])]
    (doseq [f frames]
      (let [t  (:type f)
            id (or (:messageId f) (:toolCallId f))]
        (cond
          (str/ends-with? t "_CHUNK")
          (swap! bad conj (str "chunk event emitted: " t))

          (contains? #{"TEXT_MESSAGE_START" "REASONING_MESSAGE_START"} t)
          (if (contains? @open id)
            (swap! bad conj (str "double START for " id))
            (swap! open assoc id :message))

          (contains? #{"TEXT_MESSAGE_CONTENT" "REASONING_MESSAGE_CONTENT"} t)
          (when-not (contains? @open (:messageId f))
            (swap! bad conj (str "CONTENT for unopened " (:messageId f))))

          (contains? #{"TEXT_MESSAGE_END" "REASONING_MESSAGE_END"} t)
          (if (contains? @open (:messageId f))
            (swap! open dissoc (:messageId f))
            (swap! bad conj (str "END for unopened " (:messageId f))))

          (= t "TOOL_CALL_START") (swap! open assoc id :tool)
          (= t "TOOL_CALL_ARGS")
          (when-not (contains? @open id) (swap! bad conj (str "ARGS for unopened " id)))
          (= t "TOOL_CALL_END") (swap! open dissoc id)
          (= t "RUN_FINISHED")
          (when (seq @open) (swap! bad conj (str "left open at finish: " (keys @open))))
          (= t "RUN_ERROR")
          (when (seq @open) (swap! bad conj (str "left open at error: " (keys @open)))))))
    @bad))

(deftest text-only-turn
  (let [frames (wire [(ev/run-start)
                      (ev/text-delta "he") (ev/text-delta "llo")
                      (ev/run-end)])]
    (is (= ["RUN_STARTED" "TEXT_MESSAGE_START" "TEXT_MESSAGE_CONTENT"
            "TEXT_MESSAGE_CONTENT" "TEXT_MESSAGE_END" "RUN_FINISHED"]
           (types frames)))
    (is (empty? (violations frames)))))

(deftest reasoning-group-closes-before-text-opens
  (let [frames (wire [(ev/run-start)
                      (ev/reasoning-delta "想") (ev/reasoning-delta "一下")
                      (ev/text-delta "hi")
                      (ev/run-end)])]
    (is (= ["RUN_STARTED" "REASONING_START" "REASONING_MESSAGE_START"
            "REASONING_MESSAGE_CONTENT" "REASONING_MESSAGE_CONTENT"
            "REASONING_MESSAGE_END" "REASONING_END"
            "TEXT_MESSAGE_START" "TEXT_MESSAGE_CONTENT" "TEXT_MESSAGE_END"
            "RUN_FINISHED"]
           (types frames)))
    (is (empty? (violations frames)))))

(deftest tool-call-with-no-text-still-gets-a-parent
  (let [frames (wire [(ev/run-start)
                      (ev/tool-call "c1" "read" "{}")
                      (ev/tool-result "c1" "file contents" false)
                      (ev/text-delta "done")
                      (ev/run-end)])]
    (is (empty? (violations frames)))
    (testing "an empty text message is opened and closed around the call"
      (is (= ["RUN_STARTED"
              "TEXT_MESSAGE_START" "TEXT_MESSAGE_END"
              "TOOL_CALL_START" "TOOL_CALL_ARGS" "TOOL_CALL_END"
              "TOOL_CALL_RESULT"
              "TEXT_MESSAGE_START" "TEXT_MESSAGE_CONTENT" "TEXT_MESSAGE_END"
              "RUN_FINISHED"]
             (types frames))))
    (testing "the call is parented to that message"
      (let [parent (:parentMessageId (first (filter #(= "TOOL_CALL_START" (:type %)) frames)))]
        (is (= (:messageId (first (filter #(= "TEXT_MESSAGE_START" (:type %)) frames))) parent))))
    (testing "the next turn opens a fresh message id rather than reopening"
      (let [ids (map :messageId (filter #(= "TEXT_MESSAGE_START" (:type %)) frames))]
        (is (= 2 (count ids)))
        (is (= 2 (count (distinct ids))))))))

(deftest an-error-closes-whatever-is-open
  (let [frames (wire [(ev/run-start)
                      (ev/reasoning-delta "x") (ev/text-delta "y")
                      (ev/run-error "boom")])]
    (is (= "RUN_ERROR" (last (types frames))))
    (is (empty? (violations frames)))))

(deftest drives-the-real-loop-offline
  (let [emit   (ag/outbound "thr-9" "run-9")
        frames (atom [])]
    (loop/run! (fake/scripted [{:reasoning "想一下" :content ""
                                :tool-calls [{:id "c1" :name "eval" :arguments {:code "(+ 1 2)"}}]}
                               {:content "等于 3"}])
               []
               #(swap! frames into (emit %)))
    (is (empty? (violations @frames)))
    (is (= "RUN_STARTED" (first (types @frames))))
    (is (= "RUN_FINISHED" (last (types @frames))))
    (testing "reasoning was rendered rather than dropped"
      (is (some #(= "REASONING_MESSAGE_CONTENT" (:type %)) @frames)))
    (testing "the eval tool really ran and its output came back as a tool result"
      (is (some #(and (= "TOOL_CALL_RESULT" (:type %))
                      (str/includes? (str (:content %)) "3"))
                @frames)))))

;; -------------------------------------------------------------------- inbound
;;
;; apply-frames is the bare minimum of what @ag-ui/client's applier does: accumulate
;; text and reasoning into separate messages, attach tool calls to the open assistant
;; message, and turn results into tool messages. It lives here so the round trip
;; outbound -> client -> inbound is testable offline -- and that round trip is the
;; only thing standing between this harness and a DeepSeek 400 on the second turn.

(defn- patch-by-id [messages id f]
  (mapv (fn [m] (if (= id (:id m)) (f m) m)) messages))

(defn- patch-tool-call [messages id f]
  (mapv (fn [m]
          (if (some #(= id (:id %)) (:toolCalls m))
            (update m :toolCalls #(mapv (fn [tc] (if (= id (:id tc)) (f tc) tc)) %))
            m))
        messages))

(defn- apply-frames [frames]
  (reduce
   (fn [msgs f]
     (let [t (:type f)]
       (cond
         (= t "TEXT_MESSAGE_START")
         (conj msgs {:id (:messageId f) :role "assistant" :content ""})

         (= t "TEXT_MESSAGE_CONTENT")
         (patch-by-id msgs (:messageId f) #(update % :content str (:delta f)))

         (= t "REASONING_MESSAGE_START")
         (conj msgs {:id (:messageId f) :role "reasoning" :content ""})

         (= t "REASONING_MESSAGE_CONTENT")
         (patch-by-id msgs (:messageId f) #(update % :content str (:delta f)))

         (= t "TOOL_CALL_START")
         (patch-by-id msgs (:parentMessageId f)
                      #(update % :toolCalls (fnil conj [])
                               {:id (:toolCallId f) :type "function"
                                :function {:name (:toolCallName f) :arguments ""}}))

         (= t "TOOL_CALL_ARGS")
         (patch-tool-call msgs (:toolCallId f)
                          #(update-in % [:function :arguments] str (:delta f)))

         (= t "TOOL_CALL_RESULT")
         (conj msgs {:id (:messageId f) :role "tool"
                     :toolCallId (:toolCallId f) :content (:content f)})

         :else msgs)))
   []
   frames))

(deftest outbound-then-inbound-preserves-reasoning
  (let [emit   (ag/outbound "thr-1" "run-1")
        frames (atom [])]
    (loop/run! (fake/scripted [{:reasoning "先算一下。" :content ""
                                :tool-calls [{:id "c1" :name "eval" :arguments {:code "(+ 1 2)"}}]}
                               {:content "等于 3"}])
               []
               #(swap! frames into (emit %)))
    (let [client    (apply-frames @frames)
          sent      (ag/inbound client "SYSTEM" nil)
          assistant (first (filter #(and (= "assistant" (:role %)) (:tool_calls %)) sent))]
      (testing "the client really did store reasoning as a message of its own"
        (is (some #(= "reasoning" (:role %)) client)))
      (testing "replaying it carries reasoning_content -- without this DeepSeek answers 400"
        (is (= "先算一下。" (:reasoning_content assistant))))
      (testing "tool calls are renamed to snake_case, arguments intact"
        (is (= [{:id "c1" :type "function"
                 :function {:name "eval" :arguments "{\"code\":\"(+ 1 2)\"}"}}]
               (:tool_calls assistant))))
      (testing "the tool result became a tool message keyed by tool_call_id"
        (is (= {:role "tool" :tool_call_id "c1" :content "3"}
               (last (filter #(= "tool" (:role %)) sent)))))
      (testing "the assistant turn that had no reasoning gets no reasoning_content"
        (is (not (contains? (last (filter #(= "assistant" (:role %)) sent))
                            :reasoning_content))))
      (testing "the system prompt leads"
        (is (= [{:role "system" :content "SYSTEM"}] (vec (take 1 sent))))))))

(deftest drops-activity-and-rebuilds-in-the-provider-shape
  (let [sent (ag/inbound [{:id "a" :role "activity" :activityType "x" :content "nope"}
                          {:id "r" :role "reasoning" :content "why "}
                          {:id "r2" :role "reasoning" :content "not"}
                          {:id "m" :role "assistant" :content "hi" :metadata {:k 1}
                           :toolCalls [{:id "c1" :type "function" :encryptedValue "zz"
                                        :function {:name "read" :arguments "{}"}}]}]
                         "S" nil)]
    (is (= ["system" "assistant"] (mapv :role sent)))
    (is (= "why not" (:reasoning_content (second sent))))
    (is (= [{:id "c1" :type "function" :function {:name "read" :arguments "{}"}}]
           (:tool_calls (second sent))))
    (testing "no AG-UI-only field survives"
      (is (not-any? #(contains? % :metadata) sent))
      (is (not-any? #(contains? % :encryptedValue) sent)))))

(deftest user-content-passes-through-untouched
  (let [parts [{:type "text" :text "看图"} {:type "image" :url "u"}]
        sent  (ag/inbound [{:id "u1" :role "user" :content parts}] "S" nil)]
    (testing "multimodal parts are not flattened by a whitelist"
      (is (= parts (:content (second sent)))))
    (is (not (contains? (second sent) :id)))))

(deftest a-leading-system-message-is-replaced
  (is (= [{:role "system" :content "S"}] (ag/inbound [{:role "system" :content "客户端的"}] "S" nil)))
  (is (= ["S"] (mapv :content (ag/inbound [] "S" nil)))))

(deftest context-is-appended-to-the-prompt
  (let [sent (ag/inbound [] "S" [{:description "repo" :value "lisp-harness"}])]
    (is (str/includes? (:content (first sent)) "repo: lisp-harness"))))

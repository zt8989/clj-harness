(ns harness.edge.ag-ui-test
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.edge.ag-ui :as ag]
            [harness.edge.sessions :as sessions]
            [harness.kernel.event :as ev]
            [harness.fake :as fake]
            [harness.kernel.frames :as frames]
            [harness.kernel.loop :as loop]
            [harness.test-support :as support]
            [harness.wire :as wire]))

(use-fixtures :once support/with-builtins)

(defn- wire [events]
  (let [emit (ag/outbound "thr-1" "run-1")]
    (vec (mapcat emit events))))

(defn- types [frames] (mapv :type frames))

(defn- run-events
  "Drive one scripted run through loop/run-chan and return the kernel events,
  the :run/done terminal dropped."
  [turns]
  (let [ch  (loop/run-chan (fake/scripted turns) [])
        out (atom [])]
    (loop []
      (when-let [ev (async/<!! ch)]
        (when-not (= :run/done (:type ev))
          (swap! out conj ev)
          (recur))))
    @out))

(deftest text-only-turn
  (let [frames (wire [(ev/run-start)
                      (ev/text-delta "he") (ev/text-delta "llo")
                      (ev/run-end)])]
    (is (= ["RUN_STARTED" "TEXT_MESSAGE_START" "TEXT_MESSAGE_CONTENT"
            "TEXT_MESSAGE_CONTENT" "TEXT_MESSAGE_END" "RUN_FINISHED"]
           (types frames)))
    (is (empty? (wire/violations frames)))))

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
    (is (empty? (wire/violations frames)))))

(deftest tool-call-with-no-text-still-gets-a-parent
  (let [frames (wire [(ev/run-start)
                      (ev/tool-call "c1" "read" "{}")
                      (ev/tool-result "c1" "file contents" false)
                      (ev/text-delta "done")
                      (ev/run-end)])]
    (is (empty? (wire/violations frames)))
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

(deftest reasoning-frames-carry-what-the-shipped-schema-demands
  ;; The shipped EventSchemas union is a zod discriminated union, and @ag-ui/client
  ;; parses every event through it BEFORE its applier runs. Two things it insists on
  ;; that the prose docs leave ambiguous: a messageId on the standalone REASONING_START
  ;; and REASONING_END lifecycle frames, and a literal role on REASONING_MESSAGE_START.
  ;; Omitting either is a hard failure in a real client.
  (let [frames  (wire [(ev/run-start) (ev/reasoning-delta "x") (ev/run-end)])
        at      (fn [t] (first (filter #(= t (:type %)) frames)))
        id      (fn [t] (:messageId (at t)))]
    (is (string? (id "REASONING_START")))
    (is (string? (id "REASONING_END")))
    (is (= "reasoning" (:role (at "REASONING_MESSAGE_START"))))
    (is (= "assistant" (:role (at "TEXT_MESSAGE_START"))))
    (testing "every frame in the group agrees on the id"
      (is (apply = (map id ["REASONING_START" "REASONING_MESSAGE_START"
                            "REASONING_MESSAGE_CONTENT" "REASONING_MESSAGE_END"
                            "REASONING_END"]))))
    (testing "a turn of reasoning and nothing else still emits an assistant message"
      ;; Otherwise the reasoning has nothing to fold back onto and DeepSeek 400s.
      (is (= ["RUN_STARTED" "REASONING_START" "REASONING_MESSAGE_START"
              "REASONING_MESSAGE_CONTENT" "REASONING_MESSAGE_END" "REASONING_END"
              "TEXT_MESSAGE_START" "TEXT_MESSAGE_END" "RUN_FINISHED"]
             (types frames))))))

(deftest an-interrupt-closes-the-run-with-an-outcome
  (let [frames (wire [(ev/run-start)
                      (ev/text-delta "wait")
                      (ev/tool-call "c1" "bash" "{\"command\":\"rm -rf /\"}")
                      (ev/run-interrupt [{:id "int-1" :tool-call-id "c1" :name "bash"
                                          :args "{\"command\":\"rm -rf /\"}"}])])
        last-f (last frames)]
    (is (empty? (wire/violations frames)))
    (testing "the terminal frame is still RUN_FINISHED, now carrying the interrupt"
      (is (= "RUN_FINISHED" (:type last-f)))
      (is (= "interrupt" (get-in last-f [:outcome :type])))
      (is (= ["int-1"] (mapv :id (get-in last-f [:outcome :interrupts])))))
    (testing "the interrupt object carries only the keys the shipped schema defines"
      (let [int (first (get-in last-f [:outcome :interrupts]))]
        (is (= #{:id :reason :message :toolCallId} (set (keys int))))
        (is (= "c1" (:toolCallId int)))
        (is (string? (:message int)))))
    (testing "the parked call is announced as a call, and no result follows it"
      (is (= "bash" (:toolCallName (first (filter #(= "TOOL_CALL_START" (:type %)) frames)))))
      (is (not-any? #(= "TOOL_CALL_RESULT" (:type %)) frames)))))

(deftest an-error-closes-whatever-is-open
  (let [frames (wire [(ev/run-start)
                      (ev/reasoning-delta "x") (ev/text-delta "y")
                      (ev/run-error "boom")])]
    (is (= "RUN_ERROR" (last (types frames))))
    (is (empty? (wire/violations frames)))))

(deftest drives-the-real-loop-offline
  (let [emit   (ag/outbound "thr-9" "run-9")
        frames (atom [])]
    (doseq [event (run-events [{:reasoning "想一下" :content ""
                                :tool-calls [{:id "c1" :name "eval" :arguments {:code "(+ 1 2)"}}]}
                               {:content "等于 3"}])]
      (swap! frames into (emit event)))
    (is (empty? (wire/violations @frames)))
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
;; The round trip outbound -> client -> inbound is asserted here using the applier in
;; harness.kernel.frames -- the same one the rebuild endpoint uses to hand a conversation back.
;; Testing the shipped path rather than a test-local copy is the point: that round trip
;; is the only thing standing between this harness and a DeepSeek 400 on the second turn.

(deftest outbound-then-inbound-preserves-reasoning
  (let [emit   (ag/outbound "thr-1" "run-1")
        frames (atom [])]
    (doseq [event (run-events [{:reasoning "先算一下。" :content ""
                                :tool-calls [{:id "c1" :name "eval" :arguments {:code "(+ 1 2)"}}]}
                               {:content "等于 3"}])]
      (swap! frames into (emit event)))
    (let [client    (frames/apply-frames @frames)
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

(deftest content-parts-are-translated-not-passed-through
  ;; A user's message may carry PARTS, and the two protocols spell them
  ;; differently. Passing AG-UI's spelling straight through sends the vendor a
  ;; shape it does not know, and the 400 that comes back names nothing useful.
  (testing "a url image becomes the provider's image_url part"
    (let [sent (ag/inbound [{:id "u1" :role "user"
                             :content [{:type "text" :text "看图"}
                                       {:type "image" :source {:type "url" :value "https://x/y.png"}}]}]
                           "S" nil)
          parts (:content (second sent))]
      (is (= [{:type "text" :text "看图"}
              {:type "image_url" :image_url {:url "https://x/y.png"}}]
             parts))
      (testing "and no AG-UI source wrapper survives"
        (is (not-any? #(contains? % :source) parts)))))
  (testing "an inline data image becomes a data URL, which is how bytes ride the wire"
    (let [sent (ag/inbound [{:id "u1" :role "user"
                             :content [{:type "image"
                                        :source {:type "data" :value "AAAB"
                                                 :mimeType "image/png"}}]}]
                           "S" nil)]
      (is (= [{:type "image_url"
               :image_url {:url "data:image/png;base64,AAAB"}}]
             (:content (second sent))))))
  (testing "a part type this harness cannot carry fails by name rather than leaking"
    (let [e (try (ag/inbound [{:id "u1" :role "user"
                               :content [{:type "document" :source {:type "url"
                                                                    :value "u"}}]}]
                             "S" nil)
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (str/includes? (ex-message e) "document") "names the part it could not carry")
      (is (str/includes? (ex-message e) "image") "and what it can")))
  (testing "an image source this harness cannot resolve fails by name too"
    (let [e (try (ag/inbound [{:id "u1" :role "user"
                               :content [{:type "image" :source {:type "file" :value "x"}}]}]
                             "S" nil)
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (str/includes? (ex-message e) "file")))))

(deftest a-string-content-is-untouched
  ;; The common case, and the one every other test depends on: text in, the same
  ;; text out. Parts are the exception, not the shape.
  (let [sent (ag/inbound [{:id "u1" :role "user" :content "看这个项目"} {:id "a1" :role "assistant"
                                                                        :content "好的"}]
                         "S" nil)]
    (is (= ["看这个项目" "好的"] (mapv :content (rest sent))))
    (is (every? string? (map :content (rest sent))))))

(deftest a-multimodal-assistant-turn-is-translated-too
  ;; Rare, but a rebuilt conversation can contain one, and 'the assistant path
  ;; does not need this' is exactly the kind of assumption that leaks a shape.
  (let [sent (ag/inbound [{:id "a1" :role "assistant"
                           :content [{:type "text" :text "here"}
                                     {:type "image" :source {:type "url" :value "https://x/i.png"}}]}]
                         "S" nil)]
    (is (= [{:type "text" :text "here"}
            {:type "image_url" :image_url {:url "https://x/i.png"}}]
           (:content (second sent))))))

(deftest undeclared-input-reads-what-a-run-is-about-to-send
  (testing "a text-only message carries text"
    (is (= #{:text} (ag/carried-input-types [{:role "user" :content "hi"}]))))
  (testing "parts contribute their own modalities"
    (is (= #{:text :image}
           (ag/carried-input-types [{:role "user"
                                     :content [{:type "text" :text "x"}
                                               {:type "image" :source {:type "url" :value "u"}}]}]))))
  (testing "an image with no text still carries only what it carries"
    (is (= #{:image}
           (ag/carried-input-types [{:role "user"
                                     :content [{:type "image" :source {:type "url" :value "u"}}]}]))))
  (testing "only USER messages count -- the model cannot be blamed for its own words"
    (is (= #{:text}
           (ag/carried-input-types [{:role "assistant"
                                     :content [{:type "image" :source {:type "url" :value "u"}}]}
                                    {:role "tool" :content "x"}
                                    {:role "user" :content "hello"}])))))

(deftest undeclared-input-answers-what-a-model-did-not-declare
  (let [with-image [{:role "user" :content [{:type "text" :text "x"}
                                            {:type "image" :source {:type "url" :value "u"}}]}]
        text-only  [{:role "user" :content "just words"}]]
    (testing "an undeclared modality aimed at a text-only model is reported"
      (is (= [:image] (ag/undeclared-input with-image #{:text}))))
    (testing "a modality the model does declare is fine"
      (is (= [] (ag/undeclared-input with-image #{:text :image}))))
    (testing "text alone never trips it"
      (is (= [] (ag/undeclared-input text-only #{:text}))))
    (testing "NOTHING DECLARED MEANS NOTHING GUARDED"
      ;; The load-bearing case. A model that never said what it accepts is not
      ;; silently assumed to accept text and nothing else -- that would make every
      ;; inline provider start failing runs for a rule nobody wrote down.
      (is (= [] (ag/undeclared-input with-image nil))))))

(deftest a-leading-system-message-is-replaced
  (is (= [{:role "system" :content "S"}] (ag/inbound [{:role "system" :content "客户端的"}] "S" nil)))
  (is (= ["S"] (mapv :content (ag/inbound [] "S" nil)))))

(deftest the-session-s-context-rides-in-the-conversation
  (let [sent (ag/inbound [{:id "u1" :role "user" :content "hi"}]
                         "S" [{:description "repo" :value "clj-harness"}])]
    ;; The system prompt is FROZEN -- the provider's prefill (prompt cache) keys
    ;; on it, so the session's opening context must never touch it.
    (is (= "S" (:content (first sent))))
    (testing "the context is one more message of the conversation, behind what it already held"
      ;; WHAT MOVED, AND WHY (ticket 03 of .scratch/sessions-live-on-the-server). This
      ;; used to be rendered as the LAST message of every run: the client sent the
      ;; context again on every turn, so the prefix the provider caches grew a new tail
      ;; each time and the cache missed it. It is now a message the SESSION is born with
      ;; (`context-entry`), which the edge puts in the conversation ONCE -- so the
      ;; converter here is being handed a conversation that already begins with it.
      (is (= ["system" "user" "user"] (mapv :role sent)))
      (is (= {:role "user" :content "- repo: clj-harness"} (last sent)))
      (is (nil? (:id (last sent)))
          "and the id it enters the conversation under is the conversation's business:
           a message's AG-UI id never reaches a vendor"))
    (testing "with no context nothing is appended"
      (is (= ["system" "user"]
             (mapv :role (ag/inbound [{:id "u1" :role "user" :content "hi"}] "S" nil)))))))

(deftest the-opening-enters-the-conversation-once-in-front-of-the-question
  ;; `.scratch/session-opening`. The blocks used to arrive as an argument and be spliced
  ;; in AFTER the conversation; they are now entries the conversation is born with
  ;; (`opening-entries`), so every run after the birth reads them in front of the
  ;; question -- and nothing is appended behind the conversation for a run's own answer
  ;; to land after.
  (let [blocks  [{:role "user" :content "<instructions path=\"/h/AGENTS.md\">\nrule\n</instructions>"}
                 {:role "user" :content "<skills>\n- t: t\n</skills>"}]
        entries (ag/opening-entries blocks)]
    (testing "one entry per block, numbered, in the order cap.preamble decided"
      (is (= ["session-opening-0" "session-opening-1"] (mapv :id entries)))
      (is (= ["user" "user"] (mapv :role entries)))
      (is (every? ag/opening-entry? entries))
      (is (not (ag/opening-entry? {:id "u1" :role "user" :content "hi"}))
          "a client's own message is not an opening entry"))
    (testing "one message, two readings: the card for the screen, the text for the model"
      (is (= (:content (first blocks))
             (-> entries first :content second :text))
          "the text half is the block, byte for byte")
      (is (= {:type "data" :name ag/injected-part-name
              :data {:role "user" :text (:content (first blocks))}}
             (first (:content (first entries))))
          "the card half is the shape the UI already parses, under the frame's own name"))
    (testing "the model view drops the card and keeps the text"
      ;; `sessions/model-view` is the one reader that decides this, and the opening
      ;; entry is the message that carries both halves on purpose.
      (let [view (sessions/model-view entries)]
        (is (= ["user" "user"] (mapv :role view)))
        (is (= "<instructions path=\"/h/AGENTS.md\">\nrule\n</instructions>"
               (-> view first :content first :text)))
        (is (not-any? #(some (fn [p] (= "data" (:type p))) (:content %)) view)
            "a data part must never reach a provider -- provider-part refuses one by name")))
    (testing "so the provider vector reads: system, the opening, then the conversation"
      (let [sent (ag/inbound (into (sessions/model-view entries)
                                   [{:id "u1" :role "user" :content "hi"}])
                             "S" nil)]
        (is (= ["system" "user" "user" "user"] (mapv :role sent)))
        (is (= "S" (:content (first sent))))
        (is (= "<instructions path=\"/h/AGENTS.md\">\nrule\n</instructions>"
               (-> sent second :content first :text))
            "the standing rules stand in front of the question")
        (is (= "hi" (:content (nth sent 3)))
            "and the client's own turn comes after the whole opening -- nothing of ours
             is spliced behind it"))
      (let [sent (ag/inbound (sessions/model-view entries) "S" nil)]
        (is (= 1 (count (filter #(= "system" (:role %)) sent)))
            "and the frozen system prompt is still the only system message")))))

(deftest a-field-the-client-carried-itself-is-not-dropped
  ;; The whitelist rebuild used to keep only the FOLDED reasoning (a preceding
  ;; `reasoning`-role message) and throw away a field carried on the assistant
  ;; message itself. Both are the same fact stated by different clients, and the
  ;; second is what a thinking-mode vendor insists on getting back.
  (let [inbound (fn [msgs] (ag/inbound msgs "SYS" nil))
        assistant (fn [msgs] (first (filter #(= "assistant" (:role %)) (inbound msgs))))]

    (testing "carried on the message, with no reasoning message before it"
      (let [m (assistant [{:role "user" :content "hi"}
                          {:role "assistant" :content "hello"
                           :reasoning_content "I looked it up"}])]
        (is (= "I looked it up" (:reasoning_content m))
            "the field survives the rebuild")))

    (testing "an EMPTY field is a statement too, and survives as one"
      (let [m (assistant [{:role "user" :content "hi"}
                          {:role "assistant" :content "hello" :reasoning_content ""}])]
        (is (= "" (:reasoning_content m))
            "the vendor said 'this round had no reasoning' -- the client passes that on")))

    (testing "folded from the neighbour, as before"
      (let [m (assistant [{:role "user" :content "hi"}
                          {:role "reasoning" :content "I looked it up"}
                          {:role "assistant" :content "hello"}])]
        (is (= "I looked it up" (:reasoning_content m)))))

    (testing "both at once: the message's own field wins"
      ;; It is the more specific statement -- that message's reasoning, rather than
      ;; its neighbour's -- and the rule is written down here rather than left to
      ;; whichever branch a future edit happens to run first.
      (let [m (assistant [{:role "user" :content "hi"}
                          {:role "reasoning" :content "the neighbour's words"}
                          {:role "assistant" :content "hello"
                           :reasoning_content "my own words"}])]
        (is (= "my own words" (:reasoning_content m)))))

    (testing "and nothing is invented when neither is there"
      (is (not (contains? (assistant [{:role "user" :content "hi"}
                                      {:role "assistant" :content "hello"}])
                          :reasoning_content))))))

(deftest the-first-thing-said-is-the-first-user-message-of-the-run
  ;; `first-user-text`: what a session gets named after. It is handed the conversation's
  ;; own messages -- a run body no longer carries them (ADR 0002 decision 9), so the
  ;; caller is the one that holds the conversation -- and that is what makes the answer
  ;; stable across turns: 'the first user message' has to mean the first one the CLIENT
  ;; sent rather than the newest, since the opening context this edge splices in is a
  ;; user message too.
  (testing "a plain first turn"
    (is (= "把侧边栏的标题改成会话标题"
           (ag/first-user-text [{:role "user" :content "把侧边栏的标题改成会话标题"}]))))
  (testing "the FIRST user turn, not the newest one"
    ;; On the fortieth turn the sequence holds the whole conversation, and the name it
    ;; yields is still the name the session was given on the first.
    (is (= "第一句"
           (ag/first-user-text [{:role "system" :content "you are a harness"}
                                {:role "user" :content "第一句"}
                                {:role "assistant" :content "好的"}
                                {:role "user" :content "第四十句"}]))))
  (testing "an empty turn is skipped rather than named"
    ;; 'The first thing they said' is the first message that SAYS something, which is
    ;; what 'the first thing they said' means when the first thing was an empty line.
    (is (= "真的第一句"
           (ag/first-user-text [{:role "user" :content "   "}
                                {:role "user" :content "真的第一句"}])))
    (is (nil? (ag/first-user-text [{:role "system" :content "you are a harness"}
                                   {:role "user" :content "   "}]))
        "nothing was said, so there is no name"))
  (testing "the opening context is not something a person said"
    ;; It IS an ordinary user message (`context-entry` says why), and a conversation with
    ;; no client turn at all must not be named after the harness's own block.
    (is (= "真的第一句"
           (ag/first-user-text [{:id ag/context-entry-id :role "user"
                                 :content "- repo: this one"}
                                {:role "user" :content "真的第一句"}])))
    (is (nil? (ag/first-user-text [{:id ag/context-entry-id :role "user"
                                    :content "- repo: this one"}]))
        "the context alone is not a name"))
  (testing "and neither are the opening blocks"
    ;; `.scratch/session-opening`: the instruction files and the skills catalog are user
    ;; messages in the conversation now, so a name taken from the first user turn would
    ;; otherwise become an excerpt of AGENTS.md.
    (is (= "真的第一句"
           (ag/first-user-text (into (ag/opening-entries
                                      [{:role "user" :content "<instructions path=\"/h/AGENTS.md\">\nrule\n</instructions>"}
                                       {:role "user" :content "<skills>\n- t: t\n</skills>"}])
                                     [{:role "user" :content "真的第一句"}]))))
    (is (nil? (ag/first-user-text (ag/opening-entries
                                   [{:role "user" :content "<instructions path=\"/h/AGENTS.md\">\nrule\n</instructions>"}])))
        "a conversation opened with nothing but its opening has no name yet"))
  (testing "content parts contribute their text and nothing else"
    (is (= "看看这张图\n第二行"
           (ag/first-user-text [{:role "user"
                                 :content [{:type "text" :text "看看这张图"}
                                           {:type "image" :data "AAAA"}
                                           {:type "text" :text "第二行"}]}])))
    (is (nil? (ag/first-user-text [{:role "user" :content [{:type "image" :data "AAAA"}]}]))
        "an image is not a name"))
  (testing "a conversation with no messages at all, or none from a person"
    (is (nil? (ag/first-user-text [])))
    (is (nil? (ag/first-user-text nil)))
    (is (nil? (ag/first-user-text [{:role "assistant" :content "我该说什么"}]))))
  (testing "whitespace is trimmed away on both ends"
    (is (= "贴着边的一句" (ag/first-user-text [{:role "user" :content "\n  贴着边的一句  \n\n"}]))))
  (testing "and a first message long past any title is clipped at a length a store may hold"
    ;; The storage guard, not the display rule (the client clips shorter still). It
    ;; counts CODEPOINTS: clipping UTF-16 units would cut this emoji in half and put
    ;; a lone surrogate in the database.
    (let [long (apply str (repeat 500 "あ"))]
      (is (= 200 (count (ag/first-user-text [{:role "user" :content long}])))))
    (let [emoji (str (apply str (repeat 199 "a")) "😀😀")]
      (is (= (str (apply str (repeat 199 "a")) "😀")
             (ag/first-user-text [{:role "user" :content emoji}]))
          "200 code points: the 199 a's and ONE emoji, whole"))))

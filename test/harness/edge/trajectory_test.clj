(ns harness.edge.trajectory-test
  "What the model saw, asserted over HAND-WRITTEN RECORDS.

  Same reason as harness.edge.stats-test: the interesting cases are the ones a run
  will not produce on request -- a session that parked and resumed, a tool call a
  human vetoed, an injected block, a system message that changed between turns -- and
  a record is four keys the test writes itself. The route is then exercised once, over
  real HTTP, because the one thing hand-written records cannot show is that the bytes
  on screen are the bytes the edge wrote."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.cap.providers :as providers]
            [harness.edge.ag-ui :as ag]
            [harness.edge.http :as http]
            [harness.edge.replay :as replay]
            [harness.edge.stats :as stats]
            [harness.edge.trajectory :as trajectory]
            [harness.fake :as fake]
            [harness.test-support :as support]
            [harness.infra.home :as home]
            [harness.kernel.tools :as tools]
            [harness.kernel.frames :as frames])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

;; ------------------------------------------------------------------ the records

(defn- record
  "One ROW of the record, as the file spells it and as every reader now sees it: `message`
  and `event` are the two types, `ts`/`runId` ride the envelope, and a harness FACT -- a
  provider change, a tool's three moments, a model call's start and end -- is an `event`
  carrying a CUSTOM frame named after it. KIND is the reader's answer (`replay/kind`):
  `message`, `event`, or that fact's name, which is why a fixture reads the way an
  assertion does."
  ([ts kind payload] (record ts "r1" kind payload))
  ([ts run-id kind payload]
   (if (= "message" kind)
     {:ts ts :runId run-id :type "message" :payload payload}
     {:ts ts :runId run-id :type "event"
      :payload (if (= "event" kind)
                 payload
                 {:type "CUSTOM" :name kind :value payload})})))

(defn- row-json
  "A ROW -> the line the writer would put on disk. ONE SHAPE NOW (`.scratch/jsonl-two-kinds`
  票 02): the reader holds the file's own row, so a fixture that was already built by `record`
  needs no translation to be written -- which is the seam this ticket removed."
  [row]
  (json/write-str row))

(declare system-prompt)

(defn- input
  "The lines ONE ACTION writes (`.scratch/jsonl-two-kinds` 票 02): the system message the run
  was handed -- the row that OPENS a run (`replay/system-prompt?`) -- and then one `message`
  row per message the person sent, each carrying its own identity on the ENVELOPE (`:id`) and
  the verbatim provider message (id stripped) as the payload.

  A BLOCK OF ROWS, not one: the record has no line that carries a conversation any more, so a
  fixture that wants one splices this block and `rows` below keeps the fixture flat."
  [ts & msgs]
  (into [(system-prompt ts "You are a coding agent.")]
        (map (fn [m]
               (cond-> (record ts "message" (dissoc m :id))
                 ;; THE EDGE'S OWN RULE, not a second one invented here: a message an
                 ;; action brought is the client's unless the session wrote it
                 ;; (`http/entry-source`, and `ag/injected?` is its reader).
                 (:id m) (assoc :id (:id m) :source (http/entry-source m))))
             msgs)))

(defn- user [id text] {:id id :role "user" :content text})
(defn- assistant [text] {:role "assistant" :content text})
(defn- tool-msg [call-id content] {:role "tool" :tool_call_id call-id :content content})

(defn- message
  "A `message` row for one message a RUN put in the array -- what the model returned or a
  tool answered. The payload is the provider message verbatim and the envelope carries the
  `source` the writer computes for it (`http/returned-source`), so a fixture says the same
  thing the record would."
  [ts m]
  (assoc (record ts "message" m) :source (http/returned-source m)))

(defn- system-prompt
  "The system message as the record holds it (owner, 2026-09-21): a `message` row LIKE EVERY
  OTHER element of the array the model was handed, whose envelope says the prompt put it
  there (`:source` = `system-prompt`) and names those bytes (`:hash`). The payload stays the
  provider's own map, verbatim, as every message row's does."
  [ts text]
  (assoc (record ts "message" {:role "system" :content text})
         :source "system-prompt" :hash "h"))

(defn- client
  "A `message` row for one message the CLIENT sent: the verbatim provider message (id
  stripped) as the payload, and the envelope carrying the id the session dedupes by plus
  the `source` that says a person put these bytes in the array."
  [ts m]
  (cond-> (record ts "message" (dissoc m :id))
    (:id m) (assoc :id (:id m) :source "client")))

(defn- opening
  "One of the conversation's OPENING blocks as its birth writes it: a `message` row whose id
  is the one `ag/opening-entry?` names, `source` = `opening`. ENTRY -- it has an id -- which
  is what tells it from the same file re-read by a later run (`derived`)."
  [ts i text]
  (assoc (record ts "message" {:role "user" :content text})
         :id (str "session-opening-" i) :source "opening"))

(defn- birth-context
  "The session's own CONTEXT entry (`ag/context-entry-id`), written where the conversation is
  born: an entry, so it carries its id."
  [ts text]
  (assoc (record ts "message" {:role "user" :content text})
         :id ag/context-entry-id :source "injection"))

(defn- derived
  "A block a RUN derived for itself -- a skill body, a job's ending, a re-read of the context
  or of an instruction file -- as the writer logs it: no id (nothing appended it to the
  conversation) and the `source` its provenance gives it."
  [ts source text]
  (assoc (record ts "message" {:role "user" :content text}) :source source))

(defn- rows
  "A FIXTURE -> the rows it is made of, flat. A map is ONE row; a collection is rows to be
  spliced (which is what `input` answers, so a fixture can read as one action's lines rather
  than as a list of lists)."
  [records]
  (vec (mapcat #(if (map? %) [%] %) records)))

(defn- tool-call
  "A provider-shaped tool call inside an assistant message."
  [id name args-json]
  {:id id :type "function" :function {:name name :arguments args-json}})

(defn- frame [ts type payload] (record ts "event" (merge {:type type} payload)))
(def ^:private finished (frame 90 "RUN_FINISHED" {:threadId "t" :runId "r1"}))

(defn- pre-execute
  ([ts call-id name] (pre-execute ts call-id name nil))
  ([ts call-id name outcome]
   (record ts "tools/pre-execute"
           (cond-> {:toolCallId call-id :toolName name}
             outcome (assoc :outcome outcome)))))

(defn- executed [ts call-id name] (record ts "tools/execute" {:toolCallId call-id :toolName name}))
(defn- post-execute [ts call-id name] (record ts "tools/post-execute" {:toolCallId call-id :toolName name}))

(defn- turns-of
  "RECORDS -> the folded turns. A collection, not loose arguments: a record is a map,
  and a map splatted as arguments becomes its ENTRIES."
  [records]
  (:turns (trajectory/records->trajectory (rows records))))

(defn- items-of [turns] (mapv :items turns))

(defn- kinds [turn] (mapv :kind (:items turn)))

(defn- item-of
  "The first item of KIND in TURN -- the tests are about one item at a time."
  [turn kind]
  (first (filter #(= kind (:kind %)) (:items turn))))

;; --------------------------------------------------------------------- the fold

(def ^:private item-keys
  "Every key the inventory allows on an item. The check is a SUBSET, not an equality:
  a turn with no injected context has no `:source` anywhere, and 'the vendor reported
  no reasoning' is a missing key rather than an empty string."
  #{:kind :text :initial :tools :id :reasoning :toolCallId :name :argsText
    :result :error :executed :outcome :call
    :arrivedAt :resumedAt :executedAt :closedAt :at})

(deftest one-turn-carries-what-the-model-saw
  ;; The whole point of the view, in one turn: the system message the client never
  ;; holds, the user's own words, and the tool call with BOTH halves -- the name and
  ;; arguments from the call side, the result from the answer.
  (let [[turn] (turns-of
                [(client 0 (user "u1" "读一下 README"))
                 (system-prompt 10 "You are a coding agent.")
                 (pre-execute 20 "c1" "read")
                 (executed 21 "c1" "read")
                 (post-execute 25 "c1" "read")
                 finished
                 (message 30 {:role "assistant" :content ""
                              :reasoning_content "先看一眼。"
                              :tool_calls [(tool-call "c1" "read" "{\"path\":\"README.md\"}")]})
                 (message 31 (tool-msg "c1" "# clj-harness"))
                 (message 32 (assistant "它是个 Clojure 内核。"))])]
    (is (= 1 (:index turn)))
    (is (= ["system" "user" "assistant" "tool" "assistant"] (kinds turn))
        "the record's own order: system, the client's message, then the output")
    (is (= "You are a coding agent." (:text (item-of turn "system"))))
    (is (true? (:initial (item-of turn "system"))) "the first one is the initial prompt")
    (is (= {:kind "user" :id "u1" :text "读一下 README" :at 0} (item-of turn "user"))
        "the message is dated by the input record's own :ts -- a moment, not a span")
    (is (= "先看一眼。" (:reasoning (first (filter #(= "assistant" (:kind %)) (:items turn))))))
    (is (= {:kind "tool" :toolCallId "c1" :name "read"
            :argsText "{\"path\":\"README.md\"}" :result "# clj-harness"
            :executed true :outcome "pass"
            :arrivedAt 20 :executedAt 21 :closedAt 25}
           (item-of turn "tool"))
        "name and arguments come from the call side, the result from the answer side")))

(deftest the-item-keys-are-a-closed-set
  ;; The record inventory (spec § 记录清单) is closed: every field on the wire points
  ;; at a line the record actually has. An assertion rather than a convention, because
  ;; a field that appeared from nowhere is exactly the thing this feature must not do.
  (let [[turn] (turns-of
                [(client 0 (user "u1" "hi"))
                 (system-prompt 10 "S")
                 finished
                 (message 20 (assistant "ok"))])]
    (is (= #{:kind :text :initial} (set (keys (item-of turn "system")))))
    (is (= #{:kind :id :text :at} (set (keys (item-of turn "user")))))
    (is (= #{:kind :text} (set (keys (item-of turn "assistant"))))
        "no reasoning key and no :call pointer: the vendor reported none, and this record
         has no model lines to point at -- absent, not empty")))

(deftest injected-context-is-context-and-lands-where-it-arrived
  (testing "opening blocks come before the client's message; the run's own context after it"
    (let [[turn] (turns-of
                  [(system-prompt 0 "S")
                   (opening 11 0 "<instructions path=\"AGENTS.md\">rules</instructions>")
                   (opening 12 1 "<skills>a catalog</skills>")
                   (client 13 (user "u1" "hi"))
                   (derived 14 "injection" "- project: clj-harness")
                   finished])]
      (is (= ["system" "context" "context" "user" "context"] (kinds turn))
          "the record's order, not the reference screenshot's: the blocks really do come first")
      (is (= 3 (count (filter #(= "context" (:kind %)) (:items turn))))
          "three injected blocks -- and the kind says nothing about where they sat")
      (is (= "<skills>a catalog</skills>" (:text (second (filter #(= "context" (:kind %)) (:items turn))))))))

  (testing "a skill body the model asked for mid-run is context too, where it landed"
    (let [[turn] (turns-of
                  [(client 0 (user "u1" "load it"))
                   (system-prompt 10 "S")
                   finished
                   (message 20 {:role "assistant" :content ""
                                :tool_calls [(tool-call "c1" "skill" "{\"name\":\"tdd\"}")]})
                   (message 21 (tool-msg "c1" "loaded"))
                   (message 22 (user "" "<skill name=\"tdd\">red green refactor</skill>"))
                   (message 23 (assistant "got it"))])]
      (is (= ["system" "user" "assistant" "tool" "context" "assistant"] (kinds turn)))
      (is (str/starts-with? (:text (item-of turn "context")) "<skill name=\"tdd\">")
          "the body, as the bytes it is"))))

(deftest a-job-ending-is-injected-context-too
  ;; THE OTHER SHAPE A TAIL USER MESSAGE COMES IN. A skill body is one; the ending of a
  ;; background job (`harness.cap.jobs/before-llm`) is another, and the reader does not
  ;; need to know which it is -- it shows the bytes and where they landed, which is the
  ;; whole reason a new kind of injection costs this view nothing.
  (testing "a notice in the returned tail is context, where it actually landed"
    (let [[turn] (turns-of
                  [(client 0 (user "u1" "\u5f00\u5de5"))
                   (system-prompt 10 "S")
                   finished
                   (message 20 {:role "assistant" :content ""
                                :tool_calls [(tool-call "c1" "job"
                                                        "{\"command\":\"make\"}")]})
                   (message 21 (tool-msg "c1" (str "job j1 started; read it with `job_output "
                                                  "{\"job\": \"j1\"}`.")))
                   (message 22 (user "" (str "<job-ended id=\"j1\">[exit 0]</job-ended>\n"
                                             "<command>make</command>\n"
                                             "Read what it said with job_output {\"job\": \"j1\"}.")))
                   (message 23 (assistant "noted"))])]
      (is (= ["system" "user" "assistant" "tool" "context" "assistant"] (kinds turn)))
      (is (= (str "<job-ended id=\"j1\">[exit 0]</job-ended>\n"
                  "<command>make</command>\n"
                  "Read what it said with job_output {\"job\": \"j1\"}.")
             (:text (item-of turn "context")))
          "the bytes, verbatim -- the command, the ending, and the one line that reads it")))

  (testing "and two jobs are two blocks, because the ids are part of the bytes"
    (let [[turn] (turns-of
                  [(client 0 (user "u1" "\u5f00\u5de5"))
                   (system-prompt 10 "S")
                   finished
                   (message 20 (user "" (str "<job-ended id=\"j1\">[exit 0]</job-ended>\n"
                                             "<command>make</command>\n"
                                             "Read what it said with job_output {\"job\": \"j1\"}.")))
                   (message 21 (user "" (str "<job-ended id=\"j2\">[stopped]</job-ended>\n"
                                             "<command>make test</command>\n"
                                             "Read what it said with job_output {\"job\": \"j2\"}.")))])]
      (is (= 2 (count (filter #(= "context" (:kind %)) (:items turn))))
          "the de-duplication is by bytes, and these are different bytes"))))

(deftest an-injection-is-drawn-in-every-run-that-carried-it
  ;; THE RECORD IS THE TRUTH, so this view draws every `message` row a run carried, in the
  ;; run that carried it. A block two runs carried is drawn twice; hiding the second would
  ;; conceal a real re-reading from the one reader who is here to see it (owner, 2026-09-24:
  ;; 轨迹要真实还原 jsonl 的 kind=message，不隐瞒).
  (testing "the opening blocks a later run restates are drawn again, in that run"
    (let [turns (turns-of
                 [(system-prompt 10 "S")
                  (message 11 (user "" "<instructions>rules</instructions>"))
                  (message 12 (user "" "<skills>a catalog</skills>"))
                  (client 0 (user "u1" "first"))
                  finished
                  (system-prompt 110 "S")
                  (message 111 (user "" "<instructions>rules</instructions>"))
                  (message 112 (user "" "<skills>a catalog</skills>"))
                  (client 100 (user "u2" "second"))
                  finished])]
      (is (= 2 (count turns)))
      (is (= ["system" "context" "context" "user"] (kinds (first turns))))
      (is (= ["context" "context" "user"] (kinds (second turns)))
          "the same two blocks, because this run carried them again")))

  (testing "a block whose bytes changed is drawn as the bytes this run carried"
    (let [turns (turns-of
                 [(system-prompt 10 "S")
                  (message 11 (user "" "<instructions>rules</instructions>"))
                  (client 0 (user "u1" "first"))
                  finished
                  (system-prompt 110 "S")
                  (message 111 (user "" "<instructions>rules and more</instructions>"))
                  (client 100 (user "u2" "second"))
                  finished])]
      (is (= ["context" "user"] (kinds (second turns))))
      (is (= "<instructions>rules and more</instructions>"
             (:text (item-of (second turns) "context")))
          "the edited file is what this turn really carried; hiding the change would be the
           one thing worse than repeating it")))

  (testing "the run's own trailing context obeys the same rule"
    (let [turns (turns-of
                 [(system-prompt 10 "S")
                  (client 0 (user "u1" "first"))
                  (message 12 (user "" "- project: clj-harness"))
                  finished
                  (system-prompt 110 "S")
                  (client 100 (user "u2" "second"))
                  (message 113 (user "" "- project: clj-harness"))
                  finished])]
      (is (= ["system" "user" "context"] (kinds (first turns))))
      (is (= ["user" "context"] (kinds (second turns)))
          "the same trailing context, drawn again because this run carried it"))))

(deftest a-derived-block-is-not-the-client-s-own-words
  ;; The `/name` that asked for a body is in the conversation already, so every later run
  ;; re-derives the body and splices it in beside the client's own message. The alignment
  ;; that asks 'which of the array's user messages did this run BRING' must not hand the
  ;; client's words to the injected pile: a reader would see the person's own question
  ;; drawn as something the server added.
  (let [turns (turns-of
               [(system-prompt 10 "S")
                (client 0 (user "u1" "/alpha fix the bug"))
                (message 11 (user "" "<skills>a catalog</skills>"))
                (message 13 (user "" "<skill name=\"alpha\">ALPHA BODY</skill>"))
                finished
                (message 20 (assistant "done"))
                (system-prompt 110 "S")
                (client 100 (user "u2" "and another thing"))
                (message 111 (user "" "<skills>a catalog</skills>"))
                (message 113 (user "" "<skill name=\"alpha\">ALPHA BODY</skill>"))
                finished
                (message 120 (assistant "right"))])
        [one two] turns
        ctx (fn [turn] (filter #(= "context" (:kind %)) (:items turn)))]
    (is (= 2 (count turns)))
    (is (= ["system" "user" "context" "context" "assistant"] (kinds one))
        "the person's message first -- it is what the run brought -- then the blocks it derived")
    (is (= 2 (count (ctx one)))
        "the blocks, then the body the ask put there -- one kind, no source")
    (is (= ["user" "context" "context" "assistant"] (kinds two))
        "the run restated both blocks, so both are drawn -- and the client's own message
         is still not one of them (which is this test's point)"))

  (testing "a body nobody has shown yet lands with the turn that carried it"
    (let [turns (turns-of
                 [(system-prompt 10 "S")
                  (client 0 (user "u1" "/alpha fix the bug"))
                  finished
                  (system-prompt 110 "S")
                  (message 112 (user "" "<skill name=\"alpha\">ALPHA BODY</skill>"))
                  (client 100 (user "u2" "go on"))
                  finished])]
      (is (= ["context" "user"] (kinds (second turns))))
      (is (= "<skill name=\"alpha\">ALPHA BODY</skill>"
             (:text (item-of (second turns) "context")))
          "before this turn's own message -- the nearest true anchor left"))))

(deftest history-is-not-listed-a-second-time
  ;; The client restates its whole history on every run; only what is NEW is the
  ;; turn's own material. Listing the restatement would show every message once per
  ;; run, and the second turn would look like it said everything twice.
  (let [turns (turns-of
               [(system-prompt 10 "S")
                (client 0 (user "u1" "first"))
                finished
                (message 20 (assistant "one"))
                ;; a client retrying what it already said: the SAME id, the same bytes -- the
                ;; session holds that entry once (`sessions/append!`), and the fold here must
                ;; not turn the repeat into this turn's material either
                (system-prompt 110 "S")
                (client 100 (user "u1" "first"))
                (client 100 (user "u2" "second"))
                finished
                (message 120 (assistant "two"))])]
    (is (= 2 (count turns)))
    (is (= ["system" "user" "assistant"] (kinds (first turns))))
    (is (= ["user" "assistant"] (kinds (second turns)))
        "the retransmitted history is not this turn's material")))

(deftest a-resume-continues-the-parked-turn
  ;; A park/resume hands the same conversation to the model AGAIN under the same runId and
  ;; brings no new user message: the prompt row is written once more, which is what says a
  ;; second array went out. It is the same turn -- and its injections are drawn as the resume carried them.
  (let [turns (turns-of
               [(system-prompt 10 "S")
                (message 11 (user "" "<instructions>rules</instructions>"))
                (client 0 (user "u1" "读 /etc/hosts"))
                (pre-execute 20 "c1" "read" "needs-approval")
                (post-execute 21 "c1" "read")
                (frame 30 "RUN_FINISHED" {:threadId "t" :runId "r1"})
                (message 40 {:role "assistant" :content ""
                             :tool_calls [(tool-call "c1" "read" "{\"path\":\"/etc/hosts\"}")]})
                ;; the human vetoed it, so the run goes out again over the same conversation
                (system-prompt 110 "S")
                (message 111 (user "" "<instructions>rules</instructions>"))
                (message 113 {:role "assistant" :content ""})
                (pre-execute 120 "c1" "read" "vetoed")
                (post-execute 121 "c1" "read")
                finished
                (message 130 (tool-msg "c1" "vetoed by human: the call was not executed."))
                (message 131 (assistant "已经被拦住了。"))])]
    (is (= 1 (count turns)) "one user message, one turn -- the resume opens none")
    (is (= ["system" "context" "user" "assistant" "context" "tool" "assistant"] (kinds (first turns))))
    (is (= 2 (count (filter #(= "context" (:kind %)) (:items (first turns)))))
        "the retransmitted instruction block is drawn twice: the resume really carried it again")
    (let [called (item-of (first turns) "tool")]
      (is (false? (:executed called)) "no execute line: it never ran")
      (is (= "vetoed" (:outcome called)) "the LAST verdict is the one that stuck"))))

(deftest a-vetoed-call-is-not-a-call-that-took-no-time
  ;; The gap between the lines is the story: 'it arrived' and 'it ran' are different
  ;; facts, and only the second one is a duration.
  (let [[turn] (turns-of
                [(client 0 (user "u1" "hi"))
                 (system-prompt 10 "S")
                 (pre-execute 20 "c1" "bash" "vetoed")
                 (post-execute 21 "c1" "bash")
                 finished
                 (message 30 {:role "assistant" :content "" :tool_calls [(tool-call "c1" "bash" "{}")]})
                 (message 31 (tool-msg "c1" "vetoed by human"))])]
    (is (false? (:executed (item-of turn "tool"))))
    (is (= "vetoed" (:outcome (item-of turn "tool"))))
    (is (not (contains? (item-of turn "tool") :startedAt))
        "no timing on the tool item at all -- durations are the model call's business (ticket 05)")))

(deftest the-system-message-appears-again-only-when-it-changes
  (let [turns (turns-of
               [(system-prompt 10 "S1")
                (client 0 (user "u1" "first"))
                finished
                (system-prompt 110 "S1")
                (client 100 (user "u2" "second"))
                finished
                ;; the third run's system message has grown a block
                (system-prompt 210 "S1\n<project>clj-harness</project>")
                (client 200 (user "u3" "third"))
                finished])]
    (is (= 3 (count turns)))
    (is (= ["system" "user"] (kinds (first turns))))
    (is (= ["user"] (kinds (second turns))) "the same bytes are not shown twice")
    (is (= ["system" "user"] (kinds (nth turns 2))) "different bytes: the change is visible")
    (is (not (contains? (item-of (nth turns 2) "system") :initial))
        "only the first one is the initial prompt")))

(deftest every-run-carries-the-prompt-it-was-handed
  ;; THE PROMPT IS A MESSAGE ROW (owner, 2026-09-21: a `message` row IS an element of the
  ;; messages array the model was handed, and the prompt is that array's first element). So
  ;; each run's submitted side starts with ITS OWN copy of those bytes -- no carry-forward, no
  ;; synthesis: a reader asks the row. `.scratch/jsonl-two-kinds` 票 02's whole point is that
  ;; what is on disk is what was handed over.
  (let [records [(system-prompt 10 "S1")
                 (client 0 (user "u1" "first"))
                 finished
                 (system-prompt 110 "S1\n<project>moved</project>")
                 (client 100 (user "u2" "second"))
                 finished]
        runs    (trajectory/run-segments records)]
    (is (= "S1" (:content (first (:submitted (first runs)))))
        "the first element of the array the model read")
    (is (= "S1\n<project>moved</project>" (:content (first (:submitted (second runs)))))
        "and the second run's row is what THAT run was handed -- the prompt moved, and the row says so")))

(deftest an-input-that-brings-two-user-messages-brings-two-turns
  (let [turns (turns-of
               [(client 0 (user "u1" "a"))
                (client 0 (user "u2" "b"))
                (system-prompt 10 "S")
                finished
                (message 20 (assistant "answered"))])]
    (is (= 2 (count turns)))
    (is (= ["system" "user"] (kinds (first turns))))
    (is (= ["user" "assistant"] (kinds (second turns)))
        "the run's output belongs to the turn its last user message opened")))

(deftest the-tool-table-a-call-went-out-with
  ;; Ticket 04's read half: a call leaves the table's SIGNATURE -- the name set as a
  ;; hash and the count -- not the table, and the shape a reader gets is the same
  ;; whichever spelling the record used. Two tables that differ only in a DESCRIPTION
  ;; share a hash.
  (let [specs [{:type "function" :function {:name "read" :description "Read a file"}}]
        read-back (fn [[turn]]
                    (mapv #(select-keys % [:index :model :toolsNamesHash :toolsCount])
                          (:calls turn)))]
    (testing "an OLD record still carries the table, and its signature is derived here"
      (let [[turn] (turns-of
                   [(client 0 (user "u1" "hi"))
                    (system-prompt 10 "S")
                    (record 15 "model/start" {:model "deepseek-chat" :base-url "http://x/v1" :tools specs})
                    (record 16 "model/end" {})
                    (record 17 "model/start" {:model "deepseek-chat" :base-url "http://x/v1"})
                    (record 18 "model/end" {})
                    finished
                    (message 20 (assistant "ok"))])]
        (is (= [{:index 0 :model "deepseek-chat"
                 :toolsNamesHash (tools/names-hash specs) :toolsCount 1}
                {:index 1 :model "deepseek-chat"}]
               (read-back [turn]))
            "one entry per call, in order; the second call sent no table and says so by omission")))
    (testing "a NEW record carries the signature itself, and reads the same way"
      (let [[turn] (turns-of
                   [(client 0 (user "u1" "hi"))
                    (system-prompt 10 "S")
                    (record 15 "model/start" {:model "deepseek-chat" :base-url "http://x/v1"
                                              :tools-names-hash (tools/names-hash specs)
                                              :tools-count 1})
                    (record 16 "model/end" {})
                    finished
                    (message 20 (assistant "ok"))])]
        (is (= [{:index 0 :model "deepseek-chat"
                 :toolsNamesHash (tools/names-hash specs) :toolsCount 1}]
               (read-back [turn]))))))

  (testing "a record from before the model lines has no :calls at all"
    (let [[turn] (turns-of [(client 0 (user "u1" "hi"))
                            (system-prompt 10 "S")
                            finished])]
      (is (not (contains? turn :calls))
          "absent, not []: 'the record cannot tell' is not 'no call was made'")))

  (testing "two runs of one turn each contribute their calls, in order"
    (let [turns (turns-of
                 [(system-prompt 10 "S")
                  (client 0 (user "u1" "hi"))
                  (record 15 "model/start" {:model "m"})
                  (record 16 "model/end" {})
                  finished
                  (system-prompt 110 "S")
                  ;; the retry says what it already said: same id, so it is not new material
                  (client 100 (user "u1" "hi"))
                  (record 115 "model/start" {:model "m"})
                  (record 116 "model/end" {})
                  finished])]
      (is (= 1 (count turns)))
      (is (= [0 1] (mapv :index (:calls (first turns))))))))

(deftest a-call-carries-its-span-and-what-the-vendor-said
  (let [[turn] (turns-of
                [(client 0 (user "u1" "hi"))
                 (system-prompt 10 "S")
                 (record 100 "model/start" {:model "deepseek-chat"})
                 (record 250 "model/end" {:usage {:prompt_tokens 769
                                                  :completion_tokens 324
                                                  :total_tokens 1093}
                                          :finish-reason "tool_calls"
                                          :model "deepseek-chat"})
                 finished
                 (message 300 (assistant "ok"))])]
    (is (= [{:index 0 :model "deepseek-chat" :startedAt 100 :endedAt 250
             :usage {:prompt_tokens 769 :completion_tokens 324 :total_tokens 1093}
             :tokens 1093 :finishReason "tool_calls"}]
           (:calls turn))
        "the span is the pair's :ts; the usage is the vendor's own map, and the total is derived on top")
    (is (= 0 (:call (item-of turn "assistant")))
        "the assistant item points at the call it belongs to")
    (is (not (contains? (item-of turn "assistant") :startedAt))
        "and does NOT repeat the call's times -- one fact, one place"))

  (testing "a call that reported nothing is a call with a span and no usage"
    (let [[turn] (turns-of [(client 0 (user "u1" "hi"))
                            (system-prompt 10 "S")
                            (record 100 "model/start" {:model "m"})
                            (record 250 "model/end" {})
                            finished])]
      (is (= [{:index 0 :model "m" :startedAt 100 :endedAt 250}] (:calls turn))
          "no :usage, no :tokens, no :finishReason -- absent, not zero")))

  (testing "an unterminated call is a call with a start and no end"
    ;; The session is being read while it runs: the last call has not come back.
    (let [answer (trajectory/records->trajectory
                  (rows [(client 0 (user "u1" "hi"))
                         (record 100 "model/start" {:model "m"})
                         (frame 120 "RUN_STARTED" {:threadId "t" :runId "r1"})]))]
      (is (= [{:index 0 :model "m" :startedAt 100}] (:calls (first (:turns answer)))))
      (is (true? (:incomplete answer))))))

(deftest a-tool-item-carries-its-own-four-marks
  ;; THE NAMES ARE THE POINT: `tools/execute` is when the call LEFT execution -- the tool
  ;; FINISHED -- so a tool's span is arrivedAt -> executedAt. Reading it as a start is the
  ;; mistake that draws every tool as instantaneous, and it is a mistake the line's own
  ;; name invites (it says `execute`, not `executed`).
  (let [[turn] (turns-of
                [(client 0 (user "u1" "hi"))
                 (system-prompt 10 "S")
                 (pre-execute 20 "c1" "read")
                 (executed 1020 "c1" "read")        ;; the tool took a second
                 (post-execute 1022 "c1" "read")
                 finished
                 (message 1030 {:role "assistant" :content "" :tool_calls [(tool-call "c1" "read" "{}")]})
                 (message 1031 (tool-msg "c1" "done"))])]
    (is (= {:kind "tool" :toolCallId "c1" :name "read" :argsText "{}" :result "done"
            :executed true :outcome "pass"
            :arrivedAt 20 :executedAt 1020 :closedAt 1022}
           (item-of turn "tool"))
        "four marks, none of them invented: no :resumedAt, because nobody had to decide")
    (is (= 1000 (- (:executedAt (item-of turn "tool")) (:arrivedAt (item-of turn "tool"))))
        "and that difference IS how long the tool took"))

  (testing "a park puts the human's wait in :resumedAt, and it is not the tool's own time"
    (let [[turn] (turns-of
                  [(client 0 (user "u1" "hi"))
                   (system-prompt 10 "S")
                   (pre-execute 20 "c1" "read" "needs-approval")
                   (pre-execute 5000 "c1" "read" "pass")   ;; the human said yes
                   (executed 5010 "c1" "read")
                   (post-execute 5012 "c1" "read")
                   finished
                   (message 5100 {:role "assistant" :content "" :tool_calls [(tool-call "c1" "read" "{}")]})
                   (message 5101 (tool-msg "c1" "done"))])]
      (let [called (item-of turn "tool")]
        (is (= 4980 (- (:resumedAt called) (:arrivedAt called)))
            "the wait is decided -> arrived, and it is nearly five seconds")
        (is (= 10 (- (:executedAt called) (:resumedAt called)))
            "the tool itself took 10ms -- a reader sees these as two different facts")
        (is (= "pass" (:outcome called)) "the LAST verdict is the one that stuck"))))

  (testing "a vetoed call never ran, so it has no executedAt at all"
    (let [[turn] (turns-of
                  [(client 0 (user "u1" "hi"))
                   (system-prompt 10 "S")
                   (pre-execute 20 "c1" "read" "vetoed")
                   (post-execute 22 "c1" "read")
                   finished
                   (message 30 {:role "assistant" :content "" :tool_calls [(tool-call "c1" "read" "{}")]})
                   (message 31 (tool-msg "c1" "vetoed by human"))])]
      (let [called (item-of turn "tool")]
        (is (false? (:executed called)))
        (is (not (contains? called :executedAt)) "absent, not zero")
        (is (= 20 (:arrivedAt called)) "it did arrive, and that is all that happened")))))

(deftest a-tool-call-knows-which-call-asked-for-it
  (let [[turn] (turns-of
                [(client 0 (user "u1" "hi"))
                 (system-prompt 10 "S")
                 (record 20 "model/start" {:model "m"})
                 (record 60 "model/end" {})
                 (record 61 "model/start" {:model "m"})
                 (record 90 "model/end" {})
                 finished
                 (message 100 {:role "assistant" :content "" :tool_calls [(tool-call "c1" "read" "{}")]})
                 (message 101 (tool-msg "c1" "one"))
                 (message 102 (assistant "two"))])]
    (is (= [0 1] (mapv :index (:calls turn))))
    (is (= [0 0] [(get (item-of turn "tool") :call)
                  (:call (first (filter #(= "assistant" (:kind %)) (:items turn))))])
        "the tool result and the reply that asked for it are both call 0")))

(deftest a-log-that-never-ran-a-turn-is-empty-and-not-an-error
  ;; A session that exists but has not run: only a project/bound line. An empty
  ;; answer is the honest one, and it is not a truncated run.
  (let [answer (trajectory/records->trajectory
                [(record 0 "project/bound" {:before nil :after "/tmp/x" :via "http"})])]
    (is (= [] (:turns answer)))
    (is (false? (:incomplete answer)))))

(deftest an-unfinished-run-is-a-flag-not-a-refusal
  ;; replay refuses this log; the trajectory reads it, because looking at a session
  ;; while it runs is the ordinary case.
  (let [records (rows [(client 0 (user "u1" "hi"))
                       (frame 20 "RUN_STARTED" {:threadId "t" :runId "r1"})])
        answer  (trajectory/records->trajectory records)]
    (is (true? (:incomplete answer)))
    (is (= 1 (count (:turns answer))) "as far as it got")
    (is (thrown? Exception (replay/records->messages records))
        "the conversation reader still refuses the same log -- two readers, two answers")))

(deftest a-half-written-last-line-is-dropped-and-the-rest-still-reads
  (let [f (java.io.File/createTempFile "trajectory-lines" ".jsonl")]
    (try
      (spit f (str (row-json (client 0 (user "u1" "hi"))) "\n"
                   (row-json (system-prompt 10 "S")) "\n"
                   "{\"ts\":20,\"runId\":\"r1\",\"kind\":\"mess")
            :encoding "UTF-8")
      (let [answer (trajectory/log-trajectory f)]
        (is (= 1 (count (:turns answer))))
        (is (= ["system" "user"] (kinds (first (:turns answer))))))
      (finally (.delete f)))))

;; ---------------------------------------------------------------- the endpoint

(defn- with-server
  "A live edge with a scripted provider pinned to THREAD-ID. Deliberately thin, the
  same shape harness.edge.stats-test uses: one thread, one script, one GET."
  [thread-id turns f]
  (providers/use-provider! thread-id (fake/scripted turns))
  (support/start-session! thread-id)
  (let [stop (http/start! {:port 0})]
    (try
      (f (:local-port (meta stop)))
      (finally
        (stop)
        (providers/use-provider! thread-id nil)))))

(defn- send-run!
  "One real AG-UI run, drained. Returns its response body."
  [port thread-id]
  (let [body (json/write-str {:threadId thread-id
                              ;; THE ACTION'S OWN ENTRIES (ticket 03), not the
                              ;; conversation: the server holds that.
                              :append [{:id "u1" :role "user" :content "看看这个项目"}]
                              :tools []})
        ;; THE RUN IS READ FROM THE DOWNLINK NOW (`support/mux-run!`): the POST answers an ack,
        ;; the frames arrive on `events.mux`, and this hands back the run's SSE body as before.
        result (support/mux-run! port thread-id body nil)]
    (:body result)))

(defn- get-json [port path]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
                (.GET)
                (.build))
        resp (.send (HttpClient/newHttpClient) req
                    (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))]
    [(.statusCode resp) (json/read-str (.body resp) :key-fn keyword)]))

(defn- log-messages
  "Every `message` record from THREAD-ID's own log, read back through the namespaces
  the read sides share. This is how a test gets at the EDGE's bytes rather than
  trusting the fold's copy of them."
  [thread-id]
  (->> (stats/read-records (replay/locate (home/projects-dir) thread-id))
       (filter #(= "message" (replay/kind %)))))

(defn- await-run-recorded!
  "Wait until THREAD-ID's log has stopped being written, and answer its records.

  THE RETURNED SIDE LANDS AFTER THE TERMINAL FRAME: the edge writes :run/done's
  messages once the SSE has closed, so at the moment the client's body ends the record
  is still missing the assistant messages -- and a tool call's name and arguments live
  on exactly those (the audit lines name the tool and never its body, which is why
  `call-index` reads the pair out of the messages). Folding the log without waiting is
  a race with the writer, and it is how this test failed under a full suite: the tool
  item came back with a null name because the message naming the call was not written
  yet.

  THE CONDITION IS ABOUT THE WRITER, NOT ABOUT WHAT THIS TEST WANTS TO SEE. 'A terminal
  frame is in the record and the last line is a message' is the end of the sequence the
  edge writes; asking instead for 'a tool item exists' would pass by construction and
  hide the very race the wait exists to survive."
  [thread-id ms]
  (let [f      (replay/locate (home/projects-dir) thread-id)
        ended? (fn [records]
                 (and (some #(and (= "event" (replay/kind %))
                                  (frames/terminal? (replay/payload %)))
                            records)
                      (= "message" (replay/kind (last records)))))
        finish (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [records (stats/read-records f)]
        (if (or (ended? records) (> (System/currentTimeMillis) finish))
          records
          (do (Thread/sleep 25) (recur)))))))

(deftest the-endpoint-folds-what-the-run-wrote
  ;; The whole path, once, over real HTTP: a real run writes the log, and the route
  ;; folds it. The system message is checked BYTE FOR BYTE against the line the edge
  ;; wrote -- that equality is the feature's promise, and a screenshot cannot show it.
  ;;
  ;; AND THE LINE IS AN EVENT NOW (2026-09-21): the assembled prompt is a fact about the
  ;; run (`system-prompt`, text once per conversation plus a per-run hash), so the record
  ;; no longer carries it as a message row and the route synthesizes the item from the
  ;; event. The two sides of the equality are the same bytes either way -- which is why
  ;; the assertion below did not change, only where it reads them from.
  (let [thread-id "trajectory-endpoint"]
    (with-server thread-id
      [{:content "" :tool-calls [{:id "c1" :name "no-such-tool" :arguments {}}]}
       {:content "done"}]
      (fn [port]
        (send-run! port thread-id)
        ;; BEFORE the fold, not after it: the route reads the log, so the log has to
        ;; have been finished being written (see await-run-recorded!).
        (await-run-recorded! thread-id 5000)
        (let [[status body] (get-json port (str "/api/threads/" thread-id "/trajectory"))
              items   (:items (first (:turns body)))
              by      (fn [k] (first (filter #(= k (:kind %)) items)))
              written (->> (stats/read-records (replay/locate (home/projects-dir) thread-id))
                           (filter replay/system-prompt?)
                           first replay/payload :content)]
          (is (= 200 status))
          (is (= thread-id (:threadId body)))
          (is (false? (:incomplete body)))
          (is (= 1 (count (:turns body))) "one user message, one turn")
          (is (every? #(set/subset? (set (keys %)) item-keys) items)
              "every field on the wire is one the inventory names")
          (is (= "system" (:kind (first items))) "the turn opens with the system message")
          (is (= written (:text (by "system")))
              "the system item IS the line the edge wrote, byte for byte")
          (is (seq (:tools (by "system")))
              "and the TOOL TABLE rides the item -- an item is self-contained, so a reader
               never has to pull a second record to find out what tools the run served")
          ;; NAME FOR NAME, not map-for-map: the item crossed JSON on the way out.
          (is (= (map (comp :name :function) (tools/specs thread-id))
                 (map (comp :name :function) (:tools (by "system"))))
              "it is the very table this session served, name for name")
          (is (= "看看这个项目" (:text (by "user"))))
          (is (= "no-such-tool" (:name (by "tool")))
              "the tool call the model asked for, name and arguments and all")
          (is (string? (:argsText (by "tool"))))
          (is (string? (:result (by "tool")))))))))

(deftest the-endpoint-says-not-here-for-a-session-that-has-no-log
  (with-server "trajectory-no-log" [{:content "unused"}]
    (fn [port]
      (let [[status body] (get-json port "/api/threads/never-ran/trajectory")]
        (is (= 404 status))
        (is (= "never-ran" (:threadId body)))
        (is (string? (:error body)) "the locator's own sentence, not a blank 404")))))

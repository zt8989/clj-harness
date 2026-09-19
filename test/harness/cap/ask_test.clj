(ns harness.cap.ask-test
  "`ask`: the model asks a person something, the run stops, and the answer comes
  back as that call's result.

  OVER THE REAL EDGE, because three of the things promised here are facts about the
  wire rather than about the seam: the interrupt a client receives, the question
  `GET /api/elicitation` answers (and who it says is asking), and the resume that
  carries the answers back. An offline `run-chan` call proves the park and nothing
  else.

  THE MODEL IS WHAT IS FAKED, and only the model: the scripted provider returns
  `ask` as a tool call, and everything after that -- the park, the interrupt, the
  endpoint, the resume, the result -- is the harness's own machinery. That is the
  point of driving it here rather than unit-testing the tool body: what this ticket
  changed is that a BUILT-IN can drive the elicitation chain, and a built-in's
  wiring is only visible from outside.

  NOTHING HERE IS AN MCP SERVER. That is the whole difference from
  harness.cap.mcp-wired-test, which drives the same chain through a real child
  process; the two read the same reasons, the same endpoint and the same card, and
  the ASKER is what separates them."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.cap.editing :as editing]
            [harness.cap.providers :as providers]
            [harness.cap.tools :as cap-tools]
            [harness.edge.http :as http]
            [harness.fake :as fake]
            [harness.kernel.tools :as tools]
            [harness.wire :as wire])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

;; NO NAMESPACE FIXTURE, and that is not an omission: `http/start!` IS the
;; composition root, so a test that starts a server has this harness's capabilities
;; installed for the duration of the call -- the same reason harness.edge.http-test
;; and harness.cap.mcp-wired-test declare nothing either. A live table is also what
;; makes the roster assertions below mean something: against an empty table they
;; would pass by finding nothing.

(def ^:private questions
  "Two questions, because 'one call, a list of questions' is the shape this ticket
  is about -- a single-question case would not tell a list from a scalar."
  [{:key "port" :question "Which port should it listen on?"}
   {:key "tests" :question "Should I update the tests too?"}])

(def ^:private questions-line
  "The two questions as the interrupt carries them, joined in the order asked."
  "Which port should it listen on? / Should I update the tests too?")

(defn- ask-script
  "One run: the model asks, then -- once the answers are in -- says it is done.

  THE SECOND TURN IS ONLY REACHED WHEN THE CALL RAN, which is what makes the
  resumed run's terminal frame evidence rather than decoration: a run that parked
  again never asks the model anything."
  [args]
  [{:content ""
    :tool-calls [{:id "q1" :name "ask" :arguments args}]}
   {:content "done"}])

(def ^:dynamic *port* nil)

(defn- with-server
  "A live server on an OS-chosen port, with a scripted provider pinned to THREAD.
  The port is never written down: see AGENTS.md."
  [thread turns f]
  (providers/use-provider! thread (fake/scripted turns))
  (let [stop (http/start! {:port 0})
        port (:local-port (meta stop))]
    (try (binding [*port* port] (f))
         (finally (stop) (providers/use-provider! thread nil)))))

(defn- post-run
  ([thread-id] (post-run thread-id {}))
  ([thread-id extra]
   (let [body (json/write-str (merge {:threadId thread-id
                                      :runId (str (java.util.UUID/randomUUID))
                                      :messages [{:id "u1" :role "user" :content "go"}]
                                      :tools [] :context []}
                                     extra))
         req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" *port* "/api/agent")))
                  (.header "Content-Type" "application/json")
                  (.header "Accept" "text/event-stream")
                  (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
                  (.build))]
     (.send (HttpClient/newHttpClient) req
            (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)))))

(defn- api-get
  "A management-edge GET, as {:status :body}."
  [path]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" *port* "/" path)))
                (.GET)
                (.build))
        resp (.send (HttpClient/newHttpClient) req
                    (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))]
    {:status (.statusCode resp) :body (.body resp)}))

(defn- frames [response] (wire/frames-from-sse (.body response)))

(defn- interrupt-frames
  "The terminal frames that carry interrupts, read off the wire rather than out of
  the server's own bookkeeping -- which is the difference between checking the
  protocol and checking this harness's opinion of it.

  More than one would mean a run that stopped twice, so the cases below count them
  as well as reading them."
  [body]
  (filterv #(some? (get-in % [:outcome :interrupts]))
           (filter #(= "RUN_FINISHED" (:type %)) (wire/frames-from-sse body))))

(defn- interrupts-of
  "The parked calls a run ended on, one entry each."
  [body]
  (vec (mapcat #(get-in % [:outcome :interrupts]) (interrupt-frames body))))

(defn- tool-results [body]
  (filterv #(= "TOOL_CALL_RESULT" (:type %)) (wire/frames-from-sse body)))

(defn- ask-args [qs] {:questions qs})

(defn- park-and-read
  "One run that stops, as {:id .. :prompt .. :props .. :raw ..}: the interrupt's id
  plus what the endpoint the card reads says about the question.

  `:props` IS STRING-KEYED AT EVERY LEVEL because that is what a client parses; the
  property names in it are the question keys and the enum is the model's own words,
  and reading either of them keyword-parsed here would be this test's habit rather
  than the protocol's. `:raw` is kept for the assertions that are about bytes -- the
  absence of a key, or a candidate that must not have been escaped."
  [thread]
  (let [body (.body (post-run thread))
        id   (:id (first (interrupts-of body)))
        raw  (:body (api-get (str "api/elicitation?interruptId=" id)))
        wire (json/read-str raw)]
    {:id     id
     :prompt (get wire "prompt")
     :props  (get-in wire ["schema" "properties"])
     :raw    raw}))

(defn- resume-with
  "A resume run carrying PAYLOAD as the answers to ID."
  [thread id payload]
  (post-run thread {:resume [{:interruptId id :status "resolved" :payload payload}]}))

(defn- result-of
  "The text of the single tool result a resumed run produced -- or nil, which means
  the run reported no result at all and parked instead."
  [response]
  (:content (first (tool-results (.body response)))))

(defn- result-line
  "The one line of RESULT that answers QUESTION, as the model reads it. An absent
  result yields nil rather than throwing, so the assertion that follows reads as
  `expected: \"- x -> y\" actual: nil` instead of a stack trace about split-lines."
  [result question]
  (->> (str/split-lines (or result ""))
       (filter #(str/starts-with? % (str "- " question " -> ")))
       first))

(defn- answered-line
  "One whole exchange, start to finish: this harness asks QS, the call parks, and
  PAYLOAD is what the person answered. Back comes the line the model was handed for
  QUESTION.

  A FRESH SERVER, THREAD AND PARK EVERY TIME, which is why all three live in here
  rather than being shared by the callers: a decision is SPENT by the first resume that
  reads it (`an-answer-cannot-be-spent-twice`), and a thread whose script has already
  been played does not park afresh -- a second `post-run` on it finishes the run rather
  than asking again. Two answers are two exchanges."
  [thread qs payload question]
  (with-server thread (ask-script (ask-args qs))
    (fn []
      (let [{:keys [id]} (park-and-read thread)
            content      (result-of (resume-with thread id payload))]
        (is (some? content) "the resumed run reported a result rather than parking again")
        (result-line (or content "") question)))))

(deftest asking-parks-the-run-and-the-question-comes-with-it
  (let [thread "ask-park"]
    (with-server thread (ask-script (ask-args questions))
      (fn []
        (let [res  (post-run thread)
              body (.body res)
              ints (interrupts-of body)]

          (testing "the run ends on ONE stop, and it is a QUESTION rather than an approval"
            (is (= 1 (count (interrupt-frames body)))
                "exactly one terminal frame carries interrupts")
            (is (= 1 (count ints)))
            (is (= "elicitation" (:reason (first ints))))
            (is (= "q1" (:toolCallId (first ints))))
            (testing "and it is the LAST frame, with nothing after it"
              (is (= "RUN_FINISHED" (:type (last (frames res)))))))

          (testing "the line a client puts on the card is the questions themselves,
                    in the order they were asked"
            (is (= questions-line (:message (first ints)))))

          (testing "the call did not run: no result was reported for it"
            (is (= [] (tool-results body))))

          (testing "and the SHAPE of the answer is not on the wire -- it is fetched
                    from this harness's own edge, because the interrupt's shape is
                    AG-UI's and strictly validated"
            (is (not (contains? (first ints) :schema))))

          (testing "the endpoint answers the question, its shape, and WHO is asking"
            (let [id     (:id (first ints))
                  answer (api-get (str "api/elicitation?interruptId=" id))
                  raw    (:body answer)
                  body   (json/read-str raw :key-fn keyword)
                  ;; The wire's own shape, string keys at every level, because that
                  ;; is what a client reads: the property NAMES in the schema are the
                  ;; question keys, and keyword-parsing them here would be this
                  ;; test's habit rather than the protocol's.
                  wire   (json/read-str raw)
                  props  (get-in wire ["schema" "properties"])]
              (is (= 200 (:status answer)))
              (is (= questions-line (:prompt body)))
              (is (= "model" (:askedBy body)))
              (is (= {"port" "Which port should it listen on?"
                      "tests" "Should I update the tests too?"}
                     (into {} (map (fn [[k v]] [k (get v "description")])) props)))
              (testing "and NO server, absent rather than null -- a card tells 'nobody
                        named themselves' from 'a server called null' by presence"
                (is (not (contains? body :server)))
                (is (not (str/includes? raw "\"server\""))))))

          (testing "an id nobody parked is still a named 404"
            (is (= 404 (:status (api-get "api/elicitation?interruptId=nope"))))))))))

(deftest the-answers-come-back-as-the-calls-result
  (let [thread "ask-resume"]
    (with-server thread (ask-script (ask-args questions))
      (fn []
        (let [parked   (post-run thread)
              id       (:id (first (interrupts-of (.body parked))))
              resumed  (post-run thread {:resume [{:interruptId id :status "resolved"
                                                   :payload {"port" "8080"}}]})
              body     (.body resumed)
              results  (tool-results body)]
          (testing "the resumed run FINISHES -- it does not park again"
            (is (= "RUN_FINISHED" (:type (last (frames resumed)))))
            (is (= [] (interrupts-of body))))

          (testing "the call really ran, and what it returned is the person's answers"
            (is (= 1 (count results)))
            (is (= "q1" (:toolCallId (first results))))
            (let [content (:content (first results))]
              (is (str/includes? content "8080"))
              (testing "each answer is on the line of the question that asked for it,
                        so nothing has to be matched up by hand"
                (is (str/includes? content
                                   "- Which port should it listen on? -> 8080"))
                (testing "and a question they left blank says so rather than going
                          missing"
                  (is (str/includes? content
                                     "- Should I update the tests too? -> (no answer)")))))))))))

(deftest an-answer-cannot-be-spent-twice
  (let [thread "ask-spent-once"]
    (with-server thread (ask-script (ask-args questions))
      (fn []
        (let [parked  (post-run thread)
              id      (:id (first (interrupts-of (.body parked))))
              resume  {:resume [{:interruptId id :status "resolved"
                                 :payload {"port" "8080"}}]}
              first-  (post-run thread resume)
              second- (post-run thread resume)]
          (testing "the first resume answers the call"
            (is (= "RUN_FINISHED" (:type (last (frames first-)))))
            (is (str/includes? (:content (first (tool-results (.body first-)))) "8080")))
          (testing "the second finds the decision SPENT, so the call parks again
                    rather than being answered a second time"
            (is (= [] (tool-results (.body second-))))
            (let [ints (interrupts-of (.body second-))]
              (is (= 1 (count ints)))
              (testing "on the SAME interrupt, so the card a client is holding is
                        still the right card to answer"
                (is (= id (:id (first ints)))))
              (testing "and it asks the same questions, because it is the same
                        question"
                (is (= questions-line (:message (first ints))))))))))))

(deftest a-declined-form-is-an-answer-and-not-a-failure
  (let [thread "ask-declined"]
    (with-server thread (ask-script (ask-args questions))
      (fn []
        (let [parked  (post-run thread)
              id      (:id (first (interrupts-of (.body parked))))
              resumed (post-run thread {:resume [{:interruptId id :status "cancelled"}]})
              body    (.body resumed)
              results (tool-results body)]
          (testing "the run carries on: a person saying no is not the tool going wrong"
            (is (= "RUN_FINISHED" (:type (last (frames resumed)))))
            (is (= [] (interrupts-of body))))
          (testing "and the model is told, in one sentence it can act on"
            (is (= 1 (count results)))
            (is (= "The person declined to answer." (:content (first results))))))))))

(deftest a-decline-is-not-an-error-for-the-model
  ;; THE ONE CLAIM THAT CANNOT BE MADE OVER THE WIRE. The seam's `:error` flag does
  ;; not travel: ag_ui's TOOL_CALL_RESULT carries the content and nothing about
  ;; failure, and the history's tool message has no flag either. So 'not an error'
  ;; is asserted where it exists -- on the answer `run!` gives -- and it is worth
  ;; asserting because it is the difference between a person's no and a broken tool:
  ;; a model handed `:error true` goes looking for what went wrong, and nothing did.
  ;;
  ;; The capabilities are installed by hand here rather than by a server, because
  ;; this case drives the seam and only the seam -- see the namespace docstring for
  ;; why there is no fixture doing it for the whole namespace.
  (let [td (cap-tools/install!)]
    (try
      (let [thr  "ask-decline-seam"
            args (json/write-str (ask-args questions))]
        (binding [tools/*thread-id* thr tools/*tool-call-id* "q1"]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"suspended"
                                ((:run (get (tools/effective-tools thr) "ask"))
                                 (ask-args questions)))))
        (let [rec (tools/parked-for-call thr "q1")]
          (is (= :elicitation (:reason rec)))
          (tools/decide-approval! (:interrupt-id rec) :vetoed nil)
          (let [{:keys [content error]} (tools/run! {:id "q1"
                                                     :function {:name "ask"
                                                                :arguments args}}
                                                    thr)]
            (is (= "The person declined to answer." content))
            (is (false? error)))))
      (finally (td)))))

(deftest a-form-nobody-could-answer-never-reaches-a-person
  ;; THE REFUSAL HAS TO LAND BEFORE THE PARK, because the cost of a bad form is
  ;; measured in somebody's attention: a card that cannot be filled in correctly is
  ;; worse than an error the model can fix by itself.
  (testing "nothing to ask"
    (let [thread "ask-empty"]
      (with-server thread (ask-script (ask-args []))
        (fn []
          (let [body    (.body (post-run thread))
                results (tool-results body)]
            (is (= [] (interrupts-of body)) "no card was put up for an empty form")
            (is (= 1 (count results)))
            (is (str/includes? (:content (first results)) "at least one question")))))))
  (testing "two questions that would come back under one key"
    (let [thread "ask-dupes"]
      (with-server thread (ask-script (ask-args [{:key "port" :question "First?"}
                                                 {:key "port" :question "Second?"}]))
        (fn []
          (let [body    (.body (post-run thread))
                results (tool-results body)]
            (is (= [] (interrupts-of body)) "no card was put up for a form whose answers
                                             would collide")
            (is (= 1 (count results)))
            (is (str/includes? (:content (first results)) "port"))))))))

(deftest a-question-can-offer-candidates
  ;; THE CANDIDATES GO ON THE SCHEMA VERBATIM, because they are the model's own
  ;; words for the things it is asking about and an answer comes back to be matched
  ;; against them: a space inside "香港 分行" or a comma inside "a, b" is part of the
  ;; candidate, and a card offering a trimmed or reordered list would be offering a
  ;; different answer than the one the model is comparing it to.
  (let [thread "ask-options"
        qs     [{:key "db" :question "Which database?"
                 :options ["香港 分行" "a, b" "sqlite"]}]]
    (with-server thread (ask-script (ask-args qs))
      (fn []
        (let [{:keys [id props raw]} (park-and-read thread)
              db (get props "db")]
          (testing "the candidates ride as an `enum` on the property, in the order asked"
            (is (= "string" (get db "type")))
            (is (= ["香港 分行" "a, b" "sqlite"] (get db "enum")))
            (is (= "Which database?" (get db "description"))))
          (testing "and nothing is offered that nobody asked for"
            (is (not (contains? db "x-allow-other")) "no own-words box")
            (is (not (contains? db "items")) "and not a multi-select"))
          (testing "a candidate is not mangled on its way out -- not to an HTML entity,
                    not to percent-encoding, and the comma inside `a, b` is carried as
                    itself rather than turning one candidate into two"
            ;; WHAT IS PINNED IS THE DECODED TEXT, deliberately. The wire writes
            ;; non-ASCII as JSON's own `\uXXXX` (as every response here does), which is
            ;; an encoding of these characters and not a change to them -- so the claim
            ;; worth holding is the one a client can observe: the string it parses is
            ;; the string the model wrote, character for character. `db` above is read
            ;; from that decode, which is why the list asserted there is the point.
            (is (str/includes? raw "a, b") "a comma rides as itself")
            (is (not (str/includes? raw "&amp;")) "and nothing was entity-escaped")
            (is (not (str/includes? raw "%E9%A6%99")) "nor percent-encoded"))
          (testing "the answer is the candidate, word for word"
            (is (= "- Which database? -> 香港 分行"
                   (result-line (result-of (resume-with thread id {"db" "香港 分行"}))
                                "Which database?")))))))))

(deftest a-person-may-answer-in-their-own-words
  (testing "when the question says so"
    (let [thread "ask-other"
          qs     [{:key "db" :question "Which database?"
                   :options ["postgres" "sqlite"] :allow_other true}]]
      (with-server thread (ask-script (ask-args qs))
        (fn []
          (let [{:keys [id props]} (park-and-read thread)
                db (get props "db")]
            (testing "the permission rides on the property as the client's own key,
                      beside the list it is a way out of"
              (is (true? (get db "x-allow-other")))
              (is (= ["postgres" "sqlite"] (get db "enum"))))
            (testing "and what they typed IS the answer -- ONE value, not the pick
                      plus their words"
              (is (= "- Which database? -> mysql 8"
                     (result-line (result-of (resume-with thread id {"db" "mysql 8"}))
                                  "Which database?")))))))))
  (testing "and never for a question that had nothing to choose from"
    ;; THE SWITCH IS DROPPED RATHER THAN OBEYED, and the form it produces is the one
    ;; that was asked for either way: a written answer already takes anything, so the
    ;; only thing `x-allow-other` could add here is a second box for the same answer.
    ;; The whole property is pinned, because "no enum and no extension key" is the
    ;; shape a server's own free-text field has and this must stay byte-identical to it.
    (let [thread "ask-other-no-options"]
      (with-server thread (ask-script (ask-args [{:key "note" :question "Anything to add?"
                                                  :allow_other true}]))
        (fn []
          (is (= {"note" {"type" "string" "description" "Anything to add?"}}
                 (:props (park-and-read thread)))))))))

(deftest a-question-can-take-more-than-one-answer
  ;; EVERY ANSWER HERE IS A WHOLE EXCHANGE OF ITS OWN -- its own server, thread, park and
  ;; resume. Not tidiness: a decision is spent by the first resume that reads it, and a
  ;; thread whose script has already been played does not park again, so answering four
  ;; ways off one park would be three nils reported as failures about answers.
  (let [qs [{:key "targets" :question "Which targets?"
             :options ["api" "web" "docs"] :multiple true :allow_other true}]]
    (testing "several answers is an `array` of the candidates -- the shape the client's
              own field rules already read as tick boxes"
      (with-server "ask-multi-shape" (ask-script (ask-args qs))
        (fn []
          (let [t (get (:props (park-and-read "ask-multi-shape")) "targets")]
            (is (= "array" (get t "type")))
            (is (= {"type" "string" "enum" ["api" "web" "docs"]} (get t "items")))
            (is (= "Which targets?" (get t "description")))
            (is (true? (get t "x-allow-other")))))))

    (testing "what comes back is the items themselves -- and ONE item is a list too,
              not a scalar that happens to have one thing in it"
      (is (= "- Which targets? -> api, web"
             (answered-line "ask-multi-picked" qs {"targets" ["api" "web"]} "Which targets?")))
      (is (= "- Which targets? -> docs"
             (answered-line "ask-multi-one" qs {"targets" ["docs"]} "Which targets?"))))

    (testing "ticking NOTHING is an answer, and it is not the same answer as leaving the
              question alone"
      ;; THE PAIR THIS TOOL EXISTS TO KEEP APART. "None of these" is something a model can
      ;; act on -- go and find out which one they do want -- while a missing key says
      ;; nothing back at all. Collapsing the two would hide the one fact the person just
      ;; took the trouble to state.
      (let [none   (answered-line "ask-multi-none" qs {"targets" []} "Which targets?")
            absent (answered-line "ask-multi-absent" qs {} "Which targets?")]
        (is (= "- Which targets? -> (nothing chosen)" none))
        (is (= "- Which targets? -> (no answer)" absent))
        (is (not= none absent)
            "the two states read differently, which is the whole claim")))))

(deftest a-choice-nobody-could-pick-is-refused-before-the-park
  ;; MEASURED IN SOMEBODY'S ATTENTION, the same as an empty form: a card with a
  ;; question on it that cannot be answered costs a person a walk to the screen and
  ;; buys the model nothing. All three of these are the model's to fix, so they are
  ;; refused while the cost is still its own.
  (doseq [[i [what q says]]
          (map-indexed vector
                       [["several answers with nothing to choose between"
                         {:key "t" :question "Which ones?" :multiple true}
                         "offers no `options`"]
                        ["a list with no candidates in it"
                         {:key "t" :question "Which one?" :options []}
                         "empty list of options"]
                        ["a candidate that says nothing"
                         {:key "t" :question "Which one?"
                          :options ["api" "  "]}
                         "blank candidate"]])]
    (testing what
      (let [thread (str "ask-bad-" i)]
        (with-server thread (ask-script (ask-args [q]))
          (fn []
            (let [body (.body (post-run thread))]
              (is (= [] (interrupts-of body))
                  "no card was put up for a question nobody could answer")
              (let [results (tool-results body)]
                (is (= 1 (count results)))
                (testing "and the model is told which question it was, and what about it"
                  (is (str/includes? (:content (first results)) says))
                  (is (str/includes? (:content (first results)) "question 1")))))))))))

(deftest ask-is-not-in-the-approval-path
  (with-server "ask-not-approval" (ask-script (ask-args questions))
    (fn []
      (let [tool (get (tools/effective-tools "ask-not-approval") "ask")]
        (testing "the declaration that would put it there is ABSENT, not false"
          (is (some? tool) "ask is installed")
          (is (nil? (:requires-approval tool)))
          (is (nil? (:park-reason tool))))
        (testing "and no session has required it"
          (is (false? (tools/session-approval-required? "ask-not-approval" "ask"))))
        (testing "it belongs to no editing family, so both modes serve it -- the
                  contrast is the OTHER mode's tool, which this session does not
                  serve"
          (is (true? (editing/served? "ask-not-approval" "ask")))
          (is (false? (editing/served? "ask-not-approval" "edit"))))))))

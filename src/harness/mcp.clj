(ns harness.mcp
  "MCP servers as a SOURCE OF TOOLS: read the declarations, run a server, ask it
  what it can do, and hand the answers back as ordinary tools for the table.

  WHAT THIS NAMESPACE IS, IN ONE LINE: the thing that makes a tool exist without
  anyone having written it here. Everything else about such a tool is the same as
  a built-in's -- it goes in the same table, through the same execution seam, past
  the same approval rules, and lands the same three audit lines -- because the
  seam reads the TABLE and does not care who put a row in it.

  WHAT IT DOES NOT OWN. It never writes an audit line: it records what happened in
  a process-local status and an OUTBOX, and the edge (harness.http) turns those
  into `mcp/server` lines. That is the same division the provider timeline uses
  (`providers/take-provider-changes!`), and for the same reason -- assembly
  happens inside a run and outside one, and only the edge knows which, so only the
  edge can decide what a line's runId is.

  CONNECTIONS ARE PER (PROJECT, SERVER), NOT PER SESSION. A server is declared in
  a project's `.harness/mcp.edn`, so every session of that project would ask for
  the same server with the same command in the same directory; starting one
  process per conversation would be paying N times for one answer. The key is the
  project's IDENTITY (its canonical path) rather than the spelling a session was
  bound with, so two spellings of one directory share one server -- the same
  statement `harness.project` makes with its two columns.

  A SERVER THAT WILL NOT START IS NOT A FAILED RUN. Its tools are simply absent,
  every other server's tools are still there, and the reason is NAMED in the
  status and the outbox. There is deliberately no cache of 'the tools it had last
  time': a roster is only as good as the process that answered it, and serving a
  stale one would make a dead server look alive."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.home :as home]
            [harness.hooks.dispatch :as hook]
            [harness.project :as project]
            [harness.parked :as parked]
            [harness.shell :as shell])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Version HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(def ^:private protocol-version
  "The MCP revision this client speaks. A server may answer with a DIFFERENT one
  it supports, and that answer is accepted rather than fought over: the version
  is a negotiation, and a client that insisted would be refusing servers that
  had just told it the truth."
  "2025-06-18")

(def ^:private default-timeout-ms
  "How long one request to a server may take before it is abandoned. The same
  order as the hook engine's bound, and for the same reason: a server that has
  not answered in a minute has stopped being worth the run's time. A per-server
  `:timeout` overrides it."
  60000)

(def ^:private server-name-re
  "A server name, which is also a piece of every tool name it provides. ASCII
  word characters only, so `mcp__<server>__<tool>` survives the trip through a
  provider's `function.name`."
  #"^[A-Za-z0-9_-]+$")

(def ^:private bridged-name-re
  "What an OpenAI-compatible endpoint accepts for `function.name`. Checked on the
  BRIDGED name, because that is the string the provider sees."
  #"^[a-zA-Z0-9_-]{1,64}$")

(def ^:private server-keys
  "Everything a server declaration may carry. A key outside this set fails by
  name: a stray `:commandd` would otherwise be dropped and the declaration would
  look like one with nothing to run."
  #{:command :args :env :url :timeout})

(defn- fail [msg data] (throw (ex-info msg data)))

;; ---------------------------------------------------------------- the two ats

(defonce ^:private connections
  ;; [project-identity server-name] -> {:decl .. :client .. :tools .. :skipped ..}
  ;; The DECLARATION is part of what a connection is: a file edited to run a
  ;; different command must not leave the old process answering for it.
  (atom {}))

(defonce ^:private states
  ;; the same key -> the last outcome, which is what a status surface reports
  (atom {}))

(defonce ^:private events
  ;; An OUTBOX, drained by the edge: this namespace never writes an audit line.
  (atom []))

(declare connect!)

;; -------------------------------------------------------------- declarations

(defn- check-server
  "One declaration at SERVER, validated. Returns it unchanged. Everything wrong
  with it fails HERE, with the file, the server name and the offending value in
  the message -- a server that silently does not start is indistinguishable from
  one that started and offered nothing."
  [abs server decl]
  (when-not (map? decl)
    (fail (str abs " server " (pr-str server) " must be a map, not " (pr-str (type decl)))
          {:path abs :server server :value decl}))
  (let [unknown (sort (remove server-keys (keys decl)))]
    (when (seq unknown)
      (fail (str abs " server " (pr-str server) " has unknown key(s) " (pr-str (vec unknown))
                 "; a server takes " (pr-str (vec (sort server-keys))))
            {:path abs :server server :unknown (vec unknown)})))
  (let [command (:command decl)
        url     (:url decl)]
    (cond
      (and (nil? command) (nil? url))
      (fail (str abs " server " (pr-str server)
                 " says neither :command (to spawn) nor :url (to connect to)")
            {:path abs :server server :reason :no-transport})

      (and (some? command) (some? url))
      (fail (str abs " server " (pr-str server)
                 " says both :command and :url; a server has ONE transport")
            {:path abs :server server :reason :two-transports})

      (some? command)
      (when-not (and (string? command) (not (str/blank? command)))
        (fail (str abs " server " (pr-str server) " :command must be a non-empty string, got "
                   (pr-str command))
              {:path abs :server server :command command})))
    (when (contains? decl :args)
      (let [a (:args decl)]
        (when-not (and (sequential? a) (every? string? a))
          (fail (str abs " server " (pr-str server) " :args must be a vector of strings, got "
                     (pr-str a))
                {:path abs :server server :args a}))))
    (when (contains? decl :env)
      (let [e (:env decl)]
        (when-not (and (map? e) (every? string? (keys e)) (every? string? (vals e)))
          (fail (str abs " server " (pr-str server)
                     " :env must be a map of string -> string, got " (pr-str e))
                {:path abs :server server :env e}))))
    (when (contains? decl :timeout)
      (let [t (:timeout decl)]
        (when-not (and (integer? t) (pos? t))
          (fail (str abs " server " (pr-str server)
                     " :timeout must be a positive whole number of milliseconds, got " (pr-str t))
                {:path abs :server server :timeout t}))))
    (when (some? url)
      (when-not (and (string? url) (not (str/blank? url)))
        (fail (str abs " server " (pr-str server) " :url must be a non-empty string, got "
                   (pr-str url))
              {:path abs :server server :url url}))))
  decl)

(defn- check-server-name
  "SERVER validated as a NAME. Two rules, and the second is the load-bearing one:

    - it has to survive the trip into a provider's `function.name`;
    - it must not contain `__`, because `mcp__<server>__<tool>` has to be
      INVERTIBLE. With `__` allowed, server `a__b` + tool `c` and server `a` +
      tool `b__c` are the same string -- two different hands sharing one name, a
      collision the model cannot see and no log line records."
  [abs server]
  (when-not (string? server)
    (fail (str abs " server names must be strings, got " (pr-str server))
          {:path abs :server server :reason :not-a-name}))
  (when-not (re-matches server-name-re server)
    (fail (str abs " server name " (pr-str server) " is not usable: a name becomes part of every"
               " tool name it provides (`mcp__<server>__<tool>`), so it must match "
               (str server-name-re))
          {:path abs :server server :reason :bad-name}))
  (when (str/includes? server "__")
    (fail (str abs " server name " (pr-str server)
               " contains `__`, which is the separator in `mcp__<server>__<tool>`; with it"
               " allowed, two different servers could produce one tool name")
          {:path abs :server server :reason :separator-in-name}))
  server)

(defn- read-mcp-edn
  "One mcp.edn FILE's servers, or NIL when the file does not exist -- nil rather
  than {} because the two levels are told apart by EXISTENCE, and 'this project
  declared no servers' has to be distinguishable from 'this project declared
  nothing at all'. A file that EXISTS but is broken -- not valid EDN, not a map,
  an unknown top-level key, a declaration that does not validate -- is a hard,
  NAMED failure with the absolute path. An ignored mcp.edn is indistinguishable
  from one that says nothing, and the difference is the whole meaning of the file."
  [file]
  (let [f (io/file file)]
    (if-not (.exists f)
      nil
      (let [abs (.getAbsolutePath f)
            raw (try (edn/read-string (slurp f :encoding "UTF-8"))
                     (catch Exception e
                       (fail (str abs " is not valid EDN (" (ex-message e) ")")
                             {:path abs :reason :invalid-edn})))]
        (when-not (map? raw)
          (fail (str abs " must be an EDN map of {:servers {name declaration}}")
                {:path abs :reason :not-a-map}))
        (let [unknown (sort (remove #{:servers} (keys raw)))]
          (when (seq unknown)
            (fail (str abs " has unknown key(s) " (pr-str (vec unknown))
                       "; mcp.edn carries exactly one: :servers")
                  {:path abs :unknown (vec unknown)})))
        (let [servers (:servers raw)]
          (when-not (or (nil? servers) (map? servers))
            (fail (str abs " :servers must be a map of name -> declaration, not "
                       (pr-str (type servers)))
                  {:path abs :reason :not-a-map}))
          (into {}
                (map (fn [[name decl]]
                       [(check-server-name abs name)
                        (check-server abs name decl)]))
                (or servers {})))))))

(defn config
  "The server declarations for THREAD-ID, ON DISK: the configuration home's
  mcp.edn (the USER level), unless the bound project has a `.harness/mcp.edn` of
  its own -- in which case THAT is the configuration.

  PROJECT WINS BY REPLACING, not by merging, and `:servers` being the file's only
  key is what makes those the same sentence: the rule is 'a shallow merge of
  top-level keys, project wins', and the one top-level key is :servers. So a
  project that declares servers declares its own set, and a project that declares
  none (`{}`, a file that exists) declares none at all. Same rule as hooks.edn
  (per point) and harness.edn (per key), and for the same reason: 'what will
  actually run' should be readable in one file, not inferred from how two files
  nest. The cost is the accepted one -- a project wanting one server from the user
  level and one of its own writes both.

  An unbound session sees the user level alone. Every call re-reads (the
  config.edn discipline), so an edit takes effect at the next use of the table,
  not at the next restart."
  ([] (config nil))
  ([thread-id]
   (or (when-let [dir (project/binding-for thread-id)]
         (read-mcp-edn (io/file dir ".harness" "mcp.edn")))
       (read-mcp-edn (home/mcp-file))
       {})))

;; ----------------------------------------------------------------- the client
;;
;; One connection is one JSON-RPC conversation with one server. Messages are
;; matched to requests BY ID, not by order: two tool calls of one turn run on
;; their own threads (harness.loop), so two requests can be in flight on one
;; connection and the answers may come back in either order.

(def ^:private elicitation-method
  "The MCP request a server sends when it wants the user to type something. The
  one server->client request this client answers."
  "elicitation/create")

(defn- answer-for
  "The human's decision, as the MCP response it becomes.

  BOTH DIRECTIONS ARE THE PERSON'S, and that is why neither is a hook's to give:
  a rule may answer an approval (that is a delegation), but 'fill in this form for
  me' is not something a rule can do. The verdict comes from the same parked
  record an approval uses -- resume's `resolved` carries the form's values,
  `cancelled` carries which kind of no it was."
  [decision]
  (case (:verdict decision)
    :approved {:action "accept" :content (or (:payload decision) {})}
    :vetoed   (let [p (:payload decision)]
                (case (get p :action)
                  "cancel" {:action "cancel"}
                  {:action "decline"}))))

(def ^:private not-implemented
  "The JSON-RPC code for a method this client does not have."
  -32601)

(defn- deliver-suspension!
  "Make the call that is holding this connection park, by handing the question to
  whoever is waiting on its `tools/call`.

  The reader thread cannot suspend anything itself -- suspending means unwinding
  out of a tool body -- so this is the hand-off: the waiting thread is the one with
  something to unwind. Oldest first when a server has several calls in flight: MCP
  attaches no correlation to an elicitation, so the choice has to be made here
  rather than read off the message, and the oldest has waited longest.

  False when there is no call to suspend -- which is the server asking outside any
  tool call, a case this client cannot put to a person."
  [in-flight pending question]
  (let [[id _] (->> @in-flight
                    (filter (fn [[_ v]] (= "tools/call" (:method v))))
                    (sort-by key)
                    first)]
    (if (nil? id)
      false
      (do (swap! in-flight dissoc id)
          (when-let [p (get @pending id)] (deliver p {:mcp-suspend question}))
          true))))

(defn- stdio-client
  "A connection to the server SERVER runs as COMMAND in DIR. Throws when the
  process cannot be started at all; everything after that is this connection's
  business and comes back as a NAMED failure from the request that hit it.

  A CONNECTION IS A CACHE, NOT A FACT. It knows when it has stopped being usable
  (`:why-dead`), and a caller that finds it dead DROPS it and starts another --
  which is why `:drop!` exists and why the process is killed with it. A dead
  process left running would be a leak, and a live process behind a broken
  conversation cannot be repaired: the messages after the broken one would be
  answers to questions nobody asked.

  IT ALSO ANSWERS THE SERVER'S QUESTIONS. A server may ask the user for input
  mid-call (`elicitation/create`), the one server->client request this client
  implements. The answer is a PERSON's, so the call parks and the reply is sent on
  a later run; see `on-elicit`.

  Returns {:request! :notify! :close! :stderr :alive? :why-dead :drop!}."
  [{:keys [server command dir env timeout-ms]}]
  (let [handle (shell/start {:command command :dir dir :env env})
        pending (atom {})
        ;; id -> {:method .. :ctx ..}, for the requests a server's QUESTION has to
        ;; be attached to. MCP gives an elicitation no correlation id, so the only
        ;; thing to go on is which call is in flight when it arrives.
        in-flight (atom {})
        next-id (atom 0)
        dead (atom nil)
        die! (fn [why]
               (when-not @dead
                 (reset! dead why)
                 (doseq [[_ p] @pending] (deliver p {:mcp-dead why}))))
        ;; A conversation that cannot be trusted AND a process that should not be
        ;; left running: the two always go together here.
        drop! (fn [why]
                (die! why)
                ((:close! handle)))
        write-line! (:write-line! handle)
        ;; THE SERVER ASKS, AND SOMEBODY HAS TO ANSWER. Two cases, and the
        ;; difference is whether a person has already spoken:
        ;;
        ;;   - the call this question belongs to has a decided parked record, so
        ;;     this is the resume run re-issuing the call: answer the server and
        ;;     let the call finish;
        ;;   - there is no decision yet, so the call parks.
        on-elicit
        (fn [id msg]
          (let [params (:params msg)
                question {:server server
                          :prompt (:message params)
                          :schema (:requestedSchema params)
                          ;; THE SERVER IS HOLDING A REQUEST OPEN, so this question
                          ;; has a deadline. Past it the server has stopped asking,
                          ;; and an answer then would be an answer to nobody.
                          :expires-at (+ (System/currentTimeMillis) timeout-ms)}
                ctx (->> @in-flight
                         (filter (fn [[_ v]] (= "tools/call" (:method v))))
                         (sort-by key)
                         (map (comp :ctx val))
                         first)
                rec (when-let [{:keys [thread-id tool-call-id]} ctx]
                      (when-let [r (parked/parked-for-call thread-id tool-call-id)]
                        (when (= :elicitation (:reason r)) r)))
                decision (when rec (parked/take-decision! (:interrupt-id rec)))]
            (hook/emit :elicitation {:server server :request question})
            (cond
              ;; The person came back too late. NOTHING goes to the server -- a
              ;; made-up answer is a lie written into a protocol -- and the
              ;; connection goes, because a request nobody will ever answer is
              ;; exactly what 02 says to abandon.
              (and decision rec (> (System/currentTimeMillis) (:expires-at rec)))
              (do (drop! (str "the question expired unanswered after " timeout-ms "ms"))
                  (fail (str "the question from " server " expired unanswered after "
                             timeout-ms "ms")
                        {:reason :elicitation-expired :server server}))

              decision
              (let [answer (answer-for decision)]
                (hook/emit :elicitation-result {:server server :response answer})
                (write-line! (json/write-str {:jsonrpc "2.0" :id id :result answer})))

              ;; Nobody has answered yet: park the call.
              (deliver-suspension! in-flight pending question) nil

              ;; A question with no call behind it: the server is asking outside
              ;; any tool call, which this client has no way to put to a person.
              :else
              (write-line! (json/write-str
                            {:jsonrpc "2.0" :id id
                             :error {:code not-implemented
                                     :message "no tool call is in flight to ask about"}})))))
        ;; One line from the server, routed. Lines are newline-delimited JSON-RPC
        ;; (the stdio framing), so a line that is NOT JSON is the server's own
        ;; protocol error, named as one rather than skipped: a server printing a
        ;; banner to stdout is a server whose next protocol message cannot be
        ;; trusted to be one.
        dispatch!
        (fn [line]
          (let [msg (try (json/read-str line :key-fn keyword)
                         (catch Exception e
                           (die! (str "the server wrote a line that is not JSON ("
                                      (ex-message e) "): " (pr-str line)))
                           nil))]
            (when (map? msg)
              (let [id (:id msg)]
                (cond
                  ;; A response: hand it to whoever is waiting on that id.
                  (and (some? id) (or (contains? msg :result) (some? (:error msg))))
                  (when-let [p (get @pending id)] (deliver p msg))

                  ;; A server -> client REQUEST.
                  (and (some? id) (some? (:method msg)))
                  (if (= elicitation-method (:method msg))
                    (on-elicit id msg)
                    ;; Anything else: refused in the protocol's own vocabulary
                    ;; rather than dropped. A server left waiting on an answer it
                    ;; will never get is a hang, and a hang is worse than a
                    ;; refusal -- and guessing at a method this client does not
                    ;; have would be worse than both.
                    (write-line! (json/write-str
                                  {:jsonrpc "2.0" :id id
                                   :error {:code not-implemented
                                           :message (str "this client does not implement "
                                                         (:method msg))}})))

                  ;; A notification. Nothing here is waiting on it.
                  :else nil)))))
        ;; The reader. It never blocks a caller: a request waits on its own
        ;; promise, so a slow or silent server costs that request its timeout
        ;; rather than wedging whoever asked.
        ;;
        ;; AND IT MUST NOT DIE QUIETLY. A future that throws disappears, and what
        ;; that looks like from outside is a connection that answers nothing --
        ;; i.e. exactly the 60-second hang this loop exists to prevent. So the
        ;; whole loop is guarded and a crash in it becomes a named reason, the
        ;; same as a server that closes its pipe.
        _ (future
            (try
              (loop []
                (let [v ((:next-line handle) 250)]
                  (cond
                    (shell/eof? v)
                    (die! (let [e (str/trim ((:stderr handle)))]
                            (if (str/blank? e)
                              "the server closed its output"
                              (str "the server closed its output; its stderr said: " e))))

                    (shell/timeout? v) (recur)

                    :else (do (dispatch! v) (recur)))))
              (catch Throwable t
                (die! (str "reading from the server failed: " (ex-message t))))))
        request!
        ;; VARIADIC rather than two arities, because a `let` binding cannot refer
        ;; to itself and the one-argument form would have to.
        (fn [method params & [ctx]]
          (when-let [why @dead]
             (fail (str "the server is not usable: " why) {:reason :dead :why why}))
           (let [id (swap! next-id inc)
                 p  (promise)]
             (swap! pending assoc id p)
             (when ctx (swap! in-flight assoc id {:method method :ctx ctx}))
             ;; AND CHECK AGAIN, because the check above and this registration are
             ;; not one step: a server that dies in between -- the common case for
             ;; a command that does not exist, which exits before anyone speaks to
             ;; it -- would leave this promise nobody will ever deliver, and the
             ;; caller would sit here until its timeout. A dead connection has to
             ;; cost a named failure, not a wait.
             (when-let [why @dead]
               (deliver p {:mcp-dead why}))
             (when-not (write-line! (json/write-str
                                     (cond-> {:jsonrpc "2.0" :id id :method method}
                                       (some? params) (assoc :params params))))
               (swap! pending dissoc id)
               (fail (str "could not write to the server (" (str/trim ((:stderr handle))) ")")
                     {:reason :write-failed}))
             (let [answer (deref p timeout-ms ::timeout)]
               (swap! pending dissoc id)
               (swap! in-flight dissoc id)
               (cond
                 ;; THE SERVER ASKED SOMETHING AND NOBODY HAS ANSWERED YET. The
                 ;; reader thread put this here instead of blocking on it (it must
                 ;; keep reading), so the suspension happens on THIS thread -- the
                 ;; one inside the tool body, where unwinding out of the call
                 ;; means something. It throws, and the seam catches it.
                 (:mcp-suspend answer)
                 (parked/suspend! (:thread-id ctx) (:tool-call-id ctx) (:mcp-suspend answer))

                 (= ::timeout answer)
                 ;; AND THE CONNECTION GOES WITH IT. A request still in flight is
                 ;; not waiting politely: it will answer eventually, and every
                 ;; message after it would then be off by one -- a response read
                 ;; as the next request's. Killing the conversation is the only
                 ;; way the next call can be sure what it is reading.
                 (do (drop! (str "no answer to " method " within " timeout-ms "ms"))
                     (fail (str "the server did not answer " method " within " timeout-ms "ms")
                           {:reason :timeout :method method :timeout-ms timeout-ms}))

                 (:mcp-dead answer)
                 (fail (str "the server stopped answering: " (:mcp-dead answer))
                       {:reason :dead :why (:mcp-dead answer)})

                 (map? (:error answer))
                 (fail (str "the server refused " method ": "
                            (or (:message (:error answer)) (pr-str (:error answer))))
                       {:reason :rpc :method method :error (:error answer)})

                 :else (:result answer)))))]
    {:request! request!
     :notify!  (fn [method params]
                 (write-line! (json/write-str
                               (cond-> {:jsonrpc "2.0" :method method}
                                 (some? params) (assoc :params params)))))
     ;; EACH OF THESE CALLS THROUGH TO THE HANDLE, with the double parens that
     ;; make it a call. `#(:close! handle)` would be a fn that RETURNS the
     ;; handle's fn -- a bug that reads as 'the process is never killed' and hides
     ;; in plain sight, because every one of these is only ever used by writing
     ;; `(client :close!)` and expecting something to have happened.
     :stderr   (fn [] ((:stderr handle)))
     :alive?   (fn [] ((:alive? handle)))
     :why-dead (fn [] @dead)
     :drop!    drop!
     :close!   (fn [] ((:close! handle)))}))

(defn- stdio-command
  "The command LINE a stdio declaration becomes. The declaration gives a command
  and its arguments separately, while `harness.shell` spawns a line through the
  shell -- so the arguments are quoted as POSIX words on the way in. Nothing here
  is trusted to be free of spaces."
  [{:keys [command args]}]
  (str/join " " (cons command (map shell/quote-arg args))))

;; ------------------------------------------------------------ the http client
;;
;; THE SAME CONNECTION INTERFACE as the stdio one, because everything above this
;; line -- the roster, the bridge, the timeout, the failure isolation -- is about
;; MCP rather than about a pipe. The one thing that genuinely differs is that
;; there is no process to own: `close!` has nothing to release, `stderr` is empty
;; because there is no stderr, and a failure is per-request rather than a death.

(def ^:private next-id*
  "The http client's request counter, in its own atom because it has no per-request
  closure to live in. Ids only have to be unique within one conversation, and the
  server echoes them back; a shared counter is therefore fine and simpler than a
  per-connection one."
  (atom 0))

(defn- clip-snippet
  "TEXT, cut to something a log line can carry. A server that answers a 200-byte
  error with an HTML page should not put the whole page into the audit trail."
  [text]
  (let [t (str/trim (str text))]
    (if (> (count t) 300) (str (subs t 0 300) "...") t)))

(def ^:private sse-data
  "The prefix an event-stream line carries its payload under. Anything else in
  that stream (event names, ids, comments, the blank line between events) is
  framing we do not use."
  "data:")

(defn- read-sse-message
  "The first JSON-RPC message on an event stream whose id is ID -- or nil when the
  stream ends without one.

  ONE message per request is what this client needs: MCP over HTTP answers a POST
  with the response to that POST (possibly streamed while the server works), so
  anything else on the stream is either a notification -- which no one here is
  waiting for -- or something this request does not know about. Reading until OUR
  id is the whole matching rule."
  [^java.io.InputStream body id timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (with-open [r (io/reader body :encoding "UTF-8")]
      (loop [data nil]
        (let [line (try (.readLine r) (catch Exception _ nil))]
          (cond
            (nil? line)
            (when (seq data)
              (let [m (try (json/read-str data :key-fn keyword) (catch Exception _ nil))]
                (when (= id (:id m)) m)))

            (> (System/currentTimeMillis) deadline) nil

            (str/starts-with? line sse-data)
            (let [chunk (str/trim (subs line (count sse-data)))
                  m (try (json/read-str chunk :key-fn keyword) (catch Exception _ nil))]
              ;; A complete message on one line (the ordinary case) is answered
              ;; now; otherwise the pieces are joined for the blank line that
              ;; ends the event.
              (if (and (map? m) (= id (:id m)))
                m
                (recur (if (str/blank? data) chunk (str data "\n" chunk)))))

            (str/blank? line)
            (let [m (try (json/read-str data :key-fn keyword) (catch Exception _ nil))]
              (if (and (map? m) (= id (:id m))) m (recur nil)))

            :else (recur data)))))))

(defn- http-post
  "One JSON-RPC message to URL, as the server answered it: {:status :type :body}
  or {:threw message}. Nothing here decides what the answer MEANS -- reading it is
  the caller's business, because a stateless server and a broken one are told
  apart by what is in the body, not by whether the request worked."
  [http url timeout-ms session msg]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout (java.time.Duration/ofMillis (long timeout-ms)))
                (.header "Content-Type" "application/json")
                ;; BOTH, because a server may answer either way and which one it
                ;; picks is its business.
                (.header "Accept" "application/json, text/event-stream")
                (.header "MCP-Protocol-Version" protocol-version)
                (cond-> @session (.header "Mcp-Session-Id" @session))
                (.POST (HttpRequest$BodyPublishers/ofString
                        (json/write-str msg) StandardCharsets/UTF_8))
                (.build))]
    (try
      (let [resp (.send http req (HttpResponse$BodyHandlers/ofInputStream))]
        ;; A session id, if the server minted one: carried on every request after
        ;; this. Absent is not a problem -- a stateless server has none to mint.
        (when-let [sid (first (.allValues (.headers resp) "Mcp-Session-Id"))]
          (reset! session sid))
        {:status (.statusCode resp)
         :type   (str (first (.allValues (.headers resp) "Content-Type")))
         :body   (.body resp)})
      (catch java.net.http.HttpTimeoutException _
        ;; THE DECLARATION'S OWN NUMBER, said out loud. The JVM's own sentence for
        ;; this is 'request timed out', which names neither the server nor the
        ;; bound that was hit -- and the reader of a tool result needs both to
        ;; decide whether to raise the timeout or stop using the server.
        {:threw (str "no answer within " timeout-ms "ms")})
      (catch Exception e
        ;; A CONNECTION FAILURE SOMETIMES HAS NO MESSAGE -- a JVM
        ;; ConnectException can carry nil -- and 'no message' must still be a
        ;; failure. The class name stands in, so the answer is never an empty map
        ;; a caller could read as an ordinary response.
        {:threw (or (ex-message e) (.getName (class e)))}))))

(defn- parse-body
  "BODY read as the JSON-RPC message it is, or a NAMED failure. TYPE decides how:
  an event stream is read until the answer to ID, anything else is one document."
  [url type body id timeout-ms]
  (if (str/includes? type "text/event-stream")
    (read-sse-message body id timeout-ms)
    (let [text (try (slurp body :encoding "UTF-8") (catch Exception _ ""))]
      (when-not (str/blank? text)
        (try
          (json/read-str text :key-fn keyword)
          (catch Exception e
            (fail (str url " answered with a body that is not JSON ("
                       (ex-message e) "): " (clip-snippet text))
                  {:reason :bad-body :url url})))))))

(defn- http-client
  "A connection to the MCP server at URL. The same map as `stdio-client`, minus
  the things a remote server has no equivalent for: no process to own, no stderr,
  and a failure that belongs to a request rather than to a death."
  [{:keys [url timeout-ms]}]
  (let [session (atom nil)
        dead (atom nil)
        http (-> (HttpClient/newBuilder)
                 (.version HttpClient$Version/HTTP_1_1)
                 (.connectTimeout (java.time.Duration/ofMillis (long timeout-ms)))
                 (.build))
        exchange (fn [msg] (http-post http url timeout-ms session msg))
        ;; THE SAME ARITY as the stdio client's, so the bridge above does not have
        ;; to know which transport it is talking to. The context is unused here:
        ;; MCP over HTTP cannot ASK a question this client can answer yet (a
        ;; server request would arrive on the response stream, and this client
        ;; reads one stream for one answer -- see the ticket's note), so an
        ;; elicitation over HTTP ends as that request's named timeout rather than
        ;; as a park. Kept in the signature so the two clients stay
        ;; interchangeable, which is the property the whole ticket is about.
        request! (fn [method params & [_ctx]]
                   (when-let [why @dead]
                     (fail (str "the server is not usable: " why) {:reason :dead :why why}))
                   (let [id (swap! next-id* inc)
                         answer (exchange (cond-> {:jsonrpc "2.0" :id id :method method}
                                            (some? params) (assoc :params params)))]
                     ;; ASKED WITH `contains?`, not `when-let`: a thrown-with-no
                     ;; -message is still a throw, and treating it as 'no problem'
                     ;; is how a null status reaches the caller.
                     (when (contains? answer :threw)
                       (fail (str "could not reach " url " (" (:threw answer) ")")
                             {:reason :unreachable :url url}))
                     (let [{:keys [status type body]} answer]
                       (when-not (<= 200 status 299)
                         (let [text (try (slurp body :encoding "UTF-8") (catch Exception _ ""))]
                           (fail (str url " answered HTTP " status
                                      (when-not (str/blank? text)
                                        (str ": " (clip-snippet text))))
                                 {:reason :http :url url :status status})))
                       (let [m (parse-body url type body id timeout-ms)]
                         (cond
                           (nil? m)
                           (fail (str url " sent no answer to " method)
                                 {:reason :no-answer :url url :method method})

                           (map? (:error m))
                           (fail (str "the server refused " method ": "
                                      (or (:message (:error m)) (pr-str (:error m))))
                                 {:reason :rpc :method method :error (:error m)})

                           :else (:result m))))))]
    {:request! request!
     :notify!  (fn [method params]
                 (exchange (cond-> {:jsonrpc "2.0" :method method}
                             (some? params) (assoc :params params)))
                 nil)
     ;; No process, so no stderr to report and nothing to be alive or dead; the
     ;; session id is the only thing that can go stale, and `drop!` forgets it so
     ;; the next use shakes hands again.
     :stderr   (constantly "")
     :alive?   (constantly (nil? @dead))
     :why-dead (fn [] @dead)
     :drop!    (fn [why] (reset! dead why) (reset! session nil))
     :close!   (fn [] (reset! dead "the connection was closed") nil)}))

;; ---------------------------------------------------------------- the bridge

(defn- bridge
  "One server tool -> one row of our table, or a named skip.

  THE TWO FIELDS THAT MUST NOT DRIFT are `:parameters` and `:required`: the first
  is what the model is shown, the second is what the execution seam checks a call
  against. `inputSchema` is already JSON Schema, so the first is it verbatim --
  but `required` lives INSIDE that schema as strings, while the seam wants
  keywords, so the second is derived from it rather than guessed. A mismatch here
  is a model told it forgot an argument it did give.

  TIMEOUT-MS is carried so a failed call can SAY what it waited for. The message
  the model reads has to name the server, the tool and the number: a tool result
  that says only 'it did not answer' leaves the reader with nothing to act on --
  raise the timeout, or stop using that server -- and those are different
  decisions."
  [server thread-id client timeout-ms {:keys [name description inputSchema]}]
  (let [full (str "mcp__" server "__" name)]
    (if-not (re-matches bridged-name-re full)
      {:skipped full
       :why (str "the name a provider would see is " (count full)
                 " characters and must match " (str bridged-name-re)
                 "; it is dropped rather than truncated, because truncating would"
                 " make two tools share one name")}
      {:tool full
       :def {:description (if (str/blank? (str description))
                            (str "Provided by the MCP server " (pr-str server) ".")
                            (str description))
             :parameters (or inputSchema {:type "object" :properties {}})
             :required (mapv keyword (:required inputSchema))
             :source :mcp
             :run (fn [args]
                    (let [result (try
                                   ;; THE CONTEXT is what a server's question
                                   ;; needs to find this call: MCP's
                                   ;; elicitation carries no correlation, so the
                                   ;; caller supplies the identity of the call it
                                   ;; is inside, and the client hands it back when
                                   ;; the question arrives.
                                   ((:request! client) "tools/call"
                                                       {:name name :arguments (or args {})}
                                                       {:thread-id thread-id
                                                        :tool-call-id parked/*tool-call-id*})
                                   (catch Exception e
                                     ;; Every failure of a server call is answered
                                     ;; in the server's and the tool's name, with
                                     ;; the client's own sentence kept after it --
                                     ;; an author's message beats a message about
                                     ;; the author, and the seam turns this into
                                     ;; the call's ERROR RESULT, not a failed run.
                                     (throw (ex-info
                                             (str "MCP server " (pr-str server) " failed on "
                                                  (pr-str name) ": " (ex-message e))
                                             (assoc (ex-data e) :server server :tool name)))))
                          ;; Every text part, in order: that is what the model
                          ;; reads. Other part kinds (images, embedded resources)
                          ;; are not this ticket's business and are not invented
                          ;; into the string either -- an empty answer with
                          ;; isError absent says exactly what came back.
                          text (->> (:content result)
                                    (filter #(= "text" (:type %)))
                                    (map :text)
                                    (str/join "\n"))]
                      (if (:isError result)
                        (throw (ex-info (if (str/blank? text)
                                          "the server reported an error with no message"
                                          text)
                                        {:server server :tool name}))
                        text)))}})))

(defn- open-connection
  "The connection a declaration names, chosen by WHICH KEY IT CARRIES: `:url`
  means a remote server, `:command` means a local process. Validation has already
  refused a declaration with both or neither, so this is a total dispatch rather
  than a guess -- and it is the only place that knows the difference, which is why
  nothing above it can accidentally spawn a URL or fetch a command."
  [server decl dir]
  (if-let [url (:url decl)]
    (http-client {:url url :timeout-ms (:timeout decl default-timeout-ms)})
    (stdio-client {:server server
                   :command (stdio-command decl)
                   :dir dir
                   :env (:env decl)
                   :timeout-ms (:timeout decl default-timeout-ms)})))

(defn- connect!
  "Open SERVER, shake hands, and ask what it can do. Everything a connection needs
  to be usable happens here, so a caller gets either a usable connection or an
  exception naming what went wrong -- and the two transports are indistinguishable
  from this point on."
  [server decl dir thread-id]
  (let [client (open-connection server decl dir)
        where  (or (:url decl) (:command decl))]
    (try
      ((:request! client) "initialize"
       {:protocolVersion protocol-version
        :capabilities {}
        :clientInfo {:name "clj-harness" :version "1"}})
      ((:notify! client) "notifications/initialized" nil)
      (let [listed  ((:request! client) "tools/list" {})
            bridged (mapv #(bridge server thread-id client
                                   (:timeout decl default-timeout-ms) %)
                          (:tools listed))]
        {:decl    decl
         :client  client
         :tools   (into {} (map (juxt :tool :def)) (remove :skipped bridged))
         :skipped (mapv #(select-keys % [:skipped :why]) (filter :skipped bridged))})
      (catch Throwable t
        ((:drop! client) (str "the handshake failed: " (ex-message t)))
        (throw (ex-info (str "server " (pr-str server) " (" where ") could not be used: "
                             (ex-message t))
                        (assoc (ex-data t) :server server :where where)))))))

;; ------------------------------------------------------------- the read side


(defn- usable?
  "Is CONN still the connection this declaration should be using? Two ways to fail,
  and they are the two the file cannot tell you about:

    - the DECLARATION moved. The file is read fresh, so a connection still keyed
      to the old command is a cache lying about what is in force.
    - the CONVERSATION ended -- the process died, or it wrote something that was
      not protocol. A roster is only as good as the process that answered it."
  [conn decl]
  (and (some? conn)
       (= decl (:decl conn))
       (nil? ((:why-dead (:client conn))))))

(defn- forget!
  "Take KEY's connection out of the cache and make sure its process is gone.
  Idempotent, and safe on a connection that has already died."
  [key]
  (when-let [old (get @connections key)]
    (swap! connections dissoc key)
    (try ((:close! (:client old))) (catch Exception _ nil))
    old))

(defn- server-connection
  "The connection for SERVER in this project: the cached one when it is still the
  right one, and otherwise a NEW one.

  RECONNECTING IS THE SAME DECISION AS CONNECTING, taken again. There is no
  'revive' path, because a broken conversation cannot be resumed -- the only way
  to be sure what the next message means is to start a new one. The previous
  connection's death is carried into the new one's report (`:restarted-after`), so
  the audit trail shows the two facts together instead of a server that silently
  came back."
  [identity dir server decl thread-id]
  (let [key [identity server]
        old (get @connections key)]
    (if (usable? old decl)
      old
      (let [why (when old ((:why-dead (:client old))))]
        (forget! key)
        (let [conn (connect! server decl dir thread-id)]
          (swap! connections assoc key (cond-> conn
                                         why (assoc :restarted-after why)))
          conn)))))

(defn- reap!
  "Close and forget the connections for IDENTITY whose server is no longer
  declared. The third way a connection can stop being the right one, and the one
  the cache cannot notice by itself: a declaration that has been DELETED leaves
  nothing left to compare against, so the sweep has to be told what is still live.

  Its tools leave the table for free -- `tools-for` walks the declarations, and a
  server that is not there is not walked -- so this is only about the PROCESS. A
  server the file no longer declares must not still be running."
  [identity live]
  (doseq [key (keys @connections)
          :let [[id server] key]
          :when (and (= id identity) (not (contains? live server)))]
    (forget! key)))

(defn- note-outcome!
  "Remember what happened to SERVER, and queue the fact for the audit trail WHEN
  IT IS NEWS.

  ONLY A CHANGE IS QUEUED, and that is not an optimisation: `tools-for` runs on
  the way to every LLM request, so queueing eagerly would write an `mcp/server`
  audit line per request and the trail would be noise instead of a record of what
  happened. The status is still written every time -- it is the live answer, not
  a log."
  [identity server decl outcome]
  (let [key [identity server]
        fact (cond-> {:server server :project identity :status (:status outcome)}
               (:error outcome)   (assoc :error (str (:error outcome)))
               (:command decl)    (assoc :command (:command decl))
               (:restarted outcome) (assoc :restarted-after (:restarted outcome))
               (seq (:tools outcome))   (assoc :tools (vec (sort (keys (:tools outcome)))))
               (seq (:skipped outcome)) (assoc :skipped (:skipped outcome)))
        before (get @states key)]
    (swap! states assoc key fact)
    (when-not (= (dissoc before :at) fact)
      (swap! events conj fact))))

(defn tools-for
  "NAME->TOOL for THREAD-ID from every usable MCP server it declares. The tool
  table folds this in beside its built-ins, which is the point: a tool from a
  server is an ordinary row, not a second kind of thing.

  A server that cannot be used contributes nothing and is recorded as a failure
  (see `status` and `take-events!`); every other server is unaffected. Called on
  the way to EVERY LLM request (harness.tools/specs), so a connection and its
  roster are reused rather than re-established per call.

  IT IS ALSO WHERE A BROKEN SERVER IS NOTICED, because assembly is the one thing
  that happens on every run whatever else the model decided to do: a server that
  died is found here, replaced here, and its roster taken again -- so a server
  that gains or loses a tool while it was down has a table that follows."
  [thread-id]
  (let [identity (project/identity-for thread-id)
        decls    (config thread-id)]
    ;; A declaration that is gone leaves its process running otherwise: the walk
    ;; below only ever visits what the file still says, so nothing else would
    ;; ever look at what it stopped saying.
    (reap! identity (set (keys decls)))
    (into {}
          (mapcat (fn [[server decl]]
                    (try
                      (let [conn (server-connection identity identity server decl thread-id)]
                        (note-outcome! identity server decl
                                       {:status :connected
                                        :tools (:tools conn)
                                        :skipped (:skipped conn)
                                        :restarted (:restarted-after conn)})
                        (:tools conn))
                      (catch Throwable t
                        ;; One server down is one absent source of tools, never a
                        ;; broken run -- and it is NAMED, so 'these tools are
                        ;; missing' has an answer other than a shrug.
                        (note-outcome! identity server decl
                                       {:status :failed :error (ex-message t)})
                        {})))
                  decls))))

(defn status
  "What this harness knows about the servers THREAD-ID declares, in name order:
  one entry per declared server, with the outcome of the last time it was used.

  A server declared but not yet used is `:idle` rather than absent -- the roster
  is assembled on the way to a run, so before the first run nothing has been
  asked, and saying so is the honest answer."
  [thread-id]
  (let [decls (config thread-id)
        id    (project/identity-for thread-id)]
    (mapv (fn [[server _decl]]
            (merge {:server server :status :idle}
                   (get @states [id server])))
          (sort-by key decls))))

(defn take-events!
  "Everything that has happened to a server since the last call, oldest first --
  the edge turns each into one `mcp/server` audit line. Draining rather than
  reading is what keeps a line from being written twice, exactly as the provider
  outbox works."
  []
  (let [mine @events]
    (swap! events (fn [all] (vec (drop (count mine) all))))
    mine))

(defn shutdown!
  "Close every connection and forget it. For tests and for a process on its way
  out; nothing in a running harness calls this, because a server is meant to
  outlive a run."
  []
  (doseq [[_ {:keys [client]}] @connections]
    (try (client :close!) (catch Exception _ nil)))
  (reset! connections {})
  (reset! states {})
  (reset! events [])
  nil)

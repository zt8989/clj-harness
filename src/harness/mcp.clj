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
            [harness.project :as project]
            [harness.shell :as shell]))

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

(def ^:private not-implemented
  "The JSON-RPC code for a method this client does not have."
  -32601)

(defn- stdio-client
  "A connection to the server COMMAND runs in DIR. Throws when the process cannot
  be started at all; everything after that is this connection's business and comes
  back as a NAMED failure from the request that hit it.

  Returns {:request! :notify! :close! :stderr :alive?}."
  [{:keys [command dir env timeout-ms]}]
  (let [handle (shell/start {:command command :dir dir :env env})
        pending (atom {})
        next-id (atom 0)
        dead (atom nil)
        die! (fn [why]
               (when-not @dead
                 (reset! dead why)
                 (doseq [[_ p] @pending] (deliver p {:mcp-dead why}))))
        write-line! (:write-line! handle)
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

                  ;; A server -> client REQUEST. Nothing in this ticket answers
                  ;; one, so it is refused in the protocol's own vocabulary rather
                  ;; than dropped: a server left waiting on an answer it will never
                  ;; get is a hang, and a hang is worse than a refusal.
                  ;; (Elicitation becomes a real answer in its own ticket.)
                  (and (some? id) (some? (:method msg)))
                  (write-line! (json/write-str
                                {:jsonrpc "2.0" :id id
                                 :error {:code not-implemented
                                         :message (str "this client does not implement "
                                                       (:method msg))}}))

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
        (fn [method params]
          (when-let [why @dead]
            (fail (str "the server is not usable: " why) {:reason :dead :why why}))
          (let [id (swap! next-id inc)
                p  (promise)]
            (swap! pending assoc id p)
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
              (cond
                (= ::timeout answer)
                (fail (str "the server did not answer " method " within " timeout-ms "ms")
                      {:reason :timeout :method method :timeout-ms timeout-ms})

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
     :stderr   #(:stderr handle)
     :alive?   #(:alive? handle)
     :close!   #(:close! handle)}))

(defn- stdio-command
  "The command LINE a stdio declaration becomes. The declaration gives a command
  and its arguments separately, while `harness.shell` spawns a line through the
  shell -- so the arguments are quoted as POSIX words on the way in. Nothing here
  is trusted to be free of spaces."
  [{:keys [command args]}]
  (str/join " " (cons command (map shell/quote-arg args))))

;; ---------------------------------------------------------------- the bridge

(defn- bridge
  "One server tool -> one row of our table, or a named skip.

  THE TWO FIELDS THAT MUST NOT DRIFT are `:parameters` and `:required`: the first
  is what the model is shown, the second is what the execution seam checks a call
  against. `inputSchema` is already JSON Schema, so the first is it verbatim --
  but `required` lives INSIDE that schema as strings, while the seam wants
  keywords, so the second is derived from it rather than guessed. A mismatch here
  is a model told it forgot an argument it did give."
  [server client {:keys [name description inputSchema]}]
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
                    (let [result ((:request! client) "tools/call"
                                                  {:name name :arguments (or args {})})
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

(defn- connect!
  "Spawn SERVER, shake hands, and ask what it can do. Everything a connection
  needs to be usable happens here, so a caller gets either a usable connection or
  an exception naming what went wrong."
  [server decl dir]
  (if (:url decl)
    (fail (str "server " (pr-str server) " is declared with :url, and the HTTP transport is"
               " not implemented yet; declare it with :command for now")
          {:server server :reason :http-not-implemented})
    (let [client (stdio-client {:command (stdio-command decl)
                                :dir dir
                                :env (:env decl)
                                :timeout-ms (:timeout decl default-timeout-ms)})]
      (try
        ((:request! client) "initialize"
         {:protocolVersion protocol-version
          :capabilities {}
          :clientInfo {:name "clj-harness" :version "1"}})
        ((:notify! client) "notifications/initialized" nil)
        (let [listed  ((:request! client) "tools/list" {})
              bridged (mapv #(bridge server client %) (:tools listed))]
          {:decl    decl
           :client  client
           :tools   (into {} (map (juxt :tool :def)) (remove :skipped bridged))
           :skipped (mapv #(select-keys % [:skipped :why]) (filter :skipped bridged))})
        (catch Throwable t
          (client :close!)
          (throw t))))))

;; ------------------------------------------------------------- the read side

(defn- server-connection
  "The connection for SERVER in this project, connecting if there is none or if
  the declaration has CHANGED since it was made. Reconnecting on a changed
  declaration is what makes 'the file is read fresh' true of the command and not
  only of the map: a cache keyed by name alone would keep answering with the old
  process after an edit."
  [identity dir server decl]
  (let [key [identity server]
        old (get @connections key)]
    (if (and old (= decl (:decl old)))
      old
      (do
        (when old
          (try ((:client old) :close!) (catch Exception _ nil))
          (swap! connections dissoc key))
        (let [conn (connect! server decl dir)]
          (swap! connections assoc key conn)
          conn)))))

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
  roster are reused rather than re-established per call."
  [thread-id]
  (let [identity (project/identity-for thread-id)]
    (into {}
          (mapcat (fn [[server decl]]
                    (try
                      (let [conn (server-connection identity identity server decl)]
                        (note-outcome! identity server decl
                                       {:status :connected
                                        :tools (:tools conn)
                                        :skipped (:skipped conn)})
                        (:tools conn))
                      (catch Throwable t
                        ;; One server down is one absent source of tools, never a
                        ;; broken run -- and it is NAMED, so 'these tools are
                        ;; missing' has an answer other than a shrug.
                        (note-outcome! identity server decl
                                       {:status :failed :error (ex-message t)})
                        {})))
                  (config thread-id)))))

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

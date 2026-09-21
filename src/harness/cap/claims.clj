(ns harness.cap.claims
  "WHICH PROCESS IS SERVING A CONVERSATION: one row in the home's store per live
  claim, and the three rules that go with it -- take one, hand one back, take one
  over from a process that is gone.

  WHY THIS EXISTS (ADR 0002 decision 7). The record is written asynchronously and
  is allowed to lag, and a log line carries nothing that says which process put it
  there. Two processes serving one conversation therefore interleave their frames
  into one append-only file, and the result READS as a conversation that happened
  -- not a crash, which is worse, because there is nothing to notice.
  `harness.edge.http/running?` answers this WITHIN one process; this answers it
  ACROSS processes. Neither replaces the other: a second run of one session is
  refused in both worlds, and the local registry is the one that can see a run going
  in this very JVM.

  A ROW IN THE STORE, NOT A LOCK FILE, and the reasons are the store's own
  docstring's: it is already the one place two processes of a home agree about a row,
  `with-transaction` opens with BEGIN IMMEDIATE (so a read-then-write cannot lose an
  update to a racing process), and a claim is exactly the kind of fact the store is
  for -- state, rewritten in place, not a record. A lock file would need a directory
  of its own, an atomic-create dance and its own answer to 'where', and it would
  answer the liveness question below the same way this does, so it would buy nothing
  but a second mechanism.

  WHO THE OWNER IS. Three facts, because two of them answer different questions.
  `instance` is a random id the process mints for itself ONCE, so 'is this row mine?'
  is a string comparison and no OS call at all. `pid` + `started-at` are what a LATER
  process asks the OS about: a pid that is not running is gone, and a pid that IS
  running but STARTED AT A DIFFERENT TIME is a stranger wearing the number of the
  process that died -- without that second fact a reused pid would pin a
  conversation forever.

  NO HEARTBEAT, and that is a decision rather than an omission. A heartbeat needs a
  timer in every process and only proves liveness indirectly, while the OS answers
  the question directly with the same fact the claim recorded. And what a heartbeat
  would buy -- noticing a HUNG process -- is not what this needs: a hung process is
  still the owner, and taking its claim away would be exactly the two-authorities
  failure this namespace exists to prevent. What a stuck process costs is a
  conversation nobody else may serve, which is the honest price of one authority.

  THE LIFETIME IS THE SESSION'S, NOT THE RUN'S (ticket 04, judgement 3 and 4): a
  claim is taken when a process starts SERVING a conversation and handed back when it
  puts it away (`harness.edge.sessions`). Not per run -- a run that ended would hand
  the claim back and the next turn would have to take it again, which means
  re-building a cold session -- and not forever, because an idle session holding a
  claim forever is the thing `max-running` exists to bound.

  THIS NAMESPACE WRITES ONLY HERE. Every verb below is one statement or one
  transaction against `session_claims`; nothing here touches a log, and nothing here
  knows what a session's messages are."
  (:require [harness.infra.db :as db]
            [harness.infra.log :as log])
  (:import (java.lang ProcessHandle)
           (java.util UUID)))

;; --------------------------------------------------------------- who we are

(def ^:private start-tolerance-ms
  "How far the start instant read back from the OS may differ from the one recorded
  in a claim before the claim is called somebody else's, in milliseconds.

  THEY OUGHT TO BE EQUAL -- both come from ProcessHandle, one at claim time and one
  at check time -- so this is only here for a platform that rounds the value
  differently in the two calls. It is a second wide on purpose: the two mistakes are
  not symmetric, and being a second too generous costs a claim that is taken over a
  second later than it could be, while being a millisecond too strict would call a
  LIVE owner dead and hand one conversation to two processes."
  1000)

(def ^:private me
  "This process's identity, minted once and reused for every claim it takes:
  `{:instance .. :pid .. :started-at ..}`.

  A DELAY RATHER THAN A CONSTANT because it asks the OS a question, and a `def`
  would ask it while this namespace loads -- including in a process that never
  claims anything.

  `started-at` is 0 when this platform will not say when the process started. Zero
  is the honest spelling of 'no evidence', and `alive?` reads it as 'check the pid
  and nothing more' rather than as an instant in 1970."
  (delay (let [handle (ProcessHandle/current)]
           {:instance   (str (UUID/randomUUID))
            :pid        (.pid handle)
            :started-at (or (some-> (.startInstant (.info handle)) (.orElse nil) (.toEpochMilli)) 0)})))

(defn this-process
  "This process's identity as it goes into a claim row. FOR A READER -- a refusal
  sentence, a log line, a test -- and the answer is a snapshot of a value that never
  changes."
  []
  @me)

;; ------------------------------------------------------------- is it alive?

(defn- start-instant
  "When the OS says PID started, in epoch milliseconds, or nil when it will not say
  (the process is gone, or this platform does not report start instants)."
  [pid]
  (some-> (ProcessHandle/of (long pid))
          (.orElse nil)
          (.info)
          (.startInstant)
          (.orElse nil)
          (.toEpochMilli)))

(defn- alive?
  "Is the process this claim row describes still that same process?"
  [row]
  (let [pid     (:pid row)
        started (:started-at row)]
    (if-some [handle (and pid (some-> (ProcessHandle/of (long pid)) (.orElse nil)))]
      (and (.isAlive ^ProcessHandle handle)
           (let [now-started (start-instant pid)]
             (or (nil? now-started)
                 (zero? (long (or started 0)))
                 (<= (abs (- (long now-started) (long started))) start-tolerance-ms))))
      false)))

;; --------------------------------------------------------------- the exit

(defn release-all!
  "Hand back every claim this process holds. Called from the JVM-exit hook, and
  callable on its own -- which is how it is tested.

  BY INSTANCE RATHER THAN BY TOKEN, which is safe here and only here: at exit there
  is no session being born, so there is no newer claim of ours for this to delete by
  mistake. Answers the number of rows it let go of."
  []
  (db/with-transaction
    (fn [c]
      (db/execute! c "DELETE FROM session_claims WHERE instance = ?" (:instance @me)))))

(defn install-hook!
  "Hand R to the JVM to run at exit, behind a var so a test can count the
  installations without exiting a JVM. The same seam `harness.edge.record` has, for
  the same reason."
  [^Runnable r]
  (.addShutdownHook (Runtime/getRuntime) r))

(defonce ^:private exit-hook-installed (atom false))

(defn ensure-exit-hook!
  "Install the release-on-exit hook ONCE per process, by compare-and-set rather than
  by a count: two sessions being born at the same moment both want the claim, and
  'did I install it' must have one answer.

  THIS HOOK IS THE ORDINARY WAY A CLAIM IS HANDED BACK, and what it buys is that a
  process that exits normally leaves NOTHING for the next one to clean up. A process
  that is killed cannot run it, and that is what `alive?` is for: the two are
  independent, so neither has to be perfect."
  []
  (when (compare-and-set! exit-hook-installed false true)
    (install-hook! (Thread. ^Runnable (fn [] (try (release-all!) (catch Throwable _ nil)))
                            "harness-claims-shutdown")))
  nil)

(defn reset-exit-hook!
  "Forget that the hook was installed, so the next `ensure-exit-hook!` installs one.
  FOR TESTS, which drive the installation rather than exiting a JVM."
  []
  (reset! exit-hook-installed false))

;; ------------------------------------------------------------------ the rows

(defn- row-for
  "THREAD-ID's claim row as it stands, or nil. NOT filtered by liveness: the callers
  below decide what a dead owner means, and they mean different things by it --
  `holder` answers nil, `take!` takes it over."
  [c thread-id]
  (first (db/query c "SELECT * FROM session_claims WHERE thread_id = ?" (str thread-id))))

(defn holder
  "The process holding THREAD-ID's claim that is still ALIVE, or nil.

  NIL IS ALSO THE ANSWER FOR A STALE ROW -- one left behind by a process that is
  gone -- because the caller's next move is the same as for a conversation nobody
  has claimed: take it. Which process it was taken FROM is `take!`'s news to tell,
  not this read's.

  IT ANSWERS ROWS THIS PROCESS OWNS TOO. 'Is that me?' is `mine?`, and a read that
  answered nil for our own claim would make the two questions one."
  [thread-id]
  (when-some [row (db/select "SELECT * FROM session_claims WHERE thread_id = ?" (str thread-id))]
    (when (alive? (first row))
      (first row))))

(defn mine?
  "Is HELD -- a row `holder` answered, or nil -- this process's own claim?"
  [held]
  (= (:instance held) (:instance @me)))

(defn- refusal
  "The named failure `take!` raises when a live process is in the way."
  [thread-id held]
  (ex-info (str "conversation " (pr-str (str thread-id)) " is being served by another"
                " harness process (pid " (:pid held) ", started "
                (:started-at held) "), so this process will not serve it too:"
                " two processes writing one record is two conversations in one file,"
                " and nothing in the file says which is which")
           {:reason    :session-claimed
            :thread-id (str thread-id)
            :holder    held}))

(defn take!
  "Claim THREAD-ID for this process. Answers {:token ..} -- the name of the claim it
  now holds -- and throws a NAMED refusal when another LIVE process holds it.

  CALLING IT AGAIN IS FREE, and that is not a convenience: the claim's lifetime is
  the session's, but the session can be asked for from more than one place
  (`harness.edge.sessions` builds on the first ask, and the edge asks the same
  question earlier to answer a client with a status rather than a mid-stream error),
  and two racers must not be two claims. A row this process already owns is rewritten
  with a fresh token -- see `release!` for why the token moves.

  TAKING OVER A STALE ROW IS NEWS and is logged as such: the process that left it did
  not hand it back, and whoever reads the log afterwards should be able to tell that
  this conversation changed hands. It is not a failure -- a `kill -9` is the case
  this path exists for -- so it is a WARNING with both pids in it."
  [thread-id]
  ;; THE HOOK IS INSTALLED HERE, by the first claim, rather than by whoever started a
  ;; server: a claim is what needs handing back at exit, and a process that claims
  ;; WITHOUT serving http (a script, a test, a command-line tool) is exactly the one
  ;; whose leftover row nobody would think to look for. Idempotent, so paying for it
  ;; per claim costs a compare-and-set.
  (ensure-exit-hook!)
  (let [id (str thread-id)
        us @me
        token (fn [] (str (UUID/randomUUID)))
        ours  (fn [t] {:token t})]
    (db/with-transaction
      (fn [c]
        (let [row (row-for c id)]
          (cond
            ;; NOBODY HAS IT: the ordinary birth of a conversation in this process.
            (nil? row)
            (let [t (token)]
              (db/execute! c "INSERT INTO session_claims
                                (thread_id, instance, token, pid, started_at, since)
                              VALUES (?, ?, ?, ?, ?, ?)"
                           id (:instance us) t (:pid us) (:started-at us)
                           (System/currentTimeMillis))
              (ours t))

            ;; OURS ALREADY: keep it, and move the token forward so a release that is
            ;; still in flight for the OLD token cannot delete this claim.
            (mine? row)
            (let [t (token)]
              (db/execute! c "UPDATE session_claims
                                 SET token = ?, pid = ?, started_at = ?, since = ?
                               WHERE thread_id = ?"
                           t (:pid us) (:started-at us) (System/currentTimeMillis) id)
              (ours t))

            ;; SOMEBODY ALIVE HAS IT: refuse, with their name in the sentence.
            (alive? row)
            (throw (refusal id row))

            ;; SOMEBODY IS GONE AND LEFT IT: take it, and say so.
            :else
            (let [t (token)]
              (db/execute! c "UPDATE session_claims
                                 SET instance = ?, token = ?, pid = ?, started_at = ?,
                                     since = ?
                               WHERE thread_id = ?"
                           (:instance us) t (:pid us) (:started-at us)
                           (System/currentTimeMillis) id)
              (log/warn! :claim/taken-over
                         {:thread-id     id
                          :from-pid      (:pid row)
                          :from-instance (:instance row)
                          :pid           (:pid us)})
              (ours t))))))))

(defn release!
  "Hand back the claim named by TOKEN, if this process still holds it under that
  token. Answers true when a row was actually deleted.

  THE TOKEN IS THE WHOLE REASON THIS IS NOT `DELETE WHERE thread_id = ?`. A release
  happens at the END of a session's life, and the session can be born AGAIN in the
  same process between deciding to put it away and saying so (`sweep!` removes in one
  swap and releases after it; a request arriving in that window builds a new entry).
  Deleting by thread id would then delete the NEW claim -- leaving a live session
  unclaimed, and the next process along free to take a conversation this one is
  still serving. The token belongs to the CLAIM, so a stale release finds a row that
  is no longer its own and does nothing."
  [thread-id token]
  (let [id (str thread-id)]
    (pos? (db/with-transaction
            (fn [c]
              (db/execute! c "DELETE FROM session_claims WHERE thread_id = ? AND token = ?"
                           id (str token)))))))

(defn hand-over!
  "Move THREAD-ID's claim row from FROM-TOKEN to TO-TOKEN, when this process still
  holds it under FROM-TOKEN. Answers true when the row moved.

  FOR THE ONE RACE TWO BIRTHS IN ONE PROCESS CAN RUN: both take the claim (each take
  mints a token, because that is what makes a release safe -- see `release!`), one of
  them wins the session table's swap and the other's build is discarded. The row now
  carries the DISCARDED take's token, and the entry that won holds a different one --
  so its put-away would delete nothing and the row would sit there until the process
  exited, refusing every other process a conversation this one is not even serving.
  The loser is the only party that can see both tokens, so it is the loser that
  straightens the row out.

  IT ONLY MOVES A ROW IT STILL OWNS UNDER FROM-TOKEN: if the winning entry was put
  away before this ran (it cannot be -- a put-away waits out `idle-ttl-ms` and this
  happens microseconds later), or another take has happened, the update matches
  nothing and the row is left as it is."
  [thread-id from-token to-token]
  (pos? (db/with-transaction
          (fn [c]
            (db/execute! c "UPDATE session_claims SET token = ?
                             WHERE thread_id = ? AND instance = ? AND token = ?"
                         (str to-token) (str thread-id) (:instance @me) (str from-token))))))

(defn held
  "Every claim in the store, oldest first -- {:thread-id :instance :pid :started-at
  :since}, with no opinion about who is alive. FOR A READER: a report, a test, and
  whoever is trying to work out why a conversation will not run."
  []
  (db/select "SELECT * FROM session_claims ORDER BY since"))

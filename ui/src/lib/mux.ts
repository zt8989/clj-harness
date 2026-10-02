// THE DOWNLINK: one WebSocket per page carrying every conversation this page holds and
// every run this page is driving (`events.mux`, ADR 0004).
//
// WHY ONE SOCKET AND NOT ONE PER CONVERSATION. The SSE feed this replaces opened a
// connection per watched conversation, and those connections never went away -- a host is
// never evicted and a hidden host kept its feed -- so a page that had opened a handful of
// sessions held a handful of sockets, and the browser's per-origin pool is a handful. One
// socket for the whole page makes the count constant.
//
// THE SOCKET IS DOWNLINK-ONLY. Nothing is sent over it. The subscription is an HTTP fact,
// declared in this connection's own handshake URL and updated by `POST
// /api/events.mux/subscribe` -- so what the server knows about this page never outlives
// the socket, and a reconnect re-states it (the same property the SSE feed's `since=N` had,
// ADR 0003 decision 7).
//
// ONE SOCKET, TWO KINDS OF FRAME, routed by `WINDOW_TYPES`: a window frame is this page's
// COPY of a conversation, and a run event is a run being written NOW. Both are keyed by
// `threadId`; the server filters to the threads this connection declared (so a page hears
// nothing about a conversation it is not holding).
import type { WindowFrame } from "./feed";
/// THE GOAL FAMILY'S PAYLOAD (see `GoalFrame` below): a TYPE-only import, so nothing about the
/// strip is in this module's runtime graph.
import type { Goal } from "./goal";
// `apiBase` RATHER THAN `API_BASE`: a suite points the harness origin at a server it learned
// at runtime, and this module is loaded before that (`threads.ts` says why).
import { apiBase, downlinkUrl } from "./threads";
/// `newId` RATHER THAN `crypto.randomUUID` -- the same trap `lib/id.ts` documents, and this
/// file walked into it once: the socket's name was minted with the platform's shortcut,
/// which does not exist over `http://192.168.x.x`, so the page threw before it drew anything
/// on every phone. A connection name is not a conversation name, but it is still a name the
/// page mints in the browser, so it comes from the same cross-platform generator.
import { newId } from "./id";
import { createBatch } from "./coalesce";

/// HOW LONG TO WAIT BEFORE OPENING THE DOWNLINK AGAIN after it closed on its own -- the
/// same fact the SSE feed's reconnect carried: while the socket is up nothing is asked at
/// all, and this is only the pause after a server that is down, so the reconnect is not a
/// tight loop.
const RECONNECT_MS = 1000;

/// A frame on the downlink: a window frame (or a run's AG-UI event), plus the conversation
/// it is about. THE TAG IS THE WHOLE REASON ONE SOCKET CAN CARRY MANY CONVERSATIONS -- the
/// client routes by it, and `applied` (lib/window.ts) ignores it.
export type MuxFrame = WindowFrame & { threadId: string };

/// A RUN'S OWN FRAME on the same socket: an AG-UI event (upper-case `type`), tagged like a
/// window frame. Its shape is the client library's, not ours -- this side reads `type` to
/// route it and hands the rest through to the SSE the agent parses.
export type RunFrame = { threadId: string; type: string; [key: string]: unknown };

/// THE THIRD FAMILY: FACTS ABOUT a conversation rather than parts OF it -- a turn's two ends
/// and a model call's two ends (`harness.edge.http`; ADR 0006). They are NOT AG-UI frames: they
/// never make a message, a rebuilt conversation does not contain them, and nothing echoes them
/// back to a vendor. They are not window frames either -- they are not the conversation's copy.
///
/// THEY CARRY THE RECORD'S `seq` (a line number, so the two halves of the downlink can be
/// aligned) and, for the two that have them, the session's numbers.
export type FactFrame = {
  threadId: string;
  seq: number | null;
  type: "turn/start" | "turn/end" | "model/start" | "model/end" | "step/start" | "step/end";
  payload?: unknown;
  numbers?: unknown;
  /// THE TURN'S OWN NAME (`<thread>-t<line>`), on the frame that closes one. It is the record line
  /// the turn opened on, so nothing has to be kept for it to be stable (`harness.edge.http`).
  turnId?: string;
};

/// THE FACT FAMILY'S TYPES, NAMED IN ONE PLACE. `harness.edge.http` writes these names and this
/// side routes by them, so the spelling is a contract between two processes, not a detail.
const FACT_TYPES = new Set([
  "turn/start",
  "turn/end",
  "model/start",
  "model/end",
  // THE STEP FAMILY (`.scratch/step-events`, ADR 0011): the server writes these two names and
  // this set is the client's copy of that contract, pinned against the wire by
  // `test/suites/frames.ts`'s `the-wire-says-which-names-are-facts`.
  "step/start",
  "step/end",
]);

/// THE FOURTH FAMILY: THE TASK PANE'S ANSWER, pushed (ticket 01 of `.scratch/task-pane-push`).
///
/// WHAT IT IS. The right-hand pane draws what a session has going on -- its background jobs and
/// its delegations -- and it used to POLL both routes once a second. This frame is the
/// incremental half that replaced the poll: a job appearing or ending, a delegation starting or
/// ending, and the whole pane payload arrives here.
///
/// WHY NOT A `FactFrame`. A fact carries the RECORD's line number and can be replayed by cursor,
/// because a fact IS a line of the conversation's record. A job's ending is written in the JOB's
/// own record and a delegation's end is a memory of the server's process: there is no line to
/// number and nothing to replay, so this frame is a WHOLE PAYLOAD PER CHANGE -- the same shape
/// `events.host` sends the sidebar, for the same reason.
export type TaskFrame = {
  threadId: string;
  type: "task";
  /// THE SESSION'S JOBS, as `GET /api/threads/<id>/jobs` answers them (`lib/jobs.ts`).
  jobs?: unknown;
  /// THE DELEGATIONS OF THIS SESSION, as `GET /api/subagents` answers them narrowed to it
  /// (`lib/subagents-runs.ts`).
  delegations?: unknown;
};

/// The task frame's one type name, exported so a suite can name the contract instead of
/// re-writing the spelling.
export const TASK_FRAME_TYPE = "task";

/// THE FIFTH FAMILY: A SESSION'S GOAL, pushed (ticket 03 of `.scratch/goal`).
///
/// WHAT IT IS. A conversation can be chasing ONE objective across many rounds -- a person sets
/// it, or the model does on a person's direct request -- and while it is `active` and armed the
/// harness opens the next round by itself. This frame is how a strip learns that the goal moved:
/// a pause, a clear, a round the driver judged to have made no progress, a blocker the model
/// finally reported often enough to count.
///
/// WHY NOT A `FactFrame`, and why the WHOLE PAYLOAD every time: the same two reasons the task
/// pane's frame has (`TaskFrame` above). A fact is a line of the record with a number to replay
/// by; a goal changes under a name of its own (`goal/change`), the row a strip reads is the fold
/// of those lines, and `armed` is not in the record at all (it is this PROCESS's memory). So
/// there is no `seq` to carry and nothing a cursor could repair: the frame IS the answer, exactly
/// as `GET …/goal` words it, the last one wins, and a client that missed one asks again -- the
/// two halves `docs/rules/panel-data.md` names.
export type GoalFrame = {
  threadId: string;
  type: "goal";
  /// THE GOAL AS THE ROUTE ANSWERS IT, or null for a session that has none -- `clear` pushes
  /// null too, because a strip told nothing would go on drawing the goal somebody just cleared.
  goal: Goal | null;
  /// WHETHER THIS PROCESS MAY OPEN THE NEXT ROUND, beside the goal and never inside it (the
  /// question mark is the server's own spelling, `harness.edge.http/goal-wire`).
  "armed?": boolean;
};

/// The goal frame's one type name, exported for the same reason as `TASK_FRAME_TYPE`: a suite
/// names the contract instead of re-writing the spelling.
export const GOAL_FRAME_TYPE = "goal";

/// WHICH FAMILY A FRAME BELONGS TO, as a value -- so the routing rule can be READ and TESTED
/// without a socket (`test/suites/mux.ts`, `test/suites/goal.tsx`), and so `onmessage` states it
/// once. The `default` is deliberate: anything that is not one of the named families is a RUN
/// frame, which is AG-UI's own (upper-case) vocabulary. THE NAMED ONES ARE BRANCHES OF THEIR OWN
/// -- a family that fell through to that default would be validated against AG-UI's schema and
/// take the run down with it, which is exactly what the fact and task branches say above.
export function familyOf(type: string): "window" | "fact" | "task" | "goal" | "run" {
  if (WINDOW_TYPES.has(type)) return "window";
  if (FACT_TYPES.has(type)) return "fact";
  if (type === TASK_FRAME_TYPE) return "task";
  // AND THE GOAL IS THE FIFTH, named here for the same reason the task pane's is: a goal frame
  // handed to `@ag-ui/client` as a run event would be refused by its schema, and the run the
  // page is watching would die with a frame that was never meant for it.
  if (type === GOAL_FRAME_TYPE) return "goal";
  return "run";
}

export type MuxHandlers = {
  onFrame: (frame: MuxFrame) => void;
  /// The socket went away. Nothing is wrong with the window; it is BEHIND, and the repair
  /// is the tail page plus a re-declare -- the same move a dropped feed got.
  onClosed: () => void;
};

type Subscription = {
  since: number | null;
  generation: string | null;
  handlers: MuxHandlers;
};

/// THE WINDOW'S OWN FRAME TYPES (`lib/feed`'s `WindowFrame`). Everything else on the socket
/// is a RUN event (AG-UI's vocabulary is upper-case: `RUN_STARTED`, `TEXT_MESSAGE_CONTENT`,
/// ...). The two are routed by this set -- the same socket carries both, which is what makes
/// the sender and a watcher read one stream.
const WINDOW_TYPES = new Set(["window", "append", "page", "tail", "end"]);

/// THE FRAMES THAT MAY NOT WAIT (`lib/coalesce.ts`): a run is over, or the window is. The
/// reader is told the moment one arrives -- whatever was held in front of it goes first.
const TERMINAL_TYPES = new Set(["end", "RUN_FINISHED", "RUN_ERROR", "RUN_CANCELLED"]);

/// WHAT THIS PAGE FOLLOWS, by conversation. A `Map` rather than an object because a thread
/// id is not a property name (a stem can be anything).
const subscriptions = new Map<string, Subscription>();

/// WHAT THIS PAGE IS DRIVING, by conversation: a run's own events, delivered to whoever is
/// running it. A `Map` of SETS because a page may drive runs on more than one conversation.
const runSubscriptions = new Map<string, Set<(event: RunFrame) => void>>();

/// WHAT THIS PAGE HOLDS OF EACH CONVERSATION'S RUN -- the `:seq` of the last run frame it
/// RECEIVED. It is what a socket re-declares (`runSince`), and it is what the server replays
/// from (`harness.edge.mux/run-frames-after`: a `since` is "I hold this much"), so the number
/// has to mean what the server reads it as -- what is IN HAND, not what has been drawn.
///
/// AT RECEIPT, NOT AT DELIVERY (`lib/coalesce.ts` holds frames for the next animation frame,
/// and a hidden tab draws for nobody), and that difference was a real bug: a mark that waited
/// for delivery lagged by up to `HOLD_LIMIT` frames, so a re-declaration in that window -- a
/// pane mounting, a window's repair, a reconnect -- made the server RE-SEND frames this page
/// already had. A second `TEXT_MESSAGE_END` is fatal to a run: `@ag-ui/client`'s verifier has
/// no `:seq` of its own to drop it by, and answers "No active text message found with ID ...
/// A 'TEXT_MESSAGE_START' event must be sent first" (measured in a browser, 2026-09-30 --
/// `.scratch/mux-run-replay-dupes/`). What a mark may never do is run AHEAD of what this page
/// holds; a mark that lags what it has DRAWN is exactly right, because the frames it lags are
/// in the batch above, not on the wire.
///
/// IT RESETS ON `RUN_STARTED`, because the sender's numbering does too (`record-run!` starts a
/// fresh buffer per run): carrying a finished run's high-water mark into the next one would
/// ask for frames numbered above anything the new run will ever send.
const runCursors = new Map<string, number>();

/// WHAT THIS PAGE HAS ALREADY RECEIVED OF EACH RUN, so a frame that arrives TWICE is drawn once.
///
/// WHY A FRAME CAN ARRIVE TWICE AT ALL: every declaration asks the server for the run's frames
/// after the cursor above (`harness.edge.http/mux-replay-run!`), and that replay is a REPAIR --
/// exact when the cursor is what the page holds, and only then. This is the belt to that pair of
/// braces: the frames a replay hands back have been received before, and the second copy is the
/// one `@ag-ui/client` refuses.
///
/// `upTo` IS CONTIGUOUS -- 'every number up to here has been received' -- and `ahead` holds the
/// ones that arrived before their gap was filled. A HIGH-WATER MARK WOULD BE WRONG IN ONE
/// DIRECTION: it would drop the frame a replay was repairing (a replayed burst can be overtaken
/// by a live frame, so the burst's numbers are then below the mark while never having been
/// seen), turning a duplicate into a lost START -- which the same verifier refuses just as
/// hard. A number the sender can no longer replay is given up rather than held (the bound below
/// is its own ring size).
///
/// AND IT IS KEYED BY RUN: `RUN_STARTED` is the one frame whose numbering starts over, so the
/// `runId` is what tells a NEW run from the same run's frame arriving again.
const runAhead = new Map<string, { runId: string | null; upTo: number; ahead: Set<number> }>();
const RUN_RING = 4096;

/// WHETHER THIS RUN FRAME IS ONE THIS PAGE HAS NOT RECEIVED YET, recording it when it is. A
/// `false` is a re-send, and the socket drops it before the batch: nothing downstream has to
/// know that a frame can arrive twice.
///
/// A FRAME WITH NO `:seq` IS ALWAYS NEW: the number is the sender's bookkeeping FOR this
/// question (`harness.edge.mux/record-run!` numbers every frame of a run), and one that carries
/// none cannot be placed.
function runFrameIsNew(frame: MuxFrame & RunFrame): boolean {
  const seq = typeof frame.seq === "number" ? frame.seq : null;
  if (seq === null) return true;
  const threadId = frame.threadId;
  const runId = typeof frame.runId === "string" ? frame.runId : null;
  const seen = runAhead.get(threadId);
  // WIDENED ON PURPOSE, as in `deliver`: `frame` is a window frame AND a run frame (one socket,
  // two kinds), so its `type` reads as the window union alone until it is asked for as a string.
  const type: string = frame.type;
  if (type === "RUN_STARTED") {
    // THE SAME RUN'S `RUN_STARTED` AGAIN IS THE RE-SEND THIS EXISTS FOR; a different id is a run
    // whose numbering starts over, and the mark starts over with it.
    if (seen !== undefined && seen.runId !== null && runId === seen.runId) return false;
    runAhead.set(threadId, { runId, upTo: seq, ahead: new Set() });
    return true;
  }
  if (seen === undefined) {
    runAhead.set(threadId, { runId, upTo: seq, ahead: new Set() });
    return true;
  }
  if (seq <= seen.upTo) return false;
  if (seen.ahead.has(seq)) return false;
  seen.ahead.add(seq);
  while (seen.ahead.delete(seen.upTo + 1)) seen.upTo += 1;
  // A GAP THE SENDER CAN NO LONGER FILL: past its whole ring (`harness.edge.mux/run-buffer-size`)
  // the missing frame cannot come back, so the numbers waiting on it are given up rather than
  // held for the life of the page.
  if (seen.ahead.size > RUN_RING) {
    const oldest = Math.min(...seen.ahead);
    seen.ahead.delete(oldest);
    seen.upTo = oldest;
    while (seen.ahead.delete(seen.upTo + 1)) seen.upTo += 1;
  }
  return true;
}

/// ONE RECEIVED RUN FRAME, AND THE TWO THINGS THIS MODULE OWES IT: the cursor the socket declares
/// (`runCursors` above, moved at receipt) and the answer to whether it is new (`runFrameIsNew`,
/// so a replay's copy is dropped). A `false` means the frame goes no further.
function receiveRunFrame(frame: MuxFrame & RunFrame): boolean {
  if (!runFrameIsNew(frame)) return false;
  const seq = typeof frame.seq === "number" ? frame.seq : null;
  // THE TYPE IS ASKED FOR AS A STRING, for the reason `deliver` says: one socket, two kinds of
  // frame, and only one of them has this name.
  const type: string = frame.type;
  if (seq !== null && (type === "RUN_STARTED" || seq > (runCursors.get(frame.threadId) ?? 0))) {
    runCursors.set(frame.threadId, seq);
  }
  return true;
}

/// WHAT THIS PAGE READS FACTS FROM, by conversation: the turn and model-call families
/// (`FactFrame`). A `Map` of SETS for the same reason the run map is one -- a page may hold
/// more than one conversation -- and a separate map from `runSubscriptions` ON PURPOSE: these
/// frames are not a run's, they are the conversation's, and a page that is only WATCHING
/// (driving nothing) still wants them.
const factSubscriptions = new Map<string, Set<(fact: FactFrame) => void>>();

/// THE TASK PANE'S SUBSCRIBERS, by conversation -- the fourth family's table (see
/// `TaskFrame`). A separate map for the same reason the fact family has one: a frame for a
/// conversation nobody is watching the pane of reaches nobody.
const taskSubscriptions = new Map<string, Set<(task: TaskFrame) => void>>();

/// THE GOAL STRIP'S SUBSCRIBERS, by conversation -- the fifth family's table (see `GoalFrame`).
/// A separate map for the same reason every other family has one: a frame for a conversation
/// nobody is showing the goal of reaches nobody.
const goalSubscriptions = new Map<string, Set<(goal: GoalFrame) => void>>();


/// WHO HAS TO BE TOLD WHEN THE SOCKET IS BACK -- and why anybody has to be.
///
/// A window is repaired by its own tail page and a run by its cursor: both are re-declared and
/// the server hands back what was missed (`runSince`/`factSince`, ADR 0003 decision 7). THE TASK
/// PANE HAS NO CURSOR (`TaskFrame` above), so a change that happened while the socket was down is
/// in no frame this page will ever be handed -- a fresh answer is the only repair, and this is
/// the door `open` knocks on to ask for one. `subscribeTasks` registers the reader; stopping
/// takes it away.
///
/// AND ONE MORE READER IS IN THE SAME POSITION: the goal strip. Its frame (`GoalFrame`) carries no
/// cursor either, so it reads again through the same door -- `onDownlinkOpen` directly, because
/// the strip, not this module, is the thing that knows whether it is on screen.
const openListeners = new Set<() => void>();

/// ASKED WHEN THE SOCKET HAS BEEN AWAY AND IS BACK, for a reader whose own connection the
/// server cannot watch.
///
/// THE TRAJECTORY STREAM IS THAT READER (`.scratch/memory-hygiene/` 票 02): it is a long-lived
/// HTTP response, and http-kit reports NO close for one -- `open?` stays true after the reader
/// is gone (measured) -- so the doorbell that pushes into it is not owned by that socket at
/// all. It is owned by THIS page's downlink subscription: when the socket goes, the server
/// prunes that doorbell and the stream goes quiet, so coming back is a gap only a fresh answer
/// closes -- the repair every pane owes after a reconnect (`docs/rules/panel-data.md`). Answers
/// the way to stop listening.
export function onDownlinkOpen(reader: () => void): () => void {
  openListeners.add(reader);
  return () => {
    openListeners.delete(reader);
  };
}

/// HOW FAR EACH CONVERSATION'S FACT STREAM HAS BEEN READ -- the `:seq` of the last fact this
/// page saw, WHICH IS A RECORD LINE NUMBER (`harness.edge.mux/facts-after`), not a counter the
/// sender keeps. A reconnecting socket re-declares it (`factSince`), and it is a SEPARATE number
/// from `runCursors` because the two count different things: a run frame is numbered by the
/// sender, a fact by the record line it was written for.
///
/// IT NEVER RESETS, unlike the run cursor: the record does not start over when a run does.
const factCursors = new Map<string, number>();

/// THE ROUTING, in one place, because the batch below hands frames over in groups.
///
/// ONE SOCKET, THREE KINDS OF FRAME, AND THE ROUTING IS EXPLICIT. A window frame is about
/// the conversation's copy; a FACT is about the conversation (a turn's or a call's two
/// ends); everything else is a RUN event -- AG-UI's own vocabulary, which goes to whoever
/// is driving that run.
///
/// WHY THE MIDDLE CASE IS NAMED RATHER THAN LEFT TO THE `else`: this used to be 'not a
/// window type => a run frame', and a fact falling through to `@ag-ui/client` would be
/// validated against AG-UI's schema and take the whole run down with it. A frame for a
/// conversation we are not holding still reaches nobody, which is right.
function deliver(frame: MuxFrame & RunFrame): void {
  const family = familyOf(frame.type);
  if (family === "window") {
    subscriptions.get(frame.threadId)?.handlers.onFrame(frame);
    return;
  }
  if (family === "task") {
    // NO CURSOR AND NO REMEMBERING: the frame IS the whole answer, so the last one wins and a
    // page that missed one asks again when it reopens (the pane's own snapshot read).
    for (const onTask of taskSubscriptions.get(frame.threadId) ?? []) {
      onTask(frame as unknown as TaskFrame);
    }
    return;
  }
  if (family === "goal") {
    // NO CURSOR AND NO REMEMBERING, the same two sentences the task pane's branch says: the frame
    // IS the whole payload, so the last one wins, and a reader that missed one asks again (the
    // strip's own snapshot read, on the fact family and after a reconnect).
    for (const onGoal of goalSubscriptions.get(frame.threadId) ?? []) {
      onGoal(frame as unknown as GoalFrame);
    }
    return;
  }
  if (family === "fact") {
    const fact = frame as unknown as FactFrame;
    // REMEMBER HOW FAR THIS CONVERSATION'S FACTS HAVE BEEN READ (ticket 05): the number is the
    // RECORD LINE the fact was written for, so it is not reset by a run -- and it is taken AT
    // DELIVERY, like the run's below, for the same reason: a frame still waiting in the batch has
    // been seen by nobody, and a cursor ahead of it would make the reconnect SKIP it.
    if (typeof fact.seq === "number") {
      if (fact.seq > (factCursors.get(fact.threadId) ?? 0)) {
        factCursors.set(fact.threadId, fact.seq);
      }
    }
    // AND THE SUBSCRIBERS: this frame is about the conversation, not about a run, so it goes to
    // whoever asked for the fact family (`subscribeFacts`).
    for (const onFact of factSubscriptions.get(fact.threadId) ?? []) onFact(fact);
    return;
  }
  for (const onEvent of runSubscriptions.get(frame.threadId) ?? []) onEvent(frame);
  // AND THE RUN'S OWN CURSOR IS NOT TOUCHED HERE: it belongs to the SOCKET, where the frame
  // ARRIVES (`runCursors` above says why -- a mark that waited for this line was the bug). The
  // fact family's cursor stays where it is, in its own branch above, for the reason it always
  // had: a fact is idempotent, so a re-sent one costs nothing.
}

/// THE BATCH EVERY FRAME GOES THROUGH (`lib/coalesce.ts`): run frames, window frames and
/// facts alike, so what comes out is what arrived, in the order it arrived.
const batch = createBatch<MuxFrame & RunFrame>({
  deliver: (frames) => {
    for (const frame of frames) deliver(frame);
  },
  terminal: (frame) => TERMINAL_TYPES.has(frame.type),
  schedule: (flush) => {
    // A CLOCK THAT CANNOT ARRANGE A FLUSH MUST NOT COST A FRAME: the page has
    // `requestAnimationFrame`, a suite's environment may not -- and a frame that was held
    // and never scheduled is a frame nobody ever sees (which is exactly what the client
    // suite caught: an undefined `requestAnimationFrame` inside `push` left the run's
    // frames in the queue). With no clock, the batch goes out AT ONCE, which is what this
    // side did before the batch existed.
    if (typeof requestAnimationFrame === "function") requestAnimationFrame(flush);
    else flush();
  },
});

let socket: WebSocket | null = null;
/// THE NAME OF THE CURRENT SOCKET, minted when it opens. It exists so the HTTP route that
/// updates the set can address THIS connection; it is thrown away with the socket, and a
/// reconnect mints a new one. It is not an identity and nothing outlives the connection.
let token = "";
let reconnect: ReturnType<typeof setTimeout> | null = null;
/// Whether the page WANTS a downlink at all. A page with nothing to follow keeps none.
let wanted = false;
/// WHETHER A SOCKET HAS ALREADY OPENED ON THIS PAGE. The FIRST open is not a gap: the pane takes
/// its opening read as it subscribes, and there is nothing behind the socket to repair yet.
/// Every open after it is a gap of unknown length, which is what `openListeners` is for.
let everOpen = false;

/// EVERY CONVERSATION THIS CONNECTION MUST BE TOLD ABOUT -- a window it follows, a run it
/// drives, OR ONE WHOSE FACTS IT WANTS, OR ONE A PANE IS DRAWING, OR ONE WHOSE GOAL A STRIP IS
/// SHOWING. The server filters EVERY family by this set, so a thread has to be in it however this
/// page came to hold it.
///
/// THE FACTS BELONG HERE, and their absence used to be a hole with a real edge: a page whose
/// only claim on a conversation was the run it had just driven stopped being told about that
/// conversation the moment the agent dropped its run subscription -- which is ONE KERNEL EVENT
/// BEFORE the `turn/end` that closes the turn (`.scratch/step-events`). A host holding a window
/// never noticed, because its window kept the thread declared; a freshly minted session has no
/// window yet, so its `turn/end` was simply missed.
///
/// AND SO DOES THE TASK PANE'S TABLE (ticket 01 of `.scratch/task-pane-push`): it was missing
/// here for the same reason and cost the same kind of frame. The declaration is also what a
/// REPLACED socket re-states (ADR 0003 decision 7), so the reconnect sent a set that did not
/// name the session the pane was drawing, the server dropped that watch with the old socket,
/// and the frame that ENDS a job had nowhere to go -- the row said "so far" for a job that was
/// over. A task frame carries no cursor (`TaskFrame`), so nothing would have brought it back.
function wantedThreads(): string[] {
  return [
    ...new Set<string>([
      ...subscriptions.keys(),
      ...runSubscriptions.keys(),
      ...factSubscriptions.keys(),
      ...taskSubscriptions.keys(),
      ...goalSubscriptions.keys(),
    ]),
  ];
}

/// IS ANYBODY STILL CLAIMING THIS CONVERSATION? -- the question every door's close has to ask
/// before it tells the server to stop sending. Window, run, facts, the goal strip AND THE TASK
/// PANE are FIVE SEPARATE CLAIMS on one conversation, and a door that drops only its own must
/// not take the others' with it.
/// others' with it.
///
/// THE RUN'S OWN DOOR IS THE ONE THAT MADE THIS NECESSARY: the agent lets go of a run the moment
/// its stream ends, and `turn/end` is pushed ONE KERNEL EVENT LATER -- so a page whose only other
/// claim was its fact subscription lost the fact that closes the turn it had just watched
/// (`.scratch/step-events`).
///
/// AND THE PANE IS THE CLAIM THAT MADE THE LIST MATTER (ticket 01 of `.scratch/task-pane-push`):
/// a session this page had just minted was a run and a pane with no window yet, so the run's door
/// -- asking about windows and facts only -- took the pane's watch down with it, and the pane went
/// on drawing a job from a frame that never arrived.
function stillWanted(threadId: string): boolean {
  return (
    subscriptions.has(threadId) ||
    runSubscriptions.has(threadId) ||
    factSubscriptions.has(threadId) ||
    taskSubscriptions.has(threadId) ||
    goalSubscriptions.has(threadId)
  );
}

/// THE SET, as the handshake URL and every re-declare spell it. A thread with no window
/// follower still appears, with a null cursor: the declaration is about WHICH conversations,
/// and a cursor only matters to the window half.
export function declaredSet(): Array<{
  threadId: string;
  since: number | null;
  generation: string | null;
  runSince: number | null;
  /// THE FACT FAMILY'S OWN CURSOR (ticket 05): a RECORD line number, and a separate number from
  /// `runSince` because the two count different things.
  factSince: number | null;
}> {
  return wantedThreads().map((threadId) => {
    const sub = subscriptions.get(threadId);
    return {
      threadId,
      since: sub?.since ?? null,
      generation: sub?.generation ?? null,
      runSince: runCursors.get(threadId) ?? null,
      factSince: factCursors.get(threadId) ?? null,
    };
  });
}

function open(): void {
  token = newId();
  const params = new URLSearchParams();
  params.set("subscriber", token);
  params.set("sessions", JSON.stringify(declaredSet()));
  const ws = new WebSocket(downlinkUrl("events.mux", params));
  socket = ws;
  ws.onopen = () => {
    if (socket !== ws) return;
    // DECLARE THE WHOLE SET AGAIN. The handshake URL named it, and this covers the one race
    // the URL cannot: a subscription added while the socket was still CONNECTING, when the
    // POST below had nowhere to go.
    declare({ subscribe: declaredSet() });
    // AND THE READERS A CURSOR CANNOT REPAIR ARE TOLD TO ASK AGAIN (`openListeners`): the
    // declaration above re-stated the whole set, so the watch is in place before the answer this
    // read comes from.
    if (everOpen) for (const listener of openListeners) listener();
    everOpen = true;
  };
  ws.onmessage = (event) => {
    let frame: MuxFrame & RunFrame;
    try {
      frame = JSON.parse(String(event.data)) as MuxFrame & RunFrame;
    } catch {
      // A frame this client cannot read is one nobody can show. Dropping it keeps the socket
      // and every other conversation on it alive.
      return;
    }
    // HELD, NOT DROPPED (`lib/coalesce.ts`): the frame joins the batch for the browser's
    // next animation frame, which is what keeps a fast vendor's stream at one React update
    // per frame instead of one per token. WHICH KIND of frame this is, and who reads it, is
    // `deliver` above.
    // A RUN FRAME IS ACCOUNTED FOR AT RECEIPT (`receiveRunFrame`): the cursor this page declares
    // is what it HOLDS, and a frame the server sent twice is dropped before any subscriber sees
    // it. Both are about that one fact, which is why they are one call -- and why they are HERE
    // rather than in `deliver`: a re-sent `TEXT_MESSAGE_END` reaching a subscriber is fatal to
    // the run that subscriber is driving.
    if (familyOf(frame.type) === "run" && !receiveRunFrame(frame)) return;
    batch.push(frame);
  };
  ws.onclose = () => {
    if (socket !== ws) return; // a newer socket replaced this one; its close is not ours
    socket = null;
    if (!wanted) return;
    // WHAT WAS HELD GOES OUT BEFORE THE REPAIR IS ASKED FOR: `onClosed` must be told about a
    // position the readers have already been given, or the repair reads from a mark ahead
    // of frames nobody has seen (`lib/coalesce.ts`'s header).
    batch.flush();
    // EVERY FOLLOWED WINDOW IS NOW BEHIND, and each has its own repair (a tail page).
    for (const sub of subscriptions.values()) sub.handlers.onClosed();
    schedule();
  };
}

function schedule(): void {
  if (reconnect !== null || !wanted) return;
  reconnect = setTimeout(() => {
    reconnect = null;
    if (wanted && socket === null) open();
  }, RECONNECT_MS);
}

function declare(body: { subscribe?: unknown[]; unsubscribe?: string[] }): Promise<void> | null {
  if (socket === null || socket.readyState !== WebSocket.OPEN) return null;
  return fetch(`${apiBase()}events.mux/subscribe`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ subscriber: token, ...body }),
  })
    .then(() => undefined)
    .catch(() => {
      // The declaration did not arrive. The socket still carries the set its handshake named,
      // and the next reconnect re-declares all of it; there is nothing to report here.
    });
}

function ensure(): void {
  wanted = true;
  if (socket === null) open();
}

function declareThread(threadId: string): Promise<void> | null {
  const sub = subscriptions.get(threadId);
  return declare({
    subscribe: [
      {
        threadId,
        since: sub?.since ?? null,
        generation: sub?.generation ?? null,
        runSince: runCursors.get(threadId) ?? null,
        factSince: factCursors.get(threadId) ?? null,
      },
    ],
  });
}

/// FOLLOW ONE CONVERSATION'S WINDOW over the shared downlink, and answer the way to stop.
///
/// `since`/`generation` ARE THE SAME CURSOR THE SSE FEED TOOK -- this is a carrier change,
/// not a protocol change. Calling it again for the same conversation is an UPDATE (a cursor
/// that moved after a repair), which the server is told about.
export function subscribeMux(
  threadId: string,
  window: { since: number | null; generation: string | null },
  handlers: MuxHandlers,
): () => void {
  subscriptions.set(threadId, { since: window.since, generation: window.generation, handlers });
  ensure();
  void declareThread(threadId);
  return () => {
    // A REPLACED SUBSCRIPTION MUST NOT BE TORN DOWN BY THE OLD SUBSCRIPTION'S CLOSER: only
    // the closer that still owns the entry may remove it.
    if (subscriptions.get(threadId)?.handlers !== handlers) return;
    subscriptions.delete(threadId);
    // AND THE THREAD IS ONLY UNSUBSCRIBED WHEN NO OTHER CLAIM IS LEFT: window, run, facts and
    // the pane share this one watch (`stillWanted`), so this family leaving is not the
    // conversation leaving.
    if (!stillWanted(threadId)) void declare({ unsubscribe: [threadId] });
    // THE SOCKET STAYS OPEN with nothing subscribed. One idle connection per page is the
    // budget this module exists to keep; closing and reopening it on every switch would be
    // the churn the single socket is meant to remove.
  };
}

/// DRIVE ONE RUN over the shared downlink, and answer the way to stop: every AG-UI event the
/// run emits for THREAD-ID is handed to ON_EVENT. This is the carrier `lib/agent.ts` turns
/// back into an SSE for `@ag-ui/client` to parse.
///
/// IT ANSWERS A PROMISE alongside the unsubscribe, and the caller MUST await it before
/// starting the run: the server filters run frames by what this connection declared, so a run
/// started before the declaration lands would lose its first frames.
export function subscribeRun(
  threadId: string,
  onEvent: (event: RunFrame) => void,
): { unsubscribe: () => void; declared: Promise<void> } {
  const set = runSubscriptions.get(threadId) ?? new Set<(event: RunFrame) => void>();
  set.add(onEvent);
  runSubscriptions.set(threadId, set);
  ensure();
  // WHEN THE SOCKET IS NOT OPEN YET, `declareThread` cannot post; `onopen` declares the whole
  // set, so waiting for that is the declaration. `ready` resolves either way.
  const declared = socket !== null && socket.readyState === WebSocket.OPEN
    ? declareThread(threadId) ?? Promise.resolve()
    : whenOpen().then(() => declareThread(threadId) ?? undefined).then(() => undefined);
  return {
    unsubscribe: () => {
      const current = runSubscriptions.get(threadId);
      if (current === undefined || !current.delete(onEvent)) return;
      if (current.size === 0) runSubscriptions.delete(threadId);
      // AND THE SAME QUESTION THE WINDOW'S CLOSER ASKS, for the same reason: the last claim out
      // turns off the light (`stillWanted`). THIS is the door the pane's frames went out through.
      if (!stillWanted(threadId)) void declare({ unsubscribe: [threadId] });
    },
    declared,
  };
}


/// READ A CONVERSATION'S FACTS: every turn and model-call frame for THREAD-ID is handed to
/// ON_FACT. This is the family the composer's strip takes its numbers from (and the family the
/// fold line will take its counts from), so it is the same shape as `subscribeRun` minus the
/// promise: a fact is a push nobody has to declare a cursor for yet (`_scratch/turn-and-model-
/// events` ticket 05 adds that), and one missed while the socket was down is repaired by the
/// next snapshot -- a page that opens a conversation asks `/stats` once.
export function subscribeFacts(
  threadId: string,
  onFact: (fact: FactFrame) => void,
): { unsubscribe: () => void } {
  const set = factSubscriptions.get(threadId) ?? new Set<(fact: FactFrame) => void>();
  set.add(onFact);
  factSubscriptions.set(threadId, set);
  ensure();
  // AND THE SERVER IS TOLD, like the other two doors: what it sends this connection is filtered
  // by what the connection declared, so wanting a conversation's facts is a claim on the
  // conversation (`wantedThreads` says why that matters).
  void declareThread(threadId);
  return {
    unsubscribe: () => {
      const current = factSubscriptions.get(threadId);
      if (current === undefined || !current.delete(onFact)) return;
      if (current.size === 0) factSubscriptions.delete(threadId);
      // AND THE DECLARATION GOES WHEN THE LAST CLAIM DOES: window, run, facts and the pane are
      // four claims on one conversation, and dropping this one must not take the other three's.
      if (!stillWanted(threadId)) void declare({ unsubscribe: [threadId] });
    },
  };
}

/// FOLLOW THE TASK PANE'S FACTS for THREAD-ID: every pushed pane payload is handed to ON_TASK.
/// Answers the way to stop, and the opening snapshot is the caller's to ask for (the pane reads
/// both routes once when it mounts) -- a subscription with no snapshot would be a pane that is
/// empty until something happens.
export function subscribeTasks(
  threadId: string,
  onTask: (task: TaskFrame) => void,
  /// ASKED WHEN THE SOCKET HAS BEEN AWAY AND IS BACK (`openListeners`). A task frame carries no
  /// cursor, so this is the family's only repair before the next change; the pane hands over its
  /// own idempotent snapshot read. Optional: a reader that can wait for the next push does not
  /// need it.
  onOpen?: () => void,
): { unsubscribe: () => void } {
  const set = taskSubscriptions.get(threadId) ?? new Set<(task: TaskFrame) => void>();
  set.add(onTask);
  taskSubscriptions.set(threadId, set);
  // AND THIS READER IS ONE A CURSOR CANNOT REPAIR: a task frame has no line number, so the socket
  // coming back is a gap only a fresh answer closes (`openListeners`). Registering here is also
  // what puts this thread in the declaration -- `wantedThreads` above, the other half of the same
  // bug.
  if (onOpen !== undefined) openListeners.add(onOpen);
  // THE SUBSCRIPTION IS A SERVER-SIDE SET TOO (`POST /api/events.mux/subscribe`), and the
  // declaration is what puts this connection on the session's list: `ensure` opens the socket
  // and `declareThread` states this thread on it. WHETHER THAT THREAD SURVIVES A REPLACED SOCKET
  // is not decided here (`wantedThreads`), and neither is whether another family's teardown may
  // turn the watch off (`stillWanted`) -- both are answered once, above, for every family.
  ensure();
  void declareThread(threadId);
  return {
    unsubscribe: () => {
      const current = taskSubscriptions.get(threadId);
      if (current === undefined || !current.delete(onTask)) return;
      if (current.size === 0) taskSubscriptions.delete(threadId);
      // AND THE TWO THINGS STOPPING HAS TO UNDO, both of them about the CONNECTION rather than
      // this reader: nobody is told to read again (`openListeners`), and the server is asked to
      // stop sending only when no family is left (`stillWanted`).
      if (onOpen !== undefined) openListeners.delete(onOpen);
      if (!stillWanted(threadId)) void declare({ unsubscribe: [threadId] });
    },
  };
}

/// FOLLOW A CONVERSATION'S GOAL for THREAD-ID: every pushed payload is handed to ON_GOAL, and the
/// way to stop comes back. The opening snapshot is the caller's to ask for (`lib/goal.ts`'s
/// `goalFor`) -- a subscription with no snapshot would be a strip that is empty until something
/// happens.
///
/// IT TAKES NO `onOpen` AND NEEDS NONE, unlike `subscribeTasks` above: a `goal` frame carries no
/// cursor either, so the strip's own `onDownlinkOpen` is what re-reads after a reconnect -- and
/// the strip is what knows whether it is on screen to read anything.
export function subscribeGoals(
  threadId: string,
  onGoal: (goal: GoalFrame) => void,
): { unsubscribe: () => void } {
  const set = goalSubscriptions.get(threadId) ?? new Set<(goal: GoalFrame) => void>();
  set.add(onGoal);
  goalSubscriptions.set(threadId, set);
  // The same two lines every family's door has: the socket is opened, and the server is told that
  // this connection wants this conversation (`wantedThreads` says why that matters).
  ensure();
  void declareThread(threadId);
  return {
    unsubscribe: () => {
      const current = goalSubscriptions.get(threadId);
      if (current === undefined || !current.delete(onGoal)) return;
      if (current.size === 0) goalSubscriptions.delete(threadId);
      // AND THE SAME QUESTION THE OTHER DOORS ASK: the last claim out turns off the light.
      if (!stillWanted(threadId)) void declare({ unsubscribe: [threadId] });
    },
  };
}

/// The socket's next OPEN, resolved if it is open already. Used by `subscribeRun`, which must
/// not miss a run's first frames.
function whenOpen(): Promise<void> {
  const ws = socket;
  if (ws === null || ws.readyState === WebSocket.OPEN) return Promise.resolve();
  return new Promise((resolve) => {
    const listener = () => {
      ws.removeEventListener("open", listener);
      resolve();
    };
    ws.addEventListener("open", listener);
  });
}

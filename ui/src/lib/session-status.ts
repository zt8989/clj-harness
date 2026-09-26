// What the sidebar asks about ONE session, and the two refusals that are still
// worth raising.
//
// IT REPLACED `run-state.ts`, and the name changed with the job. That module was
// "the two refusals a run in flight earns, and the probe behind them": both
// refusals were about the page -- there was one runtime, so a run anywhere
// stopped everything -- and the probe read `runtime.threads.main`, the single
// core. Every session now has its own runtime and its own core, so "is a run in
// flight" is no longer a fact about the page; it is a fact about a session, and
// the page keeps one answer per session (App's registry).
//
// So what lives here is:
//   * `SessionStatus`, the shape of that answer: whether THIS session has a run
//     in flight, and whether it has stopped to ask a human. They are two
//     different facts -- a parked session is NOT running (`isRunning` is false
//     once a run ends on an interrupt) -- and the sidebar says them differently.
//   * `blocked`, the one predicate both remaining refusals share.
//   * the sentences for those refusals, in a leaf module because two sides need
//     them and they must not drift: the sidebar raises them and shows them on
//     the row the click landed on. THEY ARE THE CATALOG'S WORDS, not literals --
//     they are this client's own sentences (the server's reasons are never
//     translated), and a sentence a person reads cannot be a module constant. The
//     translator is a PARAMETER rather than a hook here for the same reason
//     `run-state.ts` passed one: this is not a component, and its caller already
//     holds the `shell` translator, which is the face the sidebar's own refusals
//     live in (`refusal.*` -- the group the archive and remove sentences sit in;
//     the two that used to live here, `refusal.noProject*`, went with the rule that
//     a new task needed a project).
//
// WHAT IS NO LONGER REFUSED: switching sessions, and starting one. Those were
// refused because a switch would orphan a streaming run -- true when one core
// served the whole page, false now that a host owns its own core and a switch is
// a change of which host is on screen. The sentences that said otherwise are
// gone rather than reworded; a refusal nobody raises is a lie in waiting.
//
// WHAT IS STILL REFUSED: archiving a session that has not finished, and removing
// its project. Both are about the same thing -- a run that is still writing, or a
// resume that still has somewhere to write -- and in both cases the judgement is
// about THAT session rather than the one on screen. The old guard asked whether
// the session on screen was running, which was the same question only while one
// runtime existed.

import type { TFunction } from "i18next";

/// The translator these sentences are worded through: the caller's own, which is
/// the `shell` face (see the header).
type Translate = TFunction<"shell">;

/// One session's answer, as App's registry holds it.
export type SessionStatus = {
  /// A run is in flight: the composer's Send is a Cancel and the row spins.
  running: boolean;
  /// The run has stopped to ask a human -- an approval card or a server's
  /// question is up. NOT the same as `running`: the run already ended.
  parked: boolean;
};

/// The resting answer, for a session no host has reported on yet.
export const IDLE: SessionStatus = { running: false, parked: false };

/// ONE SESSION'S ANSWER, FROM THE TWO READINGS THE PAGE HAS.
///
///   `local`  -- what THIS page's runtime is doing (`thread.isRunning`, and whether the
///               run has stopped to ask a human). It is the only reading that knows about
///               a run this page just sent, and the only one that knows the composer's
///               own interrupt gate.
///   `server` -- the SERVER'S OWN WORD for the conversation, as the window's `state`
///               reports it (`running` / `parked` / `settled` / `unfinished`), or null
///               when this host holds no window at all. It is the only reading that can
///               say a run started BEFORE this page existed is still going: a run belongs
///               to the process, so a page that reloaded into one is watching it, not
///               running it.
///
/// THE UNION, NOT A REPLACEMENT, for the reason `sidebar.tsx` gives about the same pair:
/// neither reading is a superset of the other, so either one being true means a run is in
/// flight. This is the answer the sidebar draws and the one ticket 04 of
/// `.scratch/session-after-refresh` is about -- before it, the composer offered Send for a
/// conversation the server was still answering, and the only thing that came back was the
/// run edge's 409.
///
/// THE TWO READINGS ARE KEPT APART ELSEWHERE, and this function is not the place they are
/// merged for the HOST as well: `SessionHost` bookkeeps its own `ownRun` from the LOCAL
/// reading alone, because `isOwnRun` decides whether the window feed may import into this
/// runtime -- and a conversation this page is only watching is exactly what the feed is
/// for. Merging here and reusing that value there would suppress the imports that keep a
/// watched turn growing.
///
/// `parked` IS TAKEN FROM THE SERVER NOW (ticket 06 of `.scratch/session-after-refresh`): the
/// card that answers a parked run comes back on a rebuilt conversation, so a server-side
/// `parked` no longer closes a door with no way through it -- the way through is the card,
/// which `toThreadMessages` now lets the runtime find. This is the SIDEBAR's and the archive
/// gate's answer; the composer's own gate adds the server's `parked` where it is written
/// (`app.tsx`'s `isSendDisabled`).
export const statusOf = (local: SessionStatus, server: string | null): SessionStatus => ({
  running: local.running || server === "running",
  parked: local.parked || server === "parked",
});

/// WHETHER A TURN ON SCREEN IS STILL BEING WRITTEN, from the same two readings `statusOf`
/// unions.
///
/// IT EXISTS BECAUSE A TURN'S FURNITURE IS KEYED ON A QUESTION THE RUNTIME CANNOT ANSWER:
/// the action bar (Copy / Refresh / More) is drawn for a turn that has STOPPED, and the
/// runtime's `thread.isRunning` only knows about a run THIS PAGE is driving. A reload in the
/// middle of somebody else's turn -- or one that landed in a conversation the sidebar opened
/// -- has the server's word and nothing local, so a bar keyed on the local half alone offers
/// to copy and regenerate a message that is still arriving and will keep growing (measured
/// by the owner, 2026-09-25: `.scratch/refreshed-turn-keeps-growing`).
///
/// THE UNION IS THE COMPOSER'S OWN RULE (see `app.tsx`'s `isSendDisabled` and
/// `ComposerAction`): either reading being true means somebody is writing this conversation,
/// and neither is a superset of the other -- a page's own run is running before the window
/// has said anything about it, and the server's run is going while this page is idle.
/// `parked` is deliberately not asked: a parked run has ENDED on its interrupt, and the card
/// that answers it is what the turn's furniture has to stay reachable for.
export const stillBeingWritten = (ownRunning: boolean, server: string | null): boolean =>
  statusOf({ running: ownRunning, parked: false }, server).running;

// WHICH OF THE TWO THINGS A TURN'S END WEARS IS NOT ASKED HERE ANY MORE: the dot is about the
// TURN, and the turn has its own module (`lib/live-turn.ts`: `wearsWorkingDot` over the turn's
// open/closed fact, this file's `writing`, and whether the footer is the live turn's).

/// Whether this session must not be filed away or have its project removed: it
/// has work that is not finished with the log.
export const blocked = (status: SessionStatus): boolean =>
  status.running || status.parked;

/// Why an archive was refused. "That session" rather than its id, because the
/// row the sentence lands on already names it -- and the sentence sits under the
/// row it belongs to, never at the top of a list for the reader to match up.
export const archiveRefusal = (t: Translate, status: SessionStatus): string =>
  status.parked ? t("refusal.archiveParked") : t("refusal.archiveRunning");

/// Why removing a project was refused. THIS one names the session, because the
/// button that was clicked is the PROJECT's and the reason is inside it: a
/// sentence that only said "a session is running" would send the reader hunting
/// for which one.
export const removeProjectRefusal = (
  t: Translate,
  sessionId: string,
  status: SessionStatus,
): string =>
  status.parked
    ? t("refusal.removeProjectParked", { session: sessionId })
    : t("refusal.removeProjectRunning", { session: sessionId });

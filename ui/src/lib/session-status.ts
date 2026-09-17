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
//     live in (`refusal.*` -- the same group `refusal.noProject` sits in).
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

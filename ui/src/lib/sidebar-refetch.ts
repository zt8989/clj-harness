// HOW MANY TIMES THE SIDEBAR WILL ASK THE LISTING AGAIN FOR ONE MINTED SESSION, and how
// long it waits before each ask but the first.
//
// ------------------------------------------------------------- the promise this keeps
//
// A session this page minted has NO ROW until its first send writes one, so the sidebar's
// listing -- a snapshot from before that send -- does not name it, and the row for the
// conversation you are in has to be asked for. The ask is a READ, and the write it is
// waiting for may still be in flight when it goes out: the registration that creates the
// row (`app.tsx`'s `registerPending`) is one request, and a read served before it commits
// comes back without the row.
//
// ASKING ONCE TURNS THAT RACE INTO A PERMANENT MISS. "Once per id per page load" is what
// makes an endless loop impossible, and it is also what the row does not survive: the id
// is spent, the listing stays stale, and the row turns up only when something ELSE
// refreshes -- the button, a switch, a reload. That is the report this rule answers
// (点击新增……发送之后左侧不出现，刷新才出现).
//
// AND WAITING FOR THE RUN TO END IS NOT THE ANSWER EITHER, which is the second half of
// the same mistake: the row is not written by the run, it is written by the registration
// that runs BEFORE it. Holding the ask until `running` is false withholds the row for as
// long as the model takes -- which is the same complaint, one order of magnitude faster.
// So the caller asks as soon as it has a title, and retries if the answer was too early.
//
// AND THE ROW IS TWO WRITES, WHICH IS THE SECOND REASON TO ASK TWICE. The registration
// creates the row; the NAME and the send time are recorded when the run's input arrives.
// A listing read between the two names the row and still says 还没跑过 -- so asking only
// until the row exists settles a new session's row at "never run", with nothing left to
// fill it in until something else refreshes. The wait below is what gives that second
// write a chance to land, and it is why the answer this rule reads is the row's send time
// rather than its presence.
//
// AND THE THIRD REASON IS THE MIRROR OF THE FIRST. The listing carries the SERVER's
// live-runs registry as of that read, so a listing taken while the run was in flight goes
// on saying `running` -- and a row drawn from it wears its spinner until something reads
// again. That is the same trap in the other direction (a row that has arrived and cannot
// settle), and it is why the caller hands this rule its OWN registry alongside the
// listing: the end of a run there is what makes the stale snapshot due for one more read.
// A row whose run is really in flight is left alone, because asking would get the same
// answer -- the change it waits for is the run ending, and that arrives by itself.
//
// ---------------------------------------------------------------- what bounds the loop
//
// UP TO `ASK_AGAIN_LIMIT` ASKS PER ID, per page load: the first at once (the ordinary
// case -- the row is committed by the time the title is on screen) and the rest after
// `ASK_AGAIN_AFTER_MS`, because a burst of immediate reads all land inside the same
// window and all miss together. An answer that never comes still stops, and it stops per
// id: an id past its limit is skipped rather than answered, which is what lets a SECOND
// minted session waiting on the same page be asked about at all.
//
// --------------------------------------------------------------------------- the seam
//
// ZERO IMPORTS, like `lib/sidebar-rows.ts` and `lib/relative-time.ts`, and for the same
// reason: `components/sidebar.tsx` cannot be executed in the vitest run at all (it
// reaches `lib/i18n.ts`, which touches `document`), so everything about this rule that
// CAN be pinned has to be pinnable without a render -- literals in, one answer out. What
// is left for the browser is the one thing a suite cannot see: the row appearing while
// the run that created it is STILL GOING (`.scratch/new-session-appears/walkthrough.mjs`).

/// HOW MANY ASKS ONE ID IS WORTH. Five, of which the first is immediate: with the wait
/// below that is a little under two seconds of patience, which is orders of magnitude
/// more than the write being waited for (one INSERT into a local SQLite file) and far
/// less than a page load's worth of polling.
export const ASK_AGAIN_LIMIT = 5;

/// HOW LONG AFTER AN ANSWER THAT DID NOT NAME THE ROW THE NEXT ASK GOES OUT.
export const ASK_AGAIN_AFTER_MS = 400;

/// ONE ASK: which id, which attempt this is, and how long to wait before making it.
export type Ask = {
  readonly id: string;
  /// 1-BASED, AND IT COUNTS THIS ASK: `2` is the first retry.
  readonly attempt: number;
  /// MILLISECONDS TO WAIT FIRST -- 0 for the first ask, `ASK_AGAIN_AFTER_MS` for every
  /// one after it.
  readonly after: number;
};

/// WHAT THE LISTING SAYS ABOUT ONE SESSION -- a SNAPSHOT, and that word is the whole reason
/// this rule is shaped the way it is: both fields can be behind the session they describe,
/// and both are behind it in a direction this page can see.
export type ListedRow = {
  /// THE STORE'S SEND TIME, or null for a row whose send has not been recorded yet. The
  /// row is created by the registration; the name and the time are written when the run's
  /// input arrives. Between the two the row is there and still says 还没跑过.
  readonly lastSentAt: number | null;
  /// WHETHER THE LISTING SAYS A RUN IS IN FLIGHT -- the server's live-runs registry as of
  /// that read, not as of now.
  readonly running: boolean;
};

/// WHICH SESSION TO ASK ABOUT, OR UNDEFINED WHEN THERE IS NOTHING LEFT TO ASK.
///
/// `titles` is the page's own registry of the sessions it minted and HAS A TITLE FOR. A
/// title arrives with the first send and from nowhere else (`app.tsx`'s `reportTitle`), so
/// every id in it has had a write issued for it -- which is what makes an id in it a row
/// that is due rather than one nobody has asked for.
///
/// AND THE ROWS THE LISTING NAMES ARE CANDIDATES TOO, which is the half the third reason
/// cannot do without: an id LEAVES `titles` the moment the listing names it (the page's
/// own live title is only there to name a row the store has not answered for yet --
/// `app.tsx`'s `forgetListedTitles`), and the row that has ARRIVED is exactly the row the
/// third reason is about. A rule that only looked at `titles` could never ask again once a
/// row was listed, so the spinner a stale listing put on it would stay. `titles` still
/// answers FIRST, so a session with no row at all is asked about before a stale one.
///
/// `rows` IS WHAT THE LISTING SAYS, per id it names, and `running` IS WHICH SESSIONS THIS
/// PAGE IS DRIVING a run in right now -- its own registry, live, and the only thing that
/// knows a run has ended. THE TWO ARE READ TOGETHER because the listing's `running` is a
/// snapshot: a read taken while a run was in flight goes on saying so, and the row would
/// wear its spinner for ever if nothing asked again once the run was over. An id the
/// listing does not name at all answers exactly as an id whose snapshot is behind does --
/// ask again -- and a row is DUE in any of three cases:
///
///   * it is not in the listing (the registration has not been read yet);
///   * it is, with no send recorded (the run's own write is not there yet);
///   * it is, with `running` set while this page is not running it -- the run ended after
///     that read, and the row is still wearing a spinner nobody will take off.
///
/// AND AN ID THAT IS RUNNING IS LEFT ALONE: asking while a run is in flight gets the same
/// answer, and the end of the run is what changes it -- which arrives as a change to
/// `running` and re-runs the caller's effect. So this is not a poll: every ask is a
/// response to a state, and each of the three stops the moment the store catches up.
///
/// `attempts` is the count so far per id, this page load's -- see the header on why there
/// is a bound, and why it is per id.
export function nextAsk(
  titles: readonly string[],
  rows: ReadonlyMap<string, ListedRow>,
  running: ReadonlySet<string>,
  attempts: ReadonlyMap<string, number>,
): Ask | undefined {
  const candidates = [...new Set([...titles, ...rows.keys()])];
  const id = candidates.find((t) => {
    const row = rows.get(t);
    const due =
      row === undefined ||
      row.lastSentAt === null ||
      (row.running && !running.has(t));
    return due && (attempts.get(t) ?? 0) < ASK_AGAIN_LIMIT;
  });
  if (id === undefined) return undefined;
  const attempt = (attempts.get(id) ?? 0) + 1;
  return { id, attempt, after: attempt === 1 ? 0 : ASK_AGAIN_AFTER_MS };
}

/// THE ATTEMPTS WITH THIS ASK COUNTED, as a NEW map. The caller keeps them in a ref, and
/// mutating the map it is already holding is how a render gets to disagree with what was
/// actually asked.
export function countAsk(attempts: ReadonlyMap<string, number>, ask: Ask): Map<string, number> {
  return new Map(attempts).set(ask.id, ask.attempt);
}
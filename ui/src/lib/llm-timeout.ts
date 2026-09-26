// What the idle-timeout card says, as arithmetic over the frame the server sent.
//
// ONE FRAME, ONE FACT, AND THE FACT IS ABOUT A CALL RATHER THAN ABOUT THE CONVERSATION:
// the server's idle guard cuts a model call off when the vendor has said nothing for
// `idleMs` (`harness.kernel.llm`), and `harness.kernel.loop` either tries again or ends
// the run. The frame reaches the page as a CUSTOM frame, the AG-UI adapter turns it into a
// `data` part, and this module is the arithmetic the card draws from.
//
// THERE IS NO COUNTERPART IN A REBUILT CONVERSATION, and that is the feature rather than
// an omission: the frame is never recorded (`harness.edge.http/wire-only-frame?`), so this
// module has no `keepInjectionCards` -- there is nothing to put back after a refresh. What
// is on screen is what the live run said.
//
// RUNTIME-ZERO IMPORTS, like `lib/injections.ts` and for the same reason: the UI suite
// pins this as arithmetic over literals.
export const TIMEOUT_PART = "llm-timeout";

/// The frame's value, as `harness.edge.ag-ui` builds it. Every field is `unknown` because
/// this is what ARRIVED, not what we hoped for.
export type TimeoutValue = {
  readonly idleMs?: unknown;
  readonly attempt?: unknown;
  readonly limit?: unknown;
  readonly retrying?: unknown;
  readonly emitted?: unknown;
};

/// What the card draws, once the value has been read.
export type TimeoutView = {
  readonly idleMs: number;
  readonly attempt: number;
  readonly limit: number;
  readonly retrying: boolean;
  readonly emitted: boolean;
};

/// A non-negative finite number, or null for anything else.
function numberOrNull(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : null;
}

/// A frame's value -> the numbers and the two flags the card speaks, or null when there is
/// nothing to read.
///
/// THE THREE NUMBERS ARE REQUIRED AND THE TWO FLAGS ARE NOT, and the asymmetry is
/// deliberate. `idleMs`, `attempt` and `limit` are what the card's sentence SAYS ("500ms",
/// "retry 1/3"), so a frame missing one would be a card claiming something the server never
/// said -- and drawing nothing is the honest answer, exactly as `injectionView` answers null
/// for a frame with no text. `retrying` and `emitted` fall back to `false`, which is the safe
/// direction: the row then says the run is not being retried, which is what an unreadable
/// flag beside a frame like this most likely means.
export function timeoutView(value: unknown): TimeoutView | null {
  if (typeof value !== "object" || value === null) return null;
  const v = value as TimeoutValue;
  const idleMs = numberOrNull(v.idleMs);
  const attempt = numberOrNull(v.attempt);
  const limit = numberOrNull(v.limit);
  if (idleMs === null || attempt === null || limit === null) return null;
  return {
    idleMs,
    attempt,
    limit,
    retrying: v.retrying === true,
    emitted: v.emitted === true,
  };
}

/// Which of the THREE ENDINGS the server announced, which is what the expanded card's
/// sentence is about.
///
/// THE ORDER THE CASES ARE ASKED IN MATTERS. A timeout AFTER the call had already put
/// something on the wire is never retried, so `emitted` is read first; the other two only
/// describe a call that had said nothing yet, and they differ in whether another attempt
/// follows this one.
///
/// A DISCRIMINANT RATHER THAN A KEY, because the words belong to the catalogs and the call
/// sites that name them (`thread.json`, through `t(...)`) want LITERAL keys -- i18next is
/// typed from the catalogs (`src/i18next.d.ts`), so a key built by string concatenation is
/// a compile error on purpose. The same split `relative-time.ts` makes with its buckets.
export type TimeoutOutcome = "partial" | "retrying" | "exhausted";

/// The ending VIEW describes -- see `TimeoutOutcome`.
export function timeoutOutcome(view: TimeoutView): TimeoutOutcome {
  if (view.emitted) return "partial";
  if (view.retrying) return "retrying";
  return "exhausted";
}

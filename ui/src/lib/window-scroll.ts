// HOLDING THE READER'S PLACE.
//
// "Show earlier" PREPENDS to the conversation, and a prepend pushes everything the reader
// is looking at DOWN: without this, asking for older history scrolls the text you were
// reading off the bottom of the window. Nothing about that is visible to a test that
// renders components without a layout engine -- `scrollHeight` is 0 in jsdom -- which is
// why the arithmetic is here, in three pure lines, and why the browser walkthrough is
// what proves the wiring (ticket 06 of `.scratch/sessions-live-on-the-server`, judgement
// 6: "prepend by nature pushes content down, so keeping the scroll position still is
// ANCHORING, not doing nothing").
//
// THE ANCHOR IS A MESSAGE'S TEXT, NOT ITS NODE, and that is the one thing here the
// walkthrough had to teach. The obvious implementation holds the DOM element that is on
// screen and asks where it went -- and it is WRONG in this app: assistant-ui keeps a
// message node by POSITION, so after an import that prepends two turns, the node that
// was "turn 3" is still the first child, drawn exactly where it was, with "turn 1" now
// inside it. Measured, in the browser: the node was connected, its top had moved 83 -> 49
// while four thousand pixels of older conversation appeared above it, and the correction
// computed from that was wrong by everything. The element is not the message; the text
// is. (The node's own position is not even self-consistent -- see the index check in
// `sameMessage`.)
export type Anchor = { top: number; height: number };

/// The part of a scroll container the height arithmetic needs -- a real `HTMLElement`
/// fits, and so does a literal, which is what the test uses.
export type ScrollBox = { scrollTop: number; scrollHeight: number };

export function measure(el: ScrollBox | null): Anchor | null {
  if (el === null) return null;
  return { top: el.scrollTop, height: el.scrollHeight };
}

/// WHERE THE READER'S TOP SHOULD GO, now that the container's height is `el.scrollHeight`
/// instead of `anchor.height`. The difference IS the answer: if a page of older messages
/// was added above, the content grew by exactly the amount the reader has to move down.
///
/// THE FALLBACK, for the cases a message anchor cannot cover: the message the reader was
/// looking at has no text, its text has changed since (a turn that was drawn expanded
/// while it ran comes back collapsed), or the window was replaced by a reopen. It is
/// wrong by the height of anything ABOVE the messages that changed in the same instant
/// -- the "show earlier" control disappearing when the last page in front arrives is the
/// one that actually happens, five dozen pixels of it -- which is why it is the fallback
/// and not the mechanism.
export function restoredTop(anchor: Anchor, el: ScrollBox): number {
  return Math.max(0, anchor.top + (el.scrollHeight - anchor.height));
}

/// WHERE THE SCROLL HAS TO GO, given that the anchor message moved from `was` to `now` on
/// screen. The reader's place is the same when the anchor's viewport position is the same,
/// so the correction is the difference -- and it can be either sign, which is why this is
/// not the same line as `restoredTop`.
export function correctedTop(was: number, now: number, scrollTop: number): number {
  return Math.max(0, scrollTop + (now - was));
}

/// THE MESSAGE THE READER'S EYE IS ON, and everything about it that the correction below
/// needs: the first element in the message group that says anything and is not entirely
/// above the viewport's top edge.
///
/// THE MESSAGE GROUP IS FOUND BY ITS OWN SLOT (`thread.aui.tsx`), and the reading is of
/// TEXT rather than of position, so a turn that is partly scrolled off is still a fair
/// anchor: what is held still is its top, wherever that top happens to be.
type AnchorMessage = { text: string; index: number; top: number };

const textOf = (element: Element): string => (element as HTMLElement).innerText.replace(/\s+/g, " ").trim();

function anchorNow(viewport: HTMLElement): AnchorMessage | null {
  const group = viewport.querySelector('[data-slot="aui_message-group"]');
  if (group === null) return null;
  const edge = viewport.getBoundingClientRect().top;
  const children = [...group.children];
  for (let index = 0; index < children.length; index += 1) {
    const element = children[index];
    if (!(element instanceof HTMLElement)) continue;
    const text = textOf(element);
    const box = element.getBoundingClientRect();
    if (text === "" || box.bottom < edge) continue;
    return { text, index, top: box.top };
  }
  return null;
}

/// WHERE THAT MESSAGE IS NOW, by its text -- and never by its node, for the reason this
/// file's header writes down.
///
/// THE SEARCH STARTS AT THE INDEX THE ANCHOR HAD, and that check is not an optimisation: a
/// prepend only pushes messages DOWN the list, so a match at an EARLIER index is a
/// different message that happens to say the same thing (two identical prompts in one
/// conversation), and correcting by its position would throw the reader thousands of
/// pixels the wrong way. Nothing found is an honest answer -- the caller falls back to the
/// container's arithmetic.
function sameMessage(viewport: HTMLElement, anchor: AnchorMessage): HTMLElement | null {
  const group = viewport.querySelector('[data-slot="aui_message-group"]');
  if (group === null) return null;
  const children = [...group.children];
  for (let index = anchor.index; index < children.length; index += 1) {
    const element = children[index];
    if (element instanceof HTMLElement && textOf(element) === anchor.text) return element;
  }
  return null;
}

/// THE VIEWPORT THIS PAGE IS ANCHORING -- one, because only the session on screen renders
/// a conversation column (the other hosts render nothing). `thread.aui.tsx` registers its
/// viewport on mount and clears it on unmount; a page with no column (a load in flight,
/// the trajectory view) has nothing to hold.
let viewport: HTMLElement | null = null;

export function registerViewport(el: HTMLElement | null): void {
  viewport = el;
}

/// HOW LONG TO KEEP CORRECTING, and how often, in milliseconds. THE LAYOUT IS NOT DONE
/// WHEN REACT IS: importing a page re-mounts message components, and a turn that was
/// drawn expanded while it ran comes back collapsed -- the browser walkthrough measured
/// the conversation at 10729 pixels a tenth of a second after the prepend and 8327 two
/// tenths later. A single correction would have been computed against heights that were
/// about to change, and been wrong by the difference. So it corrects, waits, and corrects
/// again; the formula is idempotent (once the anchor is back where it was, the next pass
/// computes no movement), so the extra passes are free.
const SETTLE_PASSES = 3;
const SETTLE_MS = 80;

/// RUN `change` AND PUT THE READER BACK WHERE THEY WERE, if anything moved.
///
/// THE CHANGE RUNS FIRST AND THE CORRECTIONS COME AFTER, and the order is the whole
/// mechanism: React has to commit the new messages and the browser has to lay them out
/// before the anchor means anything. `behavior: "instant"` is not decoration either --
/// the viewport is styled `scroll-smooth`, so assigning `scrollTop` would ANIMATE the
/// reader back down and they would watch the conversation slide under them.
export function withHeldScroll(change: () => void): void {
  const el = viewport;
  const anchor = el === null ? null : anchorNow(el);
  const box = measure(el);
  change();
  if (el === null) return;
  const correct = () => {
    const found = anchor === null ? null : sameMessage(el, anchor);
    if (anchor !== null && found !== null) {
      const now = found.getBoundingClientRect().top;
      el.scrollTo({ top: correctedTop(anchor.top, now, el.scrollTop), behavior: "instant" });
      return;
    }
    if (box !== null) el.scrollTo({ top: restoredTop(box, el), behavior: "instant" });
  };
  requestAnimationFrame(() => {
    requestAnimationFrame(correct);
    for (let pass = 1; pass < SETTLE_PASSES; pass += 1) setTimeout(correct, SETTLE_MS * pass);
  });
}

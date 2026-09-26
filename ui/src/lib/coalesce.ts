// ONE FLUSH PER FRAME: the downlink's frames are HELD until the browser's next animation
// frame, and then handed over together, in arrival order.
//
// WHY. Every frame this side hands to a subscriber ends up as one `notifyUpdate` in the
// runtime (`@assistant-ui/react-ag-ui`'s `useAgUiRuntime`), and that is one React update.
// An update raised outside React's own event handlers is batched only within the task it
// arrived in -- and each WebSocket message IS its own task. So a vendor streaming faster
// than the browser draws produces ONE RENDER PER TOKEN: the whole conversation is
// reconciled, dozens of times a second, for five characters at a time. Holding the frames
// and delivering them in one task per frame folds that into one render per frame, which is
// all a reader can see anyway.
//
// WHAT IT DOES NOT CHANGE. The frames, their order, and which of them are delivered at
// all: a carrier change, exactly as the SSE feed's one socket was (ADR 0004). Nothing here
// inspects a frame's payload.
//
// AND A TERMINAL FRAME DOES NOT WAIT -- a run's end, a window's end. The reader is told
// the moment it arrives, and whatever was held in FRONT of it goes first, in order: these
// frames arrived before it, and the conversation is read in the order it happened.
//
// THE CURSOR MOVES WHEN A FRAME IS DELIVERED, NOT WHEN IT ARRIVES. That half is
// `lib/mux.ts`'s, and it is why this module cannot just be a queue: `runCursors` is what a
// reconnect asks from (`runSince`), so a mark that had moved past a frame still waiting
// here would make the reconnect SKIP it. Delivery is the only moment a frame has been seen.
//
// A HIDDEN PAGE DOES NOT DRAW, so `requestAnimationFrame` stops coming and the queue would
// grow for as long as the tab stays away -- which is why there is a limit: past
// `HOLD_LIMIT` frames the batch goes out without waiting for a frame. Delivery is then
// never later than a frame, and never lost to a hidden tab.

/// HOW MANY FRAMES MAY BE HELD BEFORE THE BATCH GOES OUT ANYWAY, in frames. Scaled to a
/// reader's patience rather than to memory: 200 frames is a few seconds of a fast vendor's
/// stream, and the point of the number is only that a hidden tab cannot hold a run's whole
/// output.
export const HOLD_LIMIT = 200;

export type Batch<F> = {
  /// Take a frame. A terminal one goes out now, after whatever was waiting; anything else
  /// waits for the next flush (or for the hold's limit).
  push: (frame: F) => void;
  /// Hand over what is waiting, now. An empty queue delivers nothing -- which is what a
  /// scheduled flush is allowed to find.
  flush: () => void;
};

export function createBatch<F>(options: {
  deliver: (frames: readonly F[]) => void;
  /// Whether this frame may not wait: the run is over, or the window is.
  terminal: (frame: F) => boolean;
  /// Arrange a flush. `requestAnimationFrame` on the page; a suite's own clock in a case,
  /// because the rule here is about WHEN and no test can wait for a browser.
  schedule: (flush: () => void) => void;
}): Batch<F> {
  const { deliver, terminal, schedule } = options;
  let held: F[] = [];
  let waiting = false;

  const flush = (): void => {
    // THE MARK IS CLEARED FIRST, so a flush that delivers nothing (an empty tick) still
    // lets the NEXT frame ask for one.
    waiting = false;
    if (held.length === 0) return;
    const batch = held;
    held = [];
    deliver(batch);
  };

  return {
    flush,
    push: (frame) => {
      if (terminal(frame)) {
        const before = held;
        held = [];
        waiting = false;
        if (before.length > 0) deliver(before);
        deliver([frame]);
        return;
      }
      held.push(frame);
      if (held.length >= HOLD_LIMIT) {
        flush();
        return;
      }
      if (waiting) return;
      waiting = true;
      schedule(flush);
    },
  };
}

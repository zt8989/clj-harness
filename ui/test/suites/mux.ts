// The downlink's ADDRESS and the declaration it carries (`events.mux`, ADR 0004).
//
// WHAT A SUITE CAN REACH HERE, and what it cannot: this is the shape of the handshake URL
// -- one prefix, the subscriber token, the set -- and the fact that a page following
// nothing declares nothing. WHETHER A SOCKET ACTUALLY CARRIES A WINDOW is a browser's
// question (a real WebSocket, a real run, a reconnect) and belongs to the walkthrough, not
// to this run -- `lib/mux.ts` says so at the top.
import { expect, vi } from "vitest";

import { type Case, type Suite } from "../e2e";
import { declaredSet, familyOf, subscribeTasks, TASK_FRAME_TYPE } from "../../src/lib/mux";
import { downlinkUrl } from "../../src/lib/threads";

/// A STAND-IN FOR THE BROWSER'S `WebSocket`, for the length of the cases below -- the one that is a
/// question about the SET a socket would state, and the two at the end that need an ANSWERING one.
/// Registering a subscription is what opens a socket -- that is what makes a page hear anything at
/// all.
///
/// IT NEVER LEAVES `CONNECTING` UNLESS A CASE ASKS IT TO (`open` below): the declaration case
/// wants nothing sent, nothing fetched and no reconnect timer armed, and that is the state the
/// module is left in. The two cases at the end DO need an open socket -- what happens to a frame
/// between the socket and a subscriber is not a fact about a value -- so they set the flag, feed
/// frames in, and put it back in their own `finally`. A REAL reconnect stays the walkthrough's
/// question (the header above).
class ConnectingSocket {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  /// WHETHER A CASE HAS ASKED THIS STAND-IN TO ANSWER. Every instance COMPUTES its `readyState`
  /// from this flag, so one assignment moves the socket the module is already holding as well as
  /// the next one it opens.
  static open = false;
  /// THE ONE THE MODULE HOLDS: `new WebSocket(..)` is the only door, and a case needs the instance
  /// to feed frames into.
  static last: ConnectingSocket | null = null;
  constructor(readonly url: string) {
    ConnectingSocket.last = this;
  }
  get readyState(): number {
    return ConnectingSocket.open ? ConnectingSocket.OPEN : ConnectingSocket.CONNECTING;
  }
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  addEventListener(): void {}
  removeEventListener(): void {}
  /// ONE FRAME, the way the browser hands one over: the module reads `event.data` and nothing
  /// else.
  feed(frame: Record<string, unknown>): void {
    this.onmessage?.({ data: JSON.stringify(frame) });
  }
}


/// THE FOUR BITS OF THE BROWSER THE TWO CASES AT THE END NEED, and where they go back to.
///
/// A COPY OF THE MODULE IS ONE OF THEM. The instance every other suite shares has been driving
/// real runs against the harness by the time this suite runs, so its socket is a real one and a
/// case about what arrives ON a socket has to own one. `vi.resetModules()` + a dynamic import is
/// that copy: the same source with its own state, so these two cases neither see nor disturb what
/// the rest of the run is doing with the other one.
///
/// The other three: `fetch` answers the declaration (`POST /api/events.mux/subscribe`) and records
/// what was asked for; the stand-in above is made to ANSWER (its own note); and the clock is a queue
/// NOTHING runs until the case says so -- which is a hidden tab, the state the bug these two were
/// found in was in (`lib/coalesce.ts` holds frames until the next animation frame).
async function withStubs<T>(
  body: (state: {
    mux: typeof import("../../src/lib/mux");
    declares: Array<Record<string, unknown>>;
    draw: () => void;
    socket: () => ConnectingSocket;
  }) => Promise<T>,
): Promise<T> {
  const browser = globalThis.WebSocket;
  const realFetch = globalThis.fetch;
  const realRaf = globalThis.requestAnimationFrame;
  const declares: Array<Record<string, unknown>> = [];
  const clock: Array<() => void> = [];
  globalThis.WebSocket = ConnectingSocket as unknown as typeof WebSocket;
  ConnectingSocket.open = true;
  globalThis.requestAnimationFrame = ((cb: FrameRequestCallback) => {
    clock.push(cb as unknown as () => void);
    return 0;
  }) as typeof requestAnimationFrame;
  globalThis.fetch = (async (_url: unknown, init?: RequestInit) => {
    const body = JSON.parse(String(init?.body ?? "{}")) as {
      subscribe?: Array<Record<string, unknown>>;
    };
    for (const entry of body.subscribe ?? []) declares.push(entry);
    return new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
  }) as typeof fetch;
  try {
    vi.resetModules();
    const mux = await import("../../src/lib/mux");
    return await body({
      mux,
      declares,
      draw: () => {
        for (const cb of clock.splice(0)) cb();
      },
      socket: () => {
        if (ConnectingSocket.last === null) throw new Error("the module never opened a socket");
        return ConnectingSocket.last;
      },
    });
  } finally {
    // THE COPY GOES WITH THE CASE (the next dynamic import is a fresh one again), and the stand-in
    // is left connecting: nothing this case did is visible to a later suite, which keeps the real
    // socket and the real fetch it had.
    for (const cb of clock.splice(0)) cb();
    ConnectingSocket.open = false;
    globalThis.WebSocket = browser;
    globalThis.fetch = realFetch;
    if (realRaf === undefined)
      delete (globalThis as { requestAnimationFrame?: unknown }).requestAnimationFrame;
    else globalThis.requestAnimationFrame = realRaf;
  }
}

const cases: Case[] = [
  {
    name: "the-downlink-hangs-off-the-one-prefix-and-carries-the-declaration",
    run: async () => {
      const params = new URLSearchParams();
      params.set("subscriber", "tok-1");
      params.set("sessions", JSON.stringify([{ threadId: "t-1", since: 7, generation: "g" }]));

      const url = downlinkUrl("events.mux", params);

      // THE PATH IS THE ONE PREFIX EVERY OTHER CALL USES, and the whole declaration rides
      // on it -- the socket carries no client message, so the URL is where a subscription
      // is first stated.
      expect(url).toContain("/api/events.mux?");
      expect(url).toContain("subscriber=tok-1");
      expect(url).toContain("sessions=");
      expect(url).toContain("t-1");

      // NO DOUBLE SLASH when the page has no absolute address to talk to -- the relative
      // form a built page uses, where the handshake resolves against the document.
      expect(url.includes("//api")).toBe(false);
    },
  },
  {
    name: "a-page-following-nothing-declares-nothing",
    run: async () => {
      // THE SET IS EMPTY UNTIL SOMEBODY FOLLOWS A WINDOW, and at module load nobody has:
      // the first host that opens a window is what opens the socket.
      expect(declaredSet()).toEqual([]);
    },
  },
  {
    name: "the-host-downlink-is-a-second-address-under-the-same-prefix",
    run: async () => {
      // TWO CATEGORIES, TWO SOCKETS (ADR 0004): `events.host` carries no subscription, so
      // its address is the bare path -- no token and no set.
      const url = downlinkUrl("events.host", new URLSearchParams());
      expect(url).toContain("/api/events.host");
      expect(url).not.toContain("?");
    },
  },
  {
    name: "a-fact-frame-is-not-a-run-frame",
    run: async () => {
      // THE ROUTING RULE, which is the whole of this module's safety for the third family:
      // a fact (`turn/*`, `model/*`) must NEVER be handed to `@ag-ui/client`, whose schema
      // would refuse it and take the run down with it. The two named families are explicit
      // and the default is the run family, which is AG-UI's own (upper-case) vocabulary --
      // the fall-through this test exists to keep from swallowing the middle case.
      expect(familyOf("append")).toBe("window");
      expect(familyOf("window")).toBe("window");
      expect(familyOf("model/start")).toBe("fact");
      expect(familyOf("model/end")).toBe("fact");
      expect(familyOf("turn/start")).toBe("fact");
      expect(familyOf("turn/end")).toBe("fact");
      expect(familyOf("RUN_STARTED")).toBe("run");
      expect(familyOf("TEXT_MESSAGE_CONTENT")).toBe("run");
      // AND THE FOURTH FAMILY IS NAMED TOO: a `{:type task ..}` payload is a whole pane
      // (`TaskFrame`), and handing it to `@ag-ui/client` would be the same mistake the fact
      // case above exists to stop -- it would be validated as a run and refused.
      expect(familyOf(TASK_FRAME_TYPE)).toBe("task");
    },
  },
  {
    name: "a-thread-only-the-pane-follows-is-in-the-declaration",
    run: async () => {
      // THE BUG THIS PINS (ticket 01 of `.scratch/task-pane-push`, found in a real browser):
      // the task pane's table was missing from `wantedThreads()`. The declaration is not only
      // the first handshake -- it is what a REPLACED socket re-states (ADR 0003 decision 7) --
      // so a thread the new handshake does not name is a thread the server stops sending to:
      // the pane's row went on saying "so far" for a job that had already ended.
      //
      // NO SOCKET IS OPENED: the stand-in above never connects, so this stays a question about
      // the set and costs nothing (see that class's own note).
      const browser = globalThis.WebSocket;
      globalThis.WebSocket = ConnectingSocket as unknown as typeof WebSocket;
      try {
        const pane = subscribeTasks("pane-thread-1", () => {});

        // A PANE FOLLOWING A CONVERSATION IS WHY THE SERVER MUST GO ON SENDING TO IT, and the
        // declaration is where that is said.
        expect(declaredSet().map((entry) => entry.threadId)).toContain("pane-thread-1");

        // AND A PANE THAT GOES AWAY LEAVES NOTHING OF ITSELF BEHIND.
        pane.unsubscribe();
        expect(declaredSet().map((entry) => entry.threadId)).not.toContain("pane-thread-1");
      } finally {
        // THE STAND-IN STAYS THIS MODULE'S SOCKET for the rest of the run: there is no seam to
        // put the browser's constructor back into it, and no later suite wants one -- they read
        // values and sources, not sockets (`right-pane`, `thread-messages`, `coalesce`). The
        // platform's own constructor goes back where it was all the same.
        globalThis.WebSocket = browser;
      }
    },
  },
  {
    name: "a-declared-run-cursor-is-what-the-page-holds-not-what-it-has-drawn",
    run: async () => {
      // THE BUG THIS PINS (owner's report, 2026-09-30 -- `.scratch/mux-run-replay-dupes/`):
      // "reuse the original tab" -- a tab left in the background, where the browser draws
      // nothing -- and a run dies with
      //   Cannot send 'TEXT_MESSAGE_END' event: No active text message found with ID '<id>'.
      //   A 'TEXT_MESSAGE_START' event must be sent first.
      // The page had declared a cursor that waited for the frames to be DRAWN, the server
      // replayed everything above it, and the second copy of an END is what `@ag-ui/client`'s
      // verifier refuses. A `since` means what the page HOLDS
      // (`harness.edge.mux/run-frames-after`), and this says so.
      await withStubs(async ({ declares, draw, socket, mux }) => {
        const threadId = "dupes-hold-1";
        const drawn: string[] = [];
        const run = mux.subscribeRun(threadId, (frame) => drawn.push(String(frame.type)));
        await run.declared;
        // THE FIRST DECLARATION HOLDS NOTHING -- and `null` has a meaning of its own on the
        // server: a connection about to START a run is not handed the previous one's terminal.
        expect(declares.at(-1)?.runSince).toBe(null);

        // FRAMES ARRIVE AND NONE IS DRAWN: the clock is a queue nothing runs (a hidden tab).
        socket().feed({ threadId, type: "RUN_STARTED", runId: "run-1", seq: 1 });
        socket().feed({ threadId, type: "TEXT_MESSAGE_START", messageId: "m1", seq: 2 });
        expect(drawn).toEqual([]);
        // ...AND THE CURSOR IS WHAT THE PAGE HOLDS, not what it has drawn.
        expect(mux.declaredSet().find((entry) => entry.threadId === threadId)?.runSince).toBe(2);

        // AND IT IS A RECEIPT MARK, NOT A ONE-OFF: one more arrives and is drawn, and the one
        // after it -- not drawn -- is what the page would ask from.
        socket().feed({ threadId, type: "TEXT_MESSAGE_CONTENT", messageId: "m1", seq: 3 });
        draw();
        expect(drawn).toEqual(["RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT"]);
        socket().feed({ threadId, type: "TEXT_MESSAGE_END", messageId: "m1", seq: 4 });
        expect(mux.declaredSet().find((entry) => entry.threadId === threadId)?.runSince).toBe(4);

        run.unsubscribe();
        expect(mux.declaredSet().map((entry) => entry.threadId)).not.toContain(threadId);
      });
    },
  },
  {
    name: "a-frame-the-server-sends-twice-reaches-a-run-once",
    run: async () => {
      // THE OTHER HALF OF THE SAME GUARD: a run's frames are numbered by the SENDER
      // (`harness.edge.mux/record-run!`) precisely so a reader can tell the live tail from a
      // replayed copy of it, and a duplicate of an END or a START is fatal to the run a page is
      // driving.
      await withStubs(async ({ draw, socket, mux }) => {
        const threadId = "dupes-resend-1";
        const seen: string[] = [];
        const run = mux.subscribeRun(threadId, (frame) =>
          seen.push(`${String(frame.type)}@${String(frame.seq)}`),
        );
        await run.declared;

        const first = [
          { threadId, type: "RUN_STARTED", runId: "run-1", seq: 1 },
          { threadId, type: "TEXT_MESSAGE_START", messageId: "m1", seq: 2 },
          { threadId, type: "TEXT_MESSAGE_END", messageId: "m1", seq: 3 },
        ];
        for (const frame of first) socket().feed(frame);
        draw();
        expect(seen).toEqual(["RUN_STARTED@1", "TEXT_MESSAGE_START@2", "TEXT_MESSAGE_END@3"]);

        // THE SAME THREE AGAIN, which is exactly what a declaration re-asks for when the cursor
        // it sent is behind the frames the page holds: DRAWN ONCE.
        for (const frame of first) socket().feed(frame);
        draw();
        expect(seen).toEqual(["RUN_STARTED@1", "TEXT_MESSAGE_START@2", "TEXT_MESSAGE_END@3"]);

        // A FRAME THAT ARRIVES BEFORE ITS GAP IS STILL DRAWN, and the copy that fills the gap
        // is the one dropped -- which is why the mark is CONTIGUOUS rather than a high-water
        // one: a replayed burst can be overtaken by the live tail, and dropping what a replay was
        // repairing would be a lost START, refused just as hard as a repeated END.
        socket().feed({ threadId, type: "TEXT_MESSAGE_CONTENT", messageId: "m1", seq: 5 });
        socket().feed({ threadId, type: "TEXT_MESSAGE_CONTENT", messageId: "m1", seq: 4 });
        socket().feed({ threadId, type: "TEXT_MESSAGE_CONTENT", messageId: "m1", seq: 4 });
        draw();
        expect(seen.slice(3)).toEqual(["TEXT_MESSAGE_CONTENT@5", "TEXT_MESSAGE_CONTENT@4"]);

        // AND A NEW RUN IS A NEW RUN: its numbering starts over, and numbers below the old mark
        // are not mistaken for re-sends -- the `runId` is what tells the two apart.
        socket().feed({ threadId, type: "RUN_STARTED", runId: "run-2", seq: 1 });
        socket().feed({ threadId, type: "TEXT_MESSAGE_START", messageId: "m3", seq: 2 });
        draw();
        expect(seen.slice(5)).toEqual(["RUN_STARTED@1", "TEXT_MESSAGE_START@2"]);

        run.unsubscribe();
      });
    },
  },
];

export const muxSuite: Suite = { name: "mux", cases };

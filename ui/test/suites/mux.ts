// The downlink's ADDRESS and the declaration it carries (`events.mux`, ADR 0004).
//
// WHAT A SUITE CAN REACH HERE, and what it cannot: this is the shape of the handshake URL
// -- one prefix, the subscriber token, the set -- and the fact that a page following
// nothing declares nothing. WHETHER A SOCKET ACTUALLY CARRIES A WINDOW is a browser's
// question (a real WebSocket, a real run, a reconnect) and belongs to the walkthrough, not
// to this run -- `lib/mux.ts` says so at the top.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { declaredSet, familyOf, subscribeTasks, TASK_FRAME_TYPE } from "../../src/lib/mux";
import { downlinkUrl } from "../../src/lib/threads";

/// A STAND-IN FOR THE BROWSER'S `WebSocket`, for the length of one case below. Registering a
/// subscription is what opens a socket -- that is what makes a page hear anything at all -- and
/// the case that uses this one is a question about the SET a socket would state, not about a
/// connection. So the stand-in never leaves `CONNECTING`: nothing is sent, nothing is fetched,
/// and no reconnect timer is ever armed. A REAL reconnect stays the walkthrough's question (the
/// header above).
class ConnectingSocket {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  constructor(readonly url: string) {}
  readonly readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((event: unknown) => void) | null = null;
  onclose: (() => void) | null = null;
  addEventListener(): void {}
  removeEventListener(): void {}
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
];

export const muxSuite: Suite = { name: "mux", cases };

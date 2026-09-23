// The downlink's ADDRESS and the declaration it carries (`events.mux`, ADR 0004).
//
// WHAT A SUITE CAN REACH HERE, and what it cannot: this is the shape of the handshake URL
// -- one prefix, the subscriber token, the set -- and the fact that a page following
// nothing declares nothing. WHETHER A SOCKET ACTUALLY CARRIES A WINDOW is a browser's
// question (a real WebSocket, a real run, a reconnect) and belongs to the walkthrough, not
// to this run -- `lib/mux.ts` says so at the top.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { declaredSet } from "../../src/lib/mux";
import { downlinkUrl } from "../../src/lib/threads";

const cases: Case[] = [
  {
    name: "the-downlink-hangs-off-the-one-prefix-and-carries-the-declaration",
    run: async () => {
      const params = new URLSearchParams();
      params.set("subscriber", "tok-1");
      params.set("sessions", JSON.stringify([{ threadId: "t-1", since: 7, generation: "g" }]));

      const url = downlinkUrl(params);

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
];

export const muxSuite: Suite = { name: "mux", cases };

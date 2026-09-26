// WHICH FUNCTION NAMES A CONVERSATION, and that a name is still a version-4 uuid.
//
// SMALL ON PURPOSE, and deliberately not the suite this file was on 2026-09-23: that one
// pinned a generator of the page's own -- sixteen bytes from a source, and the two branches
// of a fallback chain -- because the page owned one. It does not any more: `lib/id.ts` is a
// re-export of the client library's `randomUUID`, so what is left to pin is the DECISION
// (the name comes from `@ag-ui/client`, the library that names conversations by its own
// design) and the shape the rest of the product reads.
//
// WHAT THIS SUITE CANNOT SEE: that a page served over `http://192.168.x.x` -- where
// `crypto.randomUUID` does not exist -- actually starts and mints. Nothing in a node run is
// an insecure context (node's `crypto` HAS `randomUUID`, and the library reads it once, at
// import), so that half is the browser walkthrough's, which is where the original
// `TypeError: crypto.randomUUID is not a function` was found.
import { expect } from "vitest";

import { randomUUID } from "@ag-ui/client";

import { type Case, type Suite } from "../e2e";
import { newId } from "../../src/lib/id";

/// WHAT A VERSION-4 UUID LOOKS LIKE, as one line: the version nibble is `4` and the variant
/// nibble is `8`, `9`, `a` or `b`. This is the shape the store's `sessions.id`, a run's
/// `threadId` and a record's stem are all written in.
const V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

/// EVERY SOURCE FILE THE PAGE IS BUILT FROM, as raw text -- the whole page and not just the
/// file that names conversations, because the discipline is about a CALL SITE and the one
/// that broke was in another file.
///
/// `import.meta.glob` rather than `node:fs`, for the reason `suites/i18n.ts` gives: the glob is
/// vite's own file list, so there is no second notion of "the source".
const SOURCES = import.meta.glob("../../src/**/*.{ts,tsx}", {
  eager: true,
  query: "?raw",
  import: "default",
}) as Record<string, string>;

/// A CALL to the platform's secure-context-only mint -- `crypto.randomUUID(` -- as opposed to
/// the prose that names it.
///
/// THE PARENTHESIS IS THE WHOLE POINT: `lib/id.ts`'s header discusses the function at length,
/// and so does this file, so a bare-text search would fail on the documentation that exists to
/// warn about it. A call has an argument list; the prose does not.
///
/// A line opening a comment is skipped, which is what keeps those `///` notes from counting.
const PLATFORM_MINT = /crypto\s*\.\s*randomUUID\s*\(/;
const COMMENT_LINE = /^\s*(\/\/|\*)/;

function callsThePlatformsMint(text: string): boolean {
  return text
    .split("\n")
    .some((line) => !COMMENT_LINE.test(line) && PLATFORM_MINT.test(line));
}

const cases: Case[] = [
  {
    name: "the-name-comes-from-the-client-library-and-not-from-the-platform",
    run: async () => {
      // THE IDENTITY IS THE DECISION, and the one thing here that a shape check cannot
      // see. `crypto.randomUUID` exists only in a SECURE CONTEXT -- a phone reading the dev
      // server over `http://192.168.x.x` does not have it, which is the error this whole
      // thread started from -- so the page's mint has to BE the library's cross-platform
      // one. A wrapper, a hand-rolled v4 or the platform's call would each pass the case
      // below and fail this one.
      expect(newId).toBe(randomUUID);
    },
  },
  {
    name: "and-a-name-is-a-version-4-uuid-two-of-which-are-two-names",
    run: async () => {
      // THE SHAPE THE OTHER HALF OF THIS PRODUCT READS: a row's `sessions.id`, a run's
      // `threadId`, a record's stem. A library upgrade that stopped answering a v4 would be
      // felt there rather than here -- which is exactly why it is pinned here.
      expect(newId()).toMatch(V4);
      expect(newId()).not.toBe(newId());
    },
  },
  {
    name: "no-source-file-reaches-for-the-platform-s-mint",
    run: async () => {
      // THE HALF THIS SUITE COULD NOT SEE, and the bug's second arrival. The decision above was
      // made, documented and pinned -- and the page still died on a phone, because `lib/mux.ts`
      // named each downlink socket with `crypto.randomUUID()`: one file agreed, another called
      // the platform, and the platform is not there over `http://192.168.x.x`.
      //
      // IT IS A SOURCE-LEVEL CHECK BECAUSE THE BUG IS A CALL SITE RATHER THAN A VALUE: node's
      // `crypto` HAS `randomUUID`, so a runtime case run here would pass whichever function was
      // called and prove nothing about a browser on a LAN address.
      const offenders = Object.entries(SOURCES)
        .filter(([, text]) => callsThePlatformsMint(text))
        .map(([path]) => path);
      expect(offenders).toEqual([]);
    },
  },
];

export const idSuite: Suite = { name: "id", cases };

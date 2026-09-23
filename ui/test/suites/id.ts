// THE PAGE'S OWN NAME FOR A THING IT JUST MADE, on a browser that may not have
// `crypto.randomUUID`.
//
// PURE, in the shape `relative-time.ts` and `session-title.ts` established: `lib/id.ts`
// imports nothing, and the source of the randomness is a PARAMETER, so every branch of
// the chain is literals in and a string out -- no browser, no phone on a LAN address, no
// clock. That is the whole reason the module takes a source at all; left to
// `globalThis.crypto` it would be untestable here, which is how this bug shipped.
//
// WHAT THIS SUITE CANNOT SEE: that a page served over `http://192.168.x.x` actually
// starts. Nothing in a node run is an insecure context -- this machine's `crypto` has
// `randomUUID`, which is exactly why every gate stayed green while the phone threw
// (`TypeError: crypto.randomUUID is not a function`). What is pinned here is the RULE
// (`randomUUID` when it is a function, sixteen bytes otherwise, and the version-4 shape
// either way); the browser is `scripts/dev.mjs --scripted`'s half.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { newId, uuidFromBytes, type IdSource } from "../../src/lib/id";

/// WHAT A VERSION-4 UUID LOOKS LIKE, as one line: the version nibble is `4` and the
/// variant nibble is `8`, `9`, `a` or `b`. This is the thing the suite is about -- an id
/// that is a legal string but the wrong version is a bug nothing on screen reports.
const V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

/// SIXTEEN BYTES, each one easy to follow into the answer: `01 23 45 ...`.
const BYTES = new Uint8Array([0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef, 0xfe, 0xdc, 0xba, 0x98, 0x76, 0x54, 0x32, 0x10]);

/// A SOURCE THAT COUNTS AND FILLS, for the branch a phone on a LAN address takes:
/// `getRandomValues` there and nothing else. The bytes it writes are `i` per index, so
/// the uuid below is arithmetic over literals rather than "looks random enough".
function onlyGetRandomValues(): { source: IdSource; widths: number[] } {
  const widths: number[] = [];
  const source: IdSource = {
    getRandomValues: (bytes: Uint8Array): Uint8Array => {
      widths.push(bytes.length);
      for (let i = 0; i < bytes.length; i += 1) bytes[i] = i;
      return bytes;
    },
  };
  return { source, widths };
}

const cases: Case[] = [
  {
    name: "the-fallback-spells-out-a-version-4-uuid",
    run: async () => {
      // THE WHOLE STRING, so the two nibbles the shape overwrites are visible in the
      // expectation rather than inferred: `4def` is the version and `bedc`'s leading `b`
      // is the variant. The twelve bytes after them are the input, untouched.
      expect(uuidFromBytes(BYTES)).toBe("01234567-89ab-4def-bedc-ba9876543210");

      // AND IT IS A FUNCTION OF THE BYTES ALONE -- no clock, no counter, no second
      // source. Two calls with the same sixteen bytes are the same string, which is what
      // makes the case above an assertion about the arithmetic rather than about luck.
      expect(uuidFromBytes(BYTES)).toBe(uuidFromBytes(BYTES));

      // NOT EVERY ARRAY IS SIXTEEN BYTES LONG, and a short one reads as zeroes rather
      // than throwing: the caller that handed it a short one is already broken, and a
      // TypeError here would be the second thing to go wrong.
      expect(uuidFromBytes(new Uint8Array(4))).toBe("00000000-0000-4000-8000-000000000000");
    },
  },
  {
    name: "a-browser-with-only-getRandomValues-still-mints-one",
    run: async () => {
      const { source, widths } = onlyGetRandomValues();

      // THE PHONE'S BRANCH, and the one the fix is for: no `randomUUID` on the object at
      // all, and an id comes out anyway. The bytes are the counter above, so the answer
      // is exact.
      expect(newId(source)).toBe("00010203-0405-4607-8809-0a0b0c0d0e0f");

      // AND IT ASKED FOR EXACTLY SIXTEEN BYTES, once. The WebCrypto quirk that makes
      // this branch different from `randomUUID` is that the CALLER sizes the array, and
      // sixteen is the only width that is a UUID rather than a prefix of one.
      expect(widths).toEqual([16]);
    },
  },
  {
    name: "randomUUID-is-used-verbatim-when-the-page-has-it",
    run: async () => {
      const widths: number[] = [];
      const source: IdSource = {
        randomUUID: () => "the-browser's-own-id",
        getRandomValues: (bytes: Uint8Array): Uint8Array => {
          widths.push(bytes.length);
          return bytes;
        },
      };

      // THE SECURE CONTEXT'S BRANCH TAKES PRECEDENCE, and takes the string as given:
      // this module is a fallback, not a reimplementation, so a browser that has the
      // real thing keeps producing the real thing.
      expect(newId(source)).toBe("the-browser's-own-id");
      expect(widths).toEqual([]);

      // A PROPERTY THAT IS NOT A FUNCTION IS THE SAME AS ONE THAT IS ABSENT, and that is
      // not a hypothetical -- it is the reported error, one step earlier in the story
      // (`TypeError: crypto.randomUUID is not a function`). A truthiness test would have
      // walked straight into it.
      const notAFunction = { randomUUID: undefined, getRandomValues: undefined } as IdSource;
      expect(newId(notAFunction)).toMatch(V4);

      // AND THE DEFAULT SOURCE IS WHATEVER THE PAGE HAS: this run's node has the real
      // `randomUUID`, so the desktop path is one call away from the phone's.
      expect(newId()).toMatch(V4);
    },
  },
  {
    name: "no-webcrypto-at-all-is-still-a-name-rather-than-a-throw",
    run: async () => {
      // THE FLOOR: no `crypto` to speak of (an old WebView, or a page whose `crypto` was
      // shadowed). The id is weaker and it is still an id -- the alternative is a page
      // that does not start, which is the failure this module was written for.
      const first = newId({});
      const second = newId({});
      expect(first).toMatch(V4);
      expect(second).toMatch(V4);
      expect(first).not.toBe(second);
    },
  },
];

export const idSuite: Suite = { name: "id", cases };

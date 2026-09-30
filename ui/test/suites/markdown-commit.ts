// A MESSAGE STILL BEING WRITTEN IS REBUILT ON AN INTERVAL, NOT ON EVERY FRAME.
//
// WHAT THIS CAN AND CANNOT SEE. The rule is the library's (`useSmooth`'s `minCommitMs`), and what
// it buys -- the markdown parse of a nineteen-thousand-character answer happening about seventeen
// times a second instead of about fifty-six -- is a browser's measurement, written up in
// `.scratch/conversation-render-cost/spec.md` and `evidence/README.md`. What a node run can hold
// is the WIRING, and that is why the source is read below as well as the constants: the interval
// and the options are two constants defined next to each other, and asserting on them alone would
// stay green if the prop that actually hands them to the primitive were deleted -- which is the
// regression this case exists for. It is one prop in a copied file, and a prop is exactly what a
// later merge of that file removes without anybody noticing, because the page still works: it just
// costs three times as much while an answer is arriving.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import {
  MARKDOWN_SMOOTH,
  STREAM_COMMIT_MS,
} from "../../src/components/assistant-ui/elements/markdown-text";
import markdownTextSource from "../../src/components/assistant-ui/elements/markdown-text.tsx?raw";

/// One animation frame at 60Hz, which is the unit the interval has to beat: committing per frame
/// is the state this replaced.
const FRAME_MS = 1000 / 60;

const cases: Case[] = [
  {
    name: "the-streaming-markdown-is-committed-on-an-interval-not-on-every-frame",
    run: async () => {
      // AN INTERVAL, not an instant -- at least a couple of frames, or there is nothing to gain.
      expect(STREAM_COMMIT_MS).toBeGreaterThanOrEqual(2 * FRAME_MS);
      // AND NOT SO WIDE THAT THE TEXT STOPS ARRIVING: a reader notices stepping somewhere above
      // twenty updates a second.
      expect(STREAM_COMMIT_MS).toBeLessThanOrEqual(100);
      // THE OPTION THE PRIMITIVE IS GIVEN is the one above, not a literal that drifted.
      expect(MARKDOWN_SMOOTH.minCommitMs).toBe(STREAM_COMMIT_MS);
      // AND IT IS GIVEN TO IT AT ALL: the source, because none of the above can see the JSX that
      // spends it.
      expect(markdownTextSource).toContain("smooth={MARKDOWN_SMOOTH}");
    },
  },
];

export const markdownCommitSuite: Suite = { name: "markdown-commit", cases };

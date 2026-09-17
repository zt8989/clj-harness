// The composer's two attachment rules, and the sentences they say.
//
// BOTH CASES ARE PURE. Nothing here boots or calls the harness -- the two
// functions decide by themselves, and that is the point of keeping them in a
// module that imports nothing: the rule that turns a file away is the one thing in
// this feature that MUST agree with the server, so it is the one thing worth
// testing where a test can reach it directly. `vitest.config.ts` explains why the
// import is a relative path (no `@` alias in this run) and why that is enough.
//
// WHAT THIS SUITE CANNOT SEE: the composer's markup -- the disabled `+`, the drawn
// refusal line. No DOM here; those are measured in a real browser instead (see
// .scratch/composer-image/evidence/).
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import {
  ATTACHMENT_MAX_BYTES,
  acceptsImages,
  imageRefusal,
  overByteLimit,
  refusalFor,
  sizeRefusal,
} from "../../src/lib/attachment-rules";

const cases: Case[] = [
  {
    // Ticket 02. The judgement, and the two ways of disagreeing with the server.
    name: "the-model-rule-is-the-servers-rule",
    run: async () => {
      // A model that declares images takes them; one that declares text only does
      // not. Both halves of the declared set are read, so a model that declared
      // something else entirely is not accidentally treated as image-capable.
      expect(acceptsImages(["text", "image"])).toBe(true);
      expect(acceptsImages(["text"])).toBe(false);

      // NOTHING DECLARED IS NOTHING PROMISED. This is the case that keeps the
      // interface from being STRICTER than the server: an inline provider that
      // never said what it takes is unguarded there, so it must be attachable here,
      // or a configuration that runs today would be refused by the UI.
      expect(acceptsImages(undefined)).toBe(true);
      expect(acceptsImages(null)).toBe(true);
      expect(imageRefusal(undefined, "sees-images")).toBe(null);

      // ABSENT AND EMPTY ARE DIFFERENT ANSWERS, and the server measures them
      // differently: `harness.edge.ag-ui/undeclared-input` returns [] for nil (so
      // the run proceeds) and [:image] for #{} (so it is refused). The wire keeps
      // them apart the same way -- the key is missing for one, [] for the other --
      // and so does this. Reading [] as "declared nothing" would make the client
      // LOOSER than the server, which is the direction that costs somebody their
      // typed words: the run comes back RUN_ERROR with the composer already empty.
      expect(acceptsImages([])).toBe(false);

      // The sentence names the model, what it declared, and the way out -- the same
      // three things the server's own refusal names.
      const refusal = imageRefusal(["text"], "text-only");
      expect(refusal).not.toBe(null);
      expect(refusal).toContain("text-only");
      expect(refusal).toContain('["text"]');
      expect(refusal).toContain("change the model");
    },
  },
  {
    // Ticket 03. The cap, measured on the source file and nowhere else.
    name: "a-file-over-the-cap-is-refused-by-its-source-bytes",
    run: async () => {
      // The boundary, both sides of it: exactly at the cap passes, one byte over
      // does not. An off-by-one here is the difference between the documented
      // limit and a slightly different one.
      expect(ATTACHMENT_MAX_BYTES).toBe(2 * 1024 * 1024);
      expect(overByteLimit(ATTACHMENT_MAX_BYTES)).toBe(false);
      expect(overByteLimit(ATTACHMENT_MAX_BYTES + 1)).toBe(true);
      expect(sizeRefusal(ATTACHMENT_MAX_BYTES)).toBe(null);

      // The sentence carries the two numbers -- this one, and the cap.
      const refusal = sizeRefusal(3 * 1024 * 1024);
      expect(refusal).toContain("3 MB");
      expect(refusal).toContain("2 MB");

      // ONE MEASUREMENT, ON SOURCE BYTES. The rule takes `{ size }` and nothing
      // else, so there is no second reading of the same file (a base64 length, a
      // decoded pixel count) for a caller to reach for: the only thing it can be
      // asked about is the number a person can see in their file manager.
      expect(refusalFor({ size: ATTACHMENT_MAX_BYTES + 1 }, ["text", "image"], "m")).not.toBe(null);
      expect(refusalFor({ size: ATTACHMENT_MAX_BYTES }, ["text", "image"], "m")).toBe(null);

      // AND A REFUSED FILE IS NOT REPLACED BY A SMALLER ONE SILENTLY: the cap is
      // the same picture compressed, which passes on its own size. Nothing here
      // rewrites bytes -- see ticket 03 -- so this is the whole story: the same
      // rule, asked about a smaller file, says yes.
      expect(refusalFor({ size: 1024 * 1024 }, ["text", "image"], "m")).toBe(null);

      // The model's rule is asked FIRST when both would refuse: being told to
      // shrink a file that would be refused anyway is a refusal that leads to
      // another refusal.
      const both = refusalFor({ size: ATTACHMENT_MAX_BYTES + 1 }, ["text"], "text-only");
      expect(both).toContain("text-only");
    },
  },
];

export const attachmentsSuite: Suite = { name: "attachments", cases };

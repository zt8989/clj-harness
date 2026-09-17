// The composer's two attachment rules, and the sentences they say.
//
// BOTH CASES ARE PURE. Nothing here boots or calls the harness -- the two
// functions decide by themselves, and that is the point of keeping them in a
// module that imports nothing: the rule that turns a file away is the one thing in
// this feature that MUST agree with the server, so it is the one thing worth
// testing where a test can reach it directly. `vitest.config.ts` explains why the
// import is a relative path (no `@` alias in this run) and why that is enough.
//
// THE TWO REFUSALS ARE THIS SIDE'S OWN SENTENCES, so they have a language, and both
// are pinned in both: `translator` builds a fixed translator from the REAL
// `errors` catalog (`test/support/locale.ts`), the same way `stats` and `turns`
// pin their faces. The model id and the declared modalities cross the sentence
// UNCHANGED -- only the words around them move -- and the `(unnamed)` fallback for a
// model with no id is one of those words, which is why it is asserted here too.
//
// WHAT THIS SUITE CANNOT SEE: the composer's markup -- the disabled `+`, the drawn
// refusal line. No DOM here; those are measured in a real browser instead (see
// .scratch/composer-image/evidence/). It also cannot see the FETCH helpers'
// boundary rule (a server `error` body wins over this side's `HTTP N` fallback):
// those modules import `@/lib/threads`, and this run has no `@` alias, so that
// half is held by the comment in `projects.ts` and by the browser walkthrough.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { translator } from "../support/locale";
import {
  ATTACHMENT_MAX_BYTES,
  acceptsImages,
  imageRefusal,
  overByteLimit,
  refusalFor,
  sizeRefusal,
} from "../../src/lib/attachment-rules";

/// The `errors` catalog in the two languages the interface speaks. The refusals
/// live there because they are raised by this side, not sent by the server -- see
/// `docs/architecture/client.md`'s boundary section.
const en = translator("en", "errors");
const zh = translator("zh", "errors");

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
      expect(imageRefusal(undefined, "sees-images", en)).toBe(null);

      // ABSENT AND EMPTY ARE DIFFERENT ANSWERS, and the server measures them
      // differently: `harness.edge.ag-ui/undeclared-input` returns [] for nil (so
      // the run proceeds) and [:image] for #{} (so it is refused). The wire keeps
      // them apart the same way -- the key is missing for one, [] for the other --
      // and so does this. Reading [] as "declared nothing" would make the client
      // LOOSER than the server, which is the direction that costs somebody their
      // typed words: the run comes back RUN_ERROR with the composer already empty.
      expect(acceptsImages([])).toBe(false);

      // The sentence names the model, what it declared, and the way out -- the same
      // three things the server's own refusal names. `acme-gateway` and `["text"]`
      // are the server's data and cross VERBATIM in both languages; only the words
      // around them are translated.
      const refusal = imageRefusal(["text"], "text-only", en);
      expect(refusal).not.toBe(null);
      expect(refusal).toContain("text-only");
      expect(refusal).toContain('["text"]');
      expect(refusal).toContain("change the model");

      // THE SAME REFUSAL IN CHINESE, the whole sentence: the model id and the
      // declared list unchanged, the words ours. A model with NO id gets this side's
      // word for it -- `(unnamed)` in English, `（未命名）` in Chinese -- and the
      // JSON quoting around it stays, because that is the sentence's shape rather
      // than a word.
      expect(imageRefusal(["text"], "text-only", zh)).toBe(
        '这个模型 "text-only" 不收图片；它声明的是 ["text"] —— 换模型，或只带它声明的东西',
      );
      expect(imageRefusal(["text"], undefined, en)).toBe(
        'model "(unnamed)" does not take images; it declares ["text"] — change the model, or attach only what it declares',
      );
      expect(imageRefusal(["text"], undefined, zh)).toBe(
        '这个模型 "（未命名）" 不收图片；它声明的是 ["text"] —— 换模型，或只带它声明的东西',
      );
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
      expect(sizeRefusal(ATTACHMENT_MAX_BYTES, en)).toBe(null);

      // The sentence carries the two numbers -- this one, and the cap. The units
      // come from `formatMegabytes` and do NOT translate, so `3 MB` / `2 MB` are the
      // same strings in both languages; only the words around them move.
      const refusal = sizeRefusal(3 * 1024 * 1024, en);
      expect(refusal).toContain("3 MB");
      expect(refusal).toContain("2 MB");

      // THE SAME REFUSAL IN CHINESE, whole: the two magnitudes unchanged, the
      // sentence ours.
      expect(sizeRefusal(3 * 1024 * 1024, zh)).toBe("这张图 3 MB，上限是 2 MB");

      // ONE MEASUREMENT, ON SOURCE BYTES. The rule takes `{ size }` and nothing
      // else, so there is no second reading of the same file (a base64 length, a
      // decoded pixel count) for a caller to reach for: the only thing it can be
      // asked about is the number a person can see in their file manager.
      expect(refusalFor({ size: ATTACHMENT_MAX_BYTES + 1 }, ["text", "image"], "m", en)).not.toBe(null);
      expect(refusalFor({ size: ATTACHMENT_MAX_BYTES }, ["text", "image"], "m", en)).toBe(null);

      // AND A REFUSED FILE IS NOT REPLACED BY A SMALLER ONE SILENTLY: the cap is
      // the same picture compressed, which passes on its own size. Nothing here
      // rewrites bytes -- see ticket 03 -- so this is the whole story: the same
      // rule, asked about a smaller file, says yes.
      expect(refusalFor({ size: 1024 * 1024 }, ["text", "image"], "m", en)).toBe(null);

      // The model's rule is asked FIRST when both would refuse: being told to
      // shrink a file that would be refused anyway is a refusal that leads to
      // another refusal. The one sentence that comes out is the model's, in the
      // language it was asked in.
      const both = refusalFor({ size: ATTACHMENT_MAX_BYTES + 1 }, ["text"], "text-only", zh);
      expect(both).toContain("text-only");
      expect(both).toContain("换模型");
    },
  },
];

export const attachmentsSuite: Suite = { name: "attachments", cases };

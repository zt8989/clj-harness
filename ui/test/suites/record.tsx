// The page's half of ADR 0002 decision 6: a record that could not be written is SAID,
// and this is what says it.
//
// ================================================================ why this file
//
// The server knows the fact (`harness.edge.record` keeps the degraded thread) and puts
// it on the two reads a client looks at a conversation through. Everything from there
// to a person's eyes is this client's: a type, a decision, a sentence in two languages,
// and an element on the screen. That chain has a hole in it exactly the shape of
// `.scratch/session-title-blank/` -- the id line that rendered nothing while 859
// backend cases and 36 UI cases were green -- so the sentence is a COMPONENT
// (`components/record-notice.tsx`) rather than four lines inside `app.tsx`, and it is
// rendered here and read back.
//
// WHAT THIS FILE CANNOT SEE: `app.tsx` itself, which is where the notice is placed
// (above the conversation, below the tab strip) and where the read's `record` field is
// routed to it. That file reaches `lib/i18n.ts` and the assistant runtime and cannot be
// rendered in this run at all. The routing is pinned where it can be: the backend half
// in `test/harness/edge/http_test.clj` (the routes carry the field) and the walkthrough
// in `scripts/dev.mjs --scripted`.
//
// THE PURE HALF IS ASSERTED TOO, and deliberately: `recordNotice` is what decides
// whether there is anything to say, and a fake translator would have gone on passing
// after the catalog was edited. The real one is used (`../support/locale`), which is
// also what makes the plural assertion below mean something.
import { renderToStaticMarkup } from "react-dom/server";
import { I18nextProvider } from "react-i18next";
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { renderI18n, translator } from "../support/locale";
import { RecordNotice } from "../../src/components/record-notice";
import { recordNotice, type RecordHealth } from "../../src/lib/record-health";
import type { Language } from "../../src/lib/language";

/// A degraded record as the server writes one: the writer gave up, and this many lines
/// are waiting behind the one it could not write.
const degraded = (pending = 3): RecordHealth => ({
  state: "degraded",
  reason: "disk is full",
  pending,
});

/// THE NOTICE AS A PERSON READS IT: rendered inside a real i18n instance, the way the
/// column renders it, and returned as text.
function shown(record: RecordHealth | null, language: Language): string {
  const html = renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <RecordNotice record={record} />
    </I18nextProvider>,
  );
  // A SENTENCE WITH NO MARKUP IN IT, so stripping tags is the whole of the reading. If
  // the component ever draws something nested this still returns what is said, which is
  // the claim being made: the words reach the screen.
  return html.replace(/<[^>]*>/g, "").trim();
}

/// Whether the component drew the element at all -- a sentence with no element would be
/// a sentence nothing renders, which is the failure this suite exists for.
const drew = (record: RecordHealth | null): boolean =>
  renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n("en")}>
      <RecordNotice record={record} />
    </I18nextProvider>,
  ).includes('data-slot="record-notice"');

const cases: Case[] = [
  {
    name: "a-record-with-nothing-to-say-says-nothing",
    run: async () => {
      // THE COMMON CASE, AND IT IS NOT A FAILURE: a session whose bytes are all on disk
      // has no story, and a page that announced "saved" about every conversation would
      // be spending a strip of chrome on a fact nobody asked for. The server sends no
      // field at all in that case, which is why null and absent have to land here.
      expect(recordNotice(translator("en", "shell"), null)).toBeNull();
      expect(recordNotice(translator("en", "shell"), undefined)).toBeNull();

      // AND NOTHING IS DRAWN FOR IT -- not an empty element, which is what a reader
      // would see as a stray band above the conversation. An empty `<p>` has no text
      // but it does have a border, which is the difference between "nothing to say" and
      // "something went wrong".
      expect(drew(null)).toBe(false);
      expect(shown(null, "en")).toBe("");

      // A STATE THIS CLIENT DOES NOT KNOW IS ALSO NOTHING TO SAY. The field arrives
      // from a server, so an older client can meet a newer word -- and the honest
      // answer to a state nobody wrote a sentence for is silence rather than a
      // half-drawn strip or the raw wire value on screen.
      const unknown = { state: "something-else", reason: "?", pending: 1 } as unknown as RecordHealth;
      expect(recordNotice(translator("en", "shell"), unknown)).toBeNull();
      expect(drew(unknown)).toBe(false);
    },
  },
  {
    name: "a-record-that-could-not-be-written-is-on-screen-in-both-languages",
    run: async () => {
      // THE SENTENCE, RENDERED. What it has to carry is three things, and each is a
      // fact a person has to have to do anything about it: that this conversation is
      // not being saved, WHY (the writer's own reason, passed through rather than
      // paraphrased -- it is the only diagnostic anybody gets), and HOW MUCH is
      // waiting behind the line that failed.
      const english = shown(degraded(3), "en");
      expect(drew(degraded(3))).toBe(true);
      expect(english).toContain("could not be saved");
      expect(english).toContain("disk is full");
      expect(english).toContain("3 lines");

      // THE SINGULAR IS A FORM, NOT A NUMBER GLUED TO A NOUN: `3 lines` and `1 line`
      // are one catalog base with two spellings, and i18next picks by `count`. A
      // sentence built by hand would say "1 lines", which is the shape of defect that
      // makes a native reader stop trusting the rest of the page.
      expect(shown(degraded(1), "en")).toContain("1 line is waiting");

      // AND THE OTHER LANGUAGE SAYS THE SAME THING. Chinese has one plural form, so
      // this is not a mirror of the line above -- it is the check that the Chinese
      // catalog has a sentence AT ALL, which is the failure mode a fallback hides: a
      // missing entry renders English on an otherwise Chinese page and nobody sees it
      // until a reader does.
      const chinese = shown(degraded(3), "zh");
      expect(chinese).toContain("磁盘");
      expect(chinese).toContain("disk is full");
      expect(chinese).toContain("3");
      expect(chinese).not.toBe(english);
    },
  },
];

export const recordSuite: Suite = { name: "record", cases };

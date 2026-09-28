// WHAT A CONVERSATION OWES WHEN ITS RECORD MAY NOT BE WRITTEN TO: rendered, in both
// languages.
//
// ============================================================ why this file renders
//
// `.scratch/record-normalization` ticket 04, and the reason is the one `suites/record.tsx`
// writes down: a sentence that reaches the screen is the one thing a green tree could not
// see (the i18n merge rendered an EMPTY sidebar title in every row while 859 backend cases,
// 36 UI cases, `tsc` and the bundle were all green). The half of this ticket that must not
// go missing that way is that the notice DRAWS **and carries a button**: a page that only
// says "fork it" and offers nothing to press is the failure this case is for. So the fork
// button is found in the markup, by its `data-slot`, and the label it speaks is read back.
//
// THE REASONS ARE THE SERVER'S OWN WORDS and this suite pins that too: the criterion's
// sentences (`harness.edge.normalized`) come off the wire in one language, and a client that
// re-worded them would be a second opinion about the bytes -- the thing the criterion exists
// to avoid. A reason no catalog could contain is rendered and looked for verbatim.
//
// WHAT THIS FILE CANNOT SEE: the composer being SHUT. That lives in the copied element
// (`elements/thread.aui.tsx`), which reaches the assistant runtime, and this run has no DOM
// and no runtime -- `node scripts/dev.mjs --scripted`, per AGENTS.md, is where the disabled
// input and "the fork actually opens the new session" are checked.
import { renderToStaticMarkup } from "react-dom/server";
import { I18nextProvider } from "react-i18next";
import { expect } from "vitest";

import { NormalizationNotice } from "../../src/components/normalization-notice";
import type { Language } from "../../src/lib/language";
import { type Case, type Suite } from "../e2e";
import { renderI18n } from "../support/locale";

/// The languages the catalogs are written in. Both, always -- see `suites/record.tsx` and
/// the parity case in `suites/i18n.ts`, which can only check that a key EXISTS in both.
const LANGUAGES: readonly Language[] = ["zh", "en"];

/// ONE NOTICE, as markup. Inside a real i18n instance, because the component asks for its
/// own namespace through `useTranslation` -- the same seam the record's strip uses.
function noticeOf(language: Language, reasons: readonly string[]): string {
  return renderToStaticMarkup(
    <I18nextProvider i18n={renderI18n(language)}>
      <NormalizationNotice reasons={reasons} onFork={() => {}} />
    </I18nextProvider>,
  );
}

const saysWhatIsMissing: Case = {
  name: "the-notice-says-why-and-offers-a-button-to-press",
  run: async () => {
    for (const language of LANGUAGES) {
      const markup = noticeOf(language, ["1 次工具调用没有 message 行答复"]);

      // THE BUTTON, which is the half of this ticket a page could forget while looking
      // complete: a notice with no door out is the wall the owner's rule is against.
      expect(markup).toContain('data-slot="normalization-fork"');
      expect(markup).toContain("<button");

      // AND THE WAY IT IS WORDED -- in the language this instance is in, which is the
      // whole point of rendering it twice.
      expect(markup).toContain(language === "zh" ? "还没重整化" : "not normalized");

      // THE SERVER'S OWN SENTENCE, VERBATIM (it is Chinese either way: it names what the
      // bytes are missing, and the criterion wrote it).
      expect(markup).toContain("1 次工具调用没有 message 行答复");
    }
  },
};

const saysNothingElse: Case = {
  name: "a-notice-with-no-reasons-still-says-what-it-is-and-what-to-do",
  run: async () => {
    for (const language of LANGUAGES) {
      const markup = noticeOf(language, []);
      expect(markup).toContain(language === "zh" ? "还没重整化" : "not normalized");
      expect(markup).toContain('data-slot="normalization-fork"');
      // NOT A REASONS LIST THAT RENDERS AS AN EMPTY BULLET, and not the word `undefined`.
      expect(markup).not.toContain("<ul");
      expect(markup).not.toContain("undefined");
    }
  },
};

export const normalizationSuite: Suite = {
  name: "normalization",
  cases: [saysWhatIsMissing, saysNothingElse],
};

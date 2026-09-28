// THE SENTENCE AND THE BUTTON a conversation owes when its record may not be written to.
//
// ============================================================== what it is about
//
// `.scratch/record-normalization` ticket 04. A record is `未重整化` when its rows and its
// frames do not say the same thing -- a tool call answered by a frame alone, a `START` with
// no `END`, an old-contract line (`harness.edge.normalized` is the criterion, and the
// sentences in `reasons` are its own). Writing another turn into such a record appends to a
// history whose reading is a guess, so the run edge refuses one (409, `unnormalized`), and
// this is the other half of that refusal: the composer is SHUT and the way through is a
// FORK -- a copy, rebuilt until it reads (ticket 03).
//
// A COMPONENT RATHER THAN A FEW LINES OF JSX INSIDE THE COMPOSER, for the reason
// `record-notice.tsx` gives: the composer lives inside the copied element, which reaches the
// assistant runtime and touches `document`, so nothing can render it in the test run -- and a
// sentence nothing can render is a sentence that can go missing while every gate stays green
// (the i18n merge cost this repo exactly that). This file has no runtime in it, so
// `test/suites/normalization.tsx` renders it and reads back what it says, in both languages.
//
// THE REASONS ARE DRAWN AS THEY ARRIVE and never re-worded here: they are facts about the
// bytes, and the client that paraphrased one would be the second opinion the criterion exists
// to avoid.
import type { FC } from "react";
import { useTranslation } from "react-i18next";

export const NormalizationNotice: FC<{
  /// WHY the record may not be written to, in the server's own words (one sentence per thing
  /// that is missing), or empty when the server sent none.
  reasons: readonly string[];
  /// WHAT THE BUTTON DOES. The caller's, because forking is a request and this file has no
  /// address of its own to aim one at: the composer holds the thread id and the page holds
  /// the switch to the session the fork lands as.
  onFork: () => void;
}> = ({ reasons, onFork }) => {
  const { t } = useTranslation("elements-thread");
  return (
    <div
      data-slot="normalization-notice"
      role="status"
      className="border-destructive/40 bg-destructive/10 text-destructive mb-2 flex flex-col gap-1 rounded-(--composer-radius) border px-3 py-2 text-xs"
    >
      <p className="font-medium">{t("composer.notNormalized")}</p>
      {reasons.length > 0 && (
        <ul className="aui-composer-not-normalized-reasons list-disc pl-4">
          {reasons.map((reason) => (
            <li key={reason}>{reason}</li>
          ))}
        </ul>
      )}
      <button
        type="button"
        data-slot="normalization-fork"
        onClick={onFork}
        className="border-destructive/60 hover:bg-destructive/15 mt-0.5 self-start rounded-full border px-3 py-1 font-normal transition-colors"
      >
        {t("composer.notNormalizedFork")}
      </button>
    </div>
  );
};

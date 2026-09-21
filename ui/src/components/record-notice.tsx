// THE ONE SENTENCE THE PAGE OWES when a conversation's record could not be written.
//
// A COMPONENT RATHER THAN FOUR LINES OF JSX INSIDE `app.tsx`, and the reason is the
// one `.scratch/session-title-blank/` is kept for: a sentence nothing can render is a
// sentence that can go missing while every gate stays green. `app.tsx` cannot be
// rendered in the test run at all (it reaches `lib/i18n.ts`, which touches `document`,
// and the assistant runtime), so the sentence lives here -- somewhere
// `test/suites/record.tsx` can render it and read back what it says, in both
// languages.
//
// IT IS A STRIP AND NOT A TOAST. What it reports lasts until somebody acts on it -- a
// toast would announce a state of affairs and then take the announcement away while
// the state remained. See `lib/record-health.ts` for the decision it words, and for
// what it deliberately does NOT decide (the session keeps running: memory is the
// authority, the record is behind).
import type { FC } from "react";
import { useTranslation } from "react-i18next";

import { recordNotice, type RecordHealth } from "@/lib/record-health";

export const RecordNotice: FC<{
  /// The record's health for this session, or null when it has nothing to say -- the
  /// ordinary answer, and the one this renders nothing for.
  record: RecordHealth | null;
}> = ({ record }) => {
  const { t } = useTranslation();
  const notice = recordNotice(t, record);
  if (notice === null) return null;
  return (
    <p
      data-slot="record-notice"
      role="status"
      className="shrink-0 border-b border-destructive/40 bg-destructive/10 px-3 py-1.5 text-xs text-destructive"
    >
      {notice}
    </p>
  );
};

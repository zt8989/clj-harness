"use client";

// The subagent rows, drawn once and used by BOTH screens.
//
// ============================================================ why this file exists
//
// The two places a subagent is read -- the sidebar's block and the settings page's
// list -- say the SAME three things about each one: its name, what it is for, and
// which tools it can touch. Two copies of that would be two chances to word the
// range differently, and a person who read "everything except eval" in one place and
// "all tools" in the other would have no way to tell which one the harness believes.
// `lib/session-status.ts` makes the same argument for the archive refusals; this is
// that argument applied to a row.
//
// IT IMPORTS NOTHING THAT NEEDS A BROWSER, and that is load-bearing rather than
// tidiness: `ui/test/suites/subagents.tsx` renders these to a string, and that run
// has no DOM. `lib/i18n.ts` (which reads `document` as it loads) is the thing that
// would break it, so nothing here may reach it -- which is also why this is a file
// of its own rather than a section of `settings-panel.tsx`.
//
// THE WORDS COME FROM THE `shell` CATALOG ON BOTH SCREENS. The baseline sentence,
// "minus", "built-in", "running" are the feature's own vocabulary and there is one
// wording of each, not one per face: the settings form has its own words for its
// own fields, and none of them is a restatement of these.
import type { TFunction } from "i18next";
import type { FC } from "react";
import { useTranslation } from "react-i18next";

import type { Baseline, SubagentDefinition } from "@/lib/subagents";

/// The translator these rows are worded through. `TFunction<"shell">` rather than a
/// `TFunction` from the call site, because the type is what makes a key that is not
/// in that catalog a compile error.
type Translate = TFunction<"shell">;

/// How a baseline reads. TWO VALUES, and the vocabulary is the SERVER's (`:all` /
/// `:read-only`, arriving as JSON strings) -- what a person reads is this file's, and
/// each branch writes its own literal key so a third baseline the server adds cannot
/// render its raw keyword on screen.
///
/// ONE PAIR FOR THREE PLACES: the sidebar's definitions group, the settings roster,
/// and the settings form's `<option>`s all read these. A second pair anywhere would
/// be a second sentence about one range, and the person who picked one wording would
/// read the other back.
///
/// THE `:all` SENTENCE NAMES `eval` AND DELEGATING OUT LOUD, and that is the whole
/// feature's post-condition showing up in the copy: those two names are in no range,
/// ever, and a row that said only "all tools" would be promising something the
/// harness will not do.
export const BASELINE_LABELS: Record<Baseline, (t: Translate) => string> = {
  all: (t) => t("subagents.baselineAll"),
  "read-only": (t) => t("subagents.baselineReadOnly"),
};

/// The range in one line: the baseline, then whatever this entry takes OUT of it.
///
/// "NOTHING ELSE TAKEN OUT" IS SAID RATHER THAN OMITTED. For `general` the baseline
/// sentence already names the two names nothing serves, so a row that stopped there
/// would read as though somebody had deliberately narrowed it. The clause makes
/// "nothing more" and "the file is silent about it" the same sentence, which is what
/// they are.
export function rangeText(definition: SubagentDefinition, t: Translate): string {
  const baseline = BASELINE_LABELS[definition.baseline](t);
  return definition.exclude.length === 0
    ? `${baseline} — ${t("subagents.nothingElse")}`
    : `${baseline} — ${t("subagents.minus", { tools: definition.exclude.join(", ") })}`;
}

/// The three facts of one definition, as the inside of whatever row somebody draws.
/// Split out because the two screens wrap it in different elements -- a list item
/// here, a button there -- and that difference is the only difference.
const Facts: FC<{ definition: SubagentDefinition }> = ({ definition }) => {
  const { t } = useTranslation();
  return (
    <>
      <span className="flex items-center gap-1.5 text-xs">
        <span data-slot="subagent-definition-name" className="min-w-0 truncate font-medium">
          {definition.name}
        </span>
        <span
          data-slot="subagent-definition-badge"
          className="text-muted-foreground shrink-0 rounded border px-1 text-[10px]"
        >
          {/* ONE BADGE, SAID ON EVERY ROW rather than only on the built-ins, because
              the two screens use it differently and neither may guess: the sidebar
              reads it as "this one comes from the code", and the settings roster
              reads it as "this row has no Remove button". A row that was silent about
              being yours would make that look arbitrary, and two words for one fact
              is how the same subagent ends up called different things on two pages. */}
          {definition.builtin ? t("subagents.builtin") : t("subagents.yours")}
        </span>
      </span>
      <span
        data-slot="subagent-definition-description"
        className="text-muted-foreground text-[10px] break-words"
      >
        {definition.description}
      </span>
      <span
        data-slot="subagent-definition-range"
        className="text-muted-foreground text-[10px] break-words"
      >
        {rangeText(definition, t)}
      </span>
    </>
  );
};

/// THE SIDEBAR'S DEFINITIONS GROUP: read-only, one `<li>` per subagent.
///
/// READ-ONLY HERE BECAUSE THE FORM IS ELSEWHERE. The thing that changes these is the
/// settings panel, and a second editor in the sidebar would be a second place to
/// disagree with the file. What this group can do that a form cannot is always be on
/// screen: it is how somebody finds out the feature exists at all.
export const DefinitionRows: FC<{ definitions: readonly SubagentDefinition[] }> = ({
  definitions,
}) => {
  const { t } = useTranslation();
  const custom = definitions.filter((d) => !d.builtin).length;
  return (
    <ul data-slot="subagent-definitions" className="flex flex-col gap-0.5">
      {definitions.map((definition) => (
        <li
          key={definition.name}
          data-slot="subagent-definition"
          data-name={definition.name}
          data-builtin={definition.builtin ? "" : undefined}
          className="flex flex-col gap-0.5 rounded-md px-1.5 py-1"
        >
          <Facts definition={definition} />
        </li>
      ))}
      {/* THE GROUP IS NEVER EMPTY -- the two built-ins are the empty configuration --
          so this is not an empty state, it is the answer to "where are mine": the
          line appears when nothing in the list is the reader's own, and it names the
          screen that would change that. */}
      {custom === 0 && (
        <li
          data-slot="subagent-definitions-custom-empty"
          className="text-muted-foreground px-1.5 pt-1 text-[10px]"
        >
          {t("subagents.customEmpty")}
        </li>
      )}
    </ul>
  );
};

/// THE SETTINGS PAGE'S ROSTER: the same three facts, on a button that opens the form.
///
/// The badge is drawn on every row here rather than only on the built-ins, because
/// this is the screen where it decides something: a built-in has no Remove button and
/// a custom one does, and the badge is what says which row you are looking at before
/// you open it.
export const DefinitionButtons: FC<{
  definitions: readonly SubagentDefinition[];
  onEdit: (definition: SubagentDefinition) => void;
}> = ({ definitions, onEdit }) => (
  <div data-slot="subagent-roster" className="flex flex-col divide-y">
    {definitions.map((definition) => (
      <button
        key={definition.name}
        type="button"
        data-slot="subagent-roster-row"
        data-name={definition.name}
        data-builtin={definition.builtin ? "" : undefined}
        className="hover:bg-accent/40 flex flex-col gap-0.5 rounded-md p-2 text-left"
        onClick={() => onEdit(definition)}
      >
        <Facts definition={definition} />
      </button>
    ))}
  </div>
);

// THE RUN ROWS USED TO BE HERE (`RunRows`), and they are gone with the sidebar block
// they were written for (`.scratch/subagent-view` ticket 06). It is worth saying what
// survives and why, because this file is now smaller than the pair of screens it serves:
//
//   - `DefinitionRows` STAYS. The settings page draws it, and it is the one place the
//     range of a subagent is read into a sentence (`rangeText`), shared with the form
//     above so two screens cannot say two things about one range;
//   - `RunRows` DOES NOT, because it had exactly one reader. Its door -- open the
//     subagent's own conversation -- is the transcript's `agent` card now, and the
//     mirror that card opens (ticket 05) shows the very conversation the row used to
//     navigate to. The side-by-side panel replaced the navigation, so a second way in
//     would be a second answer to the same question;
//   - THE SERVER'S ANSWER IS NOT REMOVED WITH IT. `GET /api/subagents` still reports
//     `runs`, and the store still keeps every delegation -- the front end simply has no
//     reader for that half any more. That is a deliberate asymmetry and not a missing
//     wire: the route is cheap, it is the only answer to "what has this home ever
//     delegated", and deleting it is a separate decision from retiring one screen.

// THE TASK PANE: the right-hand column when nothing is being mirrored -- what this session has
// going on, in two sections. IT IS THE SAME COLUMN AS THE MIRROR (`.scratch/right-pane-tasks`,
// decision 1), which is why the `aside` below is the mirror's own class string to the character
// -- same width, same `border-s`, same `shrink-0`, same `md` breakpoint -- and the same `id` the
// two toggle controls name: opening the column and finding a subagent in it are two states of
// ONE element, not two panels that could drift apart. The suite compares the two strings rather
// than trusting that they were copied correctly.
//
// TWO SECTIONS, NOW BOTH FILLED. The top one is the subagents this session delegated to
// (ticket 03); the bottom one is this session's background jobs (ticket 02). BOTH READ FROM
// ONE TICK, and that is why this component does not fetch: `hooks/use-task-pane.ts` owns the
// poll (one read a second while the pane is open and the page is visible, and nothing in
// flight after either ends), and ticket 03's read joined the SAME tick rather than starting
// a second timer. What is left here is the shell: which section is which, and the sentence a
// section says when it has no rows.
import { useTranslation } from "react-i18next";
import type { FC } from "react";

import { RIGHT_PANE_ID, RightPaneCollapseButton } from "@/components/right-pane-toggle";
import { type SubagentView } from "@/components/subagent-view-context";
import { JobRows } from "@/components/task-pane-jobs";
import { SubagentRows } from "@/components/task-pane-subagents";
import { useTaskPane } from "@/hooks/use-task-pane";

/// The column, with the way out in its header. `onCollapse` closes the WHOLE column -- it is the
/// same verb as the mirror's collapse, because it is the same column (see
/// `components/right-pane-toggle.tsx` for the pair and the `aria-controls` they share).
///
/// `onOpen` IS THE PAGE'S DOOR, handed down so a row can walk through it: clicking a
/// delegation opens that delegation's mirror, which is the SAME state the transcript's
/// `agent` card writes -- so the writer is `App`'s (`openMirror`), and the row supplies only
/// which delegation it is.
export const TaskPane: FC<{
  threadId: string;
  onCollapse: () => void;
  onOpen: (view: SubagentView) => void;
}> = ({ threadId, onCollapse, onOpen }) => {
  const { t } = useTranslation();
  // THE WHOLE OF THIS COMPONENT'S RUNTIME: one hook, mounted with the pane. Closing the pane
  // unmounts it, which is one of the two ways the poll stops (`hooks/use-task-pane.ts`).
  const { jobs, subagents } = useTaskPane(threadId);
  return (
    <aside
      id={RIGHT_PANE_ID}
      data-slot="task-pane"
      aria-label={t("rightPane.title")}
      className="bg-background hidden w-[26rem] shrink-0 flex-col border-s md:flex"
    >
      <header className="flex h-12 shrink-0 items-center gap-2 border-b px-3">
        <RightPaneCollapseButton onCollapse={onCollapse} />
        <span
          data-slot="task-pane-name"
          className="min-w-0 flex-1 truncate text-sm font-medium"
        >
          {t("rightPane.title")}
        </span>
      </header>

      {/* EACH SECTION KEEPS ITS OWN HALF AND SCROLLS INSIDE IT: a section's rows are a list,
          and a list that grew past the fold would otherwise push the other section off the
          bottom of the column. */}
      <section
        data-slot="task-pane-subagents"
        className="flex min-h-0 flex-1 flex-col border-b px-3 py-3"
      >
        <h2 data-slot="task-pane-subagents-title" className="shrink-0 text-sm font-medium">
          {t("rightPane.subagents")}
        </h2>
        {/* THE ROWS, OR THE SENTENCE -- the same two arms the jobs section below chooses
            between, and for the same reason: a heading over nothing would say this section
            is empty whichever it is. */}
        {subagents.length === 0 ? (
          <p data-slot="task-pane-subagents-empty" className="text-muted-foreground mt-1 text-xs">
            {t("rightPane.subagentsEmpty")}
          </p>
        ) : (
          <SubagentRows rows={subagents} onOpen={onOpen} />
        )}
      </section>

      <section data-slot="task-pane-jobs" className="flex min-h-0 flex-1 flex-col px-3 py-3">
        <h2 data-slot="task-pane-jobs-title" className="shrink-0 text-sm font-medium">
          {t("rightPane.jobs")}
        </h2>
        {/* THE ROWS, OR THE SENTENCE. A heading over nothing would say this section is empty
            whichever it is, so the two are the two arms of one choice. */}
        {jobs.length === 0 ? (
          <p data-slot="task-pane-jobs-empty" className="text-muted-foreground mt-1 text-xs">
            {t("rightPane.jobsEmpty")}
          </p>
        ) : (
          <JobRows jobs={jobs} threadId={threadId} />
        )}
      </section>
    </aside>
  );
};

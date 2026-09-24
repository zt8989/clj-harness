// THE TASK PANE: the right-hand column when nothing is being mirrored -- what this session has
// going on, in two sections. IT IS THE SAME COLUMN AS THE MIRROR (`.scratch/right-pane-tasks`,
// decision 1), which is why the `aside` below is the mirror's own class string to the character
// -- same width, same `border-s`, same `shrink-0`, same `md` breakpoint -- and the same `id` the
// two toggle controls name: opening the column and finding a subagent in it are two states of
// ONE element, not two panels that could drift apart. The suite compares the two strings rather
// than trusting that they were copied correctly.
//
// TWO SECTIONS AND NOTHING IN THEM YET (ticket 01). The top one is the subagents this session
// delegated to (ticket 03), the bottom one this session's background jobs (ticket 02); each draws
// its heading and the sentence it says when it has no rows. The lists, the polling and the stop
// button are those tickets' -- so this component fetches nothing and holds no state at all.
import { useTranslation } from "react-i18next";
import type { FC } from "react";

import { RIGHT_PANE_ID, RightPaneCollapseButton } from "@/components/right-pane-toggle";

/// The column, with the way out in its header. `onCollapse` closes the WHOLE column -- it is the
/// same verb as the mirror's collapse, because it is the same column (see
/// `components/right-pane-toggle.tsx` for the pair and the `aria-controls` they share).
export const TaskPane: FC<{ onCollapse: () => void }> = ({ onCollapse }) => {
  const { t } = useTranslation();
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

      {/* EACH SECTION KEEPS ITS OWN HALF AND SCROLLS INSIDE IT: the rows ticket 02/03 add are
          lists, and a list that grew past the fold would otherwise push the other section off
          the bottom of the column. Empty for now, which is what the sentence under each heading
          is for. */}
      <section
        data-slot="task-pane-subagents"
        className="flex min-h-0 flex-1 flex-col border-b px-3 py-3"
      >
        <h2 data-slot="task-pane-subagents-title" className="shrink-0 text-sm font-medium">
          {t("rightPane.subagents")}
        </h2>
        <p
          data-slot="task-pane-subagents-empty"
          className="text-muted-foreground mt-1 text-xs"
        >
          {t("rightPane.subagentsEmpty")}
        </p>
      </section>

      <section data-slot="task-pane-jobs" className="flex min-h-0 flex-1 flex-col px-3 py-3">
        <h2 data-slot="task-pane-jobs-title" className="shrink-0 text-sm font-medium">
          {t("rightPane.jobs")}
        </h2>
        <p data-slot="task-pane-jobs-empty" className="text-muted-foreground mt-1 text-xs">
          {t("rightPane.jobsEmpty")}
        </p>
      </section>
    </aside>
  );
};

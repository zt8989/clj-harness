// THE STATISTICS VIEW: the right-hand column's third state -- what the WHOLE HOME has been
// doing, rather than what this one conversation has going on.
//
// IT IS THE SAME COLUMN AS THE TASK PANE AND THE MIRROR (`.scratch/right-pane-tasks`,
// decision 1), which is why the `aside` below is the mirror's own class string to the
// character -- same width, same `border-s`, same `shrink-0`, same `md` breakpoint -- and the
// same `id` the three toggles name: opening this column and finding the leaderboards in it are
// two states of ONE element, not a third panel that could drift apart.
//
// WHAT IT DRAWS, AND WHERE THE NUMBERS COME FROM. Every ranking is the PROJECTION's answer
// (`harness.edge.projection`'s leaderboards, over `tool_calls` and `model_calls`), read once
// through `GET /api/stats` and then pushed down `events.stats` -- see `hooks/use-home-stats.ts`
// for the two halves and `docs/rules/panel-data.md` for why they are two.
//
// THE MODEL RANKING IS BEHIND A DISCLOSURE. The owner asked for the tool and skill rankings on
// sight and the token-per-model one behind a click (2026-10-01), and the reason it is the odd
// one out is that it answers a different question: the first two count what this home DID, and
// this one counts what it COST. A section that is folded away costs the reader nothing until
// they want it.
//
// AND THE ABSENCES ARE DRAWN AS ABSENCES. A model whose calls reported no usage has no token
// key at all (`harness.edge.stats` keeps 'not reported' apart from zero), so its row says so
// rather than drawing a 0 -- the rule the composer's strip keeps, kept here.
import { ChevronDownIcon, ChevronRightIcon } from "lucide-react";
import { useRef, useState, type FC } from "react";
import { useTranslation } from "react-i18next";

import {
  RIGHT_PANE_ID,
  RightPaneBackButton,
  RightPaneCollapseButton,
} from "@/components/right-pane-toggle";
import { useHomeStats } from "@/hooks/use-home-stats";
import { formatTokens } from "@/lib/format";
import type { HomeStats, Leader, ModelLeader } from "@/lib/home-stats";

/// ONE RANKING OF [WHAT, HOW MANY]: the server sorts it, so this draws the array in the order
/// it arrived and never sorts it again -- a second ordering here would be a second answer.
///
/// A heading and a number per row, with the name allowed to clip and the count not: a long
/// tool name must not decide the column's width.
const LeaderRows: FC<{ rows: readonly Leader[]; slot: string }> = ({ rows, slot }) => (
  <ol data-slot={`${slot}-rows`} className="mt-1 min-h-0 flex-1 overflow-y-auto text-xs">
    {rows.map((row) => (
      <li
        key={row.name}
        data-slot={`${slot}-row`}
        className="flex items-baseline justify-between gap-2 py-0.5"
      >
        <span data-slot={`${slot}-row-name`} className="truncate">
          {row.name}
        </span>
        <span
          data-slot={`${slot}-row-calls`}
          className="text-muted-foreground shrink-0 tabular-nums"
        >
          {row.calls}
        </span>
      </li>
    ))}
  </ol>
);

/// THE TOKEN RANKING: one row per model, with the calls it made under it. THE TOTAL IS THE
/// VENDOR'S OWN when it reported one and prompt + completion otherwise -- the server already
/// decided that (`harness.edge.stats/tokens-of`), so this side formats a number it was given
/// rather than adding two.
const ModelRows: FC<{ rows: readonly ModelLeader[] }> = ({ rows }) => {
  const { t } = useTranslation();
  return (
    <ol data-slot="stats-models-rows" className="mt-1 min-h-0 flex-1 overflow-y-auto text-xs">
      {rows.map((row) => (
        <li key={row.model ?? "unknown"} data-slot="stats-model-row" className="py-1">
          <div className="flex items-baseline justify-between gap-2">
            <span data-slot="stats-model-name" className="truncate">
              {row.model ?? t("rightPane.statsUnknownModel")}
            </span>
            <span data-slot="stats-model-tokens" className="shrink-0 tabular-nums">
              {row.totalTokens === undefined
                ? t("rightPane.statsTokensAbsent")
                : formatTokens(row.totalTokens)}
            </span>
          </div>
          <div
            data-slot="stats-model-calls"
            className="text-muted-foreground text-[0.6875rem]"
          >
            {t("rightPane.statsModelCalls", { count: row.calls })}
          </div>
        </li>
      ))}
    </ol>
  );
};

/// The column, with the way out in its header (the same verb as the task pane's -- it is the
/// same column) and the way BACK to the task view at the trailing end, which is where the
/// mirror keeps its own.
export const StatsView: FC<{ onCollapse: () => void; onBack: () => void }> = ({
  onCollapse,
  onBack,
}) => {
  const { t } = useTranslation();
  const pane = useRef<HTMLElement | null>(null);
  const stats: HomeStats | null = useHomeStats(pane);
  /// CLOSED TO BEGIN WITH: the token ranking is the click the owner asked for, and a view that
  /// opened with all three unfurled would have made that click meaningless.
  const [modelsOpen, setModelsOpen] = useState(false);

  const tools = stats?.tools ?? [];
  const skills = stats?.skills ?? [];
  const models = stats?.models ?? [];

  return (
    <aside
      id={RIGHT_PANE_ID}
      ref={pane}
      data-slot="stats-view"
      aria-label={t("rightPane.stats")}
      className="bg-background absolute inset-y-0 end-0 z-30 flex w-[26rem] max-w-[calc(100%_-_3rem)] shrink-0 flex-col border-s md:static md:z-auto md:max-w-none"
    >
      <header className="flex h-12 shrink-0 items-center gap-2 border-b px-3">
        <RightPaneCollapseButton onCollapse={onCollapse} />
        <span
          data-slot="stats-view-name"
          className="min-w-0 flex-1 truncate text-sm font-medium"
        >
          {t("rightPane.stats")}
        </span>
        <RightPaneBackButton onBack={onBack} />
      </header>

      {/* EACH SECTION KEEPS ITS OWN HALF AND SCROLLS INSIDE IT, the shape the task pane's two
          sections keep: a ranking that grew past the fold would otherwise push the next one off
          the bottom of the column. */}
      <section
        data-slot="stats-tools"
        className="flex min-h-0 flex-1 flex-col border-b px-3 py-3"
      >
        <h2 data-slot="stats-tools-title" className="shrink-0 text-sm font-medium">
          {t("rightPane.statsTools")}
        </h2>
        {tools.length === 0 ? (
          <p data-slot="stats-tools-empty" className="text-muted-foreground mt-1 text-xs">
            {t("rightPane.statsToolsEmpty")}
          </p>
        ) : (
          <LeaderRows rows={tools} slot="stats-tools" />
        )}
      </section>

      <section
        data-slot="stats-skills"
        className="flex min-h-0 flex-1 flex-col border-b px-3 py-3"
      >
        <h2 data-slot="stats-skills-title" className="shrink-0 text-sm font-medium">
          {t("rightPane.statsSkills")}
        </h2>
        {skills.length === 0 ? (
          <p data-slot="stats-skills-empty" className="text-muted-foreground mt-1 text-xs">
            {t("rightPane.statsSkillsEmpty")}
          </p>
        ) : (
          <LeaderRows rows={skills} slot="stats-skills" />
        )}
      </section>

      <section data-slot="stats-models" className="flex min-h-0 flex-1 flex-col px-3 py-3">
        <button
          type="button"
          data-slot="stats-models-toggle"
          aria-expanded={modelsOpen}
          onClick={() => setModelsOpen((open) => !open)}
          className="flex shrink-0 items-center gap-1 text-sm font-medium"
        >
          {modelsOpen ? (
            <ChevronDownIcon data-slot="stats-models-open-icon" className="size-4" />
          ) : (
            <ChevronRightIcon data-slot="stats-models-closed-icon" className="size-4" />
          )}
          <span>{t("rightPane.statsModels")}</span>
        </button>
        {modelsOpen &&
          (models.length === 0 ? (
            <p data-slot="stats-models-empty" className="text-muted-foreground mt-1 text-xs">
              {t("rightPane.statsModelsEmpty")}
            </p>
          ) : (
            <ModelRows rows={models} />
          ))}
      </section>
    </aside>
  );
};

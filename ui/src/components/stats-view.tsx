// THE STATISTICS PAGE: what the WHOLE HOME has been doing, over a window a reader picks.
//
// IT IS NOT A COLUMN BESIDE THE CONVERSATION (owner, 2026-10-01): it REPLACES the middle and the
// right-hand column both -- the left sidebar stays, because that is where the page's own
// navigation lives -- so a page of rankings is as wide as the room it has. `app.tsx` hides the
// conversation while `{kind: "stats"}` is the open pane, and the one control that leaves is this
// page's own leading button; there is nothing else to fold.
//
// WHAT IT DRAWS, AND WHERE THE NUMBERS COME FROM. Every ranking is the PROJECTION's answer
// (`harness.edge.projection`'s leaderboards, over `tool_calls` and `model_calls`), read once
// through `GET /api/stats?days=` and then pushed down `events.stats?days=` -- see
// `hooks/use-home-stats.ts` for the two halves and `docs/rules/panel-data.md` for why they are two.
//
// THE WINDOW IS PART OF THE QUESTION, not a filter over one answer: 7/30/90 days are three
// questions and the server answers each with a cutoff of its own.
//
// AND THE ONE VERB ON THE PAGE (`重算`) is the feature's only full read: a content table the
// projection did not have when a log was copied cannot be filled from offsets already past those
// bytes, so making the copy again is a PERSON'S action, scoped to the window on screen
// (`lib/home-stats.ts` and `harness.edge.projection/rebuild-window!` say the same thing from the
// other two ends). Nothing in this feature scans a home on its own.
//
// THE MODEL RANKING IS STILL BEHIND A DISCLOSURE (owner, 2026-10-01): the tool and skill rankings
// count what this home DID and are on sight; the token-per-model one counts what it COST and is
// one click away.
//
// AND THE ABSENCES ARE DRAWN AS ABSENCES. A model whose calls reported no usage has no token key
// at all (`harness.edge.stats` keeps 'not reported' apart from zero), so its row says so rather
// than drawing a 0 -- the rule the composer's strip keeps, kept here.
import { ChevronDownIcon, ChevronRightIcon } from "lucide-react";
import { useRef, useState, type FC, type ReactNode } from "react";
import { useTranslation } from "react-i18next";

import { STATS_VIEW_ID, StatsCloseButton } from "@/components/right-pane-toggle";
import { Button } from "@/components/ui/button";
import { useHomeStats } from "@/hooks/use-home-stats";
import { formatTokens } from "@/lib/format";
import {
  rebuildStatsWindow,
  STATS_RANGES,
  type HomeStats,
  type Leader,
  type ModelLeader,
} from "@/lib/home-stats";

/// THE SHELL EVERY SECTION WEARS: a heading, its own scroll, and the rule between it and the next
/// one (a row of three on a wide page, a stack of three on a narrow one).
const Section: FC<{ slot: string; title: string; children: ReactNode }> = ({
  slot,
  title,
  children,
}) => (
  <section
    data-slot={slot}
    className="flex min-h-0 flex-col border-b px-4 py-4 md:border-b-0 md:border-e md:last:border-e-0"
  >
    <h2 data-slot={`${slot}-title`} className="shrink-0 text-sm font-medium">
      {title}
    </h2>
    {children}
  </section>
);

/// ONE RANKING OF [WHAT, HOW MANY]: the server sorts it, so this draws the array in the order it
/// arrived and never sorts it again -- a second ordering here would be a second answer.
///
/// A heading and a number per row, with the name allowed to clip and the count not: a long tool
/// name must not decide the page's width.
const LeaderRows: FC<{ rows: readonly Leader[]; slot: string }> = ({ rows, slot }) => (
  <ol data-slot={`${slot}-rows`} className="mt-2 min-h-0 flex-1 overflow-y-auto text-xs">
    {rows.map((row) => (
      <li
        key={row.name}
        data-slot={`${slot}-row`}
        className="flex items-baseline justify-between gap-2 py-1"
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
    <ol data-slot="stats-models-rows" className="mt-2 min-h-0 flex-1 overflow-y-auto text-xs">
      {rows.map((row) => (
        <li key={row.model ?? "unknown"} data-slot="stats-model-row" className="py-1.5">
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
          <div data-slot="stats-model-calls" className="text-muted-foreground text-[0.6875rem]">
            {t("rightPane.statsModelCalls", { count: row.calls })}
          </div>
        </li>
      ))}
    </ol>
  );
};

/// The page, with the one way out in its header: closing it puts the conversation back (there is
/// no column to fold back to -- this page IS the room).
/// `sidebarFolded` IS THE ONE THING THIS PAGE IS TOLD ABOUT ITS NEIGHBOUR, and it is the same fact
/// `app.tsx`'s session bar is told: while the LEFT sidebar is folded there is a floating "open the
/// sidebar" control in the top-left corner, and it sits exactly on this header's own leading
/// button -- found by WALKING the page rather than by reading it (792px wide, sidebar folded,
/// both boxes at 8,8). The session bar clears it with `ps-12 lg:ps-3` and explains the
/// arithmetic there; this header clears it with the same class for the same reason, and `lg:`
/// takes the inset away again where there is no floating control to clear.
export const StatsView: FC<{ onClose: () => void; sidebarFolded?: boolean }> = ({
  onClose,
  sidebarFolded = false,
}) => {
  const { t } = useTranslation();
  const pane = useRef<HTMLElement | null>(null);
  /// THE WINDOW IS THE PAGE'S OWN STATE, above the hook that reads it: changing it is changing
  /// the question, and the hook re-reads and re-subscribes for the new one.
  const [days, setDays] = useState<number>(STATS_RANGES[0]);
  const stats: HomeStats | null = useHomeStats(pane, days);
  /// CLOSED TO BEGIN WITH: the token ranking is the click the owner asked for, and a page that
  /// opened with all three unfurled would have made that click meaningless.
  const [modelsOpen, setModelsOpen] = useState(false);
  /// THE PRESS, not a progress bar: the server answers at once and the numbers arrive on the
  /// socket, so this only stops a second click from stacking a second request.
  const [rebuilding, setRebuilding] = useState(false);

  const tools = stats?.tools ?? [];
  const skills = stats?.skills ?? [];
  const models = stats?.models ?? [];

  const rebuild = (): void => {
    setRebuilding(true);
    void rebuildStatsWindow(days).finally(() => setRebuilding(false));
  };

  return (
    <aside
      id={STATS_VIEW_ID}
      ref={pane}
      data-slot="stats-view"
      aria-label={t("rightPane.stats")}
      className="bg-background flex min-h-0 min-w-0 flex-1 flex-col"
    >
      <header
        data-slot="stats-view-header"
        className={`flex h-12 shrink-0 items-center gap-2 border-b px-3${
          sidebarFolded ? " ps-12 lg:ps-3" : ""
        }`}
      >
        <StatsCloseButton onClose={onClose} />
        <span data-slot="stats-view-name" className="min-w-0 flex-1 truncate text-sm font-medium">
          {t("rightPane.stats")}
        </span>
        {/* THE WINDOW, as three buttons rather than a select: which one is on is the whole of what
            a reader needs from a range control, and `aria-pressed` says it to a screen reader. */}
        <div
          data-slot="stats-range"
          role="group"
          aria-label={t("rightPane.statsRange")}
          className="flex shrink-0 items-center gap-1"
        >
          {STATS_RANGES.map((value) => (
            <Button
              key={value}
              type="button"
              size="sm"
              variant={value === days ? "default" : "ghost"}
              data-slot={`stats-range-${value}`}
              aria-pressed={value === days}
              onClick={() => setDays(value)}
            >
              {t("rightPane.statsDays", { count: value })}
            </Button>
          ))}
        </div>
        {/* THE ONE VERB ON THE PAGE: make the projection again for this window. It is here rather
            than somewhere automatic because the machine never re-reads a log on its own. */}
        <Button
          type="button"
          size="sm"
          variant="outline"
          data-slot="stats-rebuild"
          disabled={rebuilding}
          onClick={rebuild}
          title={t("rightPane.statsRebuildHint")}
        >
          {t("rightPane.statsRebuild")}
        </Button>
      </header>

      {/* A ROW OF THREE ON A WIDE PAGE, A STACK OF THREE ON A NARROW ONE, and each keeps its own
          scroll inside it: a ranking that grew past the fold must not push the next one off. */}
      <div className="grid min-h-0 flex-1 grid-rows-3 md:grid-cols-3 md:grid-rows-1">
        <Section slot="stats-tools" title={t("rightPane.statsTools")}>
          {tools.length === 0 ? (
            <p data-slot="stats-tools-empty" className="text-muted-foreground mt-2 text-xs">
              {t("rightPane.statsToolsEmpty")}
            </p>
          ) : (
            <LeaderRows rows={tools} slot="stats-tools" />
          )}
        </Section>

        <Section slot="stats-skills" title={t("rightPane.statsSkills")}>
          {skills.length === 0 ? (
            <p data-slot="stats-skills-empty" className="text-muted-foreground mt-2 text-xs">
              {t("rightPane.statsSkillsEmpty")}
            </p>
          ) : (
            <LeaderRows rows={skills} slot="stats-skills" />
          )}
        </Section>

        <Section slot="stats-models" title={t("rightPane.statsModels")}>
          <button
            type="button"
            data-slot="stats-models-toggle"
            aria-expanded={modelsOpen}
            onClick={() => setModelsOpen((open) => !open)}
            className="mt-2 flex shrink-0 items-center gap-1 text-xs"
          >
            {modelsOpen ? (
              <ChevronDownIcon data-slot="stats-models-open-icon" className="size-4" />
            ) : (
              <ChevronRightIcon data-slot="stats-models-closed-icon" className="size-4" />
            )}
            <span>{t(modelsOpen ? "rightPane.statsModelsHide" : "rightPane.statsModelsShow")}</span>
          </button>
          {modelsOpen &&
            (models.length === 0 ? (
              <p data-slot="stats-models-empty" className="text-muted-foreground mt-2 text-xs">
                {t("rightPane.statsModelsEmpty")}
              </p>
            ) : (
              <ModelRows rows={models} />
            ))}
        </Section>
      </div>
    </aside>
  );
};

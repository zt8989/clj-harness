"use client";

// The settings panel's session page: every conversation this home keeps, a checkbox per row, and
// three batch verbs -- archive, unarchive, delete -- with a confirmation in front of the one that
// destroys.
//
// ================================================== why this is not a section of `settings-panel`
//
// ITS ROWS AND ITS CONFIRMATION HAVE TO BE RENDERABLE WITHOUT A BROWSER, and `settings-panel.tsx`
// cannot be: it imports `lib/i18n.ts`, which touches `document` as it loads, and the UI suites run
// with no DOM by design (`vitest.config.ts`). `components/subagent-list.tsx` exists for the same
// reason and argues it at length; what is added here is the confirmation, whose sentences ARE the
// feature -- they say how many conversations go and that the record goes with them -- and would
// otherwise be reachable only by opening a browser.
//
// THE RADIX PORTAL IS WHY THE BODY IS ITS OWN COMPONENT. `DialogContent` renders through a portal
// that stays empty until the browser mounts it, so a render of the open dialog is the empty string
// here -- the sentences inside it are literally invisible to this run. `DeleteSessionsConfirmBody`
// is everything inside that portal, so a suite can render it into a bare `<Dialog>` and read what a
// person would see (see `test/suites/settings-sessions.tsx`).
//
// ================================================================ one wording per fact
//
// THE THREE VERBS AND THE TWO STATES ARE THE `shell` CATALOG'S, not restatements written here:
// 归档 / 取消归档 are `session.archive` / `session.unarchive`, and 已归档 / 运行中 are
// `session.archived` / `session.running` -- the same keys the sidebar's rows and its archived block
// already read. A settings page with its own words for one action is how that action ends up called
// two things on one screen, and `settings-panel.tsx`'s subagent form makes the same call for the
// same reason. Only what is NEW on this page -- its own sentences, select-all, the delete verb and
// its confirmation -- lives in the `settings` catalog.
//
// ============================================================== what this page is not
//
// IT DOES NOT WRITE ANYTHING ITSELF. The fetch, the selection's bookkeeping and the re-read after a
// write all belong to the page (`settings-panel.tsx`'s `SessionsPage`), which owns the one thing
// these components cannot: the request. What is here is a drawing -- props in, markup out -- which
// is also what makes the delete's off-state and the confirmation's sentence assertable.
import { type FC } from "react";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import type { SessionSummary } from "@/lib/projects";
import { blocked } from "@/lib/session-status";
import { titleOf } from "@/lib/session-title";

/// ONE CONVERSATION AS THIS PAGE DRAWS IT: the checkbox that selects it, what it is called, the two
/// states worth knowing before pressing a verb, and -- when a write on THIS row was refused -- the
/// server's sentence underneath.
///
/// THE NAME IS `titleOf`'S RULE, the same one the sidebar's row follows: what the person first said,
/// clipped, and THE ID WHEN THERE IS NOTHING TO SAY. The fallback is the row's own question rather
/// than the top bar's (`sessionTitle` says `New session` there): a list of forty `New session`s
/// identifies nothing, while an id tells two rows apart. See `lib/session-title.ts`.
///
/// THE ERROR IS THE SERVER'S OWN WORDS, drawn where the row is and never translated: the server is
/// what refused, it says why, and a paraphrase here would be one more thing to distrust -- the same
/// rule `lib/projects.ts`'s `reasonFrom` states for the whole-request case.
export const SessionBatchRow: FC<{
  session: SessionSummary;
  selected: boolean;
  busy: boolean;
  /// The refusal that belongs to this row, or null. It is `DeleteResult` / `ArchiveResult`'s
  /// `error` field, kept under the id it arrived with.
  error: string | null;
  onToggle: (threadId: string) => void;
}> = ({ session, selected, busy, error, onToggle }) => {
  const { t } = useTranslation();
  const title = titleOf(session.firstUserText) ?? session.threadId;
  return (
    <li
      data-slot="settings-session-row"
      data-thread-id={session.threadId}
      className="flex flex-col gap-0.5 rounded-md border px-2 py-1"
    >
      <div className="flex items-center gap-2">
        {/* A BARE CHECKBOX, the precedent the panel's own provider form already set: there is no
            checkbox primitive in `components/ui/`, and one row needs no more than the browser's. */}
        <label className="flex min-w-0 flex-1 items-center gap-2 text-xs">
          <input
            type="checkbox"
            data-slot="settings-session-select"
            checked={selected}
            disabled={busy}
            onChange={() => onToggle(session.threadId)}
          />
          <span data-slot="settings-session-title" className="min-w-0 truncate">
            {title}
          </span>
        </label>
        {/* THE TWO STATES, SAID SEPARATELY because they ask for different things before a verb: an
            archived row is one an unarchive would move back, and a running row is one the server
            will refuse to delete. Neither is drawn for a session that is simply settled. */}
        {session.archived && (
          <span
            data-slot="settings-session-archived"
            className="text-muted-foreground shrink-0 rounded border px-1 text-[10px]"
          >
            {t("session.archived")}
          </span>
        )}
        {session.running && (
          <span
            data-slot="settings-session-running"
            className="text-foreground shrink-0 text-[10px] tracking-wide"
          >
            {t("session.running")}
          </span>
        )}
      </div>
      {error !== null && (
        <p
          role="alert"
          data-slot="settings-session-row-error"
          className="text-destructive px-0.5 text-xs break-words"
        >
          {error}
        </p>
      )}
    </li>
  );
};

/// EVERYTHING INSIDE THE DELETE CONFIRMATION, split out for the reason at the top of this file: a
/// portal draws nothing in this run, and these sentences are the feature.
///
/// IT SAYS HOW MANY AND WHAT GOES, and both are promises the click makes. The number is here
/// because a selection is what was ticked and the rows behind the modal are no longer readable; the
/// record (jsonl) is named because that is the difference between this verb and the archive beside
/// it, and "for good" is said because there is no undo behind this door.
export const DeleteSessionsConfirmBody: FC<{
  count: number;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}> = ({ count, busy, onCancel, onConfirm }) => {
  const { t } = useTranslation("settings");
  return (
    <>
      <DialogHeader>
        <DialogTitle>{t("sessions.deleteTitle")}</DialogTitle>
        <DialogDescription data-slot="settings-sessions-delete-body">
          {t("sessions.deleteBody")}
        </DialogDescription>
      </DialogHeader>
      <p data-slot="settings-sessions-delete-count" className="text-muted-foreground text-xs">
        {t("sessions.deleteCount", { count })}
      </p>
      <DialogFooter>
        <Button variant="ghost" data-slot="settings-sessions-delete-cancel" onClick={onCancel}>
          {t("sessions.deleteCancel")}
        </Button>
        <Button
          variant="destructive"
          data-slot="settings-sessions-delete-submit"
          disabled={busy}
          onClick={onConfirm}
        >
          {t("sessions.deleteSubmit")}
        </Button>
      </DialogFooter>
    </>
  );
};

/// What the panel needs to draw. Every callback is the page's: this component decides nothing about
/// the store.
export type SessionsBatchProps = {
  /// THE CONVERSATIONS TO MANAGE: both halves of the sidebar's listing, already flattened, in the
  /// listing's own order (`listSidebar` puts the newest first, and the panel keeps that -- a
  /// regrouping here would be a second answer to "in what order").
  sessions: readonly SessionSummary[];
  /// THE IDS THE PERSON HAS TICKED. A selection is a set of IDS rather than of rows, so a re-read
  /// that changes a row's facts -- archived, running -- does not silently drop it.
  selected: readonly string[];
  /// THE SERVER'S OWN SENTENCE for a row whose write in the last batch failed, keyed by the id it
  /// belongs to. Shown on that row and nowhere else.
  errors: Readonly<Record<string, string>>;
  /// A request is in flight: every control is off, so one press cannot become two.
  busy: boolean;
  /// WHETHER THE DELETE CONFIRMATION IS UP.
  confirming: boolean;
  /// What the last batch, AS A WHOLE, could not do -- a refused request rather than a refused row.
  failure: string | null;
  onToggle: (threadId: string) => void;
  onToggleAll: () => void;
  /// ONE VERB, TWO DIRECTIONS (the same shape `setArchived` has): `true` files the selection away,
  /// `false` brings it back.
  onArchive: (archived: boolean) => void;
  /// Open the confirmation. The delete itself is `onConfirmDelete`, and the split is the whole
  /// point: nothing is destroyed by this call.
  onDelete: () => void;
  onConfirmDelete: () => void;
  onCancelDelete: () => void;
};

export const SessionsBatchPanel: FC<SessionsBatchProps> = ({
  sessions,
  selected,
  errors,
  busy,
  confirming,
  failure,
  onToggle,
  onToggleAll,
  onArchive,
  onDelete,
  onConfirmDelete,
  onCancelDelete,
}) => {
  const { t } = useTranslation("settings");
  const { t: tShell } = useTranslation();
  const chosen = sessions.filter((session) => selected.includes(session.threadId));
  const allSelected = sessions.length > 0 && chosen.length === sessions.length;
  const nothingChosen = chosen.length === 0;
  /// WHETHER A RUN IS GOING FOR ANY OF THE CHOSEN, which is what turns the delete off. THE RULE IS
  /// `lib/session-status.ts`'S (`blocked`) rather than a boolean read here, because the sidebar
  /// refuses the same thing for the same reason and two copies of one rule drift apart. Only the
  /// half a listing can answer IS asked: a pull carries `running`, while `parked` -- a run that
  /// stopped to ask a human -- is a fact about a host this page does not hold, so it is `false`
  /// rather than guessed. THE SERVER REFUSES EITHER WAY, by name, and its sentence lands on the row
  /// (see `DeleteResult`), so a conversation that slipped through is refused in words rather than
  /// deleted.
  const deleteRefused = chosen.some((session) =>
    blocked({ running: session.running, parked: false }),
  );
  return (
    <div data-slot="settings-page-sessions" className="flex flex-col gap-2">
      {/* The heading is written here rather than read from `settings-panel.tsx`'s `SectionTitle`,
          which this module cannot import: that file imports THIS one. The classes are the same, so
          the two look like one page. */}
      <h3 className="text-muted-foreground mb-1 text-xs font-semibold tracking-wide uppercase">
        {t("sessions.title")}
      </h3>
      <p className="text-muted-foreground text-xs">{t("sessions.hint")}</p>

      {/* A REQUEST THE SERVER REFUSED, whole: the server's sentence, drawn above the list because
          there is no row it belongs to (an empty selection, a body it would not parse). */}
      {failure !== null && (
        <p
          role="alert"
          data-slot="settings-sessions-error"
          className="text-destructive text-xs break-words"
        >
          {failure}
        </p>
      )}

      {sessions.length === 0 ? (
        <p data-slot="settings-sessions-empty" className="text-muted-foreground text-xs">
          {t("sessions.empty")}
        </p>
      ) : (
        <>
          <div
            data-slot="settings-sessions-actions"
            className="flex flex-wrap items-center gap-2 rounded-md border p-2"
          >
            <label className="flex items-center gap-1.5 text-xs">
              <input
                type="checkbox"
                data-slot="settings-sessions-select-all"
                checked={allSelected}
                disabled={busy}
                onChange={onToggleAll}
              />
              <span data-slot="settings-sessions-select-all-label">
                {t("sessions.selectAll")}
              </span>
            </label>
            <Button
              variant="outline"
              size="sm"
              data-slot="settings-sessions-archive"
              disabled={busy || nothingChosen}
              onClick={() => onArchive(true)}
            >
              {tShell("session.archive")}
            </Button>
            <Button
              variant="outline"
              size="sm"
              data-slot="settings-sessions-unarchive"
              disabled={busy || nothingChosen}
              onClick={() => onArchive(false)}
            >
              {tShell("session.unarchive")}
            </Button>
            {/* DESTRUCTIVE AND OFF WHILE A RUN IS GOING. The reason is the title rather than a
                sentence on the page: it is one more thing about the same click, and this button is
                where the click would have landed. */}
            <Button
              variant="destructive"
              size="sm"
              data-slot="settings-sessions-delete"
              disabled={busy || nothingChosen || deleteRefused}
              title={deleteRefused ? t("sessions.deleteRunning") : undefined}
              onClick={onDelete}
            >
              {t("sessions.delete")}
            </Button>
          </div>

          <ul data-slot="settings-sessions-list" className="flex flex-col gap-1">
            {sessions.map((session) => (
              <SessionBatchRow
                key={session.threadId}
                session={session}
                selected={selected.includes(session.threadId)}
                busy={busy}
                error={errors[session.threadId] ?? null}
                onToggle={onToggle}
              />
            ))}
          </ul>
        </>
      )}

      {/* THE CONFIRMATION `sidebar-remove-confirm`'S SHAPE, for a verb whose promise is the
          opposite one: an icon-less modal (no close X -- the two buttons are the whole answer),
          a ghost cancel and a destructive submit, and every part named by a `data-slot` so a
          suite can read it. */}
      <Dialog
        open={confirming}
        onOpenChange={(open) => {
          if (!open) onCancelDelete();
        }}
      >
        <DialogContent data-slot="settings-sessions-delete-confirm" showCloseButton={false}>
          <DeleteSessionsConfirmBody
            count={chosen.length}
            busy={busy}
            onCancel={onCancelDelete}
            onConfirm={onConfirmDelete}
          />
        </DialogContent>
      </Dialog>
    </div>
  );
};

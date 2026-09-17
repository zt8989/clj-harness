"use client";

// The session row: one conversation, as the sidebar draws it.
//
// ------------------------------------------- what was copied, and what changed
//
// Copied from assistant-ui's registry -- `npx shadcn@latest add
// "@assistant-ui/thread-list"` -- and then edited IN PLACE. That in-place edit is
// the one departure from this repo's rule that copied files stay untouched, and
// the reason is that upstream's file is a self-contained thread LIST for a
// different product shape: it reads titles off the runtime, groups by date, and
// offers rename and delete. None of that is this harness. So the parts worth
// keeping were kept (the row's markup and classes, the running spinner, the
// more-menu) and the rest was removed, each removal marked `LOCAL:` below with
// what it was and why it is gone. Reconciling against upstream is therefore a
// diff of marked blocks rather than a rewrite of the file.
//
// LOCAL: removed `ThreadList`, `ThreadListRoot`, `ThreadListItems`,
// `ThreadListSearch`, `ThreadListSkeleton` and `useThreadListGroups`. Upstream's
// composite is a flat, date-grouped list of runtime-known threads; this one is
// grouped by PROJECT and its threads come from the store through
// `GET /api/projects`, because a session belongs to a project and the runtime has
// no idea that projects exist. Search and date grouping are spec non-goals, and
// `ThreadListNew`'s job is done by the sidebar's new-task button, which has to
// refuse when there is no project to open the session under (ticket 05).
//
// LOCAL: removed the rename input and the Rename / Delete menu items. This
// harness implements neither verb -- nothing renames a session (non-goal) and
// deletion is deliberately absent, since removing a project unbinds rather than
// deletes (decision 4). A menu item that throws when clicked is worse than an
// absent one, because the click looks like it worked.
//
// LOCAL: the trigger is our own button, not `ThreadListItemPrimitive.Trigger`.
// That primitive's action is `() => aui.threadListItem.switchTo()`, whose promise
// is DISCARDED (see upstream's useThreadListItemTrigger and createActionButton),
// so a refused switch surfaces as an unhandled rejection and nowhere else.
// Ticket 04 requires the refusal to appear on the row that was clicked, which
// needs a switch whose promise we hold -- so the click calls the handler this
// component is given, and the sidebar owns the guard and the error.
//
// LOCAL: the row is two lines (id, then the disk facts) rather than upstream's
// single truncated title, and `disabled` is real. A session has no title in this
// product and does have a size and an mtime; and the current row is drawn as
// current, so it must not be clickable -- "click the row you are already on" is
// not a no-op here, it is a reload of the conversation being read.
import { Loader2Icon } from "lucide-react";
import { forwardRef, type ComponentPropsWithoutRef, type FC, type ReactNode } from "react";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { formatBytes, formatTime } from "@/lib/format";
import { asLanguage } from "@/lib/language";
import type { SessionSummary } from "@/lib/projects";

/// The row's data, as the sidebar has it. Deliberately the wire shape rather
/// than a projection of the runtime's thread item: the runtime knows a thread's
/// title and last message, and this list is about a log file on disk.
export type SessionRowData = SessionSummary;

export type ThreadListItemProps = {
  session: SessionRowData;
  /// Whether this is the conversation on screen. Drawn as current AND disabled.
  current: boolean;
  /// Whether another action is in flight; every row stops accepting clicks while
  /// one is, so a slow request cannot be turned into two.
  busy: boolean;
  /// Whether this thread has a run in flight. Since only the open thread is
  /// mounted, this is the main thread's running state -- the same thing the
  /// refusal below the row is about.
  running: boolean;
  onOpen: () => void;
  /// The refusal or failure that belongs to THIS row, or null. Rendered under the
  /// row it happened on and nowhere else: a message at the top of the list makes
  /// the reader match it to a row themselves, and they match it wrong.
  error?: string | null;
  /// The row's verbs (archive, and whatever a later ticket adds), rendered inside
  /// the row's hover affordance. Passed in rather than imported so this file
  /// stays a presentation component with no opinion about the store.
  actions?: ReactNode;
};

export const ThreadListItem: FC<ThreadListItemProps> = ({
  session,
  current,
  busy,
  running,
  onOpen,
  error,
  actions,
}) => {
  const { threadId, lastActivity, bytes } = session;
  // LOCAL: the two disk facts' words. The formatters themselves are `lib/format.ts`;
  // what moved here is the ABSENCE -- "never run" and "no log yet" used to be
  // answered by the formatter, and the row is the thing that knows which absence it
  // is looking at.
  const { t, i18n } = useTranslation();
  const locale = asLanguage(i18n.language);
  return (
    <li data-slot="thread-list-item" data-current={current ? "" : undefined}>
      <div
        className={cn(
          "group relative flex items-start rounded-md transition-colors",
          current ? "bg-muted" : "hover:bg-muted/60",
        )}
      >
        <button
          type="button"
          data-slot="thread-list-item-trigger"
          disabled={busy || current}
          onClick={onOpen}
          title={threadId}
          className={cn(
            "focus-visible:ring-ring/50 flex min-w-0 flex-1 flex-col items-start gap-0.5 rounded-md px-2.5 py-1.5 text-start outline-none focus-visible:ring-1",
            "disabled:cursor-default",
          )}
        >
          <span className="flex w-full min-w-0 items-center gap-1.5">
            {running && (
              <Loader2Icon
                aria-hidden
                data-slot="thread-list-item-running"
                className="text-muted-foreground size-3.5 shrink-0 animate-spin"
              />
            )}
            <code
              data-slot="thread-list-item-id"
              className="min-w-0 flex-1 truncate font-mono text-xs"
            >
              {threadId}
            </code>
            {/* LOCAL: upstream's literal `current` is gone from this file and
                read from the shell catalog instead. It is a word a person sees
                (the small uppercase label beside the row id), so it had to
                follow the rest of the sidebar into the shell catalog -- see
                `session.current` in `locales/en/shell.json` and its Chinese
                twin. The classes are upstream's and stay. */}
            {current && (
              <span className="text-muted-foreground shrink-0 text-[10px] tracking-wide uppercase">
                {t("session.current")}
              </span>
            )}
          </span>
          <span
            data-slot="thread-list-item-meta"
            className="text-muted-foreground w-full truncate text-xs tabular-nums"
          >
            {lastActivity === null ? t("session.neverRun") : formatTime(lastActivity, locale)} ·{" "}
            {bytes === null ? t("session.noLog") : formatBytes(bytes)}
          </span>
          {/* LOCAL: upstream's literal `Running` is gone from this file and read
              from the shell catalog instead. It is the screen-reader word for the
              spinner, and screen-reader text is copy like any other -- see
              `session.running` in `locales/en/shell.json` and its Chinese twin. */}
          {running && <span className="sr-only">{t("session.running")}</span>}
        </button>
        {actions !== undefined && (
          <div className="shrink-0 pt-1 pe-1">{actions}</div>
        )}
      </div>
      {error !== null && error !== undefined && error !== "" && (
        <p
          role="alert"
          data-slot="thread-list-item-error"
          className="text-destructive px-2.5 pb-1 text-xs"
        >
          {error}
        </p>
      )}
    </li>
  );
};

/// A row-level icon button. The copied file's `ThreadListItemMore` built its
/// trigger on `Button variant="ghost" size="icon"` with these very classes; this
/// keeps that button and drops the dropdown that wrapped it, because the dropdown
/// in this product belongs to the row's ACTIONS, which are passed in (see
/// `actions` above) rather than to a menu this file would have to know about.
export const ThreadListItemAction = forwardRef<
  HTMLButtonElement,
  ComponentPropsWithoutRef<typeof Button>
>(({ className, ...props }, ref) => (
  <Button
    ref={ref}
    variant="ghost"
    size="icon"
    data-slot="thread-list-item-action"
    className={cn(
      "text-muted-foreground hover:text-foreground size-6 p-0 opacity-0 group-hover:opacity-100 group-focus-within:opacity-100",
      className,
    )}
    {...props}
  />
));

ThreadListItemAction.displayName = "ThreadListItemAction";

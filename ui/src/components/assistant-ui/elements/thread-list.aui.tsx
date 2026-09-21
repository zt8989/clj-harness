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
// `ThreadListNew`'s job is done by the sidebar's new-task button -- which makes a
// TASK, a conversation with no project, so that button never has to refuse for
// want of one. The same row is drawn in both blocks: a task row is this row.
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
// LOCAL: the row is ONE line, and it is the store's line rather than the disk's. It
// used to be two -- the title, then the log's `mtime · bytes` -- and the whole second
// line is gone (`bytes` is not in the listing any more): the TIME moved up to the right
// end of the one line, as a relative age, and the absolute value moved into the row's
// tooltip. The id line went the other way one feature ago: it used to BE the title,
// then became the fallback and the tooltip. And the current row is drawn as current, so
// it must not be clickable -- "click the row you are already on" is not a no-op here, it
// is a reload of the conversation being read.
//
// THE INDENT IS A SLOT, and that is the other half of the shape (owner's: "项目和会话
// 要有明显的缩进，留下的缩进刚好显示 loading 状态"). A session row sits to the RIGHT of
// the project's folder icon, and the strip it is pushed past holds the spinner when the
// session is running -- an empty box of exactly the spinner's width when it is not. So
// the title starts at the same x either way, and nothing jumps when a run begins or
// ends. The width is not guessed: see `ps-2` below and the arithmetic in the comment.
import { Loader2Icon } from "lucide-react";
import { forwardRef, type ComponentPropsWithoutRef, type FC, type ReactNode } from "react";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { formatTime } from "@/lib/format";
import { asLanguage } from "@/lib/language";
import { relativeAge } from "@/lib/relative-time";
import { REVEAL_ON_HOVER } from "@/lib/reveal";
import { titleOf } from "@/lib/session-title";
import type { SessionSummary } from "@/lib/projects";

/// The row's data, as the sidebar has it. Deliberately the wire shape rather
/// than a projection of the runtime's thread item: the runtime knows a thread's
/// title and last message, and this list is about a session in the store.
export type SessionRowData = SessionSummary;

/// WHICH CATALOG KEY EACH COUNTING BUCKET IS WORDED WITH. A lookup rather than a key
/// assembled from the bucket's name (`session.${kind}Ago`): the compiler checks a
/// literal, and the three of them are pinned here next to the type that has to keep
/// agreeing with them (`RelativeAge` in `lib/relative-time.ts`).
const AGO_KEY = {
  minutes: "session.minutesAgo",
  hours: "session.hoursAgo",
  days: "session.daysAgo",
} as const;

export type ThreadListItemProps = {
  session: SessionRowData;
  /// Whether this is the conversation on screen. Drawn as current AND disabled.
  current: boolean;
  /// Whether another action is in flight; every row stops accepting clicks while
  /// one is, so a slow request cannot be turned into two.
  busy: boolean;
  /// LOCAL: THIS ROW'S OWN SESSION, not the one on screen. Upstream read the
  /// runtime's main-thread state and the sidebar narrowed it with
  /// `=== currentThreadId`; there is one runtime per session now, so the sidebar
  /// passes the answer from its registry and the row draws what it is given.
  /// Whether this thread has a run in flight.
  running: boolean;
  /// LOCAL: added with ticket 03/05. A session can be WAITING rather than running
  /// -- a run ended on an interrupt, so `isRunning` is false, and a human still
  /// owes it an answer. Upstream's row has no such state; it is drawn in words
  /// because "stopped" and "waiting for you" want different reactions.
  parked?: boolean;
  /// LOCAL: added with the sidebar's archived block. A word the row SAYS ABOUT
  /// ITSELF, drawn beside the id -- today it is which PROJECT a filed-away
  /// conversation came from, because that block is flat and this is the only place
  /// the answer fits. Optional and generic on purpose: the row does not know what a
  /// project is, and it must not start.
  label?: string | null;
  /// LOCAL: added with `sessions.title`. WHAT THIS PAGE ITSELF CALLS THE
  /// CONVERSATION, when this page is the thing holding it: the title derived from the
  /// runtime's own messages (`firstUserText`, already tidied and clipped by the same
  /// rule the store's copy goes through below). It WINS OVER
  /// `session.firstUserText`, and that ordering is the point rather than a detail:
  /// the listing is a snapshot from whenever the sidebar last asked, so a session
  /// that has just been typed into is stale in it by one message, and the row it was
  /// typed into is exactly where a person is looking. Absent for every row this page
  /// has no host for, which is most of them.
  liveTitle?: string | null;
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
  parked = false,
  label = null,
  liveTitle = null,
  onOpen,
  error,
  actions,
}) => {
  const { threadId, lastSentAt } = session;
  const { t, i18n } = useTranslation();
  const locale = asLanguage(i18n.language);
  // WHAT THIS CONVERSATION IS CALLED, from the livelier of the two sources this page
  // has (`liveTitle`), falling back to the store's stored copy and then to the id --
  // see the `liveTitle` prop. `titleOf` is the same rule the bar uses, so a row and the
  // bar can never disagree about what a title is.
  const title = liveTitle ?? titleOf(session.firstUserText) ?? threadId;
  // THE TIME IS READ NOW, at render, rather than from a timer: a relative age is a
  // glance, and forty rows each holding a one-second interval to keep their own word
  // fresh would be forty timers for a number nobody is watching tick. Every listing
  // refresh re-renders these rows, and the age is recomputed then -- the buckets are
  // minutes and days wide, so being a refresh behind is not being wrong.
  const now = Date.now();
  // THE RIGHT END: the relative age, or the word for having none. `neverRun` is the
  // row's own sentence for a NULL `lastSentAt` -- a session registered and never sent
  // to -- because `lib/relative-time.ts` answers BUCKETS and knows nothing about
  // absences (the same split `lib/format.ts` makes for its numbers).
  const age = lastSentAt === null ? null : relativeAge(lastSentAt, now);
  const when =
    age === null
      ? t("session.neverRun")
      : age.kind === "justNow"
        ? t("session.justNow")
        : age.kind === "date"
          ? new Date(lastSentAt!).toLocaleDateString(locale, {
              month: "numeric",
              day: "numeric",
            })
          : t(AGO_KEY[age.kind], { count: age.count });
  // THE TOOLTIP IS TWO LINES: exactly which conversation this is, and exactly when it
  // was last sent to. Both are things the row cannot show at 288px -- a 36-character
  // id and a full timestamp -- and both are what a person needs when two rows look
  // alike, which is why they share one hover rather than competing for space.
  const tooltip =
    lastSentAt === null ? threadId : `${threadId}\n${formatTime(lastSentAt, locale)}`;
  return (
    <li data-slot="thread-list-item" data-current={current ? "" : undefined}>
      <div
        className={cn(
          "group relative flex items-center rounded-md transition-colors",
          current ? "bg-muted" : "hover:bg-muted/60",
        )}
      >
        <button
          type="button"
          data-slot="thread-list-item-trigger"
          disabled={busy || current}
          onClick={onOpen}
          title={tooltip}
          // THE INDENT, MEASURED RATHER THAN EYEBALLED. A session row lines up under the
          // project's NAME, so its x has to equal the project row's: that row is
          // `px-1.5` (6px) + a 16px folder icon + `gap-1.5` (6px) = 28px to the start of
          // the name (`sidebar.tsx`, the project header). This row reaches the same 28px
          // as `ps-2` (8px) + the slot below, which is the spinner's own `size-3.5`
          // (14px) + this row's `gap-1.5` (6px) -- 8 + 14 + 6 = 28. So the owner's
          // "缩进的宽度刚好显示 loading 状态" is literally true: the strip a row is
          // indented by IS the spinner's box, and if either number changes the other has
          // to change with it.
          className={cn(
            "focus-visible:ring-ring/50 flex min-w-0 flex-1 items-center gap-1.5 rounded-md pe-2.5 ps-2 py-1.5 text-start outline-none focus-visible:ring-1",
            "disabled:cursor-default",
          )}
        >
          {/* THE SLOT: always drawn, empty when nothing is running. That is what keeps
              the title's x fixed -- a spinner that came and went would shift every word
              on the row each time a run started, and a list that moves while you are
              reading it is a list you have to re-find your place in.
              IT IS ALSO WHERE THE INDENT COMES FROM (see the class comment above). */}
          <span
            data-slot="thread-list-item-slot"
            className="flex size-3.5 shrink-0 items-center justify-center"
          >
            {running && (
              <Loader2Icon
                aria-hidden
                data-slot="thread-list-item-running"
                className="text-muted-foreground size-3.5 animate-spin"
              />
            )}
          </span>
          {/* THE ROW'S ONE LINE OF TEXT, and it has to be said out loud that something
              is always drawn, because its absence would be invisible: a `<span>` with no
              text has no line box at all, so a row that lost this line looks like a row
              that never had one. It was lost exactly that way -- the i18n merge took
              main's parked-word block and dropped the branch's `{threadId}` along with
              its own.

              THE ID IS STILL HERE, one hover away (see `tooltip` above): a 36-character
              address belongs in a tooltip -- readable, selectable, and not taking the
              width the name needs. It is also the fallback, so a session nobody has
              spoken to yet still says which one it is.

              NOT MONOSPACE ANY MORE. The `font-mono` was the id's, and it said "machine
              address"; a sentence somebody typed is read, not compared, and the mono
              face at this size made every title look like a hash. */}
          <span
            data-slot="thread-list-item-title"
            className="min-w-0 flex-1 truncate text-xs"
          >
            {title}
          </span>
          {/* LOCAL: the two things a session can be doing that ask something of
              the reader, and they are NOT the same thing -- the spinner is
              "come back later", this is "come here". Only one can be up at a
              time (a parked run is not running), and neither is drawn for a
              session that has simply settled. A word a person reads, so it
              comes from the shell catalog.

              BESIDE THE NAME, NOT INSIDE IT, and the difference is measured rather
              than tidy: the element this label sits beside is `truncate`, so on a
              288px sidebar a long title is ellipsized. A label inside it is clipped
              away with the tail -- which would take the ONE word this row exists to
              say with it. Out here it is a flex sibling, so `shrink-0` means
              something and the name is the part that gives. The branch this came
              from had it inside; the suite that renders the row
              (`test/suites/sidebar.tsx`) is what pinned the two apart. */}
          {parked && (
            <span
              data-slot="thread-list-item-parked"
              className="text-foreground shrink-0 text-[10px] tracking-wide"
            >
              {t("session.parked")}
            </span>
          )}
          {/* LOCAL: what this row says about itself (see the prop). BESIDE the name
              and OUTSIDE its truncating element, for the same measured reason the
              parked word is: a label inside a line that ellipsizes is a label nobody
              reads. */}
          {label !== null && label !== "" && (
            <span
              data-slot="thread-list-item-label"
              className="text-muted-foreground max-w-[8rem] shrink-0 truncate text-[10px]"
            >
              {label}
            </span>
          )}
          {/* THE RIGHT END OF THE LINE: how long ago somebody last pressed send.
              The current row used to say `current` here -- that word is gone, and the
              background says it instead (owner's call: the row is one line now, and a
              word that repeats on every row you are not reading is a word spent on
              nothing). `tabular-nums` is what keeps a column of these from looking
              ragged: the digits of two ages line up. */}
          <span
            data-slot="thread-list-item-time"
            className="text-muted-foreground shrink-0 text-[10px] tabular-nums"
          >
            {when}
          </span>
          {/* LOCAL: upstream's literal `Running` is gone from this file and read
              from the shell catalog instead. It is the screen-reader word for the
              spinner, and screen-reader text is copy like any other -- see
              `session.running` in `locales/en/shell.json` and its Chinese twin. */}
          {running && <span className="sr-only">{t("session.running")}</span>}
        </button>
        {actions !== undefined && (
          <div className="shrink-0 pe-1">{actions}</div>
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
    // THE REVEAL IS `lib/reveal.ts`'S, and the `disabled:opacity-0` in it is load-bearing:
    // the sidebar disables every row action while it is busy, and shadcn's Button would
    // otherwise dim them into view (see that file -- the bug is measured there).
    className={cn(
      "text-muted-foreground hover:text-foreground size-6 p-0",
      REVEAL_ON_HOVER,
      className,
    )}
    {...props}
  />
));

ThreadListItemAction.displayName = "ThreadListItemAction";

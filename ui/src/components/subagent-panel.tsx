"use client";

// The sidebar's "Subagents" block: what this home has, and what it has handed over.
//
// -------------------------------------------------------------- what it is for
//
// THE FEATURE IS OTHERWISE INVISIBLE. A delegation is a tool call, and a tool call
// inside a transcript is a line among lines -- you can watch one go by and still not
// know that this home has two subagents, what either of them can touch, or that
// anything has ever been delegated at all. This block is where those three facts
// live: the definitions (read-only here, edited in Settings), and a record of every
// delegation, each one a door into that subagent's own conversation.
//
// A CONTAINER, NOT A ROW. Every string and every `data-slot` a person reads lives in
// `subagent-list.tsx`, because the settings page draws the same rows and two wordings
// of "what this subagent can touch" would be two claims about one range. What is here
// is the part that is the SIDEBAR's: fetching, expanding, and the one paragraph that
// says where the form is.
//
// ------------------------------------------------------- why it is a SNAPSHOT
//
// The list is read when the block is EXPANDED and not again, which is the same
// bargain the sidebar's own listing makes (see sidebar.tsx): nothing here subscribes
// to the store, so a delegation that lands while the block sits open does not change
// the rows until it is collapsed and expanded again. A panel that quietly disagreed
// with the disk would be worse than one that is visibly a reading taken at a moment.
// `running` was already stale when it arrived -- it is the server's in-memory table,
// sampled while the request was being answered -- so the row says which moment it is
// from rather than pretending to be live.
import { ChevronRightIcon, Loader2Icon } from "lucide-react";
import { useCallback, useEffect, useState, type FC } from "react";
import { useTranslation } from "react-i18next";

import { DefinitionRows, RunRows } from "@/components/subagent-list";
import { Button } from "@/components/ui/button";
import { listSubagents, type SubagentListing, type SubagentRun } from "@/lib/subagents";

export const SubagentPanel: FC<{
  /// A request of this sidebar's is in flight, so every click here is off. Owned by
  /// the sidebar because there is one `busy` for the whole column.
  busy: boolean;
  /// SHOW A DELEGATION'S CONVERSATION. The whole run rather than its id, because the
  /// caller has a second thing to do with it -- see `sidebars`'s implementation: a
  /// subagent belongs to the project its parent was bound to, so opening one must not
  /// quietly move which project "New task" would land in.
  onShow: (run: SubagentRun) => void;
}> = ({ busy, onShow }) => {
  const { t } = useTranslation();
  const { t: tErrors } = useTranslation("errors");
  const [open, setOpen] = useState(false);
  const [listing, setListing] = useState<SubagentListing | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  /// Read the block. Called on every EXPAND rather than once, and the previous answer
  /// is cleared first: a stale list under a fresh reading would be this panel saying
  /// two things at once, which is the same rule the settings panel keeps for the same
  /// reason.
  const load = useCallback(async () => {
    try {
      setListing(await listSubagents(tErrors));
      setFailure(null);
    } catch (f: unknown) {
      setListing(null);
      setFailure(f instanceof Error ? f.message : String(f));
    }
  }, [tErrors]);

  useEffect(() => {
    if (open) void load();
  }, [open, load]);

  return (
    <div data-slot="sidebar-subagents">
      <Button
        variant="ghost"
        data-slot="sidebar-subagents-trigger"
        aria-expanded={open}
        onClick={() => setOpen((was) => !was)}
        title={t("subagents.titleHint")}
        className="text-muted-foreground hover:text-foreground h-8 w-full justify-start gap-2 rounded-md px-2.5 text-sm font-normal"
      >
        <ChevronRightIcon
          aria-hidden
          data-slot="sidebar-subagents-chevron"
          className={open ? "size-4 shrink-0 rotate-90" : "size-4 shrink-0"}
        />
        {t("subagents.title")}
      </Button>

      {/* THE EXPANDED BLOCK SCROLLS ON ITS OWN, and that is a deliberate second
          scroll region rather than an oversight. The alternative -- letting it grow
          with every delegation -- would push the project list off the bottom of a
          column that is already the only place sessions are visible, and this block
          is the LESS important of the two. Bounded here, it cannot.

          `ps-[2.125rem]` IS THE CHEVRON'S WIDTH PLUS ITS GAP, so the rows below line
          up under the word "Subagents" rather than under its arrow -- the same
          indent the Archived group gives its sessions. It is a magic number in the
          sense that it is arithmetic over the trigger's own padding (`px-2.5`), a
          `size-4` icon and a `gap-2`; if those move, this moves with them. */}
      {open && (
        <div
          data-slot="sidebar-subagents-panel"
          className="max-h-72 overflow-y-auto ps-[2.125rem] pe-0.5 pb-1"
        >
          {failure !== null && (
            <p
              role="alert"
              data-slot="sidebar-subagents-error"
              className="text-destructive px-1.5 py-1 text-xs"
            >
              {failure}
            </p>
          )}

          {listing === null && failure === null && (
            <p
              data-slot="sidebar-subagents-loading"
              className="text-muted-foreground flex items-center gap-2 px-1.5 py-1 text-xs"
            >
              <Loader2Icon className="size-3.5 animate-spin" />
              {t("subagents.loading")}
            </p>
          )}

          {listing !== null && (
            <>
              {/* THE PROBLEM IS PART OF THE ANSWER, NOT A FAILURE. A harness.edn with
                  a typo in its :subagents block leaves a harness that still runs, so
                  the request answered 200 with the sentence in it -- and the sentence
                  names the thing to go and fix. Drawn above the definitions it is a
                  fact about, because that is what it is a fact about. */}
              {listing.problem !== null && (
                <p
                  data-slot="sidebar-subagents-problem"
                  className="text-destructive px-1.5 pb-1 text-xs break-words"
                >
                  {listing.problem}
                </p>
              )}

              <p className="text-muted-foreground px-1.5 pt-1 text-[10px] font-semibold tracking-wide uppercase">
                {t("subagents.definitions")}
              </p>
              <DefinitionRows definitions={listing.subagents} />
              <p className="text-muted-foreground px-1.5 pt-1 text-[10px]">
                {t("subagents.changeInSettings")}
              </p>

              <p className="text-muted-foreground px-1.5 pt-2 text-[10px] font-semibold tracking-wide uppercase">
                {t("subagents.runs")}
              </p>
              <RunRows runs={listing.runs} busy={busy} onOpen={onShow} />
            </>
          )}
        </div>
      )}
    </div>
  );
};

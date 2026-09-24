"use client";

// THE RIGHT-HAND MIRROR: one subagent's own conversation, beside the one that
// delegated to it, growing as it works.
//
// -------------------------------------------------------------- what it is not
//
// IT IS NOT A SECOND SESSION AND NOT A SECOND COMPOSER. Nothing here accepts input:
// no composer, no disabled composer, no send button, no approval gate -- those are
// all statements that this conversation can be talked to, and that is not true of a
// subagent (the spec's non-goals). What it is, is a MIRROR: the same `Thread` and the
// same `THREAD_COMPONENTS` the main column draws, so a tool card, a reasoning row and
// an answer look the same on both sides of the divider -- which is the whole reason
// the composer is a switch on `Thread` rather than a forked element.
//
// IT REPORTS NOTHING TO THE PAGE. `app.tsx`'s `SessionHost` tells the sidebar what a
// session is doing (`onStatus` / `onForget`), and a subagent is deliberately not a
// row in that list (ticket 06 of this feature removes the last one). This panel is
// its own host component and registers with nothing.
//
// ------------------------------------------------------------ where it reads from
//
// ONE CHANNEL, AND IT IS THE FOLLOW CHANNEL (ticket 02): the frames of the child's
// own run, replayed from the record and then followed live. THE TASK THE SUBAGENT WAS
// HANDED COMES DOWN THE SAME WIRE, as the `MESSAGES_SNAPSHOT` the route sends first
// -- the record's conversation minus the messages the frames rebuild -- so this panel
// needs no second read and never draws an answer to a question it did not show.
//
// AND THAT IS WHY THERE IS NO HYDRATION ADAPTER HERE. The obvious shape -- hydrate
// from `rebuild`, then stream the frames on top -- draws the child's answers TWICE:
// `rebuild` hands back the messages the record folded out of the frames, and the
// replayed frames are those same messages again, under the same ids, arriving as a
// run. One channel, read once, cannot do that.
//
// -------------------------------------------------------------- and how it ends
//
// `startRun` with no message is what opens it: a run whose transport never posts
// (see `lib/follow.ts`), so no user message is invented for it and nothing is
// appended to the conversation. A delegation that has already finished replays its
// record and stops at the terminal frame -- which is the ordinary case of opening a
// finished delegation, and no error is raised for it: that is simply what a finished
// delegation looks like. Closing the panel aborts the request, and the server drops
// the subscription with it.
import { useEffect, useMemo, useState, type FC } from "react";
import { useTranslation } from "react-i18next";

import { AssistantRuntimeProvider } from "@assistant-ui/react";
import { useAgUiRuntime } from "@assistant-ui/react-ag-ui";

import { Thread } from "@/components/assistant-ui/elements/thread.aui";
import { ThreadIdContext } from "@/components/composer-chrome";
import { THREAD_COMPONENTS } from "@/components/message-parts";
import {
  RIGHT_PANE_ID,
  RightPaneBackButton,
  RightPaneCollapseButton,
} from "@/components/right-pane-toggle";
import { type SubagentView } from "@/components/subagent-view-context";
import { FollowAgent, followUrl } from "@/lib/follow";

/// The panel, one delegation at a time. `view` is the single value the page holds
/// (ticket 05: opening another one REPLACES it), so this component has no state of
/// its own about which subagent it is showing.
export const SubagentViewPanel: FC<{
  view: SubagentView;
  /// Closes the WHOLE column (the header's leading control).
  onClose: () => void;
  /// AND BACK TO THE TASK LIST: the trailing control leaves the mirror for the list, not
  /// for a closed column -- the two controls are two verbs, and ticket 03 is where the
  /// second one landed.
  onBack: () => void;
}> = ({ view, onClose, onBack }) => {
  const { t } = useTranslation();
  const [failure, setFailure] = useState<string | null>(null);

  /// A NEW AGENT PER CHILD, and the panel is remounted on a switch (`key` below), so
  /// this memo has one id for its whole life: two subagents must never share a
  /// transport, and a host that outlived its delegation would keep a subscription
  /// nobody is reading (the spec refuses exactly that).
  const agent = useMemo(() => new FollowAgent({ url: followUrl(view.threadId) }), [view.threadId]);

  const runtime = useAgUiRuntime({
    agent,
    adapters: { threadList: { threadId: view.threadId } },
    onError: (error) => setFailure(error.message),
  });

  /// OPEN THE CHANNEL. `startRun` is the runtime's own "run again, from this point"
  /// -- with no message and no parent for it to hang off, it appends nothing and
  /// simply runs the agent, which is what the panel wants: a run that is a read.
  ///
  /// A REFUSAL IS DRAWN RATHER THAN THROWN. The one that reaches here is a thread
  /// whose follow channel answered 404 (a delegation deleted by hand) or a server
  /// that is not answering; in both cases the panel has a header, a name and a
  /// sentence, which is more use than an unmounted panel.
  useEffect(() => {
    let live = true;
    void (async () => {
      try {
        await runtime.thread.startRun({ parentId: null, sourceId: null, runConfig: {} });
      } catch (error: unknown) {
        if (live) setFailure(error instanceof Error ? error.message : String(error));
      }
    })();
    return () => {
      live = false;
      // CLOSING IS A HANG-UP, and it has to be: this is the one connection the
      // panel owns, and a panel that left it running would hold a subscription on
      // the server for a conversation nobody is looking at. The abort is what the
      // server's `on-close` sees, and it is the ONLY way this panel ever ends a
      // live stream -- a finished delegation has already been closed by the route's
      // terminal frame. Measured in a browser: closing on a subagent that was still
      // working failed the in-flight `GET .../follow` with `net::ERR_ABORTED`, and
      // the delegation itself kept running (it belongs to the parent's tool call,
      // not to this panel). In a dev build React's StrictMode mounts the effect
      // twice, so two connections can be open for a moment; both go through here.
      agent.abortRun();
    };
  }, [runtime, agent]);

  return (
    <aside
      id={RIGHT_PANE_ID}
      data-slot="subagent-view"
      aria-label={t("subagentView.title", { name: view.subagent })}
      // A THIRD `shrink-0` CHILD of the page's flex row, with a fixed width: the main
      // column keeps `min-w-0 flex-1` and gives up exactly this much, which is what
      // "side by side" means here (ticket 05). It is a column of its own rather than
      // an overlay, so the conversation stays readable while a subagent works.
      className="bg-background hidden w-[26rem] shrink-0 flex-col border-s md:flex"
    >
      <header className="flex h-12 shrink-0 items-center gap-2 border-b px-3">
        {/* THE WAY OUT OF THE WHOLE COLUMN, at the leading edge of its own header. The X that
            used to sit at the trailing end here is the SAME VERB as this control -- close the
            column -- and one verb does not stand in one row twice, so it left (see
            `components/right-pane-toggle.tsx` for the pair and where each of the two now lives).
            The trailing end is the mirror's way back to the list, which ticket 03 put there. */}
        <RightPaneCollapseButton onCollapse={onClose} />
        {/* WHO THIS IS. The card in the transcript says it too, but the panel can be
            open long after that card scrolled away, and a mirror with no name on it
            is a second conversation nobody can place. */}
        <span
          data-slot="subagent-view-name"
          className="min-w-0 flex-1 truncate text-sm font-medium"
        >
          {t("subagentView.title", { name: view.subagent })}
        </span>
        {/* THE WAY BACK TO THE LIST, at the trailing end of this row -- the end ticket 01
            reserved for it, and the control is the shared one beside the collapse. */}
        <RightPaneBackButton onBack={onBack} />
      </header>

      {failure !== null && (
        <p
          role="alert"
          data-slot="subagent-view-error"
          className="text-destructive border-b px-3 py-2 text-xs break-words"
        >
          {failure}
        </p>
      )}

      {/* `min-h-0` because this is the scrolling region inside a flex column: without
          it the transcript's content sets the column's minimum height and the panel
          grows past the viewport instead of scrolling inside it. */}
      <div className="flex min-h-0 flex-1 flex-col">
        <ThreadIdContext.Provider value={view.threadId}>
          <AssistantRuntimeProvider runtime={runtime}>
            {/* NO COMPOSER, NOT A DISABLED ONE (ticket 05). `autoFocus` is off for
                the same reason: the panel is something being watched, and stealing
                the caret from the main composer would be the panel claiming the
                conversation can be talked to. */}
            <Thread components={THREAD_COMPONENTS} autoFocus={false} composer={false} />
          </AssistantRuntimeProvider>
        </ThreadIdContext.Provider>
      </div>

    </aside>
  );
};
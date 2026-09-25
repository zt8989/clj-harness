"use client";

// LOCAL: this copied file is TRANSLATED IN PLACE. Upstream's own words -- the
// welcome heading, the composer's placeholder, the button tooltips and their
// `sr-only` twins, "Loading conversation", the action bar's Copy / Refresh / More /
// Export as Markdown, the branch picker's Previous / Next, the edit composer's
// Cancel / Update -- are gone from this file and read from the `elements-thread`
// catalog instead (spec decision 5, which reverses flat-step-rows decision 9's
// "leave the copies untouched"). A half-English, half-Chinese page is the thing
// this feature exists to remove, and this file's sentences sit at the very front
// of it. The cost, written down: this file is no longer byte-comparable with
// upstream, so each deliberate edit below is marked `LOCAL:` -- a marker says
// "this was changed on purpose", not "this is what upstream changed". Every
// non-word byte -- `data-slot`, class names, upstream identifiers -- is untouched.

import {
  ComposerAddAttachment,
  ComposerAttachments,
  UserMessageAttachments,
} from "@/components/assistant-ui/elements/attachment.aui";
import { File } from "@/components/assistant-ui/elements/file";
import { ThreadFollowupSuggestions } from "@/components/assistant-ui/elements/follow-up-suggestions.aui";
import { Image } from "@/components/assistant-ui/elements/image";
import { MarkdownText } from "@/components/assistant-ui/elements/markdown-text";
import {
  Reasoning,
  ReasoningContent,
  ReasoningRoot,
  ReasoningText,
  ReasoningTrigger,
} from "@/components/assistant-ui/elements/reasoning.aui";
import { ToolFallback } from "@/components/assistant-ui/elements/tool-fallback.aui";
import {
  ToolGroupContent,
  ToolGroupRoot,
  ToolGroupTrigger,
} from "@/components/assistant-ui/elements/tool-group.aui";
import { TooltipIconButton } from "@/components/assistant-ui/elements/tooltip-icon-button";
import { TurnStepsTrigger, useFoldedAnswer, useStepFold, useTurnFolded } from "@/components/turn-steps";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
// LOCAL (ticket 06): the window's top, and the scroll container it anchors against.
import { WindowTop, type WindowTopProps } from "@/components/window-top";
// LOCAL (ticket 02): the test that tells an opening entry (a `user` message that is
// only a card) from something a person typed.
import { InjectionCard } from "@/components/context-card";
import { isCardOnly, isOpeningEntryId, textOfParts } from "@/lib/injections";
import { cn } from "@/lib/utils";
// LOCAL (ticket 09): the server's own word for this conversation's run. The composer's
// action row reads it to decide whether Send is even on offer -- see `ComposerAction`.
import { SessionRunContext } from "@/components/session-run-state";
// LOCAL (ticket 02 of `.scratch/refreshed-turn-keeps-growing`): the criterion the action bar
// shares with the composer -- this page's run OR the server's word. See `AssistantActionBar`.
import { wearsWorkingDot, stillBeingWritten } from "@/lib/session-status";
import { registerViewport } from "@/lib/window-scroll";
import {
  ActionBarMorePrimitive,
  ActionBarPrimitive,
  AuiIf,
  type AssistantState,
  BranchPickerPrimitive,
  ComposerPrimitive,
  ErrorPrimitive,
  groupPartByType,
  MessagePrimitive,
  SuggestionPrimitive,
  ThreadPrimitive,
  type FileMessagePartComponent,
  type ImageMessagePartComponent,
  type ToolCallMessagePartComponent,
  useAuiState,
} from "@assistant-ui/react";
import {
  ArrowDownIcon,
  ArrowUpIcon,
  CheckIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  CopyIcon,
  DownloadIcon,
  MicIcon,
  MoreHorizontalIcon,
  PencilIcon,
  RefreshCwIcon,
  SquareIcon,
} from "lucide-react";
import {
  createContext,
  useContext,
  type ComponentType,
  type FC,
  type PropsWithChildren,
} from "react";
import { useTranslation } from "react-i18next";

export type ThreadGroupPart = MessagePrimitive.GroupedParts.GroupPart;

/**
 * Optional component overrides for the thread. `AssistantMessage` and
 * `Welcome` replace whole sections; the remaining slots override how the
 * assistant message renders tool calls and part groups. Tool UIs registered
 * by name (toolkit `render`, `useAssistantDataUI`) take precedence over
 * `ToolFallback`.
 */
export type ThreadComponents = {
  AssistantMessage?: ComponentType | undefined;
  Welcome?: ComponentType | undefined;
  // LOCAL: the three insertion points the composer chrome needs. `ComposerFrame`
  // wraps the composer, so a caller can put something ABOVE it inside the same
  // rounded container; `ComposerTools` renders inside the composer's own action
  // row, on the right; `ComposerAddAttachment` replaces the attach button in that
  // same row, for the one state upstream has no opinion about -- a session whose
  // model does not take images. Upstream has none of the three, and a composer
  // that can only be replaced wholesale would have meant rewriting this file
  // rather than adding seams to it -- see composer-chrome.tsx.
  ComposerFrame?: ComponentType<PropsWithChildren> | undefined;
  ComposerTools?: ComponentType | undefined;
  ComposerAddAttachment?: ComponentType | undefined;
  // LOCAL (ticket 09 of `.scratch/session-after-refresh`): what stands in the send
  // button's place while the SERVER says this conversation's run is going. The page
  // supplies it (`App`, which holds the thread id), because stopping a run this page is
  // not driving is a request to the server -- and this element has no address to aim one
  // at. Absent means nothing is drawn, which is what a page with no way to stop should
  // look like: no button, rather than a button that does nothing.
  ComposerStop?: ComponentType | undefined;
  ToolFallback?: ToolCallMessagePartComponent | undefined;
  ToolGroup?:
    | ComponentType<PropsWithChildren<{ group: ThreadGroupPart }>>
    | undefined;
  ReasoningGroup?:
    | ComponentType<PropsWithChildren<{ group: ThreadGroupPart }>>
    | undefined;
};

export type ThreadProps = {
  components?: ThreadComponents | undefined;
  autoFocus?: boolean | undefined;
  /// LOCAL (subagent-view ticket 05): WHETHER THIS THREAD HAS A COMPOSER AT ALL.
  /// `false` is the MIRROR -- the right-hand panel, which watches a subagent work
  /// and cannot talk to it -- and it is a switch here rather than a forked element
  /// for the reason the other optional props exist (`Welcome`, `ComposerFrame`,
  /// `ComposerTools`, `ComposerAddAttachment`): a second copy of this file would
  /// start drifting from this one the day either changed, and the message
  /// rendering is exactly what the two sides must agree about.
  ///
  /// NO COMPOSER, NOT A DISABLED ONE. `false` does not render the footer's
  /// composer, its chrome, or any of the states that say "this conversation could
  /// be spoken to but is shut" -- those belong to a composer that exists.
  composer?: boolean | undefined;
  // LOCAL (ticket 06): the window's top, drawn inside the viewport above the messages.
  // The page hands it down because the window belongs to the SESSION and this file is
  // the conversation's own furniture; `null` -- every session read through the sidebar,
  // and every page whose log had to be repaired -- draws nothing at all.
  window?: WindowTopProps | null | undefined;
};

const EMPTY_COMPONENTS: ThreadComponents = {};

// LOCAL: what the frame slot does when nobody overrides it. A passthrough, so
// the default rendering of this file is byte-for-byte upstream's.
const PassthroughFrame: FC<PropsWithChildren> = ({ children }) => <>{children}</>;

const ThreadComponentsContext =
  createContext<ThreadComponents>(EMPTY_COMPONENTS);

// Startup exposes a loading placeholder thread; treat it as a new chat so
// the composer mounts centered. Loads after startup keep the docked layout.
const isNewChatView = (s: AssistantState) =>
  s.thread.messages.length === 0 &&
  (!s.thread.isLoading || s.threads.isLoading);

// A switched thread that is still fetching its history: skeleton, not welcome.
const isHistoryLoadingView = (s: AssistantState) =>
  s.thread.messages.length === 0 &&
  s.thread.isLoading &&
  !s.thread.isDisabled &&
  !s.threads.isLoading;

// LOCAL: a TURN, as two questions about one message. One ReAct turn is several
// assistant messages -- the AG-UI adapter opens a new one for every LLM round,
// because one TEXT_MESSAGE per assistant message is what the wire says -- and
// the steps of a turn are therefore adjacent assistant messages, while two turns
// are separated by the user message that started the second one. `isTurnEnd` is
// "nothing of mine follows" (what the action bar is keyed on), and `isStepAfter` is
// "the thing before me was a step of this conversation too" (what the step spacing is
// keyed on); both are answered by the neighbours in the thread's own message list.
//
// Upstream never asks either question, because upstream's `AssistantMessage` is
// written for a runtime whose turns are single messages.
const isTurnEnd = (s: AssistantState) =>
  s.thread.messages[s.message.index + 1]?.role !== "assistant";

// LOCAL: IS THE MESSAGE BEFORE THIS ONE A STEP of the conversation -- a thing the RUN
// did, rather than the person asking? Two kinds of message answer yes: an assistant
// message, and an injected-context card (a card the session was born with, or one the
// rebuild made its own message; see `components/context-card.tsx`). Everything else -- a
// person's message -- is the turn boundary the message group's own `gap-y-6` is for.
//
// A CARD IS A STEP AND NOT A TURN. It says what the model was handed, which is the same
// kind of fact as what a tool call came back with, and a reader meets both in one list: a
// row of `bash`, a row of `思考`, a row of `注入的上下文`. So it takes the same spacing as
// the rows around it -- `STEP_SPACING` below -- instead of a turn's.
//
// Answered from the neighbours in the thread's own message list rather than from a field
// somebody has to keep in step, for the reason `isTurnEnd` gives.
const isStepAfter = (s: AssistantState) => {
  const previous = s.thread.messages[s.message.index - 1];
  if (previous === undefined) return false;
  return (
    previous.role === "assistant" ||
    isCardOnly(previous.parts) ||
    isOpeningEntryId(previous.id)
  );
};

// LOCAL: WHAT TWO ADJACENT STEPS ARE SEPARATED BY, spelled once because three places draw
// one: an assistant message, an injected-context card, and the rows inside them.
//
// THE ARITHMETIC. The message group spaces its children by `gap-y-6` (24px), and every step
// row carries its own `py-1.5` (6px each side). Two rows inside ONE message are therefore
// 12px apart -- the rows' own padding and nothing else, which is the rule
// `.scratch/flat-step-rows/spec.md` states and `message-parts.tsx` repeats. Two steps in
// DIFFERENT messages get that 24px gap on top of it unless the message cancels it, and
// `-mt-6` cancels ALL of it (it was `-mt-4`, which cancelled 16 of the 24 -- so a card, or a
// step of a turn, sat 8px further from its neighbour than the row inside the same message
// did, and a card the session was born with sat a whole turn's 24px away). Now every pair of
// adjacent steps is the same 12px, whatever kind of step they are and whichever message
// each one landed in.
const STEP_SPACING = "-mt-6";

// LOCAL: upstream's literal "Loading conversation" is gone from this file and read
// from the `elements-thread` catalog instead. It is the status line a screen reader
// announces while history is fetched, and screen-reader text is copy like any other.
const ThreadHistorySkeleton: FC = () => {
  const { t } = useTranslation("elements-thread");
  return (
    <div
      data-slot="aui_thread-history-skeleton"
      role="status"
      className="animate-in fade-in fill-mode-both flex flex-col gap-y-6 [animation-delay:150ms] [animation-duration:200ms]"
    >
      <span className="sr-only">{t("history.loading")}</span>
      <Skeleton className="ml-auto h-9 w-2/5 rounded-xl motion-reduce:animate-none" />
      <div className="flex flex-col gap-y-2">
        <Skeleton className="h-4 w-11/12 motion-reduce:animate-none" />
        <Skeleton className="h-4 w-4/5 motion-reduce:animate-none" />
        <Skeleton className="h-4 w-3/5 motion-reduce:animate-none" />
      </div>
      <Skeleton className="ml-auto h-9 w-1/3 rounded-xl motion-reduce:animate-none" />
      <div className="flex flex-col gap-y-2">
        <Skeleton className="h-4 w-10/12 motion-reduce:animate-none" />
        <Skeleton className="h-4 w-2/3 motion-reduce:animate-none" />
      </div>
    </div>
  );
};

export const Thread: FC<ThreadProps> = ({
  components = EMPTY_COMPONENTS,
  autoFocus = true,
  window = null,
  composer = true,
}) => {
  const isEmpty = useAuiState(isNewChatView);

  return (
    <ThreadComponentsContext.Provider value={components}>
      <ThreadRoot isEmpty={isEmpty} autoFocus={autoFocus} window={window} composer={composer} />
    </ThreadComponentsContext.Provider>
  );
};

const ThreadRoot: FC<{
  isEmpty: boolean;
  autoFocus: boolean;
  window: WindowTopProps | null;
  composer: boolean;
}> = ({ isEmpty, autoFocus, window, composer }) => {
  const { Welcome = ThreadWelcome, ComposerFrame = PassthroughFrame } =
    useContext(ThreadComponentsContext);

  return (
    <ThreadPrimitive.Root
      className="aui-root aui-thread-root bg-background @container flex h-full flex-col"
      style={{
        ["--thread-max-width" as string]: "44rem",
        ["--composer-bg" as string]: "var(--color-card)",
        ["--composer-radius" as string]: "1.5rem",
        ["--composer-padding" as string]: "8px",
      }}
    >
      {/* LOCAL: upstream anchors the viewport to the TOP of the last turn
          (`turnAnchor="top"`), and that attribute does two things at once: it
          keeps the last turn's opening line pinned near the top, and it turns
          auto-scroll OFF (see `useThreadViewportAutoScroll`, where `autoScroll`
          defaults to `turnAnchor !== "top"`). With it, a run's new content grows
          below the fold, nothing follows it, and the scroll-to-bottom button is
          showing from the first line of the run onwards.

          This repo wants upstream's other mode instead, and dropping the
          attribute is how you ask for it: `turnAnchor` is `"bottom"` by default,
          so the viewport follows the run until the reader scrolls up, and that
          button means exactly one thing -- "you have scrolled away from the end,
          click to come back". It hides again the moment the reader is back at
          the bottom, whether by that click or by hand. */}
      <ThreadPrimitive.Viewport
        data-slot="aui_thread-viewport"
        // LOCAL (ticket 06): the scroll container, registered where the window's
        // "show earlier" can anchor against it (`lib/window-scroll.ts`). It is a ref on
        // the viewport rather than a lookup by `data-slot` for the reason the helper
        // writes down: the element a prepend pushes down is THIS one, and a query would
        // need a mounted page to find it.
        ref={registerViewport}
        className="relative flex flex-1 flex-col overflow-x-auto overflow-y-scroll scroll-smooth"
      >
        <div
          className={cn(
            "mx-auto flex w-full max-w-(--thread-max-width) flex-1 flex-col px-4 pt-4",
            isEmpty && "justify-center",
          )}
        >
          {/* LOCAL (subagent-view ticket 05): A MIRROR HAS NOTHING TO WELCOME
              ANYBODY TO. The panel's thread is empty for the moment between mounting
              and the follow channel's first frame, and the new-chat screen would
              flash a greeting over a conversation that already exists -- so the
              opening furniture goes with the composer, under the same switch. */}
          {composer ? (
            <AuiIf condition={isNewChatView}>
              <Welcome />
            </AuiIf>
          ) : null}
          <AuiIf condition={isHistoryLoadingView}>
            <ThreadHistorySkeleton />
          </AuiIf>

          {/* LOCAL (ticket 06): the window's top sits between the skeleton and the
              messages, which is above every message in the conversation -- so a prepend
              grows the content BELOW it and the anchoring works on the messages the
              reader is actually looking at. */}
          {window !== null && <WindowTop {...window} />}

          <div
            data-slot="aui_message-group"
            className="mb-14 flex flex-col gap-y-6 empty:hidden"
          >
            <ThreadPrimitive.Messages>
              {() => <ThreadMessage />}
            </ThreadPrimitive.Messages>
          </div>

          <ThreadPrimitive.ViewportFooter
            className={cn(
              "aui-thread-viewport-footer bg-background flex flex-col gap-4 overflow-visible pb-4 md:pb-6",
              !isEmpty &&
                "sticky bottom-0 mt-auto rounded-t-(--composer-radius)",
            )}
          >
            <ThreadScrollToBottom />
            <ThreadFollowupSuggestions />
            {/* LOCAL: the frame wraps the composer rather than replacing it, so
                the default above still renders exactly what upstream renders.
                LOCAL (subagent-view ticket 05): and `composer={false}` renders
                NEITHER -- see `ThreadProps`. It is the whole of the mirror: what is
                gone is the composer, its frame and its chrome, not a disabled
                version of any of them. */}
            {composer ? (
              <ComposerFrame>
                <Composer autoFocus={autoFocus} />
              </ComposerFrame>
            ) : null}
            {composer ? (
              <AuiIf condition={(s) => isNewChatView(s) && s.composer.isEmpty}>
                <ThreadSuggestions />
              </AuiIf>
            ) : null}
          </ThreadPrimitive.ViewportFooter>
        </div>
      </ThreadPrimitive.Viewport>
    </ThreadPrimitive.Root>
  );
};

const ThreadMessage: FC = () => {
  const { AssistantMessage: AssistantMessageComponent = AssistantMessage } =
    useContext(ThreadComponentsContext);
  const role = useAuiState((s) => s.message.role);
  const isEditing = useAuiState((s) => s.message.composer.isEditing);

  if (isEditing) return <EditComposer />;
  if (role === "user") return <UserMessage />;
  return <AssistantMessageComponent />;
};

// LOCAL: upstream's literal "Scroll to bottom" is gone from this file and read from
// the `elements-thread` catalog instead -- the tooltip and the `sr-only` text the
// button draws through `TooltipIconButton`.
const ThreadScrollToBottom: FC = () => {
  const { t } = useTranslation("elements-thread");
  return (
    <ThreadPrimitive.ScrollToBottom asChild>
      <TooltipIconButton
        tooltip={t("scroll.toBottom")}
        variant="outline"
        className="aui-thread-scroll-to-bottom dark:border-border dark:bg-background dark:hover:bg-accent absolute -top-12 z-10 self-center rounded-full p-4 disabled:invisible"
      >
        <ArrowDownIcon />
      </TooltipIconButton>
    </ThreadPrimitive.ScrollToBottom>
  );
};

// LOCAL: upstream's welcome heading, "How can I help you today?", is gone from this
// file and read from the `elements-thread` catalog instead. It is the first sentence
// an empty conversation shows, and the one the spec names as the reason this copy
// can no longer stay untouched.
const ThreadWelcome: FC = () => {
  const { t } = useTranslation("elements-thread");
  return (
    <div className="aui-thread-welcome-root mb-6 flex flex-col items-center px-4 text-center">
      <h1 className="aui-thread-welcome-message-inner fade-in slide-in-from-bottom-1 animate-in fill-mode-both text-2xl font-medium tracking-tight duration-200">
        {t("welcome.heading")}
      </h1>
    </div>
  );
};

const ThreadSuggestions: FC = () => {
  return (
    <div className="aui-thread-welcome-suggestions flex w-full flex-wrap items-center justify-center gap-2 px-4">
      <ThreadPrimitive.Suggestions>
        {() => <ThreadSuggestionItem />}
      </ThreadPrimitive.Suggestions>
    </div>
  );
};

const ThreadSuggestionItem: FC = () => {
  return (
    <div className="aui-thread-welcome-suggestion-display fade-in slide-in-from-bottom-2 animate-in fill-mode-both duration-200">
      <SuggestionPrimitive.Trigger send asChild>
        <Button
          variant="ghost"
          className="aui-thread-welcome-suggestion text-foreground hover:bg-muted border-border/60 h-auto gap-1.5 rounded-full border px-3.5 py-1.5 text-sm font-normal whitespace-nowrap transition-colors"
        >
          <SuggestionPrimitive.Title className="aui-thread-welcome-suggestion-text-1" />
          <SuggestionPrimitive.Description className="aui-thread-welcome-suggestion-text-2 empty:hidden" />
        </Button>
      </SuggestionPrimitive.Trigger>
    </div>
  );
};

// LOCAL: upstream's composer placeholder ("Send a message...") and its input's
// `aria-label` ("Message input") are gone from this file and read from the
// `elements-thread` catalog instead. The placeholder is the interface's own
// instruction, not a model word, so it is copy and follows the language.
const Composer: FC<{ autoFocus: boolean }> = ({ autoFocus }) => {
  const { t } = useTranslation("elements-thread");
  return (
    <ComposerPrimitive.Root className="aui-composer-root relative flex w-full flex-col">
      <ComposerPrimitive.AttachmentDropzone asChild>
        <div
          data-slot="aui_composer-shell"
          className="border-border/60 data-[dragging=true]:border-ring focus-within:border-border dark:border-muted-foreground/15 dark:focus-within:border-muted-foreground/30 flex w-full cursor-text flex-col gap-2 rounded-(--composer-radius) border bg-(--composer-bg) p-(--composer-padding) transition-[border-color] data-[dragging=true]:border-dashed data-[dragging=true]:bg-[color-mix(in_oklab,var(--color-accent)_50%,var(--color-background))]"
        >
          <ComposerAttachments />
          <ComposerPrimitive.Input
            placeholder={t("composer.placeholder")}
            className="aui-composer-input caret-primary placeholder:text-muted-foreground/60 max-h-48 min-h-10 w-full resize-none bg-transparent px-2.5 py-1 text-base leading-6 outline-none"
            rows={1}
            autoFocus={autoFocus}
            enterKeyHint="send"
            aria-label={t("composer.inputLabel")}
          />
          <ComposerAction />
        </div>
      </ComposerPrimitive.AttachmentDropzone>
    </ComposerPrimitive.Root>
  );
};

const ComposerAction: FC = () => {
  // LOCAL: whatever the caller wants on the right of the composer's action row,
  // before the dictate and send buttons -- and, on the left, the attach button
  // itself when the caller has a reason to draw it differently.
  const { ComposerTools, ComposerStop, ComposerAddAttachment: Attach = ComposerAddAttachment } =
    useContext(ThreadComponentsContext);
  // LOCAL (ticket 09 of `.scratch/session-after-refresh`): WHICH RUN THE SEND BUTTON IS
  // ABOUT. Upstream only knows whether THIS PAGE is running a turn (`s.thread.isRunning`);
  // a run belongs to the PROCESS, so a page that opened somebody else's running
  // conversation has to close the same door on the SERVER's word -- which is what the
  // context carries (`App`'s `runState`, off the window's feed). The row then draws, in
  // the send button's place, the Stop the CALLER supplies: this file does not know what
  // stopping costs (a request, a signal, a wait), and the one thing every case shares is
  // that Send is not it.
  const runState = useContext(SessionRunContext);
  const ownRunning = useAuiState((s) => s.thread.isRunning);
  const serverRunning = runState === "running";
  // LOCAL: upstream's literal tooltips and `aria-label`s for the dictation and send
  // buttons -- "Voice input", "Start voice input", "Stop dictation", "Stop voice
  // input", "Send message" (twice: `tooltip` and the send button's `aria-label`) and
  // "Stop generating" -- are gone from this file and read from the `elements-thread`
  // catalog instead. Tooltips and `aria-label` values are copy, so they follow the
  // language even though they are not drawn as text.
  const { t } = useTranslation("elements-thread");

  return (
    <div className="aui-composer-action-wrapper relative flex items-center justify-between">
      <Attach />
      <div className="flex items-center gap-1.5">
        {/* LOCAL: the caller's tools, left of dictate and send. */}
        {ComposerTools !== undefined && <ComposerTools />}
        <AuiIf condition={(s) => s.thread.capabilities.dictation}>
          <AuiIf condition={(s) => s.composer.dictation == null}>
            <ComposerPrimitive.Dictate asChild>
              <TooltipIconButton
                tooltip={t("composer.voiceInput")}
                side="bottom"
                type="button"
                variant="ghost"
                size="icon"
                className="aui-composer-dictate text-muted-foreground hover:text-foreground size-7 rounded-full"
                aria-label={t("composer.voiceInputStart")}
              >
                <MicIcon className="aui-composer-dictate-icon size-4" />
              </TooltipIconButton>
            </ComposerPrimitive.Dictate>
          </AuiIf>
          <AuiIf condition={(s) => s.composer.dictation != null}>
            <ComposerPrimitive.StopDictation asChild>
              <TooltipIconButton
                tooltip={t("composer.dictationStop")}
                side="bottom"
                type="button"
                variant="ghost"
                size="icon"
                className="aui-composer-stop-dictation text-destructive size-7 rounded-full"
                aria-label={t("composer.dictationStopLabel")}
              >
                <SquareIcon className="aui-composer-stop-dictation-icon size-3.5 animate-pulse fill-current" />
              </TooltipIconButton>
            </ComposerPrimitive.StopDictation>
          </AuiIf>
        </AuiIf>
        {/* LOCAL (ticket 09): SEND IS DRAWN ONLY WHEN NOBODY IS ANSWERING THIS
            CONVERSATION -- not this page, and not the process. While the server says
            `running`, the send button's place is the caller's STOP: Send against the
            server's own word is a button whose only answer is the run edge's 409. */}
        {!ownRunning && !serverRunning && (
          <ComposerPrimitive.Send asChild>
            <TooltipIconButton
              tooltip={t("composer.send")}
              side="bottom"
              type="button"
              variant="default"
              size="icon"
              className="aui-composer-send size-7 rounded-full"
              aria-label={t("composer.send")}
            >
              <ArrowUpIcon className="aui-composer-send-icon size-4" />
            </TooltipIconButton>
          </ComposerPrimitive.Send>
        )}
        {/* LOCAL (ticket 09): THE STOP IS THE SERVER'S, in BOTH the cases above -- this
            page's own run and a run the process is answering for somebody else. What used
            to stand here is upstream's `ComposerPrimitive.Cancel`, which ABORTS THIS
            PAGE'S FETCH and does nothing at all on the other side (the run keeps going
            and the record keeps growing); the caller's stop asks the server instead, and
            a page driving the run gets the terminal it produces on this same stream. */}
        {/* THE CALLER'S STOP, IN BOTH OF THOSE CASES. The component is the page's because
            stopping is a request to the server, and this element has no address of its own
            to aim one at (`App` holds the thread id). */}
        {(ownRunning || serverRunning) && ComposerStop !== undefined && <ComposerStop />}
      </div>
    </div>
  );
};

const MessageError: FC = () => {
  return (
    <MessagePrimitive.Error>
      <ErrorPrimitive.Root className="aui-message-error-root border-destructive bg-destructive/10 text-destructive dark:bg-destructive/5 mt-2 rounded-md border p-3 text-sm dark:text-red-200">
        <ErrorPrimitive.Message className="aui-message-error-message line-clamp-2" />
      </ErrorPrimitive.Root>
    </MessagePrimitive.Error>
  );
};

const AssistantMessage: FC = () => {
  const {
    ToolFallback: ToolFallbackComponent = ToolFallback,
    ToolGroup,
    ReasoningGroup,
  } = useContext(ThreadComponentsContext);

  // LOCAL: upstream's `aria-label` "Assistant is working" on the streaming indicator
  // is gone from this file and read from the `elements-thread` catalog instead. It is
  // never drawn -- the dot is -- but it is what a screen reader announces, so it is
  // copy.
  const { t } = useTranslation("elements-thread");

  // LOCAL: the two neighbours, read off the thread's message list (see `isTurnEnd` and
  // `isStepAfter`). `continues` tightens the gap ABOVE this message so a turn's steps
  // read as one answer rather than as four separate ones: the message group's `gap-y-6`
  // stays for the space between turns, and `STEP_SPACING` cancels the whole of it after
  // a step -- which is what makes two steps in two messages the same 12px apart as two
  // rows in one.
  const turnEnd = useAuiState(isTurnEnd);
  const continues = useAuiState(isStepAfter);

  // LOCAL (ticket 02 of `.scratch/refreshed-turn-keeps-growing`): WHO IS WRITING THIS TURN,
  // read here because the footer draws ONE OF TWO things with it -- the sign that the turn is
  // still arriving, or the furniture that says it has stopped. See `stillBeingWritten`.
  const runState = useContext(SessionRunContext);
  const ownRunning = useAuiState((s) => s.thread.isRunning);
  const writing = stillBeingWritten(ownRunning, runState);
  // ...AND ONLY THE LIVE TURN WEARS THE DOT: `writing` is a fact about the CONVERSATION, and the
  // footer is drawn at EVERY turn end, so the two facts are kept apart by `wearsWorkingDot` --
  // without the last-message half a settled turn's end wore a dot too the moment the next
  // message was sent (owner's third report, 2026-09-25: two dots, one per turn end).
  const lastMessage = useAuiState((s) => s.message.isLast);

  // LOCAL: the fold. A turn that has SETTLED puts its steps away -- every message
  // of it except the answer, which stays where it is -- and its first message
  // draws the one-line summary of what went away. `fold === "step"` is this whole
  // message being put away; `fold === "head"` is this one drawing the summary and
  // having its own content put away, and `folded` says whether the reader has it
  // open. Everything behind those three values is `components/turn-steps.tsx`,
  // which is ours: the boundary of a turn and the arithmetic behind the summary
  // line are in `lib/turns.ts`. `fold === "none"` means "draw this message exactly
  // as this file always did".
  // `fold === "answer"` and `foldedAnswer` are the folded turn's CONCLUSION: the one
  // message a folded turn still shows, and only what it SAID -- its reasoning and tool
  // calls are steps like the rest, and the fold puts those away (see `useStepFold`).
  const fold = useStepFold();
  const folded = useTurnFolded();
  const foldedAnswer = useFoldedAnswer();

  const ACTION_BAR_PT = "pt-1.5";
  // Keep the action bar inside the contained root's paint box, then cancel its reserved space in flow.
  const ACTION_BAR_HEIGHT = `min-h-7.5 ${ACTION_BAR_PT}`;

  return (
    <MessagePrimitive.Root
      data-slot="aui_assistant-message-root"
      data-role="assistant"
      data-fold={fold}
      className={cn(
        "fade-in slide-in-from-bottom-1 animate-in relative -mb-7.5 pb-7.5 duration-150 [contain-intrinsic-size:auto_200px] [content-visibility:auto]",
        continues && STEP_SPACING,
        fold === "step" && "hidden",
      )}
    >
      {/* LOCAL: the summary line a folded turn leaves behind -- what it did and the
          way back in. It is drawn by the turn's FIRST message, because that is
          where a reader meets the turn; the steps it hides are its siblings. */}
      {fold === "head" ? <TurnStepsTrigger /> : null}
      <div
        data-slot="aui_assistant-message-content"
        className={cn(
          "text-foreground px-2 leading-relaxed wrap-break-word",
          fold === "head" && folded && !foldedAnswer && "hidden",
        )}
      >
        <MessagePrimitive.GroupedParts
          groupBy={groupPartByType({
            reasoning: ["group-chainOfThought", "group-reasoning"],
            "tool-call": ["group-chainOfThought", "group-tool"],
            "standalone-tool-call": [],
          })}
          // LOCAL (ticket 02 of `.scratch/refreshed-turn-keeps-growing`): THE "STILL WORKING"
          // DOT IS DRAWN IN ONE PLACE, AND IT IS THE TURN'S END (the footer below). Upstream's
          // own indicator -- a synthetic `indicator` part it appends itself, `indicator:
          // "no-text"` -- puts a second dot in the MESSAGE BODY whenever the last part is not
          // text or reasoning (an empty message, a tool call, and -- while a step is being
          // handed over -- two messages at once). Two dots for one state is what the owner
          // reported twice; the sign a reader needs one of is 'this turn is still arriving',
          // and that is the turn's own end. `never` is upstream's switch for it, and the
          // footer answers it instead (`stillBeingWritten`).
          indicator="never"
        >
          {({ part, children }) => {
            // A FOLDED ANSWER KEEPS ONLY WHAT WAS SAID. Reasoning and tool calls are
            // the steps that produced the answer, and a folded turn puts the steps away
            // -- the answer message's own included. `foldedAnswer` is true for exactly
            // that message while the turn is folded, and for nothing else.
            if (
              foldedAnswer &&
              (part.type === "group-chainOfThought" ||
                part.type === "group-reasoning" ||
                part.type === "group-tool" ||
                part.type === "reasoning" ||
                part.type === "tool-call" ||
                part.type === "indicator")
            ) {
              return null;
            }
            switch (part.type) {
              case "group-chainOfThought":
                return <div data-slot="aui_chain-of-thought">{children}</div>;
              case "group-tool":
                if (ToolGroup) {
                  return <ToolGroup group={part}>{children}</ToolGroup>;
                }
                return (
                  <ToolGroupRoot variant="ghost">
                    <ToolGroupTrigger
                      count={part.indices.length}
                      active={part.status.type === "running"}
                    />
                    <ToolGroupContent>{children}</ToolGroupContent>
                  </ToolGroupRoot>
                );
              case "group-reasoning": {
                if (ReasoningGroup) {
                  return (
                    <ReasoningGroup group={part}>{children}</ReasoningGroup>
                  );
                }
                const running = part.status.type === "running";
                return (
                  <ReasoningRoot streaming={running}>
                    <ReasoningTrigger active={running} />
                    <ReasoningContent aria-busy={running}>
                      <ReasoningText>{children}</ReasoningText>
                    </ReasoningContent>
                  </ReasoningRoot>
                );
              }
              case "text":
                return <MarkdownText />;
              case "reasoning":
                return <Reasoning {...part} />;
              case "tool-call":
                return part.toolUI ?? <ToolFallbackComponent {...part} />;
              case "data":
                return part.dataRendererUI;
              case "file":
                return (
                  <div data-slot="aui_assistant-message-file" className="py-1">
                    <File {...part} />
                  </div>
                );
              case "image":
                return (
                  <div data-slot="aui_assistant-message-image" className="py-1">
                    <Image {...part} />
                  </div>
                );
              case "indicator":
                return (
                  <span
                    data-slot="aui_assistant-message-indicator"
                    className="animate-pulse font-sans"
                    aria-label={t("message.working")}
                  >
                    {"●"}
                  </span>
                );
              default:
                return null;
            }
          }}
        </MessagePrimitive.GroupedParts>
        <MessageError />
      </div>

      <div
        data-slot="aui_assistant-message-footer"
        className={cn("ms-2 flex items-center", turnEnd && ACTION_BAR_HEIGHT)}
      >
        <BranchPicker />
        {/* LOCAL: the action bar belongs to the TURN, not to each step of it. A
            ReAct turn is several assistant messages (see `isTurnEnd`), and
            upstream draws one bar per message -- so a turn that thought, read
            and answered showed three sets of Copy / Refresh / More, two of them
            under a fragment of the answer. Copying a turn is copying the answer;
            the steps are not separately copyable things. Drawing the footer only
            at the end of a turn is the whole fix, and it keeps the buttons where
            the reader last looked.

            One consequence worth knowing: `ActionBarPrimitive.Reload` regenerates
            THIS message, which is now the turn's last one -- the answer, which is
            what "regenerate" means to a reader. */}
        <AuiIf condition={isTurnEnd}>
          {wearsWorkingDot(writing, lastMessage) ? <WorkingDot /> : <AssistantActionBar />}
        </AuiIf>
      </div>
    </MessagePrimitive.Root>
  );
};

/// LOCAL (ticket 02 of `.scratch/refreshed-turn-keeps-growing`): THE SIGN THAT THIS TURN IS
/// STILL ARRIVING, drawn where the action bar will take over.
///
/// IT IS THE DOT UPSTREAM ALREADY DRAWS while an assistant message has no parts yet (the
/// `indicator` part `MessagePrimitive.Parts` adds): the same glyph, the same pulse, the same
/// `aria-label`, so one state has one sign. What upstream cannot draw is that dot at the END
/// of a turn that already has parts -- and that is exactly what a reload lands in: the answer
/// is on screen, still growing, and the turn's furniture (Copy / Refresh / More) has not been
/// earned yet. Before this, the row was simply EMPTY, which reads as 'it is done' -- the same
/// mistake the action bar made, made by saying nothing.
const WorkingDot: FC = () => {
  const { t } = useTranslation("elements-thread");
  return (
    <span
      data-slot="aui_assistant-message-indicator"
      className="animate-pulse font-sans"
      aria-label={t("message.working")}
    >
      {"●"}
    </span>
  );
};

const AssistantActionBar: FC = () => {
  // LOCAL: upstream's action-bar literals -- the Copy, Refresh and More tooltips and
  // the "Export as Markdown" menu item -- are gone from this file and read from the
  // `elements-thread` catalog instead. Tooltips are copy, and the menu item is drawn.
  //
  // LOCAL (ticket 02 of `.scratch/refreshed-turn-keeps-growing`): WHO IS WRITING THIS TURN.
  // A turn that is still arriving must not wear Copy / Refresh / More: a reader takes those
  // for 'this is finished', and a reload in the middle of somebody else's turn is exactly
  // where they used to appear (measured in a browser, 2026-09-25).
  //
  // THE CHOICE IS THE FOOTER'S (`AssistantMessage`: `WorkingDot` while it is being written,
  // this bar afterwards), rather than upstream's `hideWhenRunning`, and that is the whole
  // tuning: that prop is ANDed with the RUNTIME's own `isRunning` -- `if (hideWhenRunning &&
  // s.thread.isRunning) return Hidden` -- and a run this page is only WATCHING is precisely
  // the case where the runtime says false, so passing `true` hid nothing (measured:
  // `hideWhenRunning={true}` with `thread.isRunning === false` drew the bar). The prop stays
  // for the runtime's own case, which is the one it can speak about.
  const { t } = useTranslation("elements-thread");
  return (
    <ActionBarPrimitive.Root
      hideWhenRunning
      autohide="not-last"
      className="aui-assistant-action-bar-root text-muted-foreground animate-in fade-in col-start-3 row-start-2 -ms-1 flex gap-1 duration-200"
    >
      <ActionBarPrimitive.Copy asChild>
        <TooltipIconButton tooltip={t("message.copy")}>
          <AuiIf condition={(s) => s.message.isCopied}>
            <CheckIcon className="animate-in zoom-in-50 fade-in duration-200 ease-out" />
          </AuiIf>
          <AuiIf condition={(s) => !s.message.isCopied}>
            <CopyIcon className="animate-in zoom-in-75 fade-in duration-150" />
          </AuiIf>
        </TooltipIconButton>
      </ActionBarPrimitive.Copy>
      <ActionBarPrimitive.Reload asChild>
        <TooltipIconButton tooltip={t("message.refresh")}>
          <RefreshCwIcon />
        </TooltipIconButton>
      </ActionBarPrimitive.Reload>
      <ActionBarMorePrimitive.Root>
        <ActionBarMorePrimitive.Trigger asChild>
          <TooltipIconButton
            tooltip={t("message.more")}
            className="data-[state=open]:bg-accent"
          >
            <MoreHorizontalIcon />
          </TooltipIconButton>
        </ActionBarMorePrimitive.Trigger>
        <ActionBarMorePrimitive.Content
          side="bottom"
          align="start"
          sideOffset={6}
          className="aui-action-bar-more-content bg-popover text-popover-foreground data-[state=open]:fade-in-0 data-[state=open]:zoom-in-95 data-[state=open]:animate-in data-[state=closed]:fade-out-0 data-[state=closed]:zoom-out-95 data-[state=closed]:animate-out data-[side=bottom]:slide-in-from-top-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2 z-50 min-w-[8rem] overflow-hidden rounded-xl border p-1.5"
        >
          <ActionBarPrimitive.ExportMarkdown asChild>
            <ActionBarMorePrimitive.Item className="aui-action-bar-more-item hover:bg-accent hover:text-accent-foreground focus:bg-accent focus:text-accent-foreground flex cursor-pointer items-center gap-2 rounded-lg px-2.5 py-1.5 text-sm outline-none select-none">
              <DownloadIcon className="size-4" />
              {t("message.exportMarkdown")}
            </ActionBarMorePrimitive.Item>
          </ActionBarPrimitive.ExportMarkdown>
        </ActionBarMorePrimitive.Content>
      </ActionBarMorePrimitive.Root>
    </ActionBarPrimitive.Root>
  );
};

const UserFilePart: FileMessagePartComponent = (part) => (
  <div data-slot="aui_user-message-file" className="py-1">
    <File {...part} />
  </div>
);

const UserImagePart: ImageMessagePartComponent = (part) => (
  <div data-slot="aui_user-message-image" className="py-1">
    <Image {...part} />
  </div>
);

// LOCAL (ticket 02 of `.scratch/session-opening`): a message that is ONLY an
// injected-context card is not a bubble.
//
// The opening's entries are `role: "user"` -- user messages to the provider, which is
// what the record says about them -- so without this branch the thread draws the
// person's AGENTS.md and skills catalog the way it draws anything they typed:
// right-aligned, in a grey bubble, with an Edit pencil beside them. Nobody typed them.
// `isCardOnly` is the test; what the row below renders is the SAME card a run streams
// for its own injections (the assistant path draws it through `dataRendererUI`, and both
// paths land on the one renderer registered in `components/context-card.tsx`), left
// where the model read it. The action bar is gone on purpose: there is nothing here to
// edit or to copy back, because a card is never sent to the server.
//
// LOCAL (2026-09-21): AND SOMETIMES THE CARD PART IS NOT THERE. A message the adapter
// imported from a `MESSAGES_SNAPSHOT` -- which is how a session's birth reaches the page
// that minted it -- keeps its id and its text and loses the `data` part, because
// upstream's snapshot conversion has no case for one. So the card is drawn from the
// message's own text in that case: the server builds the text and the part's value from
// the one block (`harness.edge.ag_ui/opening-entries`), so the row and its numbers are
// the same either way. WHICH CASE IT IS is read off the parts, not off the id: a
// message that carries the part draws it.
// LOCAL: IT IS A STEP, SO IT IS SPACED LIKE ONE. The card says what the model was handed,
// and the rows of `bash` / `思考` / `注入的上下文` are one list -- so a card that came in as its
// own message (a session's opening blocks) tightens the gap above it exactly as a step of a
// turn does (`isStepAfter` / `STEP_SPACING`). It used to keep the message group's whole
// `gap-y-6`, which put a stack of opening cards a turn's 24px apart while the rows inside a
// message sat 12px apart. A card that came in beside rows in the SAME message gets that
// spacing from the message it landed in, and needs nothing here.
//
// AND A PERSON'S MESSAGE IS NOT A STEP: a card drawn under one (the run that answers it
// starts with its own `<instructions>`) keeps the turn gap, because the bubble above it is
// where the previous turn ended.
const UserInjectionCard: FC = () => {
  const cardOnly = useAuiState((s) => isCardOnly(s.message.parts));
  const continues = useAuiState(isStepAfter);
  const text = useAuiState((s) =>
    isCardOnly(s.message.parts) ? "" : textOfParts(s.message.parts),
  );
  return (
    <MessagePrimitive.Root
      data-slot="aui_user-injection-root"
      className={cn(
        "fade-in slide-in-from-bottom-1 animate-in px-2 duration-150 [contain-intrinsic-size:auto_200px] [content-visibility:auto]",
        continues && STEP_SPACING,
      )}
      data-role="user"
    >
      {cardOnly ? (
        <MessagePrimitive.Parts />
      ) : (
        <InjectionCard value={{ role: "user", text }} />
      )}
    </MessagePrimitive.Root>
  );
};

const UserMessage: FC = () => {
  // LOCAL: the branch above. The selectors answer a BOOLEAN (and a string) on purpose --
  // `useAuiState` compares a selector's answer by value, so handing back the parts
  // themselves would re-render this row on every store update.
  //
  // TWO WAYS TO BE A CARD: the message IS only a card (`isCardOnly`), or it is one of the
  // opening entries the server wrote (whose card part this client may never have had).
  const card = useAuiState(
    (s) => isCardOnly(s.message.parts) || isOpeningEntryId(s.message.id),
  );
  if (card) return <UserInjectionCard />;
  return (
    <MessagePrimitive.Root
      data-slot="aui_user-message-root"
      className="fade-in slide-in-from-bottom-1 animate-in grid auto-rows-auto grid-cols-[minmax(72px,1fr)_auto] content-start gap-y-2 px-2 duration-150 [contain-intrinsic-size:auto_200px] [content-visibility:auto] [&:where(>*)]:col-start-2"
      data-role="user"
    >
      <UserMessageAttachments />

      <div className="aui-user-message-content-wrapper relative col-start-2 min-w-0">
        <div className="aui-user-message-content peer bg-muted text-foreground rounded-xl px-4 py-2 wrap-break-word empty:hidden">
          <MessagePrimitive.Parts
            components={{ File: UserFilePart, Image: UserImagePart }}
          />
        </div>
        <div className="aui-user-action-bar-wrapper absolute start-0 top-1/2 -translate-x-full -translate-y-1/2 pe-2 peer-empty:hidden rtl:translate-x-full">
          <UserActionBar />
        </div>
      </div>

      <BranchPicker
        data-slot="aui_user-branch-picker"
        className="col-span-full col-start-1 row-start-3 -me-1 justify-end"
      />
    </MessagePrimitive.Root>
  );
};

const UserActionBar: FC = () => {
  // LOCAL: upstream's "Edit" tooltip is gone from this file and read from the
  // `elements-thread` catalog instead.
  const { t } = useTranslation("elements-thread");
  return (
    <ActionBarPrimitive.Root
      hideWhenRunning
      autohide="not-last"
      className="aui-user-action-bar-root flex flex-col items-end"
    >
      <ActionBarPrimitive.Edit asChild>
        <TooltipIconButton
          tooltip={t("message.edit")}
          className="aui-user-action-edit"
        >
          <PencilIcon />
        </TooltipIconButton>
      </ActionBarPrimitive.Edit>
    </ActionBarPrimitive.Root>
  );
};

const EditComposer: FC = () => {
  // LOCAL: upstream's edit-composer buttons, "Cancel" and "Update", are gone from
  // this file and read from the `elements-thread` catalog instead. They are drawn.
  const { t } = useTranslation("elements-thread");
  return (
    <MessagePrimitive.Root
      data-slot="aui_edit-composer-wrapper"
      className="flex flex-col px-2 [contain-intrinsic-size:auto_200px] [content-visibility:auto]"
    >
      <ComposerPrimitive.Root className="aui-edit-composer-root border-border/60 dark:border-muted-foreground/15 ms-auto flex w-full max-w-[85%] cursor-text flex-col rounded-(--composer-radius) border bg-(--composer-bg)">
        <ComposerPrimitive.Input
          className="aui-edit-composer-input text-foreground min-h-14 w-full resize-none bg-transparent px-4 pt-3 pb-1 text-base outline-none"
          autoFocus
        />
        <div className="aui-edit-composer-footer mx-2.5 mb-2.5 flex items-center gap-1.5 self-end">
          <ComposerPrimitive.Cancel asChild>
            <Button
              variant="ghost"
              size="sm"
              className="h-8 rounded-full px-3.5"
            >
              {t("message.cancel")}
            </Button>
          </ComposerPrimitive.Cancel>
          <ComposerPrimitive.Send asChild>
            <Button size="sm" className="h-8 rounded-full px-3.5">
              {t("message.update")}
            </Button>
          </ComposerPrimitive.Send>
        </div>
      </ComposerPrimitive.Root>
    </MessagePrimitive.Root>
  );
};

const BranchPicker: FC<BranchPickerPrimitive.Root.Props> = ({
  className,
  ...rest
}) => {
  // LOCAL: upstream's branch-picker tooltips, "Previous" and "Next", are gone from
  // this file and read from the `elements-thread` catalog instead.
  const { t } = useTranslation("elements-thread");
  return (
    <BranchPickerPrimitive.Root
      hideWhenSingleBranch
      className={cn(
        "aui-branch-picker-root text-muted-foreground -ms-2 me-2 inline-flex items-center text-xs",
        className,
      )}
      {...rest}
    >
      <BranchPickerPrimitive.Previous asChild>
        <TooltipIconButton tooltip={t("message.previous")}>
          <ChevronLeftIcon />
        </TooltipIconButton>
      </BranchPickerPrimitive.Previous>
      <span className="aui-branch-picker-state font-medium">
        <BranchPickerPrimitive.Number /> / <BranchPickerPrimitive.Count />
      </span>
      <BranchPickerPrimitive.Next asChild>
        <TooltipIconButton tooltip={t("message.next")}>
          <ChevronRightIcon />
        </TooltipIconButton>
      </BranchPickerPrimitive.Next>
    </BranchPickerPrimitive.Root>
  );
};

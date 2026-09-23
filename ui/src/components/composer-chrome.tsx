// What sits around and inside the composer: the directory and branch above it,
// the model and thinking inside it, the refusal a file can earn, and the sentence
// a conversation the server is still answering owes the reader.
//
// ------------------------------------------------ three slots, and why they are here
//
// `Thread` is a COPY of the assistant-ui element (see thread.aui.tsx), and the
// composer lives inside it with no way to be replaced. Rather than edit that file
// into something we own, it gained three LOCAL: insertion points -- `ComposerFrame`,
// which wraps the composer, `ComposerTools`, which renders in the composer's own
// action row, and `ComposerAddAttachment`, which stands in for the attach button in
// that same row. Everything visible below is in THIS file; the copied element
// gained three components and no markup. That arrangement is what keeps this file's
// own copy out of the copied element -- the copies are edited in place now, each
// deliberate edit marked `LOCAL:`, so a registry pull is reconciled by reading those
// markers rather than by a byte diff.
//
// ------------------------------------------------------ and one shared store
//
// The attachment rule needs one fact -- what this session's model takes -- and
// three things read it: the adapter (which refuses), the button (which disables
// itself), and this frame (which draws the sentence). The adapter is called from
// upstream's own event handlers, so there is no React tree in reach of it; that is
// why the fact lives in `lib/attachments.ts`'s little store rather than in state
// passed down. See that file.
//
// ------------------------------------------------------- above: where, and on what
//
// DIRECTORY AND BRANCH ARE THE SESSION'S CONTEXT, not its content. The directory
// is the project the session is bound to -- the same binding the file tools and
// the shell resolve against -- so switching it here is rebinding the session, with
// everything that carries (the log moves with it; see POST /api/project). The
// branch is that directory's working tree.
//
// THEY ARE HIDDEN ONCE THE CONVERSATION STARTS. On an empty session the composer
// sits mid-screen and the bar reads as part of getting started; once there are
// messages the composer is docked at the bottom and the bar would be permanent
// furniture over the conversation. The rule is exactly that: no messages, show;
// any messages, hide.
//
// ------------------------------------------------------- inside: what answers, and how hard
//
// The model and the reasoning effort are PER SESSION and nothing else is affected:
// the server keeps the override in memory keyed by thread id (see POST /api/model),
// and config.edn and every other thread are left alone. Choosing a
// provider clears the model, because an id that belonged to the old vendor is not
// one the new one serves -- the server enforces that, and the picker just does not
// pretend otherwise.
//
// THE FOUR PICKERS ARE `components/picker.tsx` -- the projects, the branch, the
// model and the effort. They used to be native `<select>`s, and the reason that
// changed is in that file: these lists are long (thirty projects, a year of
// branches, a catalog of vendors) and a native select cannot be searched. The
// model list is the one that is GROUPED (a heading per vendor, one flat list under
// each) and it is still one pick, not vendor-then-model.
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type FC,
  type PropsWithChildren,
} from "react";
import {
  ComposerPrimitive,
  unstable_useTriggerPopoverScopeContext,
  useAuiState,
  type Unstable_TriggerMatcher,
} from "@assistant-ui/react";
import type {
  Unstable_DirectiveFormatter,
  Unstable_TriggerAdapter,
  Unstable_TriggerItem,
} from "@assistant-ui/core";
import { BrainIcon, FolderIcon, GitBranchIcon, PlusIcon, TriangleAlertIcon } from "lucide-react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";

import { ComposerAddAttachment as CopiedAddAttachment } from "@/components/assistant-ui/elements/attachment.aui";
import { TooltipIconButton } from "@/components/assistant-ui/elements/tooltip-icon-button";
import { imageRefusal } from "@/lib/attachment-rules";
import { attachmentGuard } from "@/lib/attachments";
import {
  choicesFor,
  gitStateFor,
  modelFor,
  setModel,
  switchBranch,
  type Choices,
  type ModelAnswer,
} from "@/lib/composer";
import { bindThread, listSidebar, projectName } from "@/lib/projects";
import { hasKey } from "@/lib/provider-key";
import { providerLabel } from "@/lib/provider-label";
import { layerWord, matches, skillsFor, skillsIn, type SkillGroup } from "@/lib/skills";

import { ContextRing } from "./context-ring";
import { SessionNumbers } from "./composer-numbers";
import { ComposerStats } from "./composer-stats";
import { Picker } from "./picker";
// THE SENTENCE A CONVERSATION THE SERVER IS STILL ANSWERING USED TO OWE IS GONE (ticket 09
// of `.scratch/session-after-refresh`): what stands there now is a STOP button, drawn in
// the composer's own action row by the element itself (`thread.aui.tsx`'s `ComposerStop`
// seam, supplied by `App`, which is where the thread id is), because the thing it
// replaces is Send. The CONTEXT it reads still lives in a module of its own --
// `./session-run-state` -- for the same reason as before: a suite renders what stands on
// the server's word, and THIS file cannot be imported there.

/// The thread the composer is composing for. Supplied by `App`, which owns it --
/// see the comment there on why the id's owner is React state rather than the
/// agent.
export const ThreadIdContext = createContext<string | null>(null);

const useThreadId = (): string | null => useContext(ThreadIdContext);

/// One `<option>`-list worth of state: fetch on mount and whenever the id moves,
/// and keep the failure rather than throwing it away. Small enough not to want a
/// library, and explicit enough to read.
function useRemote<T>(load: () => Promise<T>): {
  data: T | null;
  error: string | null;
  reload: () => void;
} {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);
  useEffect(() => {
    let live = true;
    load()
      .then((value) => live && (setData(value), setError(null)))
      .catch((failure: unknown) =>
        live && setError(failure instanceof Error ? failure.message : String(failure)),
      );
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nonce, load]);
  return { data, error, reload: useCallback(() => setNonce((n) => n + 1), []) };
}

/// WHAT THIS PAGE IS HOLDING FOR A SESSION THAT DOES NOT EXIST YET -- supplied by `App`
/// for exactly the sessions it minted and nobody has sent in, and NULL for every other
/// session (an id the store knows is a session whose directory is a fact the server
/// already holds, and this is not that).
///
/// WHY THE COMPOSER HAS TO KNOW. `rebind` below used to POST `/api/project` for whatever
/// id it was given, and for a session this page had just minted that POST is what CREATED
/// it: `project/bind!` is a find-or-create (`touch-session!`), so one pick of a directory
/// wrote a session row and a 160-byte log whose only line is the `project/bound` audit --
/// a thread id with no conversation behind it, listed by every later refresh as a session
/// nobody has sent to. That is the one thing lazy creation removed from every OTHER door
/// (点击新增不立刻会话，发送才新建), and the picker was the door it was left in.
///
/// SO A HELD SESSION'S PICK REMEMBERS AND WRITES NOTHING, and the first send binds it --
/// the same registration every other minted session goes through (`app.tsx`'s
/// `pendingBinds`, which `onShowFresh` already fills from the sidebar). The directory is
/// held there rather than here because the page, not this bar, is the thing that sees the
/// message arrive.
export type HeldSession = {
  /// THE DIRECTORY ITS FIRST SEND WILL BIND IT TO, or null for a task. Read at render
  /// from the page's own pending map, so a bar that has just been remounted still shows
  /// what was picked.
  readonly dir: string | null;
  /// REMEMBER A DIFFERENT ONE. Writes nothing: the first send is what binds, and this is
  /// the only thing a pick can do to a session that does not exist yet.
  remember: (dir: string) => void;
};
///
/// NULL IS THE ORDINARY ANSWER -- every session the store can answer for, and every
/// session this page did not mint.
export const HeldSessionContext = createContext<HeldSession | null>(null);

const useHeldSession = (): HeldSession | null => useContext(HeldSessionContext);

/// The directory and branch strip, shown only before the conversation starts.
const ComposerContextBar: FC<{ threadId: string }> = ({ threadId }) => {
  const { t } = useTranslation("composer");
  // The fetch failures below are this side's fallback sentences (see
  // lib/projects.ts and lib/composer.ts), so they are drawn from `errors`.
  const { t: tErrors } = useTranslation("errors");
  // THE PROJECTS HALF OF THE SIDEBAR'S LISTING, because that is what a session can
  // be bound to: the tasks in the same payload are conversations with no directory,
  // and this picker is the thing that gives one -- so lists them nothing to offer.
  const projects = useRemote(
    useCallback(() => listSidebar(tErrors), [tErrors]),
  );
  const git = useRemote(useCallback(() => gitStateFor(threadId, tErrors), [threadId, tErrors]));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // WHETHER THIS SESSION EXISTS IN THIS HOME AT ALL -- null for every session it does,
  // and the page's own memory of the directory for the ones it minted and nothing has
  // been sent in. See `HeldSessionContext`.
  const held = useHeldSession();
  // AND WHAT THIS BAR SHOWS AFTER A PICK, because `remember` writes to a ref the page
  // holds (the run reads it at the first send) and a ref does not re-render: this is the
  // render a pick owes the person who made it, and it is dropped the moment the session
  // stops being held -- the server's answer takes over at the first send.
  const [picked, setPicked] = useState<string | null>(null);

  // The label is the last path segment -- a row has to be scannable -- and the
  // whole path rides along as the hint: it is what the row is searched by (a
  // person remembers `workspace`) and what it shows when the list is open.
  const dirs = (projects.data?.projects ?? []).map((p) => ({
    value: p.path,
    label: projectName(p.path),
    hint: p.path,
  }));
  /// THE DIRECTORY THIS BAR NAMES. THREE SOURCES, in this order, and the order is the
  /// whole of it: a session the page is HOLDING has no binding to read -- `/api/git`
  /// answers `{dir: nil}` for an id this home has never heard of, which is the honest
  /// answer and the wrong one to draw -- so the page's pending directory comes first,
  /// then the pick that was just made, and only then the server's binding.
  const current = held === null ? (git.data?.dir ?? "") : (held.dir ?? picked ?? "");

  /// PUT THIS SESSION IN DIR. Two verbs, in one function, because the picker asks one
  /// question and which verb answers it is a fact about the SESSION rather than about the
  /// pick:
  ///
  ///   * a session the page is HOLDING does not exist anywhere yet, so a pick can only
  ///     REMEMBER the directory -- this bar cannot be the thing that creates a session
  ///     (see `HeldSessionContext`). No request, nothing to fail, no sentence;
  ///   * every other session is bound here and now, which for one the store already holds
  ///     is a REBIND: `POST /api/project` moves the log with it (`move-log!`).
  const rebind = async (path: string) => {
    if (held !== null) {
      held.remember(path);
      setPicked(path);
      setError(null);
      return;
    }
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await bindThread(threadId, path, tErrors);
      // The branch belongs to the directory, so the new one has to be read back:
      // keeping the old answer would name a branch the session is no longer on.
      git.reload();
      projects.reload();
    } catch (failure: unknown) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  };

  const switchTo = async (branch: string) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await switchBranch(threadId, branch, tErrors);
      git.reload();
    } catch (failure: unknown) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  };

  const branch = git.data?.branch ?? null;

  return (
    <div data-slot="composer-context" className="flex flex-col gap-1 px-1.5 pt-1 pb-0.5">
      <div className="flex items-center gap-4">
        {dirs.length > 0 && (
          <Picker
            slot="composer-directory"
            label={t("context.directory")}
            title={current}
            value={current}
            disabled={busy}
            leading={<FolderIcon className="text-muted-foreground size-4 shrink-0" />}
            options={
              // A session can be bound to a directory this home does not list (the
              // project was removed, or the id came from elsewhere). It still has to
              // be drawable, or the picker would silently show someone else's answer.
              dirs.some((d) => d.value === current) || current === ""
                ? dirs
                : [{ value: current, label: projectName(current), hint: current }, ...dirs]
            }
            onPick={(option) => void rebind(option.value)}
          />
        )}
        {git.data?.["repo?"] === true && (
          <Picker
            slot="composer-branch"
            label={t("context.branch")}
            value={branch ?? ""}
            disabled={busy}
            leading={<GitBranchIcon className="text-muted-foreground size-4 shrink-0" />}
            title={
              git.data.dirty > 0
                ? t("context.dirty", { count: git.data.dirty })
                : t("context.branchClean")
            }
            options={
              branch === null
                ? [{ value: "", label: t("context.detached") }, ...git.data.branches.map(branchesOf)]
                : git.data.branches.map(branchesOf)
            }
            onPick={(option) => void switchTo(option.value)}
          />
        )}
        {git.data?.["repo?"] === true && git.data.dirty > 0 && (
          <TriangleAlertIcon
            data-slot="composer-dirty"
            aria-label={t("context.dirtyLabel")}
            className="text-muted-foreground size-3.5 shrink-0"
          />
        )}
      </div>
      {error !== null && (
        <p role="alert" data-slot="composer-context-error" className="text-destructive text-xs">
          {error}
        </p>
      )}
    </div>
  );
};

const branchesOf = (name: string) => ({ value: name, label: name });

/// The model and thinking pickers, in the composer's action row.
///
/// IT ALSO ASKS THE SECOND QUESTION. `/api/choices` answers what this session may
/// be switched to; `/api/model` answers what it is served by and what that takes,
/// which is what the attachment rule reads. Both are per-session and both change
/// at the same moment -- the picker below is the one thing that changes either --
/// so they are asked together, and the refetch after a switch is what keeps the
/// rule in step with the model without a reload. A `/api/model` that FAILS is
/// folded into "declared nothing" rather than failing this load: the picker must
/// not go dark because a second question could not be answered, and a server that
/// cannot resolve this session's provider cannot run it either.
const ComposerTools: FC = () => {
  const { t } = useTranslation("composer");
  const { t: tErrors } = useTranslation("errors");
  const threadId = useThreadId();
  const session = useRemote(
    useCallback(async (): Promise<{ choices: Choices; model: ModelAnswer } | null> => {
      if (threadId === null) return null;
      const [choices, model] = await Promise.all([
        choicesFor(threadId, tErrors),
        modelFor(threadId, tErrors).catch((): ModelAnswer => ({})),
      ]);
      return { choices, model };
    }, [threadId, tErrors]),
  );
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const data = session.data?.choices ?? null;

  // THE GUARD IS TOLD WHAT ARRIVED, in an effect rather than inside the loader,
  // because only the answer that is actually RENDERED may reach it: `useRemote`
  // drops an answer a later one superseded, and a model noted from a load nobody
  // is looking at would be the previous session's.
  const model = session.data?.model;
  useEffect(() => {
    if (model !== undefined) attachmentGuard.noteModel(model);
  }, [model]);

  const change = async (next: { provider?: string; model?: string; "reasoning-effort"?: string }) => {
    if (threadId === null || busy) return;
    setBusy(true);
    setError(null);
    try {
      await setModel(threadId, next, tErrors);
      session.reload();
    } catch (failure: unknown) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  };

  if (data === null) {
    return session.error === null ? null : (
      <p role="alert" data-slot="composer-tools-error" className="text-destructive text-xs">
        {session.error}
      </p>
    );
  }

  // GROUPED BY VENDOR, ONE FLAT LIST, and that is the whole shape of this menu: a
  // heading per provider with its models under it, so a long catalog reads as a
  // short list of vendors -- but one pick rather than vendor-then-model, because
  // two menus for one decision is one menu too many. The row's VALUE is the model
  // id alone, because the vendor is implied by which heading it was under (`hint`
  // carries nothing: the heading above the row already says it, and searching
  // matches the heading too -- see `lib/picker.ts`). The heading's TEXT is the
  // vendor's display name when it has one and its id otherwise (see
  // `providerLabel`): the id stays the truth either way, which is what the server
  // is sent and what a log line will say.
  // A session served by a model the catalog does not list -- an inline provider in
  // config.edn, a vendor that has since been removed -- still has to be drawable,
  // exactly as the directory picker treats a directory this home does not list: a
  // picker showing nothing at all reads as a session with no model.
  // AND ONLY THE PROVIDERS THIS HOME HOLDS A KEY FOR (see `lib/provider-key.ts`, which
  // is the one copy of that rule -- the settings page reads it too): offering a provider
  // that will certainly refuse is leading a person to a run that cannot work.
  //
  // THE SESSION'S CURRENT MODEL IS NOT TOUCHED BY THAT FILTER. If it falls out of the
  // list because its vendor has no key, the branch below puts it back at the top with
  // the same 'not in the catalog' hint an unlisted model already gets -- erasing what a
  // session is being SERVED BY is a bigger lie than listing a vendor without a key.
  const listed = data.providers
    .filter(hasKey)
    .flatMap((provider) =>
      provider.models.map((model) => ({
        value: model,
        label: model,
        group: providerLabel(provider),
      })),
    );
  const options =
    data.model === undefined || listed.some((option) => option.value === data.model)
      ? listed
      : [{ value: data.model, label: data.model, hint: t("model.notInCatalog") }, ...listed];
  const currentModel = data.model ?? options[0]?.value ?? "";

  return (
    <div data-slot="composer-tools" className="flex items-center gap-3">
      {/* THE RING AND THE MODEL ARE ONE PAIR, so their own gap is tighter than the row's:
          the window belongs to the model that is selected, and a gap the width of the
          row's would read as a third control between them. */}
      <div className="flex items-center gap-1.5">
        <ContextRing />
        <Picker
        slot="composer-model"
        label={t("model.label")}
        value={currentModel}
        disabled={busy}
        title={data.provider === undefined ? data.model : `${data.provider} / ${data.model}`}
        options={options}
        onPick={(option) => {
          // The provider comes from the vendor that declares this model, because an
          // id is only meaningful against the one that does.
          const owner = data.providers.find((p) => p.models.includes(option.value));
          void change(
            owner === undefined
              ? { model: option.value }
              : { provider: owner.name, model: option.value },
          );
        }}
        />
      </div>
      <Picker
        slot="composer-effort"
        label={t("effort.label")}
        value={data["reasoning-effort"] ?? ""}
        disabled={busy}
        leading={<BrainIcon className="text-muted-foreground size-4 shrink-0" />}
        title={t("effort.title")}
        // Three options and a default: read at a glance, so no search box (see
        // `components/picker.tsx` on `searchable`).
        searchable={false}
        options={[
          { value: "", label: t("effort.default") },
          ...data["reasoning-efforts"].map((effort) => ({ value: effort, label: effort })),
        ]}
        onPick={(option) => void change({ "reasoning-effort": option.value })}
      />
      {error !== null && (
        <p role="alert" data-slot="composer-tools-error" className="text-destructive text-xs">
          {error}
        </p>
      )}
    </div>
  );
};

// ------------------------------------------------------- the attach button, refused
//
// `+` IS UPSTREAM'S BUTTON AND UPSTREAM'S FILE DIALOG (`ComposerPrimitive.
// AddAttachment`), copied in with the rest of the composer -- what this adds is
// the one state upstream has no opinion about: a session whose model does not
// take images.
//
// PRESENT AND DISABLED, NOT ABSENT, and that is a decision rather than a
// preference. A button that has quietly gone and a button that was never built
// look identical from the outside, and the harder of those two to debug must not
// be what a bug produces -- the same reasoning that keeps a broken skill listed in
// the menu above. It also speaks EARLIER than the refusal can: the paste and drop
// paths can only answer after they have been refused, while `+` is where somebody
// decides whether to try at all.
//
// THE SENTENCE SITS ON A WRAPPER, not on the button, because a disabled button
// receives no pointer events in the browsers worth caring about -- a `title` on it
// would be a tooltip nobody can ever see.
export const ComposerAttachButton: FC = () => {
  const { t } = useTranslation("composer");
  const { t: tErrors } = useTranslation("errors");
  const guard = useSyncExternalStore(attachmentGuard.subscribe, attachmentGuard.current);
  const refusal = imageRefusal(guard.input, guard.model, tErrors);
  if (refusal === null) return <CopiedAddAttachment />;
  return (
    <span data-slot="composer-attach-disabled" title={refusal}>
      <TooltipIconButton
        tooltip={refusal}
        side="bottom"
        variant="ghost"
        size="icon"
        disabled
        className="aui-composer-add-attachment text-muted-foreground size-7 rounded-full opacity-50"
        aria-label={t("attach.label")}
      >
        <PlusIcon className="aui-attachment-add-icon size-4" />
      </TooltipIconButton>
    </span>
  );
};

// ------------------------------------------------------------------ the skill list
//
// `/` IN THE COMPOSER, AND THE MENU THE KIT ALREADY SHIPS. A skill is loaded by a
// person by starting a message with `/name `, and the server has read that since
// before this file existed -- what was missing was any way to know the names. So
// this adds no protocol, no frame and no server-side rule: it puts the names on
// screen and writes the same `/name ` a person would have typed.
//
// THE POPOVER, ITS KEYS AND ITS ARIA ARE UPSTREAM'S (`Unstable_TriggerPopover*`,
// driven by a registered adapter). What is OURS is where the names come from, which
// of them are offered, and what a row says -- see lib/skills.ts for the data half.
// Rebuilding the popover here would have meant re-deriving the three seams the
// kit's own Input already has: the caret it reports to the popover, the keys it
// lets the popover consume before sending, and the four combobox attributes.

const TRIGGER_CHAR = "/";

/// `/` AT THE START OF THE MESSAGE, and nowhere else.
///
/// The kit's default matcher accepts any word boundary, which would open this menu
/// after `see /alpha` -- a sentence ABOUT a skill, which the server does not load.
/// A menu that offers a load which cannot happen is worse than no menu, so the
/// matcher is narrowed to the shape `harness.cap.skills/slash-pattern` actually reads:
/// the slash first, then a name with no whitespace after it yet.
const slashAtStart: Unstable_TriggerMatcher = (text, char, cursorPosition) => {
  const typed = text.slice(0, cursorPosition);
  if (!typed.startsWith(char)) return null;
  if (/\s/.test(typed.slice(char.length))) return null;
  return { query: typed.slice(char.length), offset: 0, endOffset: cursorPosition };
};

/// The pick, serialized as what a person would have typed: `/name`. The kit adds
/// the trailing space and puts the caret after it, so this one function decides
/// everything that lands in the composer -- there is no insertion logic here to
/// get wrong.
const slashFormatter: Unstable_DirectiveFormatter = {
  serialize: (item) => `${TRIGGER_CHAR}${item.id}`,
  // Nothing parses directives back out of this composer: it is a textarea, and the
  // text it holds IS the `/name` the server reads. A parse that claimed otherwise
  // would be describing a rendering this interface does not do.
  parse: (text) => [{ kind: "text", text }],
};

/// The translator this face is worded through: the composer's own catalog, because
/// every string below is drawn inside the composer (see `locales/<lng>/composer.json`).
type Translate = TFunction<"composer">;

/// `scan`'s four ways for a skill to be broken, as the sentence a person needs.
/// The server says WHY by keyword (the model reads the same vocabulary in a
/// refusal); what a reader of a menu needs is the everyday cause. An unknown
/// reason falls through as itself rather than as silence, and a reason the server
/// did not give at all says nothing.
///
/// THE KEYS ARE WRITTEN OUT, one case per keyword, rather than built from the
/// reason: the reason is a keyword from the server, so a template key would put a
/// raw name on screen for a cause this page has no sentence for.
function brokenWord(t: Translate, reason: string | null): string | null {
  switch (reason) {
    case "unreadable":
      return t("skill.broken.unreadable");
    case "no-frontmatter":
      return t("skill.broken.noFrontmatter");
    case "name-mismatch":
      return t("skill.broken.nameMismatch");
    case "no-description":
      return t("skill.broken.noDescription");
    default:
      return reason;
  }
}

/// A `metadata` field as a string. The kit types an item's metadata as arbitrary
/// JSON, so the narrowing lives in one place rather than in a cast at every read.
function metadataString(item: Unstable_TriggerItem, key: string): string | null {
  const value = item.metadata?.[key];
  return typeof value === "string" ? value : null;
}

/// One pickable row: the name, the layer it came from, and what it does.
const SkillListRow: FC<{ item: Unstable_TriggerItem; index: number }> = ({ item, index }) => {
  const { t } = useTranslation("composer");
  const ref = useRef<HTMLButtonElement>(null);
  const scope = unstable_useTriggerPopoverScopeContext();
  // The kit owns the highlight. What it cannot know is that this list is taller
  // than the box it is drawn in, so following the highlight is ours. The index is
  // the one the primitive itself uses for `data-highlighted`, so the row that
  // scrolls into view and the row that is drawn as current cannot disagree.
  const highlighted = scope.highlightedIndex === index;
  useEffect(() => {
    if (highlighted) ref.current?.scrollIntoView({ block: "nearest" });
  }, [highlighted]);

  const layer = layerWord(t, metadataString(item, "layer") ?? undefined);
  return (
    <ComposerPrimitive.Unstable_TriggerPopoverItem
      item={item}
      index={index}
      ref={ref}
      data-slot="skill-list-row"
      // Where this skill lives. The layer chip says which of the two it is; the
      // path is the truth underneath it -- and for a root nobody has named (a
      // configured `:skills {:roots ..}`) it is the ONLY thing that can be said.
      title={metadataString(item, "root") ?? undefined}
      className="data-[highlighted]:bg-accent flex w-full items-baseline gap-2 rounded-lg px-2 py-1.5 text-start text-sm"
    >
      <b className="shrink-0 font-medium">{item.label}</b>
      {layer !== null && (
        <span data-slot="skill-list-layer" className="text-muted-foreground shrink-0 text-xs">
          {layer}
        </span>
      )}
      {item.description !== undefined && (
        <span className="text-muted-foreground min-w-0 flex-1 truncate">{item.description}</span>
      )}
    </ComposerPrimitive.Unstable_TriggerPopoverItem>
  );
};

/// The menu itself: the trigger, the rows, and the two states a fetch has.
///
/// IT ASKS THE SERVER WHEN THE TEXT ENTERS THE TRIGGER SHAPE, once per entry --
/// not on every keystroke, and not when the page loads. That is what makes a skill
/// installed a moment ago visible on the next `/`, and it is also when the previous
/// answer is dropped: a stale menu is the one thing a menu must not be.
const SkillPicker: FC<{ threadId: string }> = ({ threadId }) => {
  const { t } = useTranslation("composer");
  const text = useAuiState((s) => s.composer.text);
  // The same shape `slashAtStart` insists on, asked of the whole text: is this
  // message opening a slash name? Question and fetch share this one answer.
  const asking = text.startsWith(TRIGGER_CHAR) && !/\s/.test(text);
  const [groups, setGroups] = useState<SkillGroup[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!asking) {
      setGroups(null);
      setError(null);
      return;
    }
    let live = true;
    skillsFor(threadId, t)
      .then((answer) => live && (setGroups(answer), setError(null)))
      .catch((failure: unknown) =>
        live && setError(failure instanceof Error ? failure.message : String(failure)),
      );
    return () => {
      live = false;
    };
  }, [asking, threadId]);

  const skills = useMemo(() => skillsIn(groups ?? []), [groups]);
  const pickable = useMemo(() => skills.filter((skill) => skill["available?"]), [skills]);
  const broken = useMemo(() => skills.filter((skill) => !skill["available?"]), [skills]);

  const adapter = useMemo<Unstable_TriggerAdapter>(
    () => ({
      // NO CATEGORIES, and that is a decision rather than a shortcut: this is one
      // flat table with a per-row layer, not a "pick a layer, then pick a skill"
      // drill-down. A consequence worth stating, because it is not obvious: the
      // kit fills a popover from `search` when there is no category to walk, so
      // leaving this out would leave the menu empty.
      categories: () => [],
      categoryItems: () => [],
      search: (query) =>
        pickable
          .filter((skill) => matches(skill, query))
          .map((skill) => ({
            id: skill.name,
            type: "skill",
            label: skill.name,
            description: skill.description ?? undefined,
            metadata: { layer: skill.layer ?? null, root: skill.root },
          })),
    }),
    [pickable],
  );

  const loading = asking && groups === null && error === null;
  // Nothing to say, no popover: handing the kit an adapter is what opens it, so a
  // session with no skills gets no empty box -- and no combobox relationship
  // pointing at a list that is not there.
  const openable = loading || error !== null || skills.length > 0;

  return (
    <ComposerPrimitive.Unstable_TriggerPopover
      char={TRIGGER_CHAR}
      matcher={slashAtStart}
      adapter={openable ? adapter : undefined}
      isLoading={loading}
      data-slot="skill-list"
      // Above the frame, not inside its flow: the composer must not resize when a
      // menu opens. The frame is the positioning box (see ComposerFrame).
      className="absolute bottom-full left-0 z-50 mb-1.5 max-h-72 w-full overflow-y-auto rounded-(--composer-radius) border bg-(--composer-bg) p-1 shadow-lg"
    >
      <ComposerPrimitive.Unstable_TriggerPopover.Directive formatter={slashFormatter} />
      {loading && (
        <p data-slot="skill-list-loading" className="text-muted-foreground px-2 py-1.5 text-sm">
          {t("skill.reading")}
        </p>
      )}
      {error !== null && (
        <p role="alert" data-slot="skill-list-error" className="text-destructive px-2 py-1.5 text-xs">
          {error}
        </p>
      )}
      <ComposerPrimitive.Unstable_TriggerPopoverItems>
        {(items) => items.map((item, index) => <SkillListRow key={item.id} item={item} index={index} />)}
      </ComposerPrimitive.Unstable_TriggerPopoverItems>
      {broken.length > 0 && (
        // LISTED BUT NOT PICKABLE, and both halves matter. Listed, because a skill
        // that silently vanished and one that was never installed look identical
        // from the outside -- the harder of the two to debug must not be what a bug
        // produces. Not pickable, because loading it cannot work: these are drawn
        // outside the kit's item list, so no arrow key and no Enter can reach them.
        <div data-slot="skill-list-unusable" className="border-border/60 mt-1 border-t pt-1">
          {broken.map((skill) => (
            <div
              key={skill.name}
              data-slot="skill-list-unusable-row"
              title={skill.root}
              className="text-muted-foreground flex items-baseline gap-2 px-2 py-1 text-sm"
            >
              <b className="shrink-0 font-medium line-through">{skill.name}</b>
              <span className="min-w-0 flex-1 truncate text-xs">
                {brokenWord(t, skill.reason)}
              </span>
            </div>
          ))}
        </div>
      )}
    </ComposerPrimitive.Unstable_TriggerPopover>
  );
};

/// The wrapper Thread renders around the composer. It draws the strip above the
/// composer, the status strip below it, and then gets out of the way; with no thread
/// id there is nothing to show, so it renders its children alone.
///
/// The two strips are at opposite ends on purpose: the context bar answers "where
/// and on what" before a conversation starts and folds away once it does, while the
/// status strip answers "what has this cost" and only appears once there is an
/// answer. Either one is invisible in the state the other is showing -- see each
/// component's own header.
///
/// It is ALSO the trigger root, and it has to be: a trigger popover must be an
/// ancestor of the composer's input -- that is what hands the input the popover's
/// combobox attributes and what lets the popover swallow Enter before the composer
/// sends. This frame is the one place that wraps the composer without touching the
/// copied element, so the declaration lives here and `thread.aui.tsx` is untouched.
export const ComposerFrame: FC<PropsWithChildren> = ({ children }) => {
  const threadId = useThreadId();
  const started = useAuiState((s) => s.thread.messages.length > 0);
  // The attachment rule's refusal, if the last file offered was turned away. THIS
  // IS THE ONLY PLACE A REFUSAL IS DRAWN -- both reasons a file can be refused
  // arrive here (see lib/attachment-rules.ts), which is what keeps "what a refusal
  // looks like" one thing rather than one per rule.
  const refusal = useSyncExternalStore(
    attachmentGuard.subscribe,
    attachmentGuard.current,
  ).refusal;

  if (threadId === null) return <>{children}</>;

  return (
    <div
      data-slot="composer-frame"
      data-started={started ? "" : undefined}
      // `relative` is for the skill list: it floats ABOVE this frame, so the frame
      // is the box it is measured against.
      className="bg-muted/40 rounded-(--composer-radius) relative p-1.5"
    >
      {/* THE NUMBERS ARE FETCHED HERE AND READ THROUGH A SCOPE (see
          components/composer-numbers.tsx): the strip below and the ring in the action
          row are the same answer at the same moment, and the triggers are written once. */}
      <SessionNumbers threadId={threadId}>
      <ComposerPrimitive.Unstable_TriggerPopoverRoot>
        <SkillPicker threadId={threadId} />
        {!started && <ComposerContextBar threadId={threadId} />}
        {/* NOTHING IS SAID ABOVE THE INPUT ABOUT A RUN THE SERVER IS ANSWERING anymore
            (ticket 09): the composer's own action row draws a STOP there instead
            (`thread.aui.tsx`'s `ComposerStop`), because the thing that was shut is a
            thing a person can now act on. */}
        {children}
        {refusal !== null && (
          <p
            role="alert"
            data-slot="composer-attachment-refusal"
            className="text-destructive px-1.5 pt-1 text-xs"
          >
            {refusal}
          </p>
        )}
        <ComposerStats />
      </ComposerPrimitive.Unstable_TriggerPopoverRoot>
      </SessionNumbers>
    </div>
  );
};

export { ComposerTools };

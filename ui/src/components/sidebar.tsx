// The sidebar: new task, projects, sessions, settings.
//
// ------------------------------------------------------------------ the shape
//
// Three regions, and only the middle one scrolls. That is the whole layout
// requirement and it is worth stating as a rule rather than a set of classes: a
// sidebar whose new-task button scrolls away is a sidebar you have to scroll to
// use. So the column is `h-full`, the middle is `min-h-0 flex-1 overflow-y-auto`
// and the two ends are ordinary blocks that simply do not shrink.
//
// The height contract with `Thread` is the reason this is a ROW of columns rather
// than another nested full-height column: `Thread`'s root is `h-full`, so whatever
// holds it has to have a height it can read. The viewport owns `h-dvh`; the
// sidebar is a `shrink-0` column of fixed width; the chat is `min-h-0 flex-1` and
// takes what is left. `min-h-0` is the load-bearing class -- without it a flex
// child refuses to shrink below its content and the page scrolls as a whole.
//
// ------------------------------------------------------------- where data comes
//
// One fetch of `GET /api/projects` answers the whole sidebar. The store decides
// which projects and sessions exist and which are archived; the tree supplies each
// log's size and mtime. The client joins nothing -- see `lib/projects.ts` for why
// that join is the server's.
//
// THE LIST IS A SNAPSHOT, AND THE UI SAYS SO. Nothing here subscribes to the log
// tree or the store, so a run that lands while the page is open does not change
// the numbers until something refreshes: coming back to the window, switching
// session, or the refresh button. The button exists because the alternative --
// a list that silently disagrees with the disk -- is worse than one that is
// visibly a snapshot.
//
// ----------------------------------------------------------- a new task's shape
//
// A new task is THREE STEPS IN THIS ORDER, and each one is load-bearing:
//
//   1. mint an id (the client owns ids -- the server has never minted one),
//   2. BIND that id to the selected project (POST /api/project), which is what
//      makes the session exist at all: the store learns about a conversation
//      when something asks for it to belong somewhere. Its log does not exist
//      yet, and that is a state the listing already handles.
//   3. switch the runtime to that id.
//
// Step 3 goes through `switchToThread`, NOT `switchToNewThread`, and that is
// deliberate rather than a shortcut: by the time it runs, the session is a ROW
// with a home, so "switch to it" is exactly what is happening. Letting the
// runtime mint its own id instead would put the id out of this component's reach
// -- and the id is what the bind needs -- so the adapter's `onSwitchToNewThread`
// had nothing left to do and was removed. A session that has never run rebuilds
// to an empty conversation (see the server's `ensure-complete!`), so the switch
// hydrates nothing and looks exactly like a new thread should.
//
// WITHOUT A PROJECT THERE IS NO NEW TASK, and the button says so instead of
// opening a session with nowhere to live. That is the product rule the whole
// feature rests on: every session belongs to a project, so the first one needs a
// project to exist first.
//
// THE SAME THREE STEPS ALSO RUN FROM A PROJECT'S OWN ROW. The header button
// starts a session in the SELECTED project -- derived, because "another task
// here" is what a person usually means -- and the row's button starts one in THAT
// project, which is the unambiguous version of the same wish. Both are guarded
// the same way and both land on the same helper; only the project differs, and
// only the refusal's destination differs (the header for the button with no row,
// the row itself for the one that has it).
//
// The row's button REPLACES THE SESSION COUNT that used to sit there. The count
// was a number you could read and do nothing with, drawn where an action belongs,
// and the sessions it counted are listed one line below it anyway. What is lost
// is the at-a-glance "how many are in here"; the answer is now the list itself.
//
// ------------------------------------------------------- adding a project's shape
//
// ONE CLICK, ONE OS DIALOG, ONE ROW. The folder button opens the native picker
// immediately, and the directory it answers is added there and then: no field to
// type into, no submit to press, no form to cancel.
//
// THIS DELIBERATELY OVERTURNS WHAT TICKET 05 DECIDED. 05 put a form in front of
// the dialog and kept submission explicit, arguing that an OS dialog is one
// keystroke from a stray selection and the store has no undo. The first half of
// that is still true; the second half is not, and it was the load-bearing half --
// removing a project is one row (`remove`), every log stays where it is, and
// re-adding the same directory brings its sessions back. A mis-pick therefore
// costs one click in the sidebar to undo, which does not buy a confirmation step
// whose only reachable content was "yes, the directory I just picked".
//
// A CANCELLED PICK SAYS NOTHING. The form used to report it -- "Selection
// cancelled — nothing was added." -- because a form left sitting there would
// otherwise look stuck. With no form, there is nothing to un-stick, and the
// person who just dismissed the window does not need to be told that dismissing
// it did nothing. A FAILURE still needs a line, and it goes under the button that
// raised it, in the server's own words, exactly as the New task refusals do.
//
// A MACHINE WITH NO DIALOG IS THE ONE FAILURE THAT EARNS A FIELD. Everything
// else -- an add the server refused, a picker that blew up -- gets the server's
// sentence under the button and nothing more, which is where every other
// refusal in this file lands. "There is no folder dialog on this machine" (a 501
// from /api/project/pick) is a DEAD END rather than a refusal: the line alone
// names no next move, so it arrives with somewhere to type the absolute path,
// and that field is drawn for no other reason. Collapsing the two is exactly how
// this shipped broken -- no dialog answered as a cancellation, a cancellation is
// silent, and the button read as idle rather than impossible.
//
// ------------------------------------------------------------- archiving's shape
//
// ARCHIVING IS A FLAG, NOT A DELETION, and the whole of the UI's part is to draw
// the flag as a place: archived sessions move out of the project's list and into
// an "Archived" group at its bottom, which is COLLAPSED BY DEFAULT -- the point of
// archiving is to stop being asked about a conversation, so the group must not
// take the same room the sessions did. Expanding it is one click, and every row in
// it offers the way back.
//
// THE EMPTY GROUP IS NOT DRAWN. A project with nothing archived shows no
// "Archived" header at all: an empty container is furniture, and furniture that
// appears and disappears is worse than furniture that simply is not there.
//
// ARCHIVING THE CURRENT SESSION IS ALLOWED, AND IT MOVES YOU. The forbidden state
// is the one where the page is open on a session the sidebar has just filed away
// -- that is the kind of wrong-looking-right state nobody notices until they type
// into it. So archiving what you are reading switches to the project's most
// recent UNARCHIVED session, and when the project has none left, it does what
// "New task" does: mints an id, binds it to this project and switches to it. That
// is the honest landing place, because it is exactly where pressing New task
// would have left you -- on an empty conversation in the project you are standing
// in. It is also why there is no "you archived the last one" special case: the
// rule is "never be reading an archived session", and that rule needs no
// exception.
//
// A RUN IN FLIGHT REFUSES IT. Archiving the current session requires switching
// away from it, and a switch mid-run is refused everywhere else in this file --
// so the archive is refused BEFORE it is written, rather than written and then
// left unable to move. A flag written while the page cannot leave is precisely
// the state the paragraph above forbids.
//
// ------------------------------------------------------------------- removal
//
// REMOVING A PROJECT IS A REMOVAL, NOT A DELETION, and there is no delete
// anywhere in this file. The project's row leaves the list; the sessions' logs do
// not move, do not shrink, and are not touched at all. Re-adding the same
// directory brings the sessions back with their archive flags, which is the
// server's adoption (see `add-project!`) and the reason the confirmation can
// honestly promise it.
//
// THE CONFIRMATION SAYS WHAT HAPPENS, NOT WHAT IT FEELS LIKE. It names the
// directory, says the sessions stay on disk, and says re-adding returns them --
// in those words, because a dialog that said "delete" would be describing an
// action this product does not have. Irreversible things get a scary dialog;
// this one is reversible, and a scary dialog here would train people to ignore
// the one that matters.
//
// THE "MORE" MENU IS A SIBLING OF THE PROJECT ROW, not a child of it, and that is
// load-bearing: the row's click both selects the project and folds it, so a menu
// trigger nested inside would toggle the folder open every time somebody reached
// for the menu. Opening the menu must not change what the row says about the
// project.
//
// A PROJECT ON SCREEN CANNOT BE REMOVED WHILE A RUN IS IN FLIGHT, because the
// page has to move off it (below) and every other switch in this file refuses
// mid-run. The guard reads the runtime's `isRunning`, so it lifts by itself.
//
// THE PAGE NEVER STAYS ON A REMOVED PROJECT. If the session being read belonged
// to the project that just went away, the sidebar moves: to the most recent
// unarchived session of another project, or -- when this was the last project --
// to a fresh session with no project at all, which is the one state the server
// tolerates and the sidebar simply does not list. What it must not do is leave
// the chat open on a project that is gone, and it must not invent a project to
// put the new session in.
//
// ------------------------------------------------------------------- refusals
//
// Two things are refused while a run is in flight, and both are shown where the
// click landed: opening another session (the run belongs to the thread it started
// on), and starting a new one (which would abandon that thread's view). The
// guard reads the runtime's own `isRunning`, so it lifts by itself when the run
// settles. `lib/run-state.ts` words the sentences from the `errors` catalog --
// they are this client's own words, not the server's, which are never translated
// -- because the adapter refuses for the same reasons and the two must not word
// them differently.
import { useCallback, useEffect, useState, type FC } from "react";
import type { AssistantRuntime } from "@assistant-ui/react";
import {
  ArchiveIcon,
  ArchiveRestoreIcon,
  ChevronRightIcon,
  CheckIcon,
  FolderIcon,
  FolderMinusIcon,
  FolderPlusIcon,
  MoreHorizontalIcon,
  RefreshCwIcon,
  SettingsIcon,
  SquarePenIcon,
  XIcon,
} from "lucide-react";
import { useTranslation } from "react-i18next";

import {
  ThreadListItem,
  ThreadListItemAction,
} from "@/components/assistant-ui/elements/thread-list.aui";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { SettingsPanel } from "@/components/settings-panel";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  addProject,
  bindThread,
  listProjects,
  pickFolder,
  PickerUnavailableError,
  projectName,
  removeProject,
  setArchived,
  type ProjectSummary,
  type SessionSummary,
} from "@/lib/projects";
import {
  runInProgress,
  runInProgressNewThreadRefusal,
  runInProgressRefusal,
} from "@/lib/run-state";

type SidebarProps = {
  runtime: AssistantRuntime;
  currentThreadId: string;
};

/// The refusal or failure that belongs to ONE row -- a refused switch, a list
/// that would not load. Keyed by the row it happened on, so it reads where the
/// click landed and a click somewhere else stops showing it.
type RowError = { id: string; message: string } | null;

/// The same thing for a PROJECT row, kept separate rather than folded into
/// `RowError` because the key would be a path where the other's is a session id,
/// and one map keyed two ways is one lookup that will eventually match the wrong
/// thing.
type ProjectError = { path: string; message: string } | null;

/// The refusals about STARTING a session, which have no row to land on. They go
/// under the New task button, which is the thing that was clicked -- and they
/// were module-level constants until this feature, because a sentence a person
/// reads cannot be one (see `lib/run-state.ts` for the same argument).

export const Sidebar: FC<SidebarProps> = ({ runtime, currentThreadId }) => {
  const { t } = useTranslation();
  // The failures this list can raise are THIS side's sentences (a listing that
  // would not load, a bind the server answered without a reason), so they come from
  // the `errors` catalog while the panel's own words stay in `shell`.
  const { t: tErrors } = useTranslation("errors");
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [listError, setListError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [rowError, setRowError] = useState<RowError>(null);
  const [projectError, setProjectError] = useState<ProjectError>(null);
  const [newTaskError, setNewTaskError] = useState<string | null>(null);
  // The Add project button's own failure -- a picker that will not open, or an
  // add the server refused. Separate from `newTaskError` because they belong to
  // two different buttons, and one firing must not answer for the other.
  const [addError, setAddError] = useState<string | null>(null);
  // The typed path, and whether it is being asked for. It appears for ONE
  // reason -- this machine has no folder dialog -- and never otherwise: it is
  // the way out of a dead end, not a second way to add a project.
  const [typingPath, setTypingPath] = useState(false);
  const [typedPath, setTypedPath] = useState("");
  const [busy, setBusy] = useState(false);
  // The project a new task will land in. Derived rather than owned: see the
  // effect below. Only an explicit click pins it.
  const [pinned, setPinned] = useState<string | null>(null);
  // The settings report is a MODAL rather than a fourth region: it is read
  // once and closed, it does not compete with the list for the middle strip, and
  // it is drawn over the page so that reading it cannot be mistaken for
  // navigating away from the conversation behind it.
  const [settingsOpen, setSettingsOpen] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setProjects(await listProjects(tErrors));
      setListError(null);
    } catch (failure: unknown) {
      setListError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setLoaded(true);
    }
  }, [tErrors]);

  // On mount, and again whenever the current thread changes -- opening a session
  // rebuilds it from its log, which appends an audit line to that very file, so
  // its size and mtime are stale the moment a switch succeeds.
  useEffect(() => {
    void refresh();
  }, [refresh, currentThreadId]);

  // WHICH PROJECT A NEW TASK LANDS IN, derived so it cannot point at something
  // that is gone. The current session's project wins when there is one, because
  // "another task here" is what a person almost always means; otherwise the first
  // project. An explicit click pins a project, and a pin that names a project no
  // longer in the list is dropped -- otherwise signing a new task to a removed
  // project would be a refusal nobody could explain.
  const currentProject = projects.find((p) =>
    p.sessions.some((s) => s.threadId === currentThreadId),
  );
  const pinnedStillListed = pinned !== null && projects.some((p) => p.path === pinned);
  const selected = pinnedStillListed
    ? projects.find((p) => p.path === pinned)!
    : (currentProject ?? projects[0] ?? null);

  const refuse = (id: string, message: string) => setRowError({ id, message });

  const openThread = async (threadId: string, projectPath: string) => {
    if (busy || threadId === currentThreadId) return;
    if (runInProgress(runtime)) {
      refuse(threadId, runInProgressRefusal(t));
      return;
    }
    setPinned(projectPath);
    setBusy(true);
    setRowError(null);
    try {
      await runtime.threads.switchToThread(threadId);
      await refresh();
    } catch (failure: unknown) {
      // The server's own refusal -- a truncated or corrupt log, a stem that
      // names two files -- arrives as the rejection's message and is shown
      // as-is under the row that asked for it. The other rows were never
      // touched, and a wrapper's paraphrase would be one more thing to distrust.
      setRowError({
        id: threadId,
        message: failure instanceof Error ? failure.message : String(failure),
      });
    } finally {
      setBusy(false);
    }
  };

  /// Archive a session, or bring it back. Refused while a run is in flight ONLY
  /// when it would have to move the page -- see the header: archiving a session
  /// you are NOT reading is a pure row write from the UI's point of view, and a
  /// running conversation elsewhere has no say in it.
  const archive = async (
    project: ProjectSummary,
    threadId: string,
    archived: boolean,
  ): Promise<void> => {
    if (busy) return;
    const movesThePage = archived && threadId === currentThreadId;
    if (movesThePage && runInProgress(runtime)) {
      refuse(threadId, runInProgressRefusal(t));
      return;
    }
    setBusy(true);
    setRowError(null);
    try {
      await setArchived(threadId, archived, tErrors);
      if (movesThePage) {
        // "Never be reading an archived session": the project's most recent
        // unarchived session, which is the first one the server listed (the
        // listing is newest-first), or -- when there is none -- the same three
        // steps the New task button runs.
        const next = project.sessions.find((s) => !s.archived && s.threadId !== threadId);
        if (next !== undefined) {
          setPinned(project.path);
          await runtime.threads.switchToThread(next.threadId);
        } else {
          const id = crypto.randomUUID();
          await bindThread(id, project.path, tErrors);
          setPinned(project.path);
          await runtime.threads.switchToThread(id);
        }
      }
      await refresh();
    } catch (failure: unknown) {
      setRowError({
        id: threadId,
        message: failure instanceof Error ? failure.message : String(failure),
      });
    } finally {
      setBusy(false);
    }
  };

  /// Remove a project from the list -- and move the page off it if that is where
  /// the page was. See this file's header: the server does the unbinding, the log
  /// files are not touched, and the only thing left for the UI is to not be left
  /// standing in a project that no longer exists.
  const remove = async (project: ProjectSummary): Promise<void> => {
    if (busy) return;
    const movesThePage = project.sessions.some((s) => s.threadId === currentThreadId);
    if (movesThePage && runInProgress(runtime)) {
      // The same sentence the session rows use, because it is the same reason --
      // the page would have to move off a running conversation -- but rendered on
      // the PROJECT row, where the click landed. A paraphrase would be a second
      // wording of one rule, and this file's header is explicit about where that
      // ends up.
      setProjectError({ path: project.path, message: runInProgressRefusal(t) });
      return;
    }
    setBusy(true);
    setProjectError(null);
    setRowError(null);
    try {
      await removeProject(project.path, tErrors);
      if (movesThePage) {
        // SOMEWHERE ELSE, in this order: the most recent unarchived session of
        // any remaining project (the lists are already newest-first), and --
        // when this was the last project -- a BRAND-NEW thread with no project
        // at all. That last branch goes through `switchToNewThread`, which is
        // the one thing in this app that does: there is nothing left to bind to,
        // and the server tolerates a session with no project by design. What
        // neither branch does is leave the chat on the project just removed, or
        // invent a project to hold the new session.
        const rest = projects.filter((p) => p.path !== project.path);
        const next = rest
          .flatMap((p) => p.sessions.filter((s) => !s.archived).map((s) => ({ p, s })))
          .sort(
            (a, b) =>
              (b.s.lastActivity ?? Number.MAX_VALUE) -
              (a.s.lastActivity ?? Number.MAX_VALUE),
          )[0];
        if (next !== undefined) {
          setPinned(next.p.path);
          await runtime.threads.switchToThread(next.s.threadId);
        } else {
          setPinned(null);
          await runtime.threads.switchToNewThread();
        }
      }
      await refresh();
    } catch (failure: unknown) {
      // The server's reason, on the row the click landed on -- the project's row
      // is the one thing on screen that names what failed.
      setProjectError({
        path: project.path,
        message: failure instanceof Error ? failure.message : String(failure),
      });
    } finally {
      setBusy(false);
    }
  };

  /// The landing half that both ways in share: send the directory, pin what comes
  /// back, refresh. Sharing it is what keeps the typed path from becoming a
  /// SECOND way to add a project -- same endpoint, same canonical path, same row,
  /// and no second set of decisions to drift out of step with the first.
  ///
  /// Throws what the server threw, which for a bad directory is that server's own
  /// named refusal ("no such directory", "not a directory") -- it says it better
  /// than an empty field's complaint would.
  const addDirectoryAt = async (dir: string): Promise<void> => {
    const added = await addProject(dir, tErrors);
    // The refusal under the New task button named a state that adding a
    // project has just ended; leaving it up would have the sidebar
    // contradicting itself one line above the new project's row.
    setNewTaskError(null);
    // The server answers the CANONICAL path, and that is what gets pinned --
    // so adding `~/proj` and then re-adding `~/proj/.` end on the same row
    // rather than two selections that look different and are not.
    setPinned(added.path);
    // Getting here means the project landed, whichever way it was asked for --
    // so the dead end is over: the sentence that announced it and the field
    // that answered it both go, together.
    setAddError(null);
    setTypingPath(false);
    setTypedPath("");
    await refresh();
  };

  /// Add a project: open the folder picker, and add whatever it answers. The
  /// whole of the interaction is this one function -- see the header for why the
  /// form that used to sit here is gone.
  ///
  /// CANCELLED IS NOT AN ERROR AND NOT A NOTICE: `pickFolder` answers null, and
  /// the right response to somebody dismissing a window is to do nothing. A
  /// rejection earns a line under the button that raised it -- and ONE KIND of
  /// rejection earns more than a line, because a machine with no dialog has put
  /// the click in a dead end and saying only "no" leaves it there.
  ///
  /// `busy` covers the whole thing, including the time the native dialog is up.
  /// That is the honest state: while a modal window is waiting on a human, every
  /// other click in this sidebar would be a second write racing the first.
  const addProjectNow = async (): Promise<void> => {
    if (busy) return;
    setBusy(true);
    setAddError(null);
    try {
      const picked = await pickFolder(tErrors);
      if (picked === null) return;
      await addDirectoryAt(picked);
    } catch (failure: unknown) {
      setAddError(failure instanceof Error ? failure.message : String(failure));
      // No dialog to ask, so ASK HERE: the field appears because this machine
      // cannot open one, and at no other time -- it is a way out, not a second
      // front door (see the header on why there is no form by default).
      if (failure instanceof PickerUnavailableError) setTypingPath(true);
    } finally {
      setBusy(false);
    }
  };

  /// The typed path's own submit. Refuses the empty field here rather than
  /// sending it, because "nothing was entered" is not the server's business and
  /// its refusal would be worded for worse things.
  const addTypedPath = async (): Promise<void> => {
    const dir = typedPath.trim();
    if (busy) return;
    if (dir === "") {
      setAddError(t("addProject.emptyPath"));
      return;
    }
    setBusy(true);
    setAddError(null);
    try {
      await addDirectoryAt(dir);
    } catch (failure: unknown) {
      setAddError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  };

  /// The shared core of every "new session": mint, bind, switch. See this file's
  /// header for why in that order -- and note that an id whose bind FAILED is
  /// never adopted, because the session does not exist and switching to it would
  /// leave the page on a thread with no home. Throws what the server threw; which
  /// slot that lands in is the caller's business, since only the caller knows
  /// which button was clicked.
  const startSessionIn = async (project: ProjectSummary): Promise<void> => {
    const id = crypto.randomUUID();
    await bindThread(id, project.path, tErrors);
    await runtime.threads.switchToThread(id);
  };

  /// A new session from the header button: in the derived selection, with the
  /// refusals landing under the header because that button has no row of its own.
  const newTask = async () => {
    if (busy) return;
    if (runInProgress(runtime)) {
      setNewTaskError(runInProgressNewThreadRefusal(t));
      return;
    }
    if (projects.length === 0) {
      // The refusal names the state; it does NOT open the picker. Popping a
      // modal OS window out of the button that says "New task" would be an
      // answer nobody asked for, and the folder button that does want it is
      // sitting next to this one.
      setNewTaskError(t("refusal.noProject"));
      return;
    }
    if (selected === null) {
      setNewTaskError(t("refusal.noProjectSelected"));
      return;
    }
    const project = selected;
    setBusy(true);
    setNewTaskError(null);
    setRowError(null);
    setProjectError(null);
    try {
      await startSessionIn(project);
      await refresh();
    } catch (failure: unknown) {
      setNewTaskError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  };

  /// A new session from a PROJECT'S OWN ROW: the same three steps in the project
  /// that was clicked, and the refusal lands on that row -- which is the whole
  /// reason this exists next to `newTask` rather than inside it. The project is
  /// not pinned: the switch makes this session the current one, and the selection
  /// is derived from that (see the effect above), so a pin would be a second
  /// opinion about a fact already settled.
  const newSession = async (project: ProjectSummary): Promise<void> => {
    if (busy) return;
    if (runInProgress(runtime)) {
      // The same sentence the header button uses, because it is the same reason
      // -- a run belongs to the thread it started on -- said on the row whose
      // click raised it. A paraphrase would be a second wording of one rule.
      setProjectError({ path: project.path, message: runInProgressNewThreadRefusal(t) });
      return;
    }
    setBusy(true);
    setProjectError(null);
    setRowError(null);
    setNewTaskError(null);
    try {
      await startSessionIn(project);
      await refresh();
    } catch (failure: unknown) {
      setProjectError({
        path: project.path,
        message: failure instanceof Error ? failure.message : String(failure),
      });
    } finally {
      setBusy(false);
    }
  };

  const running = runInProgress(runtime);

  return (
    <aside
      data-slot="sidebar"
      className="bg-background flex h-full w-72 shrink-0 flex-col border-e"
    >
      <header
        data-slot="sidebar-header"
        className="flex shrink-0 items-center gap-1 px-2 py-2"
      >
        <Button
          variant="ghost"
          data-slot="sidebar-new-task"
          disabled={busy}
          onClick={() => void newTask()}
          className="hover:bg-muted h-8 flex-1 justify-start gap-2 rounded-md px-2.5 text-sm font-normal"
        >
          <SquarePenIcon data-slot="sidebar-new-task-icon" className="size-4 shrink-0" />
          {t("sidebar.newTask")}
        </Button>
        <Button
          variant="ghost"
          size="icon"
          data-slot="sidebar-add-project"
          disabled={busy}
          onClick={() => void addProjectNow()}
          title={t("sidebar.addProjectTitle")}
          className="text-muted-foreground hover:text-foreground size-8 p-0"
        >
          <FolderPlusIcon data-slot="sidebar-add-project-icon" className="size-4" />
          <span className="sr-only">{t("sidebar.addProject")}</span>
        </Button>
        <Button
          variant="ghost"
          size="icon"
          data-slot="sidebar-refresh"
          disabled={busy}
          onClick={() => void refresh()}
          title={t("sidebar.refreshTitle")}
          className="text-muted-foreground hover:text-foreground size-8 p-0"
        >
          <RefreshCwIcon
            data-slot="sidebar-refresh-icon"
            className={busy ? "size-4 animate-spin" : "size-4"}
          />
          <span className="sr-only">{t("sidebar.refresh")}</span>
        </Button>
      </header>

      {/* The refusals about starting a session go under the header, where the
          button that raised them is -- and so does the Add project button's own
          failure, for the same reason. */}
      {newTaskError !== null && (
        <p
          role="alert"
          data-slot="sidebar-new-task-error"
          className="text-destructive shrink-0 px-2.5 pb-1 text-xs"
        >
          {newTaskError}
        </p>
      )}

      {addError !== null && (
        <p
          role="alert"
          data-slot="sidebar-add-project-error"
          className="text-destructive shrink-0 px-2.5 pb-1 text-xs"
        >
          {addError}
        </p>
      )}

      {/* The way out of a machine with no folder dialog. Drawn ONLY then -- see
          `addProjectNow` for the one condition that shows it -- so that the
          field is recognisably an answer to something rather than a form this
          product decided not to have. */}
      {typingPath && (
        <form
          data-slot="sidebar-add-project-path"
          className="flex shrink-0 items-center gap-1 px-2.5 pb-1"
          onSubmit={(event) => {
            event.preventDefault();
            void addTypedPath();
          }}
        >
          <Input
            autoFocus
            disabled={busy}
            value={typedPath}
            onChange={(event) => setTypedPath(event.target.value)}
            placeholder={t("addProject.path")}
            aria-label={t("addProject.pathLabel")}
            data-slot="sidebar-add-project-path-input"
            className="h-7 text-xs"
          />
          <Button
            type="submit"
            variant="ghost"
            size="icon"
            disabled={busy}
            title={t("addProject.submit")}
            data-slot="sidebar-add-project-path-submit"
            className="text-muted-foreground hover:text-foreground size-7 p-0"
          >
            <CheckIcon className="size-4" />
            <span className="sr-only">{t("addProject.submit")}</span>
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            disabled={busy}
            title={t("addProject.cancel")}
            data-slot="sidebar-add-project-path-cancel"
            className="text-muted-foreground hover:text-foreground size-7 p-0"
            onClick={() => {
              setTypingPath(false);
              setTypedPath("");
              setAddError(null);
            }}
          >
            <XIcon className="size-4" />
            <span className="sr-only">{t("addProject.cancel")}</span>
          </Button>
        </form>
      )}

      <div
        data-slot="sidebar-scroll"
        // `contain: paint` states the invariant rather than patching one offender: the
        // list's content may never change the PAGE's size. It makes this element the
        // containing block for every absolutely positioned descendant inside it, so a
        // future row that forgets its `relative` (or an `sr-only` added straight into a
        // static wrapper) cannot push the document down again. The Radix menus and
        // tooltips are portaled to `body`, so they are not descendants and are
        // unaffected -- verified by opening the project menu with this in place.
        className="min-h-0 flex-1 overflow-y-auto px-2 pb-2 [contain:paint]"
      >
        {listError !== null && (
          <p role="alert" data-slot="sidebar-list-error" className="text-destructive px-1.5 py-1 text-xs">
            {listError}
          </p>
        )}

        {projects.map((project) => (
          <ProjectSection
            key={project.projectId}
            project={project}
            currentThreadId={currentThreadId}
            selected={selected?.path === project.path}
            onSelect={() => setPinned(project.path)}
            busy={busy}
            running={running}
            rowError={rowError}
            removeError={projectError?.path === project.path ? projectError.message : null}
            onOpen={(threadId) => void openThread(threadId, project.path)}
            onArchive={(threadId, archived) => void archive(project, threadId, archived)}
            onNewSession={() => void newSession(project)}
            onRemove={() => void remove(project)}
          />
        ))}

        {loaded && projects.length === 0 && listError === null && (
          <p data-slot="sidebar-empty" className="text-muted-foreground px-1.5 py-4 text-xs">
            {t("sidebar.empty")}
          </p>
        )}
      </div>

      {/* The third region, pinned like the first. "Settings" opens the read-only
          report -- what this session is running on, where each choice came from,
          and whether a key is configured. It is a REPORT and not a form: nothing
          in it writes anything, which is why opening it is safe while a run is
          in flight and why it never touches the current session. */}
      <footer
        data-slot="sidebar-footer"
        className="shrink-0 border-t px-2 py-2"
      >
        <Button
          variant="ghost"
          data-slot="sidebar-settings"
          onClick={() => setSettingsOpen(true)}
          title={t("sidebar.settingsTitle")}
          className="text-muted-foreground hover:text-foreground h-8 w-full justify-start gap-2 rounded-md px-2.5 text-sm font-normal"
        >
          <SettingsIcon data-slot="sidebar-settings-icon" className="size-4 shrink-0" />
          {t("sidebar.settings")}
        </Button>
      </footer>

      <SettingsPanel
        open={settingsOpen}
        onOpenChange={setSettingsOpen}
        threadId={currentThreadId}
      />
    </aside>
  );
};

/// One project and the sessions in it: the project's row, then its conversations
/// in last-activity order (the server's order -- see `newest-first`), then -- when
/// there are any -- an Archived group collapsed by default.
///
/// The row carries THREE hits: the row itself, which selects the project and
/// folds it; a new-session button; and a "more" button that appears on hover and
/// opens the menu holding "Remove". The last two are SIBLINGS of the
/// row rather than children of it, for the reason this file's header gives: a
/// trigger nested in the row would fold the folder open every time somebody
/// reached for it.
///
/// The new-session button is a sibling for exactly that reason too -- it is where
/// the session count used to be, which only looked like part of the row because a
/// number cannot be clicked. It stays VISIBLE rather than appearing on hover, the
/// way the count did: it is the row's primary verb, and an action you have to go
/// looking for is one people do not find.
const ProjectSection: FC<{
  project: ProjectSummary;
  currentThreadId: string;
  selected: boolean;
  onSelect: () => void;
  busy: boolean;
  running: boolean;
  rowError: RowError;
  removeError: string | null;
  onOpen: (threadId: string) => void;
  onArchive: (threadId: string, archived: boolean) => void;
  onNewSession: () => void;
  onRemove: () => void;
}> = ({
  project,
  currentThreadId,
  selected,
  onSelect,
  busy,
  running,
  rowError,
  removeError,
  onOpen,
  onArchive,
  onNewSession,
  onRemove,
}) => {
  const { t } = useTranslation();
  // If the session on screen is in this project -- among the ones the project
  // draws in its main list -- the project opens with it. A collapsed project
  // hiding the conversation being read would make the highlight invisible exactly
  // when it matters most. An ARCHIVED current session is not this case: it is the
  // Archived group's business below, because that is the list it is drawn in.
  const holdsCurrent = project.sessions.some(
    (s) => !s.archived && s.threadId === currentThreadId,
  );
  const [open, setOpen] = useState(true);
  useEffect(() => {
    if (holdsCurrent) setOpen(true);
  }, [holdsCurrent]);
  // The Archived group's own state, and it does NOT follow the project's: a
  // session you filed away is one you asked not to be shown, so nothing here
  // reopens it for you. It does open itself when the CURRENT session is in there
  // -- which happens either because it was archived from elsewhere (another
  // window, the API) or because you deliberately opened a row from this group.
  // Either way, hiding it would be hiding the answer to "what am I reading".
  const [archivedOpen, setArchivedOpen] = useState(false);
  useEffect(() => {
    if (project.sessions.some((s) => s.archived && s.threadId === currentThreadId)) {
      setArchivedOpen(true);
    }
  }, [project.sessions, currentThreadId]);
  // Whether the confirmation is up. Held here rather than in the sidebar because
  // the menu item that opens it belongs to this row, and a row that has been
  // removed -- or is being removed -- cannot have a dialog of its own.
  const [confirming, setConfirming] = useState(false);
  useEffect(() => {
    if (removeError !== null) setConfirming(false);
  }, [removeError]);

  const name = projectName(project.path);
  const sessions: readonly SessionSummary[] = project.sessions;
  // The split is the client's because the SERVER sends both, flagged and in one
  // order -- see the endpoint's docstring: which group to draw them in is the
  // screen's decision, and a listing that dropped them would make "where did my
  // session go" a question with no server-side answer.
  const active = sessions.filter((s) => !s.archived);
  const archived = sessions.filter((s) => s.archived);

  const row = (session: SessionSummary) => (
    <ThreadListItem
      key={session.threadId}
      session={session}
      current={session.threadId === currentThreadId}
      busy={busy}
      running={running && session.threadId === currentThreadId}
      onOpen={() => onOpen(session.threadId)}
      error={rowError?.id === session.threadId ? rowError.message : null}
      actions={
        <ThreadListItemAction
          data-slot="thread-list-item-archive"
          data-archived={session.archived ? "" : undefined}
          disabled={busy}
          title={
            session.archived ? t("session.unarchiveTitle") : t("session.archiveTitle")
          }
          onClick={() => onArchive(session.threadId, !session.archived)}
        >
          {session.archived ? (
            <ArchiveRestoreIcon className="size-3.5" />
          ) : (
            <ArchiveIcon className="size-3.5" />
          )}
          <span className="sr-only">
            {session.archived ? t("session.unarchive") : t("session.archive")}
          </span>
        </ThreadListItemAction>
      }
    />
  );

  return (
    <section
      data-slot="sidebar-project"
      data-path={project.path}
      data-selected={selected ? "" : undefined}
      className="mt-1"
    >
      <div
        data-slot="sidebar-project-row"
        // `group` so the "more" button is revealed by hovering anywhere on the
        // row, and `focus-within` so it is there for the keyboard too -- a hover
        // affordance with no keyboard path is a verb half the people cannot use.
        // `relative` IS LOAD-BEARING, and it is not about the hover: every button in
        // this row carries an `sr-only` span, and `sr-only` is `position: absolute`.
        // With no positioned ancestor those spans' containing block is the PAGE, so
        // the last project's "New session"/"More" sit at their real page offset --
        // thousands of pixels down a long sidebar -- and the scrolling list CANNOT clip
        // them (a clip does not reach a descendant whose containing block is outside
        // it). The document grows to include them and the whole page scrolls into blank
        // space. Measured: 20 projects made the document 2432px tall in a 900px window,
        // and this one class took it back to 900.
        className={
          selected
            ? "group relative bg-muted/70 flex w-full items-center rounded-md"
            : "group relative hover:bg-muted/60 flex w-full items-center rounded-md"
        }
      >
        <button
          type="button"
          data-slot="sidebar-project-trigger"
          aria-expanded={open}
          // The full path on hover, because the visible name is the last segment
          // -- and two directories named `foo` are told apart by nothing else.
          title={project.path}
          onClick={() => {
            // Select AND toggle in one click, because the two are the same
            // intention here: pointing at a project is how you say "here". The
            // selection is what a new task uses.
            onSelect();
            setOpen((was) => !was);
          }}
          className="flex min-w-0 flex-1 items-center gap-1.5 rounded-md px-1.5 py-1 text-start"
        >
          <FolderIcon
            data-slot="sidebar-project-icon"
            className="text-muted-foreground size-4 shrink-0"
          />
          <span
            data-slot="sidebar-project-name"
            className="min-w-0 flex-1 truncate text-sm font-medium"
          >
            {name}
          </span>
        </button>
        <Button
          variant="ghost"
          size="icon-xs"
          data-slot="sidebar-project-new-session"
          // Disabled only while a request is in flight. A run in flight is NOT
          // this button's disabled state: it is a refusal WITH A SENTENCE, and
          // the click is how you get to read it (see `newSession`).
          disabled={busy}
          onClick={onNewSession}
          title={t("project.newSessionTitle")}
          className="text-muted-foreground hover:text-foreground shrink-0"
        >
          <SquarePenIcon
            data-slot="sidebar-project-new-session-icon"
            className="size-3.5"
          />
          <span className="sr-only">{t("project.newSession")}</span>
        </Button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="ghost"
              size="icon-xs"
              data-slot="sidebar-project-more"
              disabled={busy}
              title={t("project.moreTitle")}
              className="text-muted-foreground hover:text-foreground me-1 shrink-0 opacity-0 group-hover:opacity-100 group-focus-within:opacity-100 aria-expanded:opacity-100"
            >
              <MoreHorizontalIcon
                data-slot="sidebar-project-more-icon"
                className="size-3.5"
              />
              <span className="sr-only">{t("project.more")}</span>
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent data-slot="sidebar-project-menu" align="end">
            <DropdownMenuItem
              data-slot="sidebar-project-remove"
              // The menu item's own `disabled` is what the ticket asks for: the
              // request is in flight, so every action in this sidebar is off.
              disabled={busy}
              onSelect={() => setConfirming(true)}
            >
              <FolderMinusIcon data-slot="sidebar-project-remove-icon" />
              {/* ONE WORD, BECAUSE THE MENU IS ONLY AS WIDE AS ITS TRIGGER.
                  `DropdownMenuContent` is `w-(--radix-dropdown-menu-trigger-width)
                  min-w-32`, and this trigger is a 24px icon button -- so the menu
                  is 128px wide and the content clips its overflow. "Remove
                  project…" did not fit and wrapped onto two lines inside a
                  one-line row. What this menu offers, in a menu that is about a
                  project, needs no qualifier; the dialog it opens says the rest,
                  at a width that has room for it. */}
              {t("project.remove")}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      {/* The server's reason, on the row whose click raised it. Moved here from
          the session list because the thing that failed is now the project. */}
      {removeError !== null && (
        <p
          role="alert"
          data-slot="sidebar-project-error"
          className="text-destructive px-2.5 pb-1 text-xs"
        >
          {removeError}
        </p>
      )}

      {/* WHAT THE CONFIRMATION SAYS, AND WHY IT SAYS IT THAT WAY. It does not use
          the word "delete", because this dialog does not delete anything: the
          sessions' logs stay on disk, and re-adding the directory brings the
          sessions -- and their archive flags -- back. A confirmation that
          described a deletion would be describing a product that does not exist,
          and one that exaggerated the danger would train people to click through
          the dialogs that are not exaggerating. */}
      <Dialog open={confirming} onOpenChange={setConfirming}>
        <DialogContent data-slot="sidebar-remove-confirm" showCloseButton={false}>
          <DialogHeader>
            <DialogTitle>{t("dialog.removeTitle")}</DialogTitle>
            {/* min-w-0 AND A BREAKABLE PATH ARE LOAD-BEARING, not tidiness.
                `DialogContent` is a CSS GRID, and a grid's implicit column is
                `auto` -- which cannot shrink below its items' MIN-CONTENT width.
                A project path is one unbreakable token, so a long one (a temp
                directory, a deep checkout) pushed the column wider than the
                dialog's own 384px box: every child stretched to the column,
                including the full-bleed `-mx-4` footer, while the dialog's
                background painted only its border box. That is the misalignment
                -- a footer sticking out past the white pane with a rounded
                corner of its own.
                `[overflow-wrap:anywhere]` collapses the path's min-content width
                to a single character, so the column can no longer be widened by
                it, and `min-w-0` lets this block shrink as well.

                THE SENTENCE IS TWO CATALOG KEYS AROUND THE PATH, and that is
                what keeps the name and the path as the styled elements they
                were: i18next's `t` returns a string, so a single key holding
                both would flatten the `<span>` and the `<code>` into plain text.
                Each half is a whole clause, so a language that orders them
                differently can still say the same thing. */}
            <DialogDescription className="min-w-0">
              <span data-slot="sidebar-remove-name" className="font-medium">
                {name}
              </span>{" "}
              {t("dialog.removeLeaves")}{" "}
              <code className="font-mono text-xs [overflow-wrap:anywhere]">{project.path}</code>{" "}
              {t("dialog.removeTail")}
            </DialogDescription>
          </DialogHeader>
          <p
            data-slot="sidebar-remove-count"
            className="text-muted-foreground text-xs"
          >
            {sessions.length === 0
              ? t("dialog.removeNone")
              : t("dialog.removeSessions", { count: sessions.length })}
          </p>
          <DialogFooter>
            <Button
              variant="ghost"
              data-slot="sidebar-remove-cancel"
              onClick={() => setConfirming(false)}
            >
              {t("dialog.keep")}
            </Button>
            <Button
              variant="destructive"
              data-slot="sidebar-remove-submit"
              disabled={busy}
              onClick={() => {
                setConfirming(false);
                onRemove();
              }}
            >
              {t("dialog.removeSubmit")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {open && (
        <>
          <ul data-slot="sidebar-sessions" className="flex flex-col gap-0.5">
            {active.map(row)}
            {active.length === 0 && (
              <li className="text-muted-foreground px-2.5 py-1 text-xs">
                {archived.length === 0
                  ? t("session.empty")
                  : t("session.allArchived")}
              </li>
            )}
          </ul>

          {/* The whole group is one conditional: a project with nothing archived
              draws no header, no chevron and no empty list. */}
          {archived.length > 0 && (
            <div data-slot="sidebar-archived" className="mt-0.5">
              <button
                type="button"
                data-slot="sidebar-archived-trigger"
                aria-expanded={archivedOpen}
                onClick={() => setArchivedOpen((was) => !was)}
                className="text-muted-foreground hover:bg-muted/60 hover:text-foreground flex w-full items-center gap-1 rounded-md px-1.5 py-1 text-start text-xs"
              >
                <ChevronRightIcon
                  aria-hidden
                  className={
                    archivedOpen ? "size-3.5 shrink-0 rotate-90" : "size-3.5 shrink-0"
                  }
                />
                <span className="flex-1">{t("session.archived")}</span>
                <span className="shrink-0 tabular-nums">{archived.length}</span>
              </button>
              {archivedOpen && (
                <ul data-slot="sidebar-archived-sessions" className="flex flex-col gap-0.5">
                  {archived.map(row)}
                </ul>
              )}
            </div>
          )}
        </>
      )}
    </section>
  );
};

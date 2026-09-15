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
// settles. `lib/run-state.ts` holds the sentences, because the adapter refuses
// for the same reasons and the two must not word them differently.
import { useCallback, useEffect, useState, type FC } from "react";
import type { AssistantRuntime } from "@assistant-ui/react";
import {
  ArchiveIcon,
  ArchiveRestoreIcon,
  ChevronRightIcon,
  FolderIcon,
  FolderMinusIcon,
  FolderPlusIcon,
  FolderSearchIcon,
  MoreHorizontalIcon,
  RefreshCwIcon,
  SettingsIcon,
  SquarePenIcon,
} from "lucide-react";

import {
  ThreadListItem,
  ThreadListItemAction,
} from "@/components/assistant-ui/elements/thread-list.aui";
import { Button } from "@/components/ui/button";
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
  projectName,
  removeProject,
  setArchived,
  type ProjectSummary,
  type SessionSummary,
} from "@/lib/projects";
import {
  RUN_IN_PROGRESS_NEW_THREAD_REFUSAL,
  RUN_IN_PROGRESS_REFUSAL,
  runInProgress,
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
/// under the New task button, which is the thing that was clicked.
export const NO_PROJECT_REFUSAL =
  "Add a project first — a session belongs to a project.";
export const NO_PROJECT_SELECTED_REFUSAL =
  "Pick a project first — a session belongs to a project.";

export const Sidebar: FC<SidebarProps> = ({ runtime, currentThreadId }) => {
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [listError, setListError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [rowError, setRowError] = useState<RowError>(null);
  const [projectError, setProjectError] = useState<ProjectError>(null);
  const [newTaskError, setNewTaskError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // The project a new task will land in. Derived rather than owned: see the
  // effect below. Only an explicit click pins it.
  const [pinned, setPinned] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  // The settings report is a MODAL rather than a fourth region: it is read
  // once and closed, it does not compete with the list for the middle strip, and
  // it is drawn over the page so that reading it cannot be mistaken for
  // navigating away from the conversation behind it.
  const [settingsOpen, setSettingsOpen] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setProjects(await listProjects());
      setListError(null);
    } catch (failure: unknown) {
      setListError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setLoaded(true);
    }
  }, []);

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
      refuse(threadId, RUN_IN_PROGRESS_REFUSAL);
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
      refuse(threadId, RUN_IN_PROGRESS_REFUSAL);
      return;
    }
    setBusy(true);
    setRowError(null);
    try {
      await setArchived(threadId, archived);
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
          await bindThread(id, project.path);
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
      setProjectError({ path: project.path, message: RUN_IN_PROGRESS_REFUSAL });
      return;
    }
    setBusy(true);
    setProjectError(null);
    setRowError(null);
    try {
      await removeProject(project.path);
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

  const newTask = async () => {
    if (busy) return;
    if (runInProgress(runtime)) {
      setNewTaskError(RUN_IN_PROGRESS_NEW_THREAD_REFUSAL);
      return;
    }
    if (projects.length === 0) {
      setNewTaskError(NO_PROJECT_REFUSAL);
      setAdding(true);
      return;
    }
    if (selected === null) {
      setNewTaskError(NO_PROJECT_SELECTED_REFUSAL);
      return;
    }
    const project = selected;
    setBusy(true);
    setNewTaskError(null);
    setRowError(null);
    try {
      // Mint, then bind, then switch -- see this file's header for why in that
      // order. An id whose bind FAILED is never adopted: the session does not
      // exist, and switching to it would leave the page on a thread with no home.
      const id = crypto.randomUUID();
      await bindThread(id, project.path);
      await runtime.threads.switchToThread(id);
      await refresh();
    } catch (failure: unknown) {
      setNewTaskError(failure instanceof Error ? failure.message : String(failure));
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
          New task
        </Button>
        <Button
          variant="ghost"
          size="icon"
          data-slot="sidebar-add-project-toggle"
          disabled={busy}
          onClick={() => {
            setAdding((was) => !was);
            setNewTaskError(null);
          }}
          title="Add a project — a directory this home's sessions can live in"
          className="text-muted-foreground hover:text-foreground size-8 p-0"
        >
          <FolderPlusIcon data-slot="sidebar-add-project-icon" className="size-4" />
          <span className="sr-only">Add project</span>
        </Button>
        <Button
          variant="ghost"
          size="icon"
          data-slot="sidebar-refresh"
          disabled={busy}
          onClick={() => void refresh()}
          title="Re-read the list from disk and the store"
          className="text-muted-foreground hover:text-foreground size-8 p-0"
        >
          <RefreshCwIcon
            data-slot="sidebar-refresh-icon"
            className={busy ? "size-4 animate-spin" : "size-4"}
          />
          <span className="sr-only">Refresh</span>
        </Button>
      </header>

      {/* The refusals about starting a session go under the header, where the
          button that raised them is. */}
      {newTaskError !== null && (
        <p
          role="alert"
          data-slot="sidebar-new-task-error"
          className="text-destructive shrink-0 px-2.5 pb-1 text-xs"
        >
          {newTaskError}
        </p>
      )}

      <div
        data-slot="sidebar-scroll"
        className="min-h-0 flex-1 overflow-y-auto px-2 pb-2"
      >
        <AddProject
          open={adding}
          busy={busy}
          onOpenChange={setAdding}
          onAdded={async (path) => {
            setAdding(false);
            // The refusal under the New task button named a state that adding a
            // project has just ended; leaving it up would have the sidebar
            // contradicting itself one line above the new project's row.
            setNewTaskError(null);
            setPinned(path);
            await refresh();
          }}
        />

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
            onRemove={() => void remove(project)}
          />
        ))}

        {loaded && projects.length === 0 && listError === null && !adding && (
          <p data-slot="sidebar-empty" className="text-muted-foreground px-1.5 py-4 text-xs">
            No projects yet. A session belongs to a project, so one has to be
            added before a task can start.
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
          title="What this session is running on — read-only"
          className="text-muted-foreground hover:text-foreground h-8 w-full justify-start gap-2 rounded-md px-2.5 text-sm font-normal"
        >
          <SettingsIcon data-slot="sidebar-settings-icon" className="size-4 shrink-0" />
          Settings
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

/// Adding a project: a directory path, typed or picked, and an explicit submit.
///
/// TWO FILLS, ONE SUBMIT, AND THE SUBMIT IS ALWAYS EXPLICIT. The folder dialog
/// only fills the field -- picking a folder is not a one-step commit, because the
/// OS dialog is one keystroke from a stray selection and the store has no undo.
/// Cancelling the dialog is reported as what it is (nothing happened), not as an
/// error.
///
/// The form stays open on failure with the server's own reason under it, so the
/// path that failed is still there to fix. On success the caller closes it.
const AddProject: FC<{
  open: boolean;
  busy: boolean;
  onOpenChange: (open: boolean) => void;
  onAdded: (path: string) => Promise<void>;
}> = ({ open, busy, onOpenChange, onAdded }) => {
  const [dir, setDir] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [inFlight, setInFlight] = useState(false);

  if (!open) return null;

  const submit = async () => {
    if (inFlight || busy) return;
    if (dir.trim() === "") {
      setError("Give an absolute path to a directory.");
      return;
    }
    setInFlight(true);
    setError(null);
    setNotice(null);
    try {
      const added = await addProject(dir.trim());
      setDir("");
      // The server answers the CANONICAL path, and that is what the caller
      // selects -- so adding `~/proj` and then re-adding `~/proj/.` end on the
      // same row rather than two selections that look different and are not.
      await onAdded(added.path);
    } catch (failure: unknown) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setInFlight(false);
    }
  };

  const browse = async () => {
    if (inFlight || busy) return;
    setInFlight(true);
    setError(null);
    setNotice(null);
    try {
      const picked = await pickFolder();
      if (picked === null) {
        setNotice("Selection cancelled — nothing was added.");
      } else {
        setDir(picked);
        setNotice(null);
      }
    } catch (failure: unknown) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setInFlight(false);
    }
  };

  const pending = inFlight || busy;

  return (
    <section
      data-slot="sidebar-add-project"
      className="mt-1 mb-2 rounded-md border px-2 py-2"
    >
      <h3 className="text-muted-foreground mb-1 text-xs font-semibold tracking-wide uppercase">
        Add project
      </h3>
      <input
        type="text"
        data-slot="sidebar-add-project-path"
        aria-label="Project directory"
        placeholder="/absolute/path/to/a/directory"
        value={dir}
        disabled={pending}
        onChange={(event) => setDir(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            event.preventDefault();
            void submit();
          }
        }}
        className="border-input focus-visible:ring-ring/50 h-8 w-full rounded-md border bg-transparent px-2 font-mono text-xs outline-none focus-visible:ring-1 disabled:opacity-60"
      />
      {/* The two fills side by side, and BOTH are disabled while either is in
          flight: two clicks landing together would be two writes. */}
      <div className="mt-1 flex items-center gap-1">
        <Button
          size="sm"
          variant="ghost"
          data-slot="sidebar-add-project-submit"
          disabled={pending}
          onClick={() => void submit()}
          className="h-7 px-2 text-xs"
        >
          Add
        </Button>
        <Button
          size="sm"
          variant="ghost"
          data-slot="sidebar-add-project-browse"
          disabled={pending}
          onClick={() => void browse()}
          className="h-7 px-2 text-xs"
        >
          <FolderSearchIcon
            data-slot="sidebar-add-project-browse-icon"
            className="size-3.5"
          />
          Choose folder…
        </Button>
        <Button
          size="sm"
          variant="ghost"
          data-slot="sidebar-add-project-cancel"
          disabled={pending}
          onClick={() => onOpenChange(false)}
          className="text-muted-foreground ml-auto h-7 px-2 text-xs"
        >
          Cancel
        </Button>
      </div>
      {error !== null && (
        <p role="alert" data-slot="sidebar-add-project-error" className="text-destructive mt-1 text-xs">
          {error}
        </p>
      )}
      {notice !== null && (
        <p data-slot="sidebar-add-project-notice" className="text-muted-foreground mt-1 text-xs">
          {notice}
        </p>
      )}
    </section>
  );
};

/// One project and the sessions in it: the project's row, then its conversations
/// in last-activity order (the server's order -- see `newest-first`), then -- when
/// there are any -- an Archived group collapsed by default.
///
/// The row carries two hits: the row itself, which selects the project and folds
/// it, and a "more" button that appears on hover and opens the menu holding
/// "Remove project…". They are SIBLINGS for the reason this file's header gives:
/// a trigger nested in the row would fold the folder open every time somebody
/// reached for the menu.
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
  onRemove,
}) => {
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
            session.archived
              ? "Unarchive — put this session back with the project's other sessions"
              : "Archive — keep this session and its log, but move it out of the way"
          }
          onClick={() => onArchive(session.threadId, !session.archived)}
        >
          {session.archived ? (
            <ArchiveRestoreIcon className="size-3.5" />
          ) : (
            <ArchiveIcon className="size-3.5" />
          )}
          <span className="sr-only">{session.archived ? "Unarchive" : "Archive"}</span>
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
        className={
          selected
            ? "group bg-muted/70 flex w-full items-center rounded-md"
            : "group hover:bg-muted/60 flex w-full items-center rounded-md"
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
          {/* The count is of the sessions you can SEE, which is the unarchived
              ones: a badge that counted the archived too would make "3" mean
              "3 rows once you go looking for them". */}
          <span className="text-muted-foreground shrink-0 text-xs tabular-nums">
            {active.length}
          </span>
        </button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="ghost"
              size="icon-xs"
              data-slot="sidebar-project-more"
              disabled={busy}
              title="More — actions for this project"
              className="text-muted-foreground hover:text-foreground me-1 shrink-0 opacity-0 group-hover:opacity-100 group-focus-within:opacity-100 aria-expanded:opacity-100"
            >
              <MoreHorizontalIcon
                data-slot="sidebar-project-more-icon"
                className="size-3.5"
              />
              <span className="sr-only">More</span>
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
              Remove project…
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
            <DialogTitle>Remove this project?</DialogTitle>
            <DialogDescription>
              <span data-slot="sidebar-remove-name" className="font-medium">
                {name}
              </span>{" "}
              leaves the sidebar. Its sessions are kept on disk — nothing under{" "}
              <code className="font-mono text-xs">{project.path}</code> is deleted
              or moved — and adding this directory again brings them back, as they
              were.
            </DialogDescription>
          </DialogHeader>
          <p
            data-slot="sidebar-remove-count"
            className="text-muted-foreground text-xs"
          >
            {sessions.length === 0
              ? "It has no sessions."
              : `${sessions.length} session${sessions.length === 1 ? "" : "s"} will stop being listed here.`}
          </p>
          <DialogFooter>
            <Button
              variant="ghost"
              data-slot="sidebar-remove-cancel"
              onClick={() => setConfirming(false)}
            >
              Keep it
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
              Remove project
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
                  ? "No sessions in this project yet."
                  : "Every session here is archived."}
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
                <span className="flex-1">Archived</span>
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

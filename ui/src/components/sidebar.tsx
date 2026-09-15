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
  FolderIcon,
  FolderPlusIcon,
  FolderSearchIcon,
  RefreshCwIcon,
  SettingsIcon,
  SquarePenIcon,
} from "lucide-react";

import { ThreadListItem } from "@/components/assistant-ui/elements/thread-list.aui";
import { Button } from "@/components/ui/button";
import {
  addProject,
  bindThread,
  listProjects,
  pickFolder,
  projectName,
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
  const [newTaskError, setNewTaskError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // The project a new task will land in. Derived rather than owned: see the
  // effect below. Only an explicit click pins it.
  const [pinned, setPinned] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

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
            onOpen={(threadId) => void openThread(threadId, project.path)}
          />
        ))}

        {loaded && projects.length === 0 && listError === null && !adding && (
          <p data-slot="sidebar-empty" className="text-muted-foreground px-1.5 py-4 text-xs">
            No projects yet. A session belongs to a project, so one has to be
            added before a task can start.
          </p>
        )}
      </div>

      {/* The third region, pinned like the first. It holds a position rather
          than a verb for now: the read-only configuration view is ticket 08's,
          and this button is the seat it will fill. */}
      <footer
        data-slot="sidebar-footer"
        className="shrink-0 border-t px-2 py-2"
      >
        <Button
          variant="ghost"
          disabled
          data-slot="sidebar-settings"
          title="The read-only configuration view arrives with ticket 08"
          className="text-muted-foreground h-8 w-full justify-start gap-2 rounded-md px-2.5 text-sm font-normal"
        >
          <SettingsIcon data-slot="sidebar-settings-icon" className="size-4 shrink-0" />
          Settings
        </Button>
      </footer>
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
/// in last-activity order (the server's order -- see `newest-first`).
const ProjectSection: FC<{
  project: ProjectSummary;
  currentThreadId: string;
  selected: boolean;
  onSelect: () => void;
  busy: boolean;
  running: boolean;
  rowError: RowError;
  onOpen: (threadId: string) => void;
}> = ({ project, currentThreadId, selected, onSelect, busy, running, rowError, onOpen }) => {
  // If the session on screen is in this project, the project opens with it. A
  // collapsed project hiding the conversation being read would make the highlight
  // invisible exactly when it matters most.
  const holdsCurrent = project.sessions.some((s) => s.threadId === currentThreadId);
  const [open, setOpen] = useState(true);
  useEffect(() => {
    if (holdsCurrent) setOpen(true);
  }, [holdsCurrent]);

  const name = projectName(project.path);
  const sessions: readonly SessionSummary[] = project.sessions;

  return (
    <section
      data-slot="sidebar-project"
      data-path={project.path}
      data-selected={selected ? "" : undefined}
      className="mt-1"
    >
      <button
        type="button"
        data-slot="sidebar-project-trigger"
        aria-expanded={open}
        // The full path on hover, because the visible name is the last segment --
        // and two directories named `foo` are told apart by nothing else.
        title={project.path}
        onClick={() => {
          // Select AND toggle in one click, because the two are the same
          // intention here: pointing at a project is how you say "here". The
          // selection is what a new task uses.
          onSelect();
          setOpen((was) => !was);
        }}
        className={
          selected
            ? "bg-muted/70 hover:bg-muted flex w-full items-center gap-1.5 rounded-md px-1.5 py-1 text-start"
            : "hover:bg-muted/60 flex w-full items-center gap-1.5 rounded-md px-1.5 py-1 text-start"
        }
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
        <span className="text-muted-foreground shrink-0 text-xs tabular-nums">
          {sessions.length}
        </span>
      </button>

      {open && (
        <ul data-slot="sidebar-sessions" className="flex flex-col gap-0.5">
          {sessions.map((session: SessionSummary) => (
            <ThreadListItem
              key={session.threadId}
              session={session}
              current={session.threadId === currentThreadId}
              busy={busy}
              running={running && session.threadId === currentThreadId}
              onOpen={() => onOpen(session.threadId)}
              error={rowError?.id === session.threadId ? rowError.message : null}
            />
          ))}
          {sessions.length === 0 && (
            <li className="text-muted-foreground px-2.5 py-1 text-xs">
              No sessions in this project yet.
            </li>
          )}
        </ul>
      )}
    </section>
  );
};

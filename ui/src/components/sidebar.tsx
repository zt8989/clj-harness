// The sidebar: new task, projects, sessions.
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
// One fetch of `GET /api/projects` answers the entire sidebar. The store decides
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
import { FolderIcon, RefreshCwIcon, SquarePenIcon } from "lucide-react";

import { ThreadListItem } from "@/components/assistant-ui/elements/thread-list.aui";
import { Button } from "@/components/ui/button";
import { listProjects, projectName, type ProjectSummary, type SessionSummary } from "@/lib/projects";
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

export const Sidebar: FC<SidebarProps> = ({ runtime, currentThreadId }) => {
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [listError, setListError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [rowError, setRowError] = useState<RowError>(null);
  const [busy, setBusy] = useState(false);

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

  const refuse = (id: string, message: string) => setRowError({ id, message });

  const openThread = async (threadId: string) => {
    if (busy || threadId === currentThreadId) return;
    if (runInProgress(runtime)) {
      refuse(threadId, RUN_IN_PROGRESS_REFUSAL);
      return;
    }
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

  const newThread = async () => {
    if (busy) return;
    if (runInProgress(runtime)) {
      // There is no row to hang this on -- starting a session is not about an
      // existing one -- so it goes on the current row, which is the one the
      // reader was looking at when they clicked.
      refuse(currentThreadId, RUN_IN_PROGRESS_NEW_THREAD_REFUSAL);
      return;
    }
    setBusy(true);
    setRowError(null);
    try {
      await runtime.threads.switchToNewThread();
    } catch (failure: unknown) {
      refuse(currentThreadId, failure instanceof Error ? failure.message : String(failure));
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
        className="flex items-center gap-1 px-2 py-2"
      >
        <Button
          variant="ghost"
          data-slot="sidebar-new-task"
          disabled={busy}
          onClick={() => void newThread()}
          className="hover:bg-muted h-8 flex-1 justify-start gap-2 rounded-md px-2.5 text-sm font-normal"
        >
          <SquarePenIcon data-slot="sidebar-new-task-icon" className="size-4 shrink-0" />
          New task
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

      <div
        data-slot="sidebar-scroll"
        className="min-h-0 flex-1 overflow-y-auto px-2 pb-2"
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
            busy={busy}
            running={running}
            rowError={rowError}
            onOpen={(threadId) => void openThread(threadId)}
          />
        ))}

        {loaded && projects.length === 0 && listError === null && (
          <p data-slot="sidebar-empty" className="text-muted-foreground px-1.5 py-4 text-xs">
            No projects yet. A session belongs to a project, so one has to be
            added before a task can start.
          </p>
        )}
      </div>
    </aside>
  );
};

/// One project and the sessions in it: the project's row, then its conversations
/// in last-activity order (the server's order -- see `newest-first`).
const ProjectSection: FC<{
  project: ProjectSummary;
  currentThreadId: string;
  busy: boolean;
  running: boolean;
  rowError: RowError;
  onOpen: (threadId: string) => void;
}> = ({ project, currentThreadId, busy, running, rowError, onOpen }) => {
  // If the session on screen is in this project, the project opens with it. A
  // collapsed project hiding the conversation being read would make the highlight
  // invisible exactly when it matters most.
  const holdsCurrent = project.sessions.some((s) => s.threadId === currentThreadId);
  const [open, setOpen] = useState(true);
  useEffect(() => {
    if (holdsCurrent) setOpen(true);
  }, [holdsCurrent]);

  const name = projectName(project.path);
  const sessions = project.sessions;

  return (
    <section data-slot="sidebar-project" data-path={project.path} className="mt-1">
      <button
        type="button"
        data-slot="sidebar-project-trigger"
        aria-expanded={open}
        // The full path on hover, because the visible name is the last segment --
        // and two directories named `foo` are told apart by nothing else.
        title={project.path}
        onClick={() => setOpen((was) => !was)}
        className="hover:bg-muted/60 flex w-full items-center gap-1.5 rounded-md px-1.5 py-1 text-start"
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

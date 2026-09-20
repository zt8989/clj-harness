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
// One fetch of `GET /api/projects` answers the whole sidebar, and it answers it as
// TWO BLOCKS: the projects, each with its sessions, and the tasks -- conversations
// with no project and no memory of one, flat and ungrouped. The store decides which
// conversations exist, which belong where and which are archived; the tree supplies
// each log's size and mtime. The client joins nothing -- see `lib/projects.ts` for
// why that join is the server's.
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
// Starting a conversation is THREE STEPS IN THIS ORDER, and each one is
// load-bearing:
//
//   1. mint an id (the client owns ids -- the server has never minted one),
//   2. REGISTER that id, which is what makes the conversation exist at all -- for a
//      project session, BIND it (POST /api/project, which is also what makes it
//      exist); for a task, `POST /api/sessions`, which records it with no project
//      and no memory of one. Its log does not exist yet, and that is a state the
//      listing already handles.
//   3. SHOW that id -- `onShowFresh`, which is the page putting a host on screen
//      and NOT rebuilding: the id is this client's, it just made it up, and there
//      is no conversation under it yet.
//
// Step 3 is a page action rather than a runtime one, and that was the whole of the
// parallel-sessions ticket in this file: `runtime.threads.switchToThread` and its
// `switchToNewThread` sibling are gone, because their effect was to clear the core
// before refilling it -- and the core that is now streaming belongs to a host that
// never gets refilled. `onShowFresh` also keeps the id in this component's hands,
// which step 2 needs; letting the runtime mint one would put it out of reach.
//
// A NEW TASK NEEDS NO PROJECT, and that is the rule this feature turned over. The
// header's button used to start a session in the sidebar's SELECTED project and to
// refuse outright when this home had no projects ("add a project first"); now it
// mints an id, registers it as a conversation of this home with no project, and
// shows it. It lands in the TASK LIST above the projects a moment later. The old
// refusal was not a nudge -- it was the shape of the product, and the two sentences
// that expressed it (`refusal.noProject`, `refusal.noProjectSelected`) are gone from
// both catalogs rather than left behind as dead keys.
//
// THE SAME STEPS STILL RUN FROM A PROJECT'S OWN ROW, and that button is unchanged:
// it starts a session in THAT project, which is now the ONLY way to say so. The two
// buttons used to be one verb with a derived destination; they are two verbs now,
// which is what makes "where did this land" a question nobody has to ask.
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
// A SESSION THAT HAS NOT FINISHED REFUSES IT, and now that means the session
// being archived rather than the session on screen. A run is still appending to
// the log this row is filing away, and a resume still has somewhere to write,
// so the archive is refused BEFORE it is written -- rather than written and then
// left pointing at a log that moved. It is refused WHETHER IT IS ON SCREEN OR
// NOT (ticket 04): the old guard asked `=== currentThreadId` because one runtime
// made "running" and "on screen" the same fact, and with a host per session
// that question would let a background run be archived out from under itself.
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
// A PROJECT HOLDING A SESSION THAT HAS NOT FINISHED CANNOT BE REMOVED, because
// the log goes on being written (or a resume goes on having somewhere to write)
// while the row that names it leaves the sidebar. ANY such session stops it, not
// just one on screen: the same ticket-04 correction the archive guard needed, and
// the sentence names which session, because the button cannot. The registry lifts
// the guard by itself when the session settles.
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
// WHAT IS NO LONGER REFUSED: switching sessions and starting new ones. Both used
// to be refused while a run was in flight, because there was one runtime and a
// switch cleared the core the run was streaming into. There is one runtime PER
// SESSION now (see app.tsx), so a switch is a change of which host is on screen
// and the run it left behind keeps running into its own core. Nothing here reads
// a runtime any more -- the sidebar does not have one.
//
// WHAT IS STILL REFUSED: filing away a session that has not finished, and
// removing a project that has one inside it. The judgement is about THAT session
// -- it has a run still writing, or a resume that still has somewhere to write --
// and it comes from the page's registry rather than from whatever is on screen.
// That distinction is the whole of ticket 04: the old guard asked whether the
// CURRENT page was running, which was the same question only while one runtime
// existed, and let a background session be archived out from under itself.
//
// `lib/session-status.ts` words the sentences from the `shell` catalog -- they are
// this client's own words, not the server's, which are never translated -- because
// the row and the project menu both show them and two wordings of one rule drift.
import { useCallback, useEffect, useState, type FC } from "react";
import { useTranslation } from "react-i18next";
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

import {
  ThreadListItem,
  ThreadListItemAction,
} from "@/components/assistant-ui/elements/thread-list.aui";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { SettingsPanel } from "@/components/settings-panel";
import { SIDEBAR_ID, SidebarCollapseButton } from "@/components/sidebar-toggle";
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
  listSidebar,
  pickFolder,
  PickerUnavailableError,
  projectName,
  removeProject,
  setArchived,
  startTask,
  type ProjectSummary,
  type SessionSummary,
  type SidebarListing,
} from "@/lib/projects";
import {
  archiveRefusal,
  blocked,
  IDLE,
  removeProjectRefusal,
  type SessionStatus,
} from "@/lib/session-status";

type SidebarProps = {
  /// WHICH SESSION IS ON SCREEN. The sidebar does not own it and cannot change
  /// it by itself: it asks the page (see `onShow`), which is the same thing every
  /// `runtime.threads.switchToThread` call here used to do through a runtime.
  currentThreadId: string;
  /// ONE ANSWER PER SESSION, owned by the page and reported by each host: whether
  /// that session has a run in flight and whether it has stopped to ask a human.
  /// A session nothing has reported on reads `IDLE`.
  statuses: Record<string, SessionStatus>;
  /// The refusals that came from SESSIONS rather than from this component -- a
  /// history that would not load, keyed by the session it would not load for.
  /// The page owns them because the load happens in a host, not here; they are
  /// drawn in exactly the same place as this component's own row errors.
  openErrors: Record<string, string>;
  /// SHOW A SESSION THAT HAS A CONVERSATION: host it if it has no host yet and
  /// rebuild its history once, then put it on screen.
  onShow: (threadId: string) => void;
  /// SHOW A SESSION THIS CLIENT HAS JUST MINTED (and, on every path that has a
  /// project, just bound): nothing to rebuild, so the host starts empty. Kept
  /// distinct from `onShow` for that reason -- under a brand-new id there is no
  /// log, and asking the server to rebuild one is asking it to find a file that
  /// is not there.
  onShowFresh: (threadId: string) => void;
  /// EVERY LISTING THIS COMPONENT LANDS, handed up as it arrives. The page needs one
  /// of them and only one: the mount restore asks whether the session it remembers is
  /// still a session, and the answer is in exactly this payload (ticket 03). It is a
  /// callback rather than a second fetch ON PURPOSE -- the sidebar is already reading
  /// every session of every project, and a page that asked again would be asking the
  /// same question twice to get the same bytes.
  onListed: (listing: SidebarListing) => void;
  /// FOLD THIS COLUMN AWAY. The sidebar does not own that state and cannot put itself
  /// back -- a folded sidebar is a hidden subtree, which cannot draw a visible control --
  /// so folding is a request to the page, which is also what draws the floating control
  /// that unfolds it (see `components/sidebar-toggle.tsx`, where the pair and the
  /// `aria-controls` they share are explained).
  ///
  /// FOLDED MEANS HIDDEN, NOT UNMOUNTED, and that is load-bearing rather than thrift:
  /// this component's `refresh` is the ONE reader of `GET /api/projects`, and the page's
  /// mount restore learns whether the session it remembers still exists from exactly that
  /// reading (`app.tsx`, `onListed`). A fold that unmounted this would leave a phone-sized
  /// window unable to restore anything -- and would throw away the list's own state (where
  /// it was scrolled, which projects were folded open) on every fold.
  folded: boolean;
  onCollapse: () => void;
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
/// reads cannot be one (see `lib/session-status.ts` for the same argument).

export const Sidebar: FC<SidebarProps> = ({
  currentThreadId,
  statuses,
  openErrors,
  onShow,
  onShowFresh,
  onListed,
  folded,
  onCollapse,
}) => {
  const { t } = useTranslation();
  // The failures this list can raise are THIS side's sentences (a listing that
  // would not load, a bind the server answered without a reason), so they come from
  // the `errors` catalog while the panel's own words stay in `shell`.
  const { t: tErrors } = useTranslation("errors");
  const [listing, setListing] = useState<SidebarListing>({ projects: [], tasks: [] });
  // THE TWO BLOCKS, read off the one snapshot. `projects` is named here so that
  // every reader below keeps saying what it always said: the projects are one half
  // of the answer, and `listing.tasks` is the other.
  const projects = listing.projects;
  const tasks = listing.tasks;
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
  // WHICH PROJECT ROW IS LIT. It used to answer "where does the next task land" and
  // no longer answers anything but that -- see `selected` below. Only an explicit
  // click (or opening a session that has a project) sets it.
  const [pinned, setPinned] = useState<string | null>(null);
  // THE ARCHIVED BLOCK'S own state, and it does NOT follow anything else: a session
  // somebody filed away is one they asked not to be shown, so nothing here reopens it
  // for them. It does open itself when the CURRENT session is in there -- archived from
  // another window, from the API, or because you deliberately opened a row from this
  // block. Either way, hiding it would be hiding the answer to "what am I reading".
  const [archivedOpen, setArchivedOpen] = useState(false);
  // The settings report is a MODAL rather than a fourth region: it is read
  // once and closed, it does not compete with the list for the middle strip, and
  // it is drawn over the page so that reading it cannot be mistaken for
  // navigating away from the conversation behind it.
  const [settingsOpen, setSettingsOpen] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const listed = await listSidebar(tErrors);
      setListing(listed);
      setListError(null);
      onListed(listed);
    } catch (failure: unknown) {
      setListError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setLoaded(true);
    }
  }, [onListed]);

  // On mount, and again whenever the current thread changes -- opening a session
  // rebuilds it from its log, which appends an audit line to that very file, so
  // its size and mtime are stale the moment a switch succeeds.
  useEffect(() => {
    void refresh();
  }, [refresh, currentThreadId]);

  // WHICH PROJECT ROW IS LIT, derived so it cannot point at something that is gone.
  // The current session's project wins when it has one -- pointing at the project you
  // are standing in is what a person means by "here" -- and an explicit click pins a
  // project. A pin that names a project no longer in the list is dropped, otherwise
  // the row would keep pointing at a directory that is gone.
  //
  // IT DECIDES NOTHING BUT THAT, and that is the whole of the change: this used to
  // answer "where does the next task land", and the header's button makes a TASK now,
  // which belongs to no project by definition -- so nothing here is a destination any
  // more. What is left is what the row LOOKS like, and it is honest about the case that
  // used to be papered over: a session with no project lights NO project row, instead
  // of lighting the first one in the list.
  const currentProject = projects.find((p) =>
    p.sessions.some((s) => s.threadId === currentThreadId),
  );
  const pinnedStillListed = pinned !== null && projects.some((p) => p.path === pinned);
  const selected = pinnedStillListed
    ? projects.find((p) => p.path === pinned)!
    : (currentProject ?? null);

  /// EVERY ARCHIVED ROW, from both halves, as ONE list -- which is what makes the
  /// archived block a block rather than a per-project footnote (ticket 03).
  ///
  /// A PROJECT SESSION CARRIES ITS PROJECT (or null for a task), because the block is
  /// flat: with the grouping gone, nothing else on the row would say which directory
  /// the conversation came from -- and its log is still under that project's
  /// workspace.
  ///
  /// THE ORDER IS THE LISTING'S OWN RULE (`newest-first`), applied across the halves
  /// rather than within one of them, so the block reads the way the lists above it do.
  const archived = [
    ...projects.flatMap((project) =>
      project.sessions.filter((s) => s.archived).map((session) => ({ session, project })),
    ),
    ...tasks.filter((task) => task.archived).map((task) => ({ session: task, project: null })),
  ].sort(
    (a, b) =>
      (b.session.lastActivity ?? Number.MAX_VALUE) -
      (a.session.lastActivity ?? Number.MAX_VALUE),
  );
  // Whether the conversation on screen is IN that block -- a boolean, so the effect
  // below has a dependency that does not change identity on every render.
  const currentIsArchived = archived.some((row) => row.session.threadId === currentThreadId);
  useEffect(() => {
    if (currentIsArchived) setArchivedOpen(true);
  }, [currentIsArchived]);

  const refuse = (id: string, message: string) => setRowError({ id, message });

  /// SHOW A SESSION FROM THE LIST. Not refused, whatever its own status: a run in
  /// another session keeps running where it is (see this file's header), and a run
  /// in THIS one is not disturbed either -- the host that owns it stays mounted and
  /// keeps streaming while the page looks elsewhere.
  ///
  /// The rebuild that used to happen here (and whose server-side refusal used to be
  /// caught here) now belongs to the host: it loads its own history once, when it
  /// is first mounted. A history that will not load comes back through
  /// `openErrors`, so the sentence still lands on the row that was clicked.
  // `projectPath` is NULL for a TASK, and null is a fact rather than a missing
  // argument: the pin is dropped instead of pointed at something (see `selected`).
  const openThread = async (threadId: string, projectPath: string | null) => {
    if (busy || threadId === currentThreadId) return;
    setPinned(projectPath);
    setBusy(true);
    setRowError(null);
    try {
      onShow(threadId);
      await refresh();
    } finally {
      setBusy(false);
    }
  };

  /// Archive a session, or bring it back -- ONE VERB FOR BOTH BLOCKS, because a task
  /// and a session in a project are the same kind of thing here (see the header).
  /// REFUSED WHILE THAT SESSION HAS NOT FINISHED -- running or parked -- whatever is
  /// on screen (see this file's header): a run is still writing to the log being filed
  /// away, and a resume still has somewhere to write. Filing away a settled session
  /// you are not reading is a pure row write and stays allowed.
  ///
  /// `project` is NULL FOR A TASK, and that is the only difference between the two
  /// callers: it decides WHERE the page goes when the session it is reading is the one
  /// being filed away (see `movesThePage` below), because "the session's own kind" is
  /// the set you can be moved to.
  const archive = async (
    project: ProjectSummary | null,
    threadId: string,
    archived: boolean,
  ): Promise<void> => {
    if (busy) return;
    const status = statuses[threadId] ?? IDLE;
    if (blocked(status)) {
      refuse(threadId, archiveRefusal(t, status));
      return;
    }
    const movesThePage = archived && threadId === currentThreadId;
    setBusy(true);
    setRowError(null);
    try {
      await setArchived(threadId, archived, tErrors);
      if (movesThePage) {
        // "Never be reading an archived session", and where you land is decided by
        // WHAT KIND of session was filed away:
        //
        //   * a project session -- the same PROJECT's most recent unarchived
        //     session, which is the first one the server listed (the listing is
        //     newest-first), or, when there is none, the three steps that project
        //     row's own button runs;
        //   * a task -- the most recent unarchived TASK, or a brand-new one.
        //
        // The sets are NOT interchangeable, and that is the point of the branch: a
        // task is a conversation with no project, so being moved into some project's
        // session would make archiving a way of acquiring a home.
        const siblings = project === null ? tasks : project.sessions;
        const next = siblings.find((s) => !s.archived && s.threadId !== threadId);
        if (next !== undefined) {
          setPinned(project === null ? null : project.path);
          onShow(next.threadId);
        } else {
          const id = crypto.randomUUID();
          if (project === null) {
            await startTask(id, tErrors);
            setPinned(null);
          } else {
            await bindThread(id, project.path, tErrors);
            setPinned(project.path);
          }
          onShowFresh(id);
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
    // ANY session in this project that has not finished stops the removal --
    // not just one on screen (ticket 04, and ticket 05 for the parked case).
    // The two are the same reason: the log being taken out of the sidebar is
    // one a run is still appending to, or one a resume still has to reach.
    const unfinished = project.sessions.find((s) => blocked(statuses[s.threadId] ?? IDLE));
    if (unfinished !== undefined) {
      // Rendered on the PROJECT row, where the click landed, and it NAMES the
      // session, because the button cannot: a sentence that only said "a session"
      // would send the reader hunting for which one.
      setProjectError({
        path: project.path,
        message: removeProjectRefusal(
          t,
          unfinished.threadId,
          statuses[unfinished.threadId] ?? IDLE,
        ),
      });
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
        // at all. That last branch is `onShowFresh` with an id nobody bound --
        // minted here rather than by the runtime, which no longer mints: there is
        // nothing left to bind to, and the server tolerates a session with no
        // project by design. What neither branch does is leave the chat on the
        // project just removed, or invent a project to hold the new session.
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
          onShow(next.s.threadId);
        } else {
          // A BRAND-NEW TASK, and registered for the reason every other path
          // registers: the page must never be parked on a conversation the sidebar
          // cannot draw (see `newTask`, which is the same three steps).
          const id = crypto.randomUUID();
          await startTask(id, tErrors);
          setPinned(null);
          onShowFresh(id);
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
    onShowFresh(id);
  };

  /// NEW TASK: a conversation that belongs to NO PROJECT, minted and registered in
  /// one go. The refusals land under the header, because that button has no row.
  ///
  /// THIS IS THE BUTTON THAT CHANGED MEANING, and it is the point of the feature: it
  /// used to start a session in whatever project the sidebar had selected, and to
  /// refuse outright when this home had no projects at all ("add a project first").
  /// A task needs neither -- it is registered and then shown, and it appears in the
  /// flat task list above the projects a moment later.
  ///
  /// STARTING A SESSION IN A PROJECT IS STILL AVAILABLE, one row down: each project
  /// row has its own button, and that is the unambiguous version of the same wish.
  /// The two are different verbs now rather than the same one with a selection.
  const newTask = async () => {
    if (busy) return;
    // NOT gated on a run in flight any more. Starting a session used to be
    // refused because it would abandon the one on screen; a new session gets its
    // own host now, and whatever is running keeps running in its own.
    const id = crypto.randomUUID();
    setBusy(true);
    setNewTaskError(null);
    setRowError(null);
    setProjectError(null);
    try {
      // REGISTERED BEFORE IT IS SHOWN, and in that order: the row exists on the
      // server the moment it is asked for, so the refresh below lists it -- and a
      // task that failed to register is never adopted, because switching to it
      // would leave the page on a conversation this home does not have.
      await startTask(id, tErrors);
      onShowFresh(id);
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


  return (
    <aside
      // THE ELEMENT BOTH TOGGLE CONTROLS NAME (`aria-controls`): see
      // `components/sidebar-toggle.tsx`, and the suite that compares the two strings
      // -- there is no DOM here to resolve the reference, so the pair is pinned by
      // reading it rather than by asking the browser.
      id={SIDEBAR_ID}
      data-slot="sidebar"
      // THE NARROW-WINDOW SHAPE, and it is a breakpoint rather than a second piece of
      // state: below `lg` this column FLOATS OVER the conversation instead of taking a
      // 288px bite out of it, because a phone-sized window has no room to give. That is
      // why the fold is worth having at all there, and why the unfolding control lives
      // outside this component: a hidden subtree cannot draw a control meant to be seen.
      //
      // FOLDED IS `hidden` AND NOT A MISSING ELEMENT -- `display: none` is what takes it
      // out of the accessibility tree, and (unlike unmounting it) what keeps the list's own
      // state: where it was scrolled, which projects were open, what was typed into the
      // escape hatch. See the `folded` prop for why the difference is a correctness one.
      className={
        folded
          ? "hidden"
          : "bg-background absolute inset-y-0 start-0 z-30 flex h-full w-72 shrink-0 flex-col border-e lg:static lg:z-auto"
      }
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
        {/* THE WAY OUT, at the end of the row of icon buttons. It is the last thing in
            the header rather than the first because the header's leading space belongs
            to "New task" -- the verb this column exists for -- and an exit is read for
            once, in the corner where a panel's close control is expected. */}
        <SidebarCollapseButton onCollapse={onCollapse} />
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

        {/* THE TASKS, ABOVE THE PROJECTS, and only when there are any: an empty
            container is furniture, and furniture that appears and disappears is
            worse than furniture that is simply not there (the same rule the
            archived group follows).

            THE TWO BLOCKS LOOK ALIKE ON PURPOSE -- a task row is the same row, with
            the same id, the same size and mtime, the same current/running/parked
            states -- because a task is the same kind of thing as a session in a
            project. What differs is only that nothing owns it, and the block
            heading is what says so. */}
        {tasks.filter((task) => !task.archived).length > 0 && (
          <section data-slot="sidebar-tasks-section" className="mb-1">
            <h2
              data-slot="sidebar-tasks-heading"
              className="text-muted-foreground px-1.5 py-1 text-xs font-medium tracking-wide"
            >
              {t("task.section")}
            </h2>
            <ul data-slot="sidebar-tasks" className="flex flex-col gap-0.5">
              {tasks
                .filter((task) => !task.archived)
                .map((task) => (
                  <ThreadListItem
                    key={task.threadId}
                    session={task}
                    current={task.threadId === currentThreadId}
                    busy={busy}
                    running={(statuses[task.threadId] ?? IDLE).running}
                    parked={(statuses[task.threadId] ?? IDLE).parked}
                    onOpen={() => void openThread(task.threadId, null)}
                    error={
                      rowError?.id === task.threadId
                        ? rowError.message
                        : (openErrors[task.threadId] ?? null)
                    }
                    actions={
                      <ThreadListItemAction
                        data-slot="thread-list-item-archive"
                        disabled={busy}
                        title={t("session.archiveTitle")}
                        onClick={() => void archive(null, task.threadId, true)}
                      >
                        <ArchiveIcon className="size-3.5" />
                        <span className="sr-only">{t("session.archive")}</span>
                      </ThreadListItemAction>
                    }
                  />
                ))}
            </ul>
          </section>
        )}

        {projects.map((project) => (
          <ProjectSection
            key={project.projectId}
            project={project}
            currentThreadId={currentThreadId}
            selected={selected?.path === project.path}
            onSelect={() => setPinned(project.path)}
            busy={busy}
            statuses={statuses}
            rowError={rowError}
            openErrors={openErrors}
            removeError={projectError?.path === project.path ? projectError.message : null}
            onOpen={(threadId) => void openThread(threadId, project.path)}
            onArchive={(threadId, archived) => void archive(project, threadId, archived)}
            onNewSession={() => void newSession(project)}
            onRemove={() => void remove(project)}
          />
        ))}

        {/* ONE ARCHIVED BLOCK FOR BOTH KINDS (ticket 03), at the END of the list and
            collapsed by default: the point of archiving is to stop being asked about a
            conversation, so it must not take the room it took before. Empty means not
            drawn at all -- no heading, no chevron, no empty list -- because an empty
            container is furniture and furniture that comes and goes is worse than
            furniture that is not there.

            A ROW FROM A PROJECT SAYS WHICH ONE (the `label` below). The block is flat,
            so the row is the only place that answer can live -- and the answer matters,
            because that conversation's log is still under that directory's workspace. */}
        {archived.length > 0 && (
          <div data-slot="sidebar-archived" className="mt-1">
            <button
              type="button"
              data-slot="sidebar-archived-trigger"
              aria-expanded={archivedOpen}
              onClick={() => setArchivedOpen((was) => !was)}
              className="text-muted-foreground hover:bg-muted/60 hover:text-foreground flex w-full items-center gap-1 rounded-md px-1.5 py-1 text-start text-xs"
            >
              <ChevronRightIcon
                aria-hidden
                className={archivedOpen ? "size-3.5 shrink-0 rotate-90" : "size-3.5 shrink-0"}
              />
              <span className="flex-1">{t("session.archived")}</span>
              <span className="shrink-0 tabular-nums">{archived.length}</span>
            </button>
            {archivedOpen && (
              <ul data-slot="sidebar-archived-sessions" className="flex flex-col gap-0.5">
                {archived.map(({ session, project }) => (
                  <ThreadListItem
                    key={session.threadId}
                    session={session}
                    // The project's display name, which is the last path segment --
                    // the same word the project row above uses for the same directory.
                    label={project === null ? null : projectName(project.path)}
                    current={session.threadId === currentThreadId}
                    busy={busy}
                    running={(statuses[session.threadId] ?? IDLE).running}
                    parked={(statuses[session.threadId] ?? IDLE).parked}
                    onOpen={() => void openThread(session.threadId, project?.path ?? null)}
                    error={
                      rowError?.id === session.threadId
                        ? rowError.message
                        : (openErrors[session.threadId] ?? null)
                    }
                    actions={
                      <ThreadListItemAction
                        data-slot="thread-list-item-archive"
                        data-archived=""
                        disabled={busy}
                        title={t("session.unarchiveTitle")}
                        onClick={() => void archive(project, session.threadId, false)}
                      >
                        <ArchiveRestoreIcon className="size-3.5" />
                        <span className="sr-only">{t("session.unarchive")}</span>
                      </ThreadListItemAction>
                    }
                  />
                ))}
              </ul>
            )}
          </div>
        )}

        {loaded && projects.length === 0 && tasks.length === 0 && listError === null && (
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

/// One project and the sessions in it: the project's row, then its conversations in
/// last-activity order (the server's order -- see `newest-first`).
///
/// ARCHIVED SESSIONS ARE NOT DRAWN HERE ANY MORE (ticket 03). This section used to end
/// with its own collapsed "Archived" group; there is now ONE such block for the whole
/// sidebar, at the bottom, holding the archived sessions of every project AND the
/// archived tasks -- because archiving is one idea about one kind of thing, and two
/// containers for it (plus a third the tasks would have needed) is three. This
/// component therefore draws only the unarchived list, and the count of what a project
/// holds is no longer the count of what is drawn.
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
  /// ONE ANSWER PER SESSION, from the page's registry. Read per row: this
  /// project draws many sessions and each reports its own status (ticket 03).
  statuses: Record<string, SessionStatus>;
  rowError: RowError;
  openErrors: Record<string, string>;
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
  statuses,
  rowError,
  openErrors,
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
  // when it matters most. An ARCHIVED current session is NOT this case: it is drawn
  // in the sidebar's one archived block, which opens itself for it.
  const holdsCurrent = project.sessions.some(
    (s) => !s.archived && s.threadId === currentThreadId,
  );
  const [open, setOpen] = useState(true);
  useEffect(() => {
    if (holdsCurrent) setOpen(true);
  }, [holdsCurrent]);
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
  // session go" a question with no server-side answer. Here the archived ones go
  // to the sidebar's single block; this component draws the active list only.
  const active = sessions.filter((s) => !s.archived);
  const archived = sessions.filter((s) => s.archived);

  const row = (session: SessionSummary) => (
    <ThreadListItem
      key={session.threadId}
      session={session}
      current={session.threadId === currentThreadId}
      busy={busy}
      // THIS ROW'S OWN SESSION, not the one on screen. It used to be
      // `running && session.threadId === currentThreadId`, because there was one
      // core and the only honest answer was "is the page running"; with one core
      // per session the registry answers for the row itself, and that comparison
      // became the WRONG answer (ticket 03: A running while you look at B used to
      // put A's row out).
      running={(statuses[session.threadId] ?? IDLE).running}
      parked={(statuses[session.threadId] ?? IDLE).parked}
      onOpen={() => onOpen(session.threadId)}
      // The row's own refusal, from EITHER source: this component's (`rowError`, a
      // row write that failed) or the page's (`openErrors`, a history that would
      // not load). Same place, because it is the same promise to the reader --
      // the sentence lands under the row that was clicked, never at the top of
      // the list for them to match up.
      error={
        rowError?.id === session.threadId
          ? rowError.message
          : (openErrors[session.threadId] ?? null)
      }
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
            // selection is now only what the row LOOKS like (see `selected` on the
            // sidebar) -- it no longer decides where a new conversation lands, since
            // the header's button makes a task.
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
                {/* TWO ABSENCES, and the reader can tell them apart: nothing here
                    ever, or everything here filed away -- in which case the sessions
                    are one block below, and this line says so rather than leaving
                    the project looking empty. */}
                {archived.length === 0
                  ? t("session.empty")
                  : t("session.allArchived")}
              </li>
            )}
          </ul>

          {/* NO ARCHIVED GROUP HERE. It used to be this project's own, collapsed by
              default; it is now the sidebar's single block at the bottom, holding the
              archived sessions of every project and the archived tasks (ticket 03).
              This component draws the active list and nothing else. */}
        </>
      )}
    </section>
  );
};

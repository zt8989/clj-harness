// WHICH SESSION THIS PAGE WAS LAST SHOWING, remembered across a reload.
//
// The page used to mint a fresh id on every mount (`crypto.randomUUID()` in App's
// initial state) and had nowhere to put it: no routing, no storage. That made
// "refresh lands you in a new, empty conversation" the ONLY behaviour, not a bug in
// a branch -- and a run that was still going in the process became invisible the
// moment the tab reloaded, because nothing on the page remembered which conversation
// it had been looking at.
//
// localStorage RATHER THAN sessionStorage, and that is the whole point (spec decision
// three): a closed tab reopened, a second tab, a browser restart all come back to the
// session you were in. The cost is named there too and it is the reason the server
// has to refuse a second run for one thread: with a shared memory, TWO TABS LANDING IN
// ONE SESSION is the ordinary case, so the client can never be assumed to be the only
// one talking.
//
// IT IS NOT A PREFERENCE. `App`'s view switch is deliberately not persisted (a way of
// looking at a conversation, not a fact about one); this is the other kind -- which
// conversation you are in is the thing the page is about.
//
// THE STORAGE IS A PARAMETER, and a narrow one: not `Storage` but the three methods
// this module uses, so the UI suite (which runs in node, with no DOM) can hand it a
// stub and read the same code the browser runs. `null` and a THROWING storage are both
// handled: Safari's private mode and a locked-down webview refuse localStorage
// entirely, and a conversation that cannot be remembered is not an error -- it is the
// behaviour the page had before this file existed.
export type SessionStorage = {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
};

/// THE PAGE'S OWN STORAGE, or null when there is none to have. Reading
/// `window.localStorage` can THROW rather than answer (a browser with site data
/// blocked, a sandboxed frame), and a page that cannot remember which session it is in
/// is a page that starts fresh -- which is the behaviour before this file existed.
export function browserStorage(): SessionStorage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}

/// What `listedSession` walks: THE SIDEBAR'S WHOLE LISTING -- the projects, each with
/// the sessions in it, AND the flat task list. A session is a session wherever it is
/// drawn, so a task can be the session this page comes back to (see `listedSession`).
export type SessionListing = {
  readonly projects: readonly ProjectSessionListing[];
  readonly tasks: readonly ListedSession[];
};

/// One project as a listing writes it: the sessions it holds. Structural rather than
/// the API's `ProjectSummary`, for the reason above -- this module asks for the one
/// field it reads.
export type ProjectSessionListing = {
  readonly sessions: readonly ListedSession[];
};

/// One session as a listing writes it: its id, and WHEN IT WAS LAST SENT TO -- null
/// until something has been sent to it (see `lib/projects.ts` for why that nullability
/// is meaningful). It used to ask for the log's `bytes`, and the question it answers is
/// the same one: has this conversation ever actually run.
export type ListedSession = { readonly threadId: string; readonly lastSentAt: number | null };

/// The one key. Namespaced like the interface's own storage keys (`clj-harness.*`),
/// because the origin may be shared with whatever else a deployment serves.
export const SESSION_KEY = "clj-harness.session";

/// The remembered session id, or null -- because nothing was remembered, because the
/// remembered value is not a string any session could have (an empty one), or because
/// the storage refused to be read.
export function rememberedSession(storage: SessionStorage | null | undefined): string | null {
  try {
    const id = storage?.getItem(SESSION_KEY) ?? null;
    return id !== null && id !== "" ? id : null;
  } catch {
    return null;
  }
}

/// The session ID in a listing, or null -- the restore's whole question about whether
/// there is anything to come back to.
///
/// STRUCTURAL RATHER THAN THE API'S TYPES, and that is what keeps this module free of
/// them: it asks for the fields it reads, and `GET /api/projects`' own shape satisfies
/// it as it stands.
///
/// BOTH HALVES ARE SEARCHED, and the flat list is the one that would be forgotten: a
/// task is a session with no project, so looking only inside projects would mean the
/// page forgets a conversation the moment it stops belonging anywhere -- and the
/// sidebar would come back to a fresh session while the row you were in is right
/// there in the task list.
///
/// IT ANSWERS THE ROW, NOT A BOOLEAN, because the page needs one more thing off it:
/// whether this session has ever been SENT TO (`lastSentAt`, which is null until it
/// has). A listed session with no send is a session whose conversation is empty by
/// construction -- there is no log to read and asking the server for one is asking it
/// about something that does not exist -- so the restore opens it empty instead of
/// reading it. (This used to be asked as "is its log size null" -- the same question
/// about the same session, from the disk side; the store answers it without a stat.)
/// A remembered id that is NOT there at all -- deleted, archived, moved by hand -- is
/// not a situation anybody can act on: the page says nothing about it and starts a
/// fresh conversation (see `App`'s `onListed`).
export function listedSession(
  id: string,
  listing: SessionListing | null | undefined,
): ListedSession | null {
  for (const project of listing?.projects ?? []) {
    for (const session of project.sessions) {
      if (session.threadId === id) return session;
    }
  }
  for (const session of listing?.tasks ?? []) {
    if (session.threadId === id) return session;
  }
  return null;
}

/// Remember ID as the session this page is showing. Silently does nothing when the
/// storage refuses to be written -- see this file's header.
export function rememberSession(storage: SessionStorage | null | undefined, id: string): void {
  try {
    storage?.setItem(SESSION_KEY, id);
  } catch {
    /* a storage that will not be written is not a failure to report */
  }
}

/// Forget the remembered session -- but ONLY when ID is the remembered one. Without
/// that check, a page that decided a stale id was gone would erase the memory of a
/// session somebody moved to in the meantime.
export function forgetSession(storage: SessionStorage | null | undefined, id: string): void {
  try {
    if (storage?.getItem(SESSION_KEY) === id) storage.removeItem(SESSION_KEY);
  } catch {
    /* nothing to do about it, and nothing broke */
  }
}

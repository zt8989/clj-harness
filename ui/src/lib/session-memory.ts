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

/// What `listedSession` walks: the projects a listing holds, each with the sessions in
/// it, and the two fields it reads off a session.
export type SessionListing = {
  readonly sessions: readonly ListedSession[];
};

/// One session as a listing writes it: its id, and the size of its log -- null until it
/// has run (see `lib/projects.ts` for why that nullability is meaningful).
export type ListedSession = { readonly threadId: string; readonly bytes: number | null };

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
/// STRUCTURAL RATHER THAN `ProjectSummary`, and that is what keeps this module free of
/// the API's types: it asks for the two fields it reads, and `GET /api/projects`' own
/// shape satisfies them as it stands.
///
/// IT ANSWERS THE ROW, NOT A BOOLEAN, because the page needs one more thing off it:
/// whether the session has a LOG (`bytes`, which is null until it has run). A listed
/// session with no log is a session whose conversation is empty by construction -- there
/// is no file to read and asking the server for one is asking it about something that
/// does not exist -- so the restore opens it empty instead of reading it. A remembered
/// id that is NOT there at all -- deleted, archived, moved by hand -- is not a situation
/// anybody can act on: the page says nothing about it and starts a fresh conversation
/// (see `App`'s `onListed`).
export function listedSession(
  id: string,
  projects: readonly SessionListing[],
): ListedSession | null {
  for (const project of projects) {
    for (const session of project.sessions) {
      if (session.threadId === id) return session;
    }
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

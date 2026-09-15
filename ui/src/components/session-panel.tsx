// The session panel: what the log directory holds, and the verbs on it.
//
// ---------------------------------------------------------------- the road here
//
// The runtime offers two seams for conversation history, and this panel is the
// reason `adapters.threadList` won. The choice is argued once, in spec.md's
// decisions, and only the shape of it lives here: `adapters.threadList` is
// built for "many threads, switch between them" -- the runtime clears, calls
// out for the messages of the thread being opened, and hydrates what comes
// back. `adapters.history` is built for "this thread, at page load" and would
// leave every switch hand-rolled on top of it. The adapter is marked
// experimental upstream; that risk is named in spec.md's known risks.
//
// threadId has changed owners with this ticket: the AGENT no longer mints and
// holds it -- React state in `app.tsx` does, and the agent is written back
// before anything async happens (the adapter's own hard rule: set the selected
// id, then wait for history; a switch superseded by a later one has its
// messages discarded by the runtime, and the id must already name the winner).
// The panel only ever sees the state, never the agent.
//
// ------------------------------------------------------------------ the listing
//
// The listing is this panel's own fetch of `GET /api/threads`, not the
// adapter's `threads` field: the panel must show each session's last-activity
// time and on-disk size, and the adapter's thread shape has no field for
// either. Cutting the data to fit the upstream shape would be the tail
// wagging the dog, so the panel fetches the wire shape directly and the
// adapter carries only the switching verbs.
//
// A rebuild appends an audit line to the log it rebuilt, so a restore changes
// what the listing reports -- sizes grow, order can move. Every successful
// switch therefore refreshes, and so does a change of the current thread.
//
// ------------------------------------------------------------------ the guards
//
// Restoring is refused while a run is in progress, with the reason shown where
// the click landed -- never silently queued, never dropped. The same guard
// covers starting a new session mid-run: the run belongs to the thread it
// started on, and abandoning the view mid-flight would orphan it. A rebuild
// that the server refuses (truncated or corrupt log) shows the server's own
// reason under that row; the other rows stay clickable.
import { useCallback, useEffect, useState, type FC } from "react";
import type { AssistantRuntime } from "@assistant-ui/react";
import { RefreshCwIcon, SquarePenIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { listThreads, type ThreadSummary } from "@/lib/threads";

/// Bytes, in the units a person reads: whole KB under a megabyte, whole MB
/// above, one decimal nowhere -- a log file's size is a sanity signal, not a
/// measurement.
function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${Math.round(bytes / (1024 * 1024))} MB`;
}

/// The log file's mtime, in the reader's own timezone and punctuation. Epoch
/// millis are for machines; a session list is read by a person looking for
/// "the one from this morning".
function formatTime(ms: number): string {
  return new Date(ms).toLocaleString();
}

/// The sentences every refused switch (and refused new session) says, and the
/// one probe behind them. They live here because the panel is where the
/// refusals are displayed, and `app.tsx` imports all three rather than
/// re-wording or re-deriving them -- two copies of a refusal reason are how
/// "refused for the same reason" becomes false one refactor from now.
export const RUN_IN_PROGRESS_REFUSAL =
  "A run is in progress; switching is refused until it settles.";

export const RUN_IN_PROGRESS_NEW_THREAD_REFUSAL =
  "A run is in progress; a new session waits until it settles.";

export const runInProgress = (runtime: AssistantRuntime): boolean =>
  runtime.threads.main.getState().isRunning;

type SessionPanelProps = {
  runtime: AssistantRuntime;
  currentThreadId: string;
};

export const SessionPanel: FC<SessionPanelProps> = ({
  runtime,
  currentThreadId,
}) => {
  const [threads, setThreads] = useState<ThreadSummary[]>([]);
  const [listError, setListError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  // The refusal or failure that belongs to ONE row -- a refused restore, a
  // rebuild the server rejected. Keyed by the row it happened on, so it reads
  // where the click landed and a second click elsewhere stops showing it.
  const [rowError, setRowError] = useState<{ id: string; message: string } | null>(
    null,
  );
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setThreads(await listThreads());
      setListError(null);
    } catch (failure: unknown) {
      setListError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setLoaded(true);
    }
  }, []);

  // On mount, and again whenever the current thread changes -- a switch just
  // wrote an audit line into some log, and the listing is stale until it says
  // so.
  useEffect(() => {
    void refresh();
  }, [refresh, currentThreadId]);

  const refuse = (threadId: string, reason: string) => {
    setRowError({ id: threadId, message: reason });
  };

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
      setRowError(null);
      await refresh();
    } catch (failure: unknown) {
      // The server's own refusal (a truncated or corrupt log) arrives as the
      // rejection's message, and is shown as-is under the row that asked for
      // it. The other rows were never touched.
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
      refuse(currentThreadId, RUN_IN_PROGRESS_NEW_THREAD_REFUSAL);
      return;
    }
    setBusy(true);
    try {
      await runtime.threads.switchToNewThread();
      setRowError(null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section
      data-slot="session-panel"
      className="aui-session-panel bg-background/95 border-b px-4 py-2"
    >
      <header className="aui-session-panel-header flex items-center gap-2">
        <h2 className="text-muted-foreground text-xs font-semibold tracking-wide uppercase">
          Sessions
        </h2>
        {/* The id is spelled out in full on the current row so "which one am I
            on" is answered by comparison, not by trust. */}
        <span className="text-muted-foreground ml-2 truncate font-mono text-xs">
          {currentThreadId}
        </span>
        <div className="ml-auto flex items-center gap-1">
          <Button
            size="sm"
            variant="ghost"
            className="aui-session-panel-new h-7 px-2 text-xs"
            disabled={busy}
            onClick={() => void newThread()}
          >
            <SquarePenIcon data-slot="session-panel-new-icon" className="size-3.5" />
            New session
          </Button>
          <Button
            size="sm"
            variant="ghost"
            className="aui-session-panel-refresh h-7 px-2 text-xs"
            disabled={busy}
            onClick={() => void refresh()}
          >
            <RefreshCwIcon
              data-slot="session-panel-refresh-icon"
              className={busy ? "size-3.5 animate-spin" : "size-3.5"}
            />
            Refresh
          </Button>
        </div>
      </header>

      {listError !== null && (
        <p role="alert" className="aui-session-panel-list-error text-destructive mt-1 text-xs">
          {listError}
        </p>
      )}

      <ul className="aui-session-panel-list mt-1 max-h-36 overflow-y-auto">
        {threads.map((thread) => {
          const current = thread.threadId === currentThreadId;
          return (
            <li key={thread.threadId} className="aui-session-panel-row">
              <button
                type="button"
                disabled={busy || current}
                onClick={() => void openThread(thread.threadId)}
                title={thread.threadId}
                className={
                  current
                    ? "bg-muted text-foreground flex w-full items-baseline gap-3 rounded-md px-2 py-1 text-left text-sm"
                    : "hover:bg-muted/60 flex w-full items-baseline gap-3 rounded-md px-2 py-1 text-left text-sm disabled:opacity-60"
                }
              >
                <code className="aui-session-panel-row-id shrink-0 font-mono text-xs break-all">
                  {thread.threadId}
                </code>
                <span className="text-muted-foreground aui-session-panel-row-meta ml-auto shrink-0 text-xs tabular-nums">
                  {formatTime(thread.lastActivity)} · {formatBytes(thread.bytes)}
                  {current ? " · current" : ""}
                </span>
              </button>
              {rowError?.id === thread.threadId && rowError.message !== "" && (
                <p
                  role="alert"
                  className="aui-session-panel-row-error text-destructive px-2 pb-1 text-xs"
                >
                  {rowError.message}
                </p>
              )}
            </li>
          );
        })}
        {loaded && threads.length === 0 && (
          <li className="text-muted-foreground px-2 py-1 text-xs">
            No sessions in the log directory yet.
          </li>
        )}
      </ul>
    </section>
  );
};

// The MCP servers this session is running, as a person sees them.
//
// ---------------------------------------------------------------- why a panel
//
// A server is an OUTSIDE program the harness hands tools to, and the two things
// that go wrong with one are invisible from the conversation: it does not start,
// or it was switched off and nobody remembers. Both look the same from inside a
// run -- some tools are missing -- so the ledger has to be somewhere a person can
// look. That is `GET /api/mcp?threadId=`, and this draws it.
//
// The switch is per SESSION and it does not edit `mcp.edn`: what a file says is
// what this harness has been told to offer, and what this panel changes is
// whether YOU are using it right now. Restart and the file is in force again.
//
// ------------------------------------------------------- what it must not say
//
// NO SERVER'S ENVIRONMENT, at any depth. The endpoint does not send it and this
// does not ask for it: a server is configured WITH a token, and the panel's job
// is to say it is configured, never with what. Same rule as the api key, and the
// same reason -- a screen is a place secrets get copied out of.
import { useCallback, useEffect, useState, type FC } from "react";
import { RefreshCwIcon, ServerIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { AGENT_URL } from "@/lib/threads";

/// One server, as the endpoint describes it.
export type McpServer = {
  server: string;
  /// "stdio" or "http" -- the first thing worth knowing when one misbehaves.
  transport: string;
  /// connected | failed | disabled | idle
  status: string;
  error?: string;
  tools?: readonly { name: string; description?: string }[];
  skipped?: readonly { skipped: string; why: string }[];
};

const STATUS_LABELS: Record<string, string> = {
  connected: "connected",
  failed: "failed",
  disabled: "switched off",
  idle: "not used yet",
};

/// The status, as a person reads it -- and the tone it is drawn in.
///
/// FAILED AND SWITCHED OFF ARE NOT THE SAME FACT and are never merged: a failed
/// server is retried the next time it is used, a switched-off one is not until
/// somebody turns it back on. A panel that showed one word for both would hide
/// the only difference that matters when a tool goes missing.
function statusLabel(server: McpServer): string {
  return STATUS_LABELS[server.status] ?? server.status;
}

async function fetchServers(threadId: string): Promise<readonly McpServer[]> {
  const res = await fetch(`${AGENT_URL}api/mcp?threadId=${encodeURIComponent(threadId)}`);
  if (!res.ok) throw new Error(`listing MCP servers failed: HTTP ${res.status}`);
  const body = (await res.json()) as { servers?: readonly McpServer[] };
  return body.servers ?? [];
}

async function setEnabled(
  threadId: string,
  server: string,
  enabled: boolean,
): Promise<void> {
  const res = await fetch(`${AGENT_URL}api/mcp`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ threadId, server, enabled }),
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { error?: string };
    throw new Error(body.error ?? `switching ${server} failed: HTTP ${res.status}`);
  }
}

/// One server's row: what it is, what it is doing, and the switch.
const ServerRow: FC<{
  server: McpServer;
  busy: boolean;
  onToggle: (enabled: boolean) => void;
}> = ({ server, busy, onToggle }) => {
  const off = server.status === "disabled";
  return (
    <div
      data-slot="mcp-server"
      data-server={server.server}
      data-status={server.status}
      className="border-border/60 flex flex-col gap-1 rounded-lg border p-2.5"
    >
      <div className="flex items-center justify-between gap-2">
        <span className="flex items-center gap-2 text-sm font-medium">
          <ServerIcon className="size-4 shrink-0" />
          {server.server}
          <span className="text-muted-foreground font-mono text-xs">
            {server.transport}
          </span>
        </span>
        <span
          data-slot="mcp-server-status"
          className={
            server.status === "failed"
              ? "text-destructive text-xs"
              : "text-muted-foreground text-xs"
          }
        >
          {statusLabel(server)}
        </span>
      </div>

      {/* The reason, when there is one. A failed server with no visible reason is
          exactly the "why did my tools go away" that this panel exists for. */}
      {server.error !== undefined && (
        <p data-slot="mcp-server-error" className="text-destructive text-xs">
          {server.error}
        </p>
      )}

      {server.tools !== undefined && server.tools.length > 0 && (
        <ul data-slot="mcp-server-tools" className="text-muted-foreground text-xs">
          {server.tools.map((tool) => (
            <li key={tool.name} className="font-mono">
              {tool.name}
            </li>
          ))}
        </ul>
      )}

      {/* A tool dropped for a name a provider would refuse: said out loud, because
          a silently shorter list is a capability somebody thinks they have. */}
      {server.skipped !== undefined && server.skipped.length > 0 && (
        <p data-slot="mcp-server-skipped" className="text-muted-foreground text-xs">
          {server.skipped.map((s) => s.skipped).join(", ")} — not usable as a tool name
        </p>
      )}

      <div className="mt-1">
        <Button
          size="sm"
          variant="outline"
          data-slot="mcp-server-toggle"
          disabled={busy}
          onClick={() => {
            onToggle(off);
          }}
        >
          {off ? "Turn on" : "Turn off"}
        </Button>
      </div>
    </div>
  );
};

/// The panel: every server this session declares, and a switch per server.
///
/// A SNAPSHOT, like the session list: nothing here subscribes, so the refresh
/// button is what makes the numbers move. The alternative -- a panel that
/// silently disagrees with the server -- is worse than one that is visibly
/// something you asked for.
export const McpPanel: FC<{ threadId: string }> = ({ threadId }) => {
  const [servers, setServers] = useState<readonly McpServer[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setServers(await fetchServers(threadId));
      setError(null);
    } catch (failure: unknown) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
  }, [threadId]);

  useEffect(() => {
    void load();
  }, [load]);

  const toggle = useCallback(
    async (server: string, enabled: boolean) => {
      setBusy(server);
      try {
        await setEnabled(threadId, server, enabled);
        await load();
        setError(null);
      } catch (failure: unknown) {
        // The reason goes where the click was, and the list is reloaded so the
        // switch reflects what the server actually did rather than what was
        // asked for.
        setError(failure instanceof Error ? failure.message : String(failure));
        await load();
      } finally {
        setBusy(null);
      }
    },
    [threadId, load],
  );

  if (servers === null) {
    return (
      <p data-slot="mcp-panel-loading" className="text-muted-foreground text-xs">
        {error ?? "Loading…"}
      </p>
    );
  }

  return (
    <div data-slot="mcp-panel" className="flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <p className="text-sm font-medium">MCP servers</p>
        <Button
          size="sm"
          variant="ghost"
          data-slot="mcp-panel-refresh"
          onClick={() => {
            void load();
          }}
        >
          <RefreshCwIcon className="size-3.5" />
        </Button>
      </div>

      {servers.length === 0 ? (
        <p data-slot="mcp-panel-empty" className="text-muted-foreground text-xs">
          No servers are declared for this session. Declare one in
          <code className="bg-muted mx-1 rounded px-1">mcp.edn</code>.
        </p>
      ) : (
        servers.map((server) => (
          <ServerRow
            key={server.server}
            server={server}
            busy={busy === server.server}
            onToggle={(enabled) => {
              void toggle(server.server, enabled);
            }}
          />
        ))
      )}

      {error !== null && (
        <p data-slot="mcp-panel-error" className="text-destructive text-xs">
          {error}
        </p>
      )}
    </div>
  );
};

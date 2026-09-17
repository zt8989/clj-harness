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
import type { TFunction } from "i18next";
import { useCallback, useEffect, useState, type FC } from "react";
import { RefreshCwIcon, ServerIcon } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";
import { API_BASE } from "@/lib/threads";

/// The translator this face is worded through: the settings catalog, because the
/// panel is drawn on the settings dialog's MCP page (see `locales/<lng>/settings.json`).
/// The `TFunction` import is a TYPE import, so nothing is added to the runtime graph.
type Translate = TFunction<"settings">;

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

/// The status, as a person reads it. `server.status` is the SERVER's keyword
/// (`connected` / `failed` / `disabled` / `idle`); the word is ours. Each branch
/// therefore writes its own literal key, and a keyword this panel has no sentence
/// for falls through as itself rather than as silence -- an unknown status is still
/// a fact, and hiding it would be worse than showing the raw word.
///
/// FAILED AND SWITCHED OFF ARE NOT THE SAME FACT and are never merged: a failed
/// server is retried the next time it is used, a switched-off one is not until
/// somebody turns it back on. A panel that showed one word for both would hide
/// the only difference that matters when a tool goes missing.
function statusLabel(t: Translate, server: McpServer): string {
  switch (server.status) {
    case "connected":
      return t("mcp.status.connected");
    case "failed":
      return t("mcp.status.failed");
    case "disabled":
      return t("mcp.status.disabled");
    case "idle":
      return t("mcp.status.idle");
    default:
      return server.status;
  }
}

/// THE TWO SENTENCES BELOW ARE THE PANEL'S OWN, and that is why they are the only
/// error strings here that go through the catalog: a server that refused sends
/// `body.error` and that sentence passes through VERBATIM (see the boundary in
/// `settings-panel`), while "listing MCP servers failed: HTTP 500" is raised here,
/// on this side of the wire. The server name and the status code interpolate; the
/// words are ours.
async function fetchServers(
  t: Translate,
  threadId: string,
): Promise<readonly McpServer[]> {
  const res = await fetch(`${API_BASE}mcp?threadId=${encodeURIComponent(threadId)}`);
  if (!res.ok) throw new Error(t("mcp.listingFailed", { status: res.status }));
  const body = (await res.json()) as { servers?: readonly McpServer[] };
  return body.servers ?? [];
}

async function setEnabled(
  t: Translate,
  threadId: string,
  server: string,
  enabled: boolean,
): Promise<void> {
  const res = await fetch(`${API_BASE}mcp`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ threadId, server, enabled }),
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { error?: string };
    throw new Error(
      body.error ?? t("mcp.switchingFailed", { server, status: res.status }),
    );
  }
}

/// One server's row: what it is, what it is doing, and the switch.
const ServerRow: FC<{
  server: McpServer;
  busy: boolean;
  onToggle: (enabled: boolean) => void;
}> = ({ server, busy, onToggle }) => {
  const { t } = useTranslation("settings");
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
          {statusLabel(t, server)}
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
          {server.skipped.map((s) => s.skipped).join(", ")}
          {t("mcp.notUsable")}
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
          {off ? t("mcp.turnOn") : t("mcp.turnOff")}
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
  const { t } = useTranslation("settings");
  const [servers, setServers] = useState<readonly McpServer[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setServers(await fetchServers(t, threadId));
      setError(null);
    } catch (failure: unknown) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
  }, [threadId, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const toggle = useCallback(
    async (server: string, enabled: boolean) => {
      setBusy(server);
      try {
        await setEnabled(t, threadId, server, enabled);
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
    [threadId, load, t],
  );

  if (servers === null) {
    return (
      <p data-slot="mcp-panel-loading" className="text-muted-foreground text-xs">
        {error ?? t("mcp.loading")}
      </p>
    );
  }

  return (
    <div data-slot="mcp-panel" className="flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <p className="text-sm font-medium">{t("mcp.heading")}</p>
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
          {t("mcp.emptyLead")}
          <code className="bg-muted mx-1 rounded px-1">mcp.edn</code>
          {t("mcp.emptyTail")}
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

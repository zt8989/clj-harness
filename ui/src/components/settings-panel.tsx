"use client";

// The settings panel: what this session is actually running on.
//
// ---------------------------------------------------------------- read only
//
// NOTHING HERE EDITS ANYTHING. The panel answers "who am I talking to, and where
// did that come from" -- config.edn, providers.edn, a session's own override --
// and writing any of those back is a different feature. That is why there is no
// input, no save button and no disabled-looking form: a form that cannot be
// submitted is furniture, and this is a report.
//
// THE ANSWER IS ALWAYS LIVE, which is the one property worth building the panel
// around. The server re-reads the files per call, so the panel refetches every
// time it is opened rather than caching anything -- opening it after editing
// config.edn shows the new value, with no restart. A cached answer would make
// the panel a snapshot of start-up, which is precisely the thing this product's
// configuration discipline says it is not.
//
// ------------------------------------------------------------------ the key
//
// THE KEY IS NEVER IN THE RESPONSE, so there is nothing here to redact -- see
// `lib/settings.ts`. What the panel draws is presence and origin: whether one is
// configured at all, and whether it comes from the home's `.env` or the
// environment. Those two facts answer the two questions people actually have
// ("is it set up?" and "which of the two places do I edit?"), and neither can be
// answered by looking at a masked value.
//
// ------------------------------------------------------------------ refusals
//
// A CONFIGURATION THAT CANNOT BE RESOLVED IS THE PANEL'S CONTENT, not an error
// state: the server's sentence goes where the values would have been. A
// half-edited config.edn is the ordinary way a person meets this endpoint, and
// the message names the provider it could not find and the ones it could have --
// which is more use than a blank panel and a generic apology.
//
// The refetch button is there for the same reason the sidebar has one: a report
// that reads files can be out of date the moment a file changes, and the honest
// answer to that is a visible way to re-ask rather than a subscription to
// something the server does not watch.
import { Loader2Icon, RefreshCwIcon } from "lucide-react";
import { useCallback, useEffect, useState, type FC } from "react";

import { Button } from "@/components/ui/button";
import { McpPanel } from "@/components/mcp-panel";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { getSettings, type Settings, type Tier } from "@/lib/settings";

/// How a tier reads on screen. `catalog` is spelled out rather than shown as the
/// word: "the model's default" says what happened, where "catalog" is a name for
/// a table nobody outside this repo has seen.
const TIER_LABELS: Record<Tier, string> = {
  config: "config.edn",
  session: "this session",
  request: "this run's request",
  catalog: "the provider's default",
};

const ORIGIN_LABELS: Record<Settings["home"]["origin"], string> = {
  environment: "from CLJ_HARNESS_HOME",
  override: "from a test override",
  default: "the default (~/.clj-harness)",
};

const KEY_SOURCE_LABELS: Record<NonNullable<Settings["key"]["source"]>, string> = {
  "env-file": "from the home's .env",
  environment: "from the environment",
};

/// NOTE: the path is shown WHOLE, never shortened to its last segments. A
/// "…/foo/.clj-harness" would be prettier and would also hide the one thing this
/// row is for -- the ticket asks for the ABSOLUTE path precisely so a person can
/// tell which home they are looking at, and two homes ending in the same three
/// segments is the case that matters most. It wraps instead.
function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[7.5rem_1fr] items-baseline gap-x-2 py-0.5">
      <span className="text-muted-foreground text-xs">{label}</span>
      <span className="min-w-0 text-xs break-words">{children}</span>
    </div>
  );
}

export const SettingsPanel: FC<{
  open: boolean;
  onOpenChange: (open: boolean) => void;
  threadId: string;
}> = ({ open, onOpenChange, threadId }) => {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setSettings(await getSettings(threadId));
      setFailure(null);
    } catch (failure: unknown) {
      // The server's own sentence, kept whole: it names the provider it could
      // not resolve and the ones the catalog does define, and a paraphrase would
      // be one more thing to distrust.
      setSettings(null);
      setFailure(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setLoading(false);
    }
  }, [threadId]);

  // Refetched on EVERY open, which is the whole "read it fresh" contract showing
  // through: there is no cache to go stale because there is no cache.
  useEffect(() => {
    if (open) void load();
  }, [open, load]);

  const tier = (knob: "provider" | "model" | "reasoning-effort") =>
    settings?.tiers[knob];

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        data-slot="settings-panel"
        className="sm:max-w-lg"
        aria-describedby={undefined}
      >
        <DialogHeader>
          <DialogTitle>Settings</DialogTitle>
          <DialogDescription>
            What this session is running on right now. Read from the files on
            every open — edit one and reopen to see the change. Nothing here
            writes anything.
          </DialogDescription>
        </DialogHeader>

        {failure !== null && (
          <div data-slot="settings-error" className="rounded-md border p-2">
            <p className="text-destructive text-xs break-words">{failure}</p>
            <p className="text-muted-foreground mt-1 text-xs">
              A configuration that cannot be resolved is shown instead of the
              values — the files are still yours to fix.
            </p>
          </div>
        )}

        {loading && settings === null && failure === null && (
          <p
            data-slot="settings-loading"
            className="text-muted-foreground flex items-center gap-2 text-xs"
          >
            <Loader2Icon className="size-3.5 animate-spin" />
            Reading the configuration…
          </p>
        )}

        {settings !== null && (
          <div className="flex flex-col gap-3">
            <section data-slot="settings-model">
              <h3 className="text-muted-foreground mb-1 text-xs font-semibold tracking-wide uppercase">
                Model
              </h3>
              <Row label="provider">
                <code data-slot="settings-provider" className="font-mono">
                  {settings.provider ?? "—"}
                </code>
                {tier("provider") !== undefined && (
                  <span className="text-muted-foreground">
                    {" "}
                    · {TIER_LABELS[tier("provider")!]}
                  </span>
                )}
              </Row>
              <Row label="model">
                <code data-slot="settings-model-id" className="font-mono">
                  {settings.model ?? "—"}
                </code>
                {tier("model") !== undefined && (
                  <span className="text-muted-foreground">
                    {" "}
                    · {TIER_LABELS[tier("model")!]}
                  </span>
                )}
              </Row>
              <Row label="reasoning">
                <span data-slot="settings-reasoning">
                  {settings["reasoning-effort"] ?? "—"}
                </span>
                {tier("reasoning-effort") !== undefined && (
                  <span className="text-muted-foreground">
                    {" "}
                    · {TIER_LABELS[tier("reasoning-effort")!]}
                  </span>
                )}
              </Row>
              {settings["base-url"] !== undefined && (
                <Row label="endpoint">
                  <code className="text-muted-foreground font-mono break-all">
                    {settings["base-url"]}
                  </code>
                </Row>
              )}
            </section>

            <section data-slot="settings-key">
              <h3 className="text-muted-foreground mb-1 text-xs font-semibold tracking-wide uppercase">
                API key
              </h3>
              {/* Presence and origin, and there is deliberately no third thing:
                  no value, no length, no masked hint. A row of dots answers
                  neither of the questions this section exists for. */}
              <Row label="configured">
                <span data-slot="settings-key-present">
                  {settings.key["present?"] ? "yes" : "no"}
                </span>
                {settings.key.source !== null && (
                  <span className="text-muted-foreground">
                    {" "}
                    · {KEY_SOURCE_LABELS[settings.key.source]}
                  </span>
                )}
              </Row>
              <p className="text-muted-foreground mt-0.5 text-xs">
                The value itself is never sent to this page.
              </p>
            </section>

            <section data-slot="settings-home">
              <h3 className="text-muted-foreground mb-1 text-xs font-semibold tracking-wide uppercase">
                Config home
              </h3>
              <Row label="path">
                <code
                  data-slot="settings-home-path"
                  className="font-mono break-all"
                >
                  {settings.home.path}
                </code>
              </Row>
              <Row label="where from">
                <span data-slot="settings-home-origin">
                  {ORIGIN_LABELS[settings.home.origin]}
                </span>
              </Row>
              <Row label="files">
                <ul data-slot="settings-home-files" className="flex flex-col gap-0.5">
                  {settings.home.files.map((f) => (
                    <li key={f.name} className="flex items-baseline gap-1.5">
                      <span
                        data-present={f["present?"] ? "" : undefined}
                        className={
                          f["present?"]
                            ? "font-mono"
                            : "text-muted-foreground font-mono line-through"
                        }
                      >
                        {f.name}
                      </span>
                      {!f["present?"] && (
                        <span className="text-muted-foreground text-[10px]">
                          not here
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              </Row>
            </section>
          </div>
        )}

        {/* THE MCP LEDGER, embedded rather than given an entry of its own --
            which is what the MCP ticket said would happen once this page
            existed: one place to look at what this session is running on. It
            brings its OWN switch, because unlike everything else on this panel a
            server can be turned off, and that is a session decision rather than a
            report. */}
        <section data-slot="settings-mcp" className="flex flex-col gap-2">
          <McpPanel threadId={threadId} />
        </section>

        <div className="flex items-center justify-between gap-2">
          <span className="text-muted-foreground text-xs">
            {settings?.source === "inline"
              ? "config.edn describes this provider inline"
              : settings?.source === "request"
                ? "this run's request picked it"
                : ""}
          </span>
          <Button
            variant="ghost"
            size="sm"
            data-slot="settings-refresh"
            disabled={loading}
            onClick={() => void load()}
            title="Read the files again"
            className="h-7 px-2 text-xs"
          >
            <RefreshCwIcon
              className={loading ? "size-3.5 animate-spin" : "size-3.5"}
            />
            Re-read
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
};

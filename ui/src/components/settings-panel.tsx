"use client";

// The settings panel: three pages, and TWO of them write.
//
// ---------------------------------------------------------------- the pages
//
// General   what this session is running on, and the DEFAULT TIER -- the three
//           knobs a NEW session starts from, which is the one thing on this page
//           that changes a file.
// Models    the provider catalog: every vendor, where it came from, and the form
//           that adds, edits and removes one.
// MCP       the outside programs this session hands tools to, and the per-session
//           switch for each -- not a file of its own, and not one of the two above.
//
// THREE PAGES, AND THE PAGE IS COMPONENT STATE -- not a route, and not a place a URL
// can point at. General and Models both write config.edn; the key's presence, the
// credential NAME it is read from and the home's path are facts the Models rows and
// the composer already carry, so they are not pages of their own.
//
// The pages that DO write are honest about it: General's Save writes config.edn's
// :default, Models' form writes config.edn's :providers (and, when a key is typed,
// one line of .env). The third is a report, and it stays a report.
//
// THE ANSWER IS ALWAYS LIVE. The server re-reads the files per call, so the panel
// refetches every time it is opened rather than caching anything -- opening it
// after editing config.edn shows the new value, with no restart. A cached answer
// would make the panel a snapshot of start-up, which is precisely the thing this
// product's configuration discipline says it is not.
//
// ------------------------------------------------------------------ the key
//
// THE KEY IS NEVER IN THE RESPONSE, so there is nothing here to redact. What the
// panel draws is presence, origin, and the credential NAME -- the name is the
// useful half, because it is the line to edit or the line to add, and deriving it
// from the provider id in your head is the step this feature exists to remove.
//
// ------------------------------------------------------------------ refusals
//
// A CONFIGURATION THAT CANNOT BE RESOLVED IS THE PANEL'S CONTENT, not an error
// state: the server's sentence goes where the values would have been. A
// half-edited config.edn is the ordinary way a person meets this endpoint, and the
// message names the provider it could not find and the ones it could have -- which
// is more use than a blank panel and a generic apology. THE SAME RULE GOVERNS THE
// FORMS: a refused write shows the server's sentence where the form is, and the
// form stays put, because the file did not move either.
import type { TFunction } from "i18next";
import { ArrowLeftIcon, Loader2Icon, PlusIcon, RefreshCwIcon, TrashIcon } from "lucide-react";
import { useCallback, useEffect, useState, type FC } from "react";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";
import { McpPanel } from "@/components/mcp-panel";
import { BASELINE_LABELS, DefinitionButtons } from "@/components/subagent-list";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { setLanguage } from "@/lib/i18n";
import { SUPPORTED_LANGUAGES, isLanguage, type Language } from "@/lib/language";
import {
  probeModels,
  putDefaults,
  putProvider,
  registryFor,
  removeProvider,
  type DefaultKnobs,
  type ModelRow,
  type Origin,
  type ProviderRow,
  type Registry,
} from "@/lib/providers";
import { hasKey, splitByKey } from "@/lib/provider-key";
import { providerLabel } from "@/lib/provider-label";
import { getSettings, type Settings, type Tier } from "@/lib/settings";
import {
  listSubagents,
  putSubagent,
  removeSubagent,
  type Baseline,
  type SubagentDefinition,
  type SubagentListing,
} from "@/lib/subagents";

/// The translator this face is worded through: the settings catalog, because every
/// string below is drawn in the panel (see `locales/<lng>/settings.json`). The
/// `TFunction` import is a TYPE import, so nothing is added to the runtime graph.
type Translate = TFunction<"settings">;

/// How a tier reads on screen. `catalog` is spelled out rather than shown as the
/// word: "the provider's default" says what happened, where "catalog" is a name for
/// a table nobody outside this repo has seen.
///
/// THE WORD IS OURS, THE KEY IS THE SERVER'S. `Tier` is the server's vocabulary --
/// `config` / `session` / `request` / `catalog` -- while "this session" and "本次请求"
/// are what a reader gets. Each entry therefore closes over its own key, written
/// literally (`t("tier.session")`), so a tier the server adds cannot render its raw
/// keyword on screen: a `t(`tier.${tier}`)` would have no sentence to fall back on.
const TIER_LABELS: Record<Tier, (t: Translate) => string> = {
  config: (t) => t("tier.config"),
  session: (t) => t("tier.session"),
  request: (t) => t("tier.request"),
  catalog: (t) => t("tier.catalog"),
};

/// Where a provider came from, in the words a person would use. The three are
/// different edits -- a built-in, your own vendor, your own patch of a built-in --
/// and only the second and third are yours to change or remove.
///
/// Same rule as `TIER_LABELS`: `Origin` is the server's keyword, the word is ours,
/// and each branch writes its own literal key.
const ORIGIN_LABELS_PROVIDER: Record<Origin, (t: Translate) => string> = {
  builtin: (t) => t("origin.builtin"),
  user: (t) => t("origin.user"),
  "builtin-patched": (t) => t("origin.builtinPatched"),
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

/// The server's sentence, shown where the thing it is about would have been. One
/// component, because a refusal in the panel and a refusal in a form have to look
/// like the same event: the file says no, and this is what it said.
const Refusal: FC<{ slot: string; message: string; note?: string }> = ({
  slot,
  message,
  note,
}) => (
  <div data-slot={slot} className="rounded-md border p-2">
    <p className="text-destructive text-xs break-words">{message}</p>
    {note !== undefined && <p className="text-muted-foreground mt-1 text-xs">{note}</p>}
  </div>
);

const SectionTitle: FC<{ children: React.ReactNode }> = ({ children }) => (
  <h3 className="text-muted-foreground mb-1 text-xs font-semibold tracking-wide uppercase">
    {children}
  </h3>
);

const Field: FC<{
  label: string;
  slot: string;
  hint?: string;
  children: React.ReactNode;
}> = ({ label, slot, hint, children }) => (
  <label data-slot={slot} className="flex flex-col gap-1">
    <span className="text-xs font-medium">{label}</span>
    {children}
    {hint !== undefined && <span className="text-muted-foreground text-xs">{hint}</span>}
  </label>
);

const inputClass =
  "h-8 w-full rounded-md border bg-transparent px-2 text-xs outline-none focus-visible:border-ring";

// ------------------------------------------------------------------- General

/// The three knobs, editable. What makes this different from the composer's
/// pickers is the TIER it writes: this is config.edn's :default -- what a NEW
/// session starts from -- where those change THIS session and forget it on
/// restart. The panel says which tier each knob came from right above, so the two
/// are never confused for one another.
const Defaults: FC<{ registry: Registry; onChanged: () => void }> = ({
  registry,
  onChanged,
}) => {
  const { t } = useTranslation("settings");
  const { t: tErrors } = useTranslation("errors");
  const tier = registry.default;
  /// An INLINE description cannot be expressed by three selects: there is no
  /// provider NAME in it. So the controls start empty, the page says what is there
  /// instead, and Save stays disabled until a vendor is picked -- because saving a
  /// form that looks untouched would otherwise delete the description.
  const inline = !("provider" in tier) && Object.keys(tier).length > 0;

  const [provider, setProvider] = useState<string>(
    typeof tier.provider === "string" ? tier.provider : "",
  );
  const [model, setModel] = useState<string>(
    typeof tier.model === "string" ? tier.model : "",
  );
  const [effort, setEffort] = useState<string>(
    typeof tier["reasoning-effort"] === "string" ? (tier["reasoning-effort"] as string) : "",
  );
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const chosen = registry.providers.find((p) => p.name === provider);

  /// A MODEL THAT IS ALWAYS ONE THE VENDOR DECLARES. Two ways the tier can hold
  /// something else: it names a model this vendor does not serve (hand-written, or
  /// the tier points at a different vendor than the control does), or it names none
  /// at all. Both land on the vendor's OWN default, which is what the server would
  /// have resolved anyway -- the control just says so out loud instead of sending a
  /// value the select cannot even show.
  const modelFor = (p: ProviderRow | undefined, want: string): string =>
    p !== undefined && p.models.some((m) => m.id === want) ? want : (p?.model ?? "");

  // The tier is re-read after every write, so the controls follow the file rather
  // than their own last submission.
  useEffect(() => {
    const name = typeof tier.provider === "string" ? tier.provider : "";
    setProvider(name);
    setModel(
      modelFor(
        registry.providers.find((p) => p.name === name),
        typeof tier.model === "string" ? tier.model : "",
      ),
    );
    setEffort(
      typeof tier["reasoning-effort"] === "string" ? (tier["reasoning-effort"] as string) : "",
    );
  }, [tier, registry]);
  const save = async () => {
    setBusy(true);
    setFailure(null);
    try {
      // EVERY KNOB IS SENT, with "" meaning REMOVE THE KEY -- the controls show the
      // tier's whole content, so a knob cleared here is a knob cleared in the file.
      const knobs: DefaultKnobs = {
        provider: provider === "" ? null : provider,
        model: model === "" ? null : model,
        "reasoning-effort": effort === "" ? null : effort,
      };
      await putDefaults(knobs, tErrors);
      onChanged();
    } catch (f: unknown) {
      setFailure(f instanceof Error ? f.message : String(f));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div data-slot="settings-defaults" className="flex flex-col gap-2">
      <SectionTitle>{t("defaults.title")}</SectionTitle>
      {/* THE TWO IDENTIFIERS ARE NOT COPY: `config.edn` and `:default` name a file
          and a key, so they stay literal and stay in `<code>`, and the sentence is
          split into the runs around them. The reason is the one the sidebar's remove
          dialog spells out: `t` returns a string, so a single key holding both would
          flatten the `<code>` elements into plain text. The possessive between them
          is its own fragment because the two languages attach it differently
          ("config.edn's" vs "config.edn 的"). */}
      <p className="text-muted-foreground text-xs">
        {t("defaults.introLead")}
        <code className="font-mono">config.edn</code>
        {t("defaults.introPossessive")}
        <code className="font-mono">:default</code>
        {t("defaults.introTail")}
      </p>

      {inline && (
        <div data-slot="settings-default-inline" className="rounded-md border p-2">
          {/* Same split around the endpoint values: the base url and the model id
              come from the server and pass through verbatim, while "describes this
              provider inline" is the panel's own sentence. */}
          <p className="text-xs">
            config.edn <em>{t("defaults.inlineDescribes")}</em>{t("defaults.inlineAfter")}
            <code className="font-mono break-all">
              {String(tier["base-url"] ?? "")} / {String(tier.model ?? "")}
            </code>
          </p>
          <p className="text-muted-foreground mt-1 text-xs">{t("defaults.inlineNote")}</p>
        </div>
      )}

      <Field label={t("defaults.provider")} slot="settings-default-provider">
        <select
          aria-label={t("defaults.provider")}
          className={inputClass}
          value={provider}
          disabled={busy}
          onChange={(e) => {
            const name = e.target.value;
            setProvider(name);
            // A model id means "an id this vendor serves", so switching vendor lands
            // on the NEW vendor's own default -- which is what the server would
            // resolve anyway, said out loud rather than left blank.
            setModel(modelFor(registry.providers.find((p) => p.name === name), ""));
          }}
        >
          <option value="">{t("defaults.none")}</option>
          {registry.providers.map((p) => (
            <option key={p.name} value={p.name}>
              {providerLabel(p)}
            </option>
          ))}
        </select>
      </Field>

      <Field
        label={t("defaults.model")}
        slot="settings-default-model"
        hint={
          chosen === undefined
            ? t("defaults.modelNoProvider")
            : t("defaults.modelHint")
        }
      >
        {/* NO EMPTY CHOICE: the tier names a model, and a vendor always has a default
            one, so "— the vendor's own default —" was an option whose only content was
            the thing the field would have said anyway. A vendor with no models cannot
            be reached here at all (the catalog refuses one), so the list is never
            empty for a chosen provider. */}
        <select
          aria-label={t("defaults.model")}
          className={inputClass}
          value={model}
          disabled={busy || chosen === undefined}
          onChange={(e) => setModel(e.target.value)}
        >
          {(chosen?.models ?? []).map((m) => (
            <option key={m.id} value={m.id}>
              {m.id}
            </option>
          ))}
        </select>
      </Field>

      <Field label={t("defaults.reasoning")} slot="settings-default-reasoning">
        <select
          aria-label={t("defaults.reasoning")}
          className={inputClass}
          value={effort}
          disabled={busy}
          onChange={(e) => setEffort(e.target.value)}
        >
          <option value="">{t("defaults.noneSent")}</option>
          {registry["reasoning-efforts"].map((r) => (
            <option key={r} value={r}>
              {r}
            </option>
          ))}
        </select>
      </Field>

      <div className="flex items-center gap-2">
        <Button
          size="sm"
          data-slot="settings-default-save"
          disabled={busy || (inline && provider === "")}
          onClick={() => void save()}
        >
          {t("defaults.save")}
        </Button>
        {busy && <Loader2Icon className="text-muted-foreground size-3.5 animate-spin" />}
      </div>
      {failure !== null && (
        <Refusal
          slot="settings-default-error"
          message={failure}
          note={t("defaults.errorNote")}
        />
      )}
    </div>
  );
};

/// Each language written IN ITS OWN LANGUAGE, and deliberately NOT in the catalogs.
///
/// This is the one place on the page where translating would defeat the purpose: a
/// reader who has landed on a language they cannot read has to be able to find their
/// own in this list, and `中文` is findable to someone who does not know the word
/// "Chinese". Every other string in the panel goes through the catalogs; these two
/// do not move.
const LANGUAGE_NAMES: Record<Language, string> = {
  en: "English",
  zh: "中文",
};

/// The panel's own language, and the only control on any page of it that writes no
/// file.
const LanguageRow: FC = () => {
  const { t, i18n } = useTranslation("settings");
  return (
    <section data-slot="settings-language">
      <SectionTitle>{t("language.title")}</SectionTitle>
      <Field
        label={t("language.field")}
        slot="settings-language-field"
        hint={t("language.hint")}
      >
        <select
          aria-label={t("language.field")}
          className={inputClass}
          value={i18n.language}
          onChange={(event) => {
            // The options are generated from the same list, so this is always one of
            // them -- and the guard is here rather than a cast because the value is
            // DOM-supplied, which is exactly where a closed list stops being closed.
            const chosen = event.target.value;
            if (isLanguage(chosen)) setLanguage(chosen);
          }}
        >
          {SUPPORTED_LANGUAGES.map((language) => (
            <option key={language} value={language}>
              {LANGUAGE_NAMES[language]}
            </option>
          ))}
        </select>
      </Field>
    </section>
  );
};

const GeneralPage: FC<{
  settings: Settings | null;
  registry: Registry | null;
  onChanged: () => void;
}> = ({ settings, registry, onChanged }) => {
  const { t } = useTranslation("settings");
  const tier = (knob: "provider" | "model" | "reasoning-effort") => settings?.tiers[knob];
  return (
    <div data-slot="settings-page-general" className="flex flex-col gap-4">
      {/* The report is ABSENT when it could not be resolved -- and the controls
          below are still here, which is what makes a broken home fixable from the
          page that shows it is broken. */}
      {settings !== null && (
      <section data-slot="settings-model">
        <SectionTitle>{t("report.title")}</SectionTitle>
        <Row label={t("report.provider")}>
          <code data-slot="settings-provider" className="font-mono">
            {settings.provider ?? "—"}
          </code>
          {settings["display-name"] !== undefined && (
            <span className="text-muted-foreground"> ({settings["display-name"]})</span>
          )}
          {tier("provider") !== undefined && (
            <span className="text-muted-foreground"> · {TIER_LABELS[tier("provider")!](t)}</span>
          )}
        </Row>
        <Row label={t("report.model")}>
          <code data-slot="settings-model-id" className="font-mono">
            {settings.model ?? "—"}
          </code>
          {tier("model") !== undefined && (
            <span className="text-muted-foreground"> · {TIER_LABELS[tier("model")!](t)}</span>
          )}
        </Row>
        <Row label={t("report.reasoning")}>
          <span data-slot="settings-reasoning">{settings["reasoning-effort"] ?? "—"}</span>
          {tier("reasoning-effort") !== undefined && (
            <span className="text-muted-foreground">
              {" "}
              · {TIER_LABELS[tier("reasoning-effort")!](t)}
            </span>
          )}
        </Row>
        {settings["base-url"] !== undefined && (
          <Row label={t("report.endpoint")}>
            <code className="text-muted-foreground font-mono break-all">
              {settings["base-url"]}
            </code>
          </Row>
        )}
      </section>
      )}

      {registry !== null && <Defaults registry={registry} onChanged={onChanged} />}

      {/* THE LANGUAGE ROW IS OUTSIDE BOTH CONDITIONALS, and that is the requirement
          rather than the layout: a home whose config.edn cannot be resolved draws a
          page of refusals, and a reader who has been put in front of that page in a
          language they cannot read must still be able to change it. It is also the
          honest place for the only control in this modal that writes nothing --
          it changes this browser, not this harness. */}
      <LanguageRow />
    </div>
  );
};

// -------------------------------------------------------------------- Models

const emptyModel = (id: string, input: string[] = ["text"]): ModelRow => ({
  id,
  input,
  output: ["text"],
});

/// One model row: what it is called, what it accepts, and whether it is the
/// vendor's default. Output is TEXT and only text -- the catalog's vocabulary has
/// one output type -- so it is stated rather than offered as a choice.
const ModelRowEditor: FC<{
  row: ModelRow;
  canRemove: boolean;
  onChange: (row: ModelRow) => void;
  onRemove: () => void;
}> = ({ row, canRemove, onChange, onRemove }) => {
  const { t } = useTranslation("settings");
  return (
    <div
      data-slot="settings-provider-model"
      className="flex flex-col gap-1 rounded-md border p-2"
    >
      <div className="flex items-center gap-2">
        <Input
          aria-label={t("form.modelId")}
          className="h-7 flex-1 text-xs"
          value={row.id}
          onChange={(e) => onChange({ ...row, id: e.target.value })}
        />
        <Button
          variant="ghost"
          size="icon-xs"
          aria-label={t("form.removeModel")}
          title={t("form.removeModelTitle")}
          disabled={!canRemove}
          onClick={onRemove}
        >
          <TrashIcon />
        </Button>
      </div>
      <div className="flex items-center gap-3">
        {(["text", "image"] as const).map((modality) => (
          <label key={modality} className="flex items-center gap-1 text-xs">
            <input
              type="checkbox"
              // THE WORD IS OURS, THE KEY IS NOT: `modality` is the catalog's own
              // vocabulary ("text" / "image") and is printed as-is below, while the
              // accessible name is a sentence, so each branch names its own key.
              aria-label={
                modality === "text" ? t("form.acceptsText") : t("form.acceptsImage")
              }
              checked={row.input.includes(modality)}
              onChange={(e) => {
                const next = e.target.checked
                  ? [...row.input, modality]
                  : row.input.filter((m) => m !== modality);
                onChange({ ...row, input: next });
              }}
            />
            {modality}
          </label>
        ))}
        <span className="text-muted-foreground text-xs">{t("form.outText")}</span>
        <details data-slot="settings-provider-model-limits" className="ml-auto">
          <summary className="text-muted-foreground cursor-pointer text-xs">
            {t("form.limits")}
          </summary>
          <div className="mt-1 flex items-center gap-2">
            {(["context-window", "max-output-tokens"] as const).map((count) => (
              <label key={count} className="flex items-center gap-1 text-xs">
                {count === "context-window" ? t("form.context") : t("form.maxOut")}
                <Input
                  // The accessible name keeps the field's raw key, which is what the
                  // English aria-label was; it is the one string here that does not
                  // translate, because it names the config.edn key itself.
                  aria-label={
                    count === "context-window"
                      ? t("form.contextWindow")
                      : t("form.maxOutputTokens")
                  }
                  className="h-6 w-20 text-xs"
                  inputMode="numeric"
                  value={row[count] ?? ""}
                  onChange={(e) => {
                    const raw = e.target.value.trim();
                    const next = { ...row };
                    if (raw === "") delete next[count];
                    else next[count] = Number(raw);
                    onChange(next);
                  }}
                />
              </label>
            ))}
          </div>
          <p className="text-muted-foreground mt-1 text-[10px]">{t("form.limitsNote")}</p>
        </details>
      </div>
    </div>
  );
};

type Draft = {
  id: string;
  displayName: string;
  baseUrl: string;
  protocol: string;
  apiKey: string;
  models: ModelRow[];
  editing: boolean;
};

const draftOf = (provider: ProviderRow): Draft => ({
  id: provider.name,
  displayName: provider["display-name"] ?? "",
  baseUrl: provider["base-url"],
  protocol: provider.protocol,
  apiKey: "",
  models: provider.models.map((m) => ({ ...m })),
  editing: true,
});

const blankDraft = (protocols: readonly string[]): Draft => ({
  id: "",
  displayName: "",
  baseUrl: "",
  // THE DEFAULT PROTOCOL IS THE ONE REAL VENDORS SPEAK, when this process has it:
  // the server's list is "what this process can speak", which in a dev process also
  // includes the test double -- selectable, truthfully, but a terrible thing to
  // start a new vendor on by accident.
  protocol: protocols.includes("openai-completions")
    ? "openai-completions"
    : (protocols[0] ?? ""),
  apiKey: "",
  models: [],
  editing: false,
});

const ProviderForm: FC<{
  draft: Draft;
  protocols: readonly string[];
  onCancel: () => void;
  onSaved: () => void;
  onRemoved: () => void;
}> = ({ draft: initial, protocols, onCancel, onSaved, onRemoved }) => {
  const { t } = useTranslation("settings");
  const { t: tErrors } = useTranslation("errors");
  const [draft, setDraft] = useState<Draft>(initial);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [offered, setOffered] = useState<string[] | null>(null);
  const [picked, setPicked] = useState<string[]>([]);

  const set = (patch: Partial<Draft>) => setDraft((d) => ({ ...d, ...patch }));

  const save = async () => {
    setBusy(true);
    setFailure(null);
    try {
      await putProvider({
        id: draft.id,
        ...(draft.displayName === "" ? {} : { "display-name": draft.displayName }),
        protocol: draft.protocol,
        "base-url": draft.baseUrl,
        // THE FIRST ROW IS THE VENDOR'S DEFAULT MODEL, and the catalogue requires one
        // (an entry whose :model is not among its models is refused). Asking here
        // would ask a question General already answers for the thing people mean by
        // "the default model" -- which one a run STARTS on.
        model: draft.models[0]?.id ?? "",
        models: draft.models,
        // ABSENT when the field is empty: that means "leave .env alone", which is
        // what an untouched key field means. (The server refuses an empty string.)
        ...(draft.apiKey === "" ? {} : { "api-key": draft.apiKey }),
      }, tErrors);
      onSaved();
    } catch (f: unknown) {
      setFailure(f instanceof Error ? f.message : String(f));
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    setFailure(null);
    try {
      await removeProvider(draft.id, tErrors);
      onRemoved();
    } catch (f: unknown) {
      setFailure(f instanceof Error ? f.message : String(f));
    } finally {
      setBusy(false);
    }
  };

  const fetchModels = async () => {
    setBusy(true);
    setFailure(null);
    setOffered(null);
    try {
      const { models } = await probeModels({
        ...(draft.editing ? { id: draft.id } : {}),
        "base-url": draft.baseUrl,
        protocol: draft.protocol,
        ...(draft.apiKey === "" ? {} : { "api-key": draft.apiKey }),
      }, tErrors);
      setOffered(models);
      setPicked([]);
    } catch (f: unknown) {
      setFailure(f instanceof Error ? f.message : String(f));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div data-slot="settings-provider-form" className="flex flex-col gap-3">
      <Field
        label={t("form.providerId")}
        slot="settings-provider-id"
        hint={
          draft.editing
            ? t("form.providerIdEditingHint")
            : t("form.providerIdHint")
        }
      >
        <Input
          className="h-8 font-mono text-xs"
          placeholder={t("form.providerIdPlaceholder")}
          value={draft.id}
          disabled={draft.editing || busy}
          onChange={(e) => set({ id: e.target.value })}
        />
      </Field>

      <Field
        label={t("form.displayName")}
        slot="settings-provider-display-name"
        hint={t("form.displayNameHint")}
      >
        <Input
          className="h-8 text-xs"
          value={draft.displayName}
          disabled={busy}
          onChange={(e) => set({ displayName: e.target.value })}
        />
      </Field>

      <Field label={t("form.baseUrl")} slot="settings-provider-base-url">
        <Input
          className="h-8 font-mono text-xs"
          placeholder={t("form.baseUrlPlaceholder")}
          value={draft.baseUrl}
          disabled={busy}
          onChange={(e) => set({ baseUrl: e.target.value })}
        />
      </Field>

      <Field label={t("form.protocol")} slot="settings-provider-protocol">
        <select
          aria-label={t("form.protocol")}
          className={inputClass}
          value={draft.protocol}
          disabled={busy}
          onChange={(e) => set({ protocol: e.target.value })}
        >
          {protocols.map((p) => (
            <option key={p} value={p}>
              {p}
            </option>
          ))}
        </select>
      </Field>

      <Field
        label={t("form.apiKey")}
        slot="settings-provider-key"
        hint={
          draft.editing
            ? t("form.apiKeyEditingHint")
            : t("form.apiKeyHint")
        }
      >
        <Input
          className="h-8 font-mono text-xs"
          type="password"
          autoComplete="off"
          value={draft.apiKey}
          disabled={busy}
          onChange={(e) => set({ apiKey: e.target.value })}
        />
      </Field>

      <div className="flex flex-col gap-2">
        <SectionTitle>{t("form.catalogTitle")}</SectionTitle>
        {draft.models.length > 0 && (
          <p className="text-muted-foreground text-xs">
            {t("form.firstLead")}
            <strong>{t("form.firstWord")}</strong>
            {t("form.firstTail")}
          </p>
        )}
        {draft.models.length === 0 && (
          <p className="text-muted-foreground text-xs">{t("form.noModels")}</p>
        )}
        {draft.models.map((row, i) => (
          <ModelRowEditor
            key={`${i}-${row.id}`}
            row={row}
            // The LAST model cannot go: a vendor with no models cannot be selected
            // at all, so the form would be building something the file refuses.
            canRemove={draft.models.length > 1}
            onChange={(next) =>
              set({ models: draft.models.map((m, j) => (j === i ? next : m)) })
            }
            onRemove={() => set({ models: draft.models.filter((_, j) => j !== i) })}
          />
        ))}

        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            data-slot="settings-provider-model-add"
            disabled={busy}
            onClick={() =>
              set({ models: [...draft.models, emptyModel(`model-${draft.models.length + 1}`)] })
            }
          >
            <PlusIcon /> {t("form.addModel")}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            data-slot="settings-provider-model-fetch"
            disabled={busy || draft.baseUrl === ""}
            title={
              draft.baseUrl === ""
                ? t("form.fetchNoAddress")
                : t("form.fetchTitle")
            }
            onClick={() => void fetchModels()}
          >
            {t("form.fetch")}
          </Button>
        </div>

        {offered !== null && (
          <div data-slot="settings-provider-model-offered" className="rounded-md border p-2">
            <p className="text-muted-foreground text-xs">
              {offered.length === 0
                ? t("form.offeredEmpty")
                : t("form.offeredHint")}
            </p>
            <div className="mt-1 flex max-h-40 flex-col gap-0.5 overflow-y-auto">
              {offered.map((id) => (
                <label key={id} className="flex items-center gap-1.5 font-mono text-xs">
                  <input
                    type="checkbox"
                    checked={picked.includes(id)}
                    onChange={(e) =>
                      setPicked(
                        e.target.checked ? [...picked, id] : picked.filter((p) => p !== id),
                      )
                    }
                  />
                  {id}
                </label>
              ))}
            </div>
            {picked.length > 0 && (
              <Button
                variant="outline"
                size="sm"
                className="mt-1"
                data-slot="settings-provider-model-take"
                onClick={() => {
                  const have = new Set(draft.models.map((m) => m.id));
                  const add = picked.filter((id) => !have.has(id)).map((id) => emptyModel(id));
                  set({ models: [...draft.models, ...add] });
                  setOffered(null);
                  setPicked([]);
                }}
              >
                {t("form.takeWithCount", { count: picked.length })}
              </Button>
            )}
          </div>
        )}
      </div>

      {failure !== null && (
        <Refusal
          slot="settings-provider-error"
          message={failure}
          note={t("form.errorNote")}
        />
      )}

      <div className="flex items-center gap-2">
        <Button size="sm" data-slot="settings-provider-submit" disabled={busy} onClick={() => void save()}>
          {draft.editing ? t("form.save") : t("form.create")}
        </Button>
        <Button variant="ghost" size="sm" disabled={busy} onClick={onCancel}>
          {t("form.cancel")}
        </Button>
        {draft.editing && (
          <Button
            variant="ghost"
            size="sm"
            className="text-destructive ml-auto"
            data-slot="settings-provider-remove"
            disabled={busy}
            title={t("form.removeTitle")}
            onClick={() => void remove()}
          >
            {t("form.remove")}
          </Button>
        )}
      </div>
    </div>
  );
};

/// ONE ROW, DRAWN IN BOTH SECTIONS: the providers this home holds a key for, and --
/// behind a sentence that says how to bring them back -- the ones it does not. The same
/// row either way, because a provider without a key is not a different thing to edit:
/// opening it is exactly how a person gives it one.
const ProviderListRow: FC<{ provider: ProviderRow; onOpen: (provider: ProviderRow) => void }> = ({
  provider: p,
  onOpen,
}) => {
  const { t } = useTranslation("settings");
  return (
    <button
      type="button"
      data-slot="settings-provider-row"
      data-origin={p.origin}
      className="hover:bg-accent/40 flex flex-col gap-0.5 rounded-md p-2 text-left"
      onClick={() => onOpen(p)}
    >
      <span className="flex items-center gap-2 text-xs">
        <span className="font-medium">{providerLabel(p)}</span>
        <span className="text-muted-foreground rounded border px-1 text-[10px]">
          {ORIGIN_LABELS_PROVIDER[p.origin](t)}
        </span>
        {hasKey(p) ? (
          <span className="text-muted-foreground text-[10px]">{t("models.keyPresent")}</span>
        ) : (
          <span className="text-muted-foreground text-[10px]">{t("models.keyMissing")}</span>
        )}
      </span>
      <span className="text-muted-foreground font-mono text-[10px] break-all">
        {p["base-url"]}
      </span>
      <span className="text-muted-foreground text-[10px]">
        {t("models.count", { count: p.models.length, credential: p.credential })}
      </span>
    </button>
  );
};

const ModelsPage: FC<{
  registry: Registry | null;
  failure: string | null;
  onChanged: () => void;
}> = ({ registry, failure, onChanged }) => {
  const { t } = useTranslation("settings");
  const [draft, setDraft] = useState<Draft | null>(null);

  if (failure !== null && registry === null) {
    return <Refusal slot="settings-providers-error" message={failure} />;
  }
  if (registry === null) {
    return (
      <p data-slot="settings-page-models-loading" className="text-muted-foreground flex items-center gap-2 text-xs">
        <Loader2Icon className="size-3.5 animate-spin" />
        {t("models.loading")}
      </p>
    );
  }

  if (draft !== null) {
    return (
      <>
        <Button
          variant="ghost"
          size="sm"
          data-slot="settings-provider-back"
          className="-ml-2 mb-1"
          onClick={() => setDraft(null)}
        >
          <ArrowLeftIcon /> {t("models.back")}
        </Button>
        <ProviderForm
          key={`${draft.id}-${draft.editing}`}
          draft={draft}
          protocols={registry.protocols}
          onCancel={() => setDraft(null)}
          onSaved={() => {
            setDraft(null);
            onChanged();
          }}
          onRemoved={() => {
            setDraft(null);
            onChanged();
          }}
        />
      </>
    );
  }

  // ONE RULE, TWO SECTIONS (see `lib/provider-key.ts`): what this home holds a key for
  // is the list, and what it does not is behind a sentence.
  const { keyed, unkeyed } = splitByKey(registry.providers);
  const open = (p: ProviderRow) => setDraft(draftOf(p));

  return (
    <div data-slot="settings-page-models" className="flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <SectionTitle>{t("models.title")}</SectionTitle>
        <Button
          variant="outline"
          size="sm"
          data-slot="settings-provider-add"
          onClick={() => setDraft(blankDraft(registry.protocols))}
        >
          <PlusIcon /> {t("models.add")}
        </Button>
      </div>
      {/* THE PROVIDERS THIS HOME HOLDS A KEY FOR. One that will certainly refuse is
          not put in front of a person by default -- `lib/provider-key.ts` is the rule,
          and the composer's model picker reads the same one. */}
      <div data-slot="settings-providers" className="flex flex-col divide-y">
        {keyed.map((p) => (
          <ProviderListRow key={p.name} provider={p} onOpen={open} />
        ))}
      </div>

      {/* AND THE REST: not offered, but not gone either. One sentence says how many
          there are and how to bring them back, and opening it gives today's row --
          clickable, able to take a key, able to save it.

          IT IS A `<details>` AND NOT STATE OF OURS, which is what makes opening and
          closing it a LAYOUT act rather than a fetch: the rows are already in the DOM,
          no request goes out to see them, and no state this panel holds -- including a
          draft mid-edit -- is touched by a disclosure triangle.

          Hiding them outright would take the built-in table's ids off the page, and
          'add a provider to give openrouter a key' is an action a person takes by
          reading one. */}
      {unkeyed.length > 0 && (
        <details data-slot="settings-providers-unkeyed" className="rounded-md border p-2">
          <summary className="cursor-pointer text-xs">
            {t("models.withoutKeys", { count: unkeyed.length })}
          </summary>
          <p className="text-muted-foreground mt-1 text-[10px]">{t("models.withoutKeysHint")}</p>
          <div data-slot="settings-providers-without-keys" className="mt-1 flex flex-col divide-y">
            {unkeyed.map((p) => (
              <ProviderListRow key={p.name} provider={p} onOpen={open} />
            ))}
          </div>
        </details>
      )}
    </div>
  );
};

// ------------------------------------------------------------------ Subagents

/// What the subagent form holds while it is open.
type SubagentEdit = {
  /// The name in the field. DISABLED while editing rather than merely ignored: the
  /// name IS the row in harness.edn, so a "rename" is not an edit at all -- it is a
  /// different subagent -- and the server refuses a name already in force rather than
  /// quietly taking it over.
  name: string;
  description: string;
  baseline: Baseline;
  /// The exclusions as the ONE thing a person types: comma-separated tool names. This
  /// side does not own the tool vocabulary and must not pretend to -- the server
  /// judges the names and its sentence is what a refusal shows (see lib/subagents.ts).
  exclude: string;
  /// Which screen sent it. False means "add one under this name", and that is what
  /// makes a name already in force a REFUSAL; true means "change the row I am showing".
  editing: boolean;
  /// Whether the row being edited is one the CODE supplies. Read off the listing
  /// rather than compared against a list of two names here -- a second answer to that
  /// question is a second thing to keep in step.
  builtin: boolean;
};

const editOf = (d: SubagentDefinition): SubagentEdit => ({
  name: d.name,
  description: d.description,
  baseline: d.baseline,
  exclude: d.exclude.join(", "),
  editing: true,
  builtin: d.builtin,
});

/// A NEW SUBAGENT STARTS READ-ONLY, and the default is a decision rather than a
/// placeholder: `:read-only` is the baseline whose worst case is a wasted delegation,
/// where `:all` is a second pair of hands with write access to everything -- handed
/// out by somebody who has not typed anything into the form yet.
const blankSubagent = (): SubagentEdit => ({
  name: "",
  description: "",
  baseline: "read-only",
  exclude: "",
  editing: false,
  builtin: false,
});

/// The field's text -> the array the wire takes. Empty pieces are dropped, because a
/// trailing comma is how a person ends a list, not a request to exclude a tool whose
/// name is the empty string.
const exclusionsOf = (text: string): string[] =>
  text
    .split(",")
    .map((part) => part.trim())
    .filter((part) => part !== "");

/// THE FORM FOR ONE SUBAGENT, and the only thing in the app that writes harness.edn.
///
/// A REFUSAL LEAVES THE FORM OPEN AND THE FILE ALONE, which is the whole promise it
/// makes. The server validates the entire block before it opens the file (see
/// `check-block!`), so the sentence shown here is also the proof that nothing moved --
/// and the note above the buttons is what tells a person which file that is and how to
/// get the previous version back.
const SubagentForm: FC<{
  edit: SubagentEdit;
  /// The user-level harness.edn these definitions live in, so the note can NAME the
  /// file rather than describe it. The server always answers an absolute path, even
  /// for a home that has no such file yet -- which is exactly when the name matters.
  file: string;
  onCancel: () => void;
  onSaved: () => void;
  onRemoved: () => void;
}> = ({ edit: initial, file, onCancel, onSaved, onRemoved }) => {
  const { t } = useTranslation("settings");
  const { t: tErrors } = useTranslation("errors");
  // A SECOND TRANSLATOR, ON PURPOSE, and the only place in this panel that has one.
  // The two baseline sentences are the FEATURE's vocabulary, not this form's: they are
  // the same words the sidebar's block and the roster show (see subagent-list.tsx), so
  // they are read from the catalog they live in rather than restated here in
  // `settings`. A "settings.baselineAll" beside a "shell.baselineAll" would be two
  // claims about one range, which is exactly what the shared module exists to prevent.
  const { t: tSubagents } = useTranslation();
  const [draft, setDraft] = useState<SubagentEdit>(initial);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const set = (patch: Partial<SubagentEdit>) => setDraft((d) => ({ ...d, ...patch }));

  const save = async () => {
    setBusy(true);
    setFailure(null);
    try {
      await putSubagent(
        {
          name: draft.name,
          description: draft.description,
          baseline: draft.baseline,
          exclude: exclusionsOf(draft.exclude),
        },
        draft.editing,
        tErrors,
      );
      onSaved();
    } catch (f: unknown) {
      setFailure(f instanceof Error ? f.message : String(f));
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    setFailure(null);
    try {
      await removeSubagent(draft.name, tErrors);
      onRemoved();
    } catch (f: unknown) {
      setFailure(f instanceof Error ? f.message : String(f));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div data-slot="settings-subagent-form" className="flex flex-col gap-3">
      <Field
        label={t("subagents.name")}
        slot="settings-subagent-name"
        hint={
          draft.editing ? t("subagents.editingNameHint") : t("subagents.nameHint")
        }
      >
        <Input
          className="h-8 font-mono text-xs"
          value={draft.name}
          disabled={draft.editing || busy}
          onChange={(e) => set({ name: e.target.value })}
        />
      </Field>

      <Field
        label={t("subagents.description")}
        slot="settings-subagent-description"
        hint={t("subagents.descriptionHint")}
      >
        <Textarea
          className="min-h-16 text-xs"
          value={draft.description}
          disabled={busy}
          onChange={(e) => set({ description: e.target.value })}
        />
      </Field>

      <Field
        label={t("subagents.baseline")}
        slot="settings-subagent-baseline"
        hint={t("subagents.baselineHint")}
      >
        <select
          aria-label={t("subagents.baseline")}
          className={inputClass}
          value={draft.baseline}
          disabled={busy}
          onChange={(e) => set({ baseline: e.target.value as Baseline })}
        >
          {/* THE TWO OPTIONS ARE THE TWO SENTENCES the sidebar and the roster show,
              from `subagent-list.tsx` -- the same wording in all three places, so a
              person who picked "everything this session has, except eval and
              delegating" here reads that phrase back on the row. */}
          <option value="all">{BASELINE_LABELS.all(tSubagents)}</option>
          <option value="read-only">{BASELINE_LABELS["read-only"](tSubagents)}</option>
        </select>
      </Field>

      <Field
        label={t("subagents.exclude")}
        slot="settings-subagent-exclude"
        hint={t("subagents.excludeHint")}
      >
        <Input
          className="h-8 font-mono text-xs"
          placeholder={t("subagents.excludePlaceholder")}
          value={draft.exclude}
          disabled={busy}
          onChange={(e) => set({ exclude: e.target.value })}
        />
      </Field>

      {failure !== null && (
        <Refusal
          slot="settings-subagent-error"
          message={failure}
          note={t("subagents.errorNote")}
        />
      )}

      {/* WHERE THIS LANDS, IN WORDS, ON EVERY OPEN FORM -- because the two things a
          person cannot see from inside a dialog are that a built-in has no row of its
          own to change and that the file is rewritten whole. Both are said, and the
          file is named, so "I broke my harness.edn" has an answer before it is asked. */}
      <p
        data-slot="settings-subagent-where"
        className="text-muted-foreground text-xs break-words"
      >
        {draft.builtin
          ? t("subagents.builtinNote", { file })
          : t("subagents.customNote", { file })}{" "}
        {t("subagents.fileNote")}
      </p>

      <div className="flex items-center gap-2">
        <Button
          size="sm"
          data-slot="settings-subagent-save"
          disabled={busy}
          onClick={() => void save()}
        >
          {busy && <Loader2Icon className="animate-spin" />}
          {draft.editing ? t("subagents.save") : t("subagents.create")}
        </Button>
        <Button
          variant="ghost"
          size="sm"
          data-slot="settings-subagent-cancel"
          disabled={busy}
          onClick={onCancel}
        >
          {t("subagents.cancel")}
        </Button>
        {/* NO REMOVE ENTRY FOR A BUILT-IN, and none is drawn and disabled: a disabled
            button is a promise that the action exists. A built-in is edited, never
            removed, and the note above says why. */}
        {draft.editing && !draft.builtin && (
          <Button
            variant="destructive"
            size="sm"
            data-slot="settings-subagent-remove"
            title={t("subagents.removeTitle")}
            disabled={busy}
            className="ms-auto"
            onClick={() => void remove()}
          >
            <TrashIcon /> {t("subagents.remove")}
          </Button>
        )}
      </div>
    </div>
  );
};

/// THE PAGE. A list, and -- once a row is clicked or Add is pressed -- the form.
///
/// IT READS ITS OWN ENDPOINT AND RELOADS ITSELF, unlike the two pages above. Those
/// share `GET /api/settings` and the provider catalog because they are two readings of
/// ONE file, config.edn; this page's file is harness.edn, and a page that refreshed
/// somebody else's reading would be claiming a relationship that is not there. What
/// they do share is the discipline: read fresh, show the server's sentence, never
/// cache.
const SubagentsPage: FC = () => {
  const { t } = useTranslation("settings");
  const { t: tErrors } = useTranslation("errors");
  const [listing, setListing] = useState<SubagentListing | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [draft, setDraft] = useState<SubagentEdit | null>(null);

  /// A POST-WRITE RELOAD KEEPS THE OLD LIST UNTIL THE NEW ONE ARRIVES, unlike the
  /// panel's first read, which clears. The difference is what would flash: a first
  /// read clearing is a blank panel becoming full, and a reload clearing is a full
  /// list blinking empty on its way to a list that differs from it by one row.
  const load = useCallback(async () => {
    try {
      setListing(await listSubagents(tErrors));
      setFailure(null);
    } catch (f: unknown) {
      setListing(null);
      setFailure(f instanceof Error ? f.message : String(f));
    }
  }, [tErrors]);

  useEffect(() => {
    void load();
  }, [load]);

  if (draft !== null && listing !== null) {
    return (
      <>
        <Button
          variant="ghost"
          size="sm"
          data-slot="settings-subagent-back"
          className="-ml-2 mb-1"
          onClick={() => setDraft(null)}
        >
          <ArrowLeftIcon /> {t("subagents.back")}
        </Button>
        <SubagentForm
          key={`${draft.name}-${draft.editing}`}
          edit={draft}
          file={listing.path ?? ""}
          onCancel={() => setDraft(null)}
          onSaved={() => {
            setDraft(null);
            void load();
          }}
          onRemoved={() => {
            setDraft(null);
            void load();
          }}
        />
      </>
    );
  }

  return (
    <div data-slot="settings-page-subagents" className="flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <SectionTitle>{t("subagents.title")}</SectionTitle>
        <Button
          variant="outline"
          size="sm"
          data-slot="settings-subagent-add"
          onClick={() => setDraft(blankSubagent())}
        >
          <PlusIcon /> {t("subagents.add")}
        </Button>
      </div>

      {failure !== null && <Refusal slot="settings-subagents-error" message={failure} />}

      {listing === null && failure === null && (
        <p
          data-slot="settings-page-subagents-loading"
          className="text-muted-foreground flex items-center gap-2 text-xs"
        >
          <Loader2Icon className="size-3.5 animate-spin" />
          {t("subagents.loading")}
        </p>
      )}

      {listing !== null && (
        <>
          {/* THE PROBLEM IS PART OF THE ANSWER, NOT AN ERROR STATE -- the same
              distinction the sidebar's block draws, for the same reason: a typo in
              harness.edn leaves a harness that still runs (the reader is tolerant and
              the built-ins survive it), so the request answered 200 and this is one
              more thing the page has to say. Until the block is fixed it cannot be
              SAVED over either, which is what the refusal on a save attempt names. */}
          {listing.problem !== null && (
            <p
              data-slot="settings-subagents-problem"
              className="text-destructive text-xs break-words"
            >
              {t("subagents.problemLead")}
              {listing.problem}
              {t("subagents.problemTail")}
            </p>
          )}
          <DefinitionButtons definitions={listing.subagents} onEdit={(d) => setDraft(editOf(d))} />
        </>
      )}
    </div>
  );
};

// ------------------------------------------------------------------- the rest

// ------------------------------------------------------------------ the panel

/// A PAGE, not a section: the nav is the one place a person looks for something,
/// and "where do I see the servers" should have the same answer as every other
/// question about this session.
///
/// MCP IS NOT A SETTING, and it is here anyway. Nothing on this page writes
/// `mcp.edn` -- what a server IS comes from the files, and this only shows the
/// ledger and switches servers on or off FOR THIS SESSION. It sits beside the
/// others because it is one of the things a person asks about "what is this
/// session running on", which is what this dialog is for.
///
/// SUBAGENTS IS THE FOURTH PAGE, and the only one whose file is harness.edn. It is a
/// page rather than a section of General because "where do I change what a session can
/// hand work to" deserves the same answer as every other question about this session,
/// and because a list of definitions plus a form that rewrites a file is not a row in
/// somebody else's report.
///
/// `general` / `models` / `mcp` / `subagents`.
type Page = "general" | "models" | "mcp" | "subagents";

const PAGES: { id: Page; label: (t: Translate) => string }[] = [
  { id: "general", label: (t) => t("page.general") },
  { id: "models", label: (t) => t("page.models") },
  { id: "mcp", label: (t) => t("page.mcp") },
  { id: "subagents", label: (t) => t("page.subagents") },
];

export const SettingsPanel: FC<{
  open: boolean;
  onOpenChange: (open: boolean) => void;
  threadId: string;
}> = ({ open, onOpenChange, threadId }) => {
  const { t } = useTranslation("settings");
  const { t: tErrors } = useTranslation("errors");
  const [page, setPage] = useState<Page>("general");
  const [settings, setSettings] = useState<Settings | null>(null);
  const [registry, setRegistry] = useState<Registry | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [registryFailure, setRegistryFailure] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  /// TWO CALLS, TWO FAILURES, AND THAT SEPARATION IS THE POINT rather than a
  /// detail of the code below: `GET /api/settings` RESOLVES the configuration and
  /// fails when it cannot be served, while `GET /api/providers` only reads it and
  /// keeps working. A home whose :default names a provider somebody just removed is
  /// exactly that state -- the report refuses, the catalog answers -- and nulling
  /// both on one failure would take away the controls that FIX it.
  ///
  /// A FAILED READ LEAVES NOTHING BEHIND: the previous answer is cleared, because a
  /// stale value sitting next to a refusal is the panel saying two things at once.
  /// The server's own sentence, kept whole, is what goes in its place -- it names the
  /// provider it could not resolve and the ones the catalog does define, and a
  /// paraphrase would be one more thing to distrust.
  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [s, r] = await Promise.allSettled([getSettings(threadId, tErrors), registryFor(tErrors)]);
      if (s.status === "fulfilled") {
        setSettings(s.value);
        setFailure(null);
      } else {
        setSettings(null);
        setFailure(s.reason instanceof Error ? s.reason.message : String(s.reason));
      }
      if (r.status === "fulfilled") {
        setRegistry(r.value);
        setRegistryFailure(null);
      } else {
        setRegistry(null);
        setRegistryFailure(r.reason instanceof Error ? r.reason.message : String(r.reason));
      }
    } finally {
      setLoading(false);
    }
  }, [threadId, tErrors]);

  // Refetched on EVERY open, which is the whole "read it fresh" contract showing
  // through: there is no cache to go stale because there is no cache.
  useEffect(() => {
    if (open) void load();
  }, [open, load]);

  /// After a write: the same read, and stay where the person was. Not a second
  /// implementation of it -- a write that needs a different refresh is a sign the
  /// refresh was wrong.
  const reload = load;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        data-slot="settings-panel"
        className="sm:max-w-3xl"
        aria-describedby={undefined}
      >
        <DialogHeader>
          <DialogTitle>{t("panel.title")}</DialogTitle>
        </DialogHeader>

        {/* THE DIALOG DOES NOT GROW WITH ITS CONTENT. A provider form is taller than
            the panel, and a modal that resized around it would move the nav and the
            buttons while somebody is typing in it. So the size is fixed here and the
            PAGE scrolls inside. */}
        <div className="flex h-[min(30rem,62vh)] gap-4">
          <nav data-slot="settings-nav" className="flex w-36 shrink-0 flex-col gap-0.5">
            {PAGES.map((p) => (
              <button
                key={p.id}
                type="button"
                data-slot={`settings-nav-${p.id}`}
                aria-current={page === p.id ? "page" : undefined}
                className={
                  page === p.id
                    ? "bg-accent text-accent-foreground rounded-md px-2 py-1.5 text-left text-xs"
                    : "text-muted-foreground hover:bg-accent/40 rounded-md px-2 py-1.5 text-left text-xs"
                }
                onClick={() => setPage(p.id)}
              >
                {p.label(t)}
              </button>
            ))}
          </nav>

          <div className="min-w-0 flex-1 overflow-y-auto pr-1">
            {failure !== null && (
              <Refusal
                slot="settings-error"
                message={failure}
                note={t("panel.errorNote")}
              />
            )}

            {loading && settings === null && failure === null && (
              <p
                data-slot="settings-loading"
                className="text-muted-foreground flex items-center gap-2 text-xs"
              >
                <Loader2Icon className="size-3.5 animate-spin" />
                {t("panel.loading")}
              </p>
            )}

            {page === "general" && (settings !== null || registry !== null) && (
              <GeneralPage settings={settings} registry={registry} onChanged={reload} />
            )}
            {page === "models" && (
              <ModelsPage registry={registry} failure={registryFailure} onChanged={reload} />
            )}
            {page === "mcp" && (
              <section data-slot="settings-mcp" className="flex flex-col gap-3">
                <SectionTitle>{t("mcp.heading")}</SectionTitle>
                {/* `mcp.edn` is a filename, not copy: it stays literal and stays in
                    `<code>`, and the sentence is split into the runs around it (the
                    same reason the Default-tier intro gives). */}
                <p className="text-muted-foreground text-xs">
                  {t("mcp.introLead")}
                  <code className="bg-muted mx-1 rounded px-1">mcp.edn</code>
                  {t("mcp.introTail")}
                </p>
                <McpPanel threadId={threadId} />
              </section>
            )}
            {/* NO `threadId`, and that is the page's own claim rather than an
                omission: a subagent is defined for the HOME, not for the session
                looking at it. The sidebar's block and this form read the same file
                and must give the same answer -- which is what "the settings form
                writes the user level only" is for (see subagents.clj). */}
            {page === "subagents" && <SubagentsPage />}
          </div>
        </div>

        <div className="flex items-center justify-between gap-2">
          <span className="text-muted-foreground text-xs">
            {settings?.source === "inline"
              ? t("panel.sourceInline")
              : settings?.source === "request"
                ? t("panel.sourceRequest")
                : ""}
          </span>
          <Button
            variant="ghost"
            size="sm"
            data-slot="settings-refresh"
            disabled={loading}
            onClick={() => void load()}
            title={t("panel.rereadTitle")}
            className="h-7 px-2 text-xs"
          >
            <RefreshCwIcon className={loading ? "size-3.5 animate-spin" : "size-3.5"} />
            {t("panel.reread")}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
};

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
import { SessionsBatchPanel, type SessionFilter } from "@/components/session-management";
import { SensitivePathsRow } from "@/components/security-paths";
import { GroupsPage } from "@/components/session-groups";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { applyLanguage } from "@/lib/i18n";
import { saveLanguage } from "@/lib/languageSetting";
import { SUPPORTED_LANGUAGES, isLanguage, type Language } from "@/lib/language";
import {
  archiveSessions,
  deleteSessions,
  listSidebar,
  type SessionSummary,
  type SidebarListing,
} from "@/lib/projects";
import {
  probeModels,
  putDefaults,
  putProvider,
  registryFor,
  removeProvider,
  type DefaultKnobs,
  type KnownProvider,
  type ModelRow,
  type ModelSuggestion,
  type Origin,
  type ProviderRow,
  type Registry,
} from "@/lib/providers";
import { entryOf } from "@/lib/model-entry";
import { drawnInSettings, hasKey } from "@/lib/provider-key";
import { effortsForModel, effortsOffered } from "@/lib/efforts";
import { providerLabel } from "@/lib/provider-label";
import { getSettings, type Settings } from "@/lib/settings";
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


/// Where a provider came from, in the words a person would use. The three are
/// different edits -- a built-in, your own vendor, your own patch of a built-in --
/// and only the second and third are yours to change or remove.
///
/// THE WORD IS OURS, THE KEY IS THE SERVER'S: `Origin` is the server's keyword, the word
/// is ours, and each branch writes its own literal key.
const ORIGIN_LABELS_PROVIDER: Record<Origin, (t: Translate) => string> = {
  builtin: (t) => t("origin.builtin"),
  user: (t) => t("origin.user"),
  "builtin-patched": (t) => t("origin.builtinPatched"),
};


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

/// A LABEL AND ITS CONTROL, side by side, in the one row's rhythm (owner, 2026-10-06).
/// NOT the page's `Field`, and the difference is the axis: `Field` stacks a label ABOVE a
/// full-width input, which is the right shape for a value you type into and the wrong one
/// here -- inside the facts fold the controls are checkboxes and a short select, so a label
/// above each would spend three lines saying 输入 / 输出 / 指令变了怎么送达 before the
/// first tick box appeared.
///
/// THE LABEL COLUMN IS FIXED (`w-20`) so the three rows' controls START IN THE SAME PLACE.
/// Without it the boxes line up under a ragged edge and the eye reads the fold as one
/// paragraph again -- the thing the labels were added to end.
const FactRow: FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div className="flex w-full items-center gap-3">
    <span className="w-20 shrink-0 text-xs font-medium">{label}</span>
    {children}
  </div>
);

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

  /// WHAT THE EMPTY OPTION MEANS, NAMED. An effort the tier does not pin puts the
  /// choice back on the vendor's own default -- so the empty row says so, read
  /// from the same vendor table the composer's 默认档 row uses (`lib/efforts.ts`).
  /// A vendor with no stated default (OpenAI: it is per model) keeps the
  /// vendor-neutral row instead of inventing a level here.
  const vendorDefaultLabel: string | null = effortsForModel(model).default ?? null;
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
          {/* 默认档, NOT 'NONE SENT'. The empty option is the tier saying nothing,
              which puts the effort back on the vendor's own default -- and that is
              NAMED HERE, from the same vendor table the composer reads, so the two
              faces adapt together (OpenAI, whose default is per model, keeps the
              vendor-neutral row). The server refuses no level; this is a label,
              not a guard. */}
          <option value="">
            {vendorDefaultLabel === null
              ? t("defaults.noneSent")
              : t("defaults.followsVendor", { level: vendorDefaultLabel })}
          </option>
          {effortsOffered(model, effort).map((r) => (
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

/// The panel's own language -- and the ONE control on any page of it that writes
/// config.edn's `:ui` section rather than a provider: the language is the HOME's own
/// setting, not a knob a session starts from.
const LanguageRow: FC = () => {
  const { t, i18n } = useTranslation("settings");
  const { t: tErrors } = useTranslation("errors");
  // THE WRITE IS A ROUND TRIP NOW, so the row owns its own state: whether a save is in
  // flight, and what the server said when it refused. The language in force is still
  // i18n's -- nothing here keeps a second copy of it.
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
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
          disabled={saving}
          onChange={(event) => {
            // The options are generated from the same list, so this is always one of
            // them -- and the guard is here rather than a cast because the value is
            // DOM-supplied, which is exactly where a closed list stops being closed.
            const chosen = event.target.value;
            if (!isLanguage(chosen)) return;
            setError(null);
            setSaving(true);
            // WRITE FIRST, THEN SWITCH: a switch that could not be saved must not be
            // shown as if it had been. The server's own sentence is what the person
            // reads when it refuses.
            void saveLanguage(chosen, tErrors)
              .then(() => applyLanguage(chosen))
              .catch((reason: unknown) => {
                setError(reason instanceof Error ? reason.message : String(reason));
              })
              .finally(() => setSaving(false));
          }}
        >
          {SUPPORTED_LANGUAGES.map((language) => (
            <option key={language} value={language}>
              {LANGUAGE_NAMES[language]}
            </option>
          ))}
        </select>
        {error !== null && (
          <p className="text-destructive mt-2 text-xs break-words" role="alert">
            {error}
          </p>
        )}
      </Field>
    </section>
  );
};

// ----------------------------------------------------------------- Security
//
// THE SECURITY ROW LIVES IN ITS OWN MODULE (`components/security-paths.tsx`), the way the
// session-management panel does: it draws THIS HOME's built-in half as NOT deletable and its
// custom half as deletable, and a module of its own is what lets a browserless run render it
// (`test/suites/security-paths.tsx`). See that file for the two-group design.

const GeneralPage: FC<{
  registry: Registry | null;
  onChanged: () => void;
}> = ({ registry, onChanged }) => {
  return (
    <div data-slot="settings-page-general" className="flex flex-col gap-4">

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

// ------------------------------------------------------------------ Security
//
// THE SENSITIVE PATHS ARE NOT A MODEL SETTING and not this session's: they are THIS HOME's,
// on top of the built-in list. A PAGE of its own rather than a section of General, because it
// is the one control that changes what a tool call may DO rather than what a run talks to --
// and because 'where do I say what is secret here' deserves the same answer as every other
// question about this session. The row itself lives in components/security-paths.tsx.
const SecurityPage: FC = () => (
  <div data-slot="settings-page-security" className="flex flex-col gap-4">
    <SensitivePathsRow />
  </div>
);

// -------------------------------------------------------------------- Models

/// A FRESH ROW DECLARES NOTHING BUT ITS ID. It used to arrive claiming `text`, and
/// that was this form inventing a fact about somebody's model: the server now reads
/// an empty pair as SILENCE and fills it from the models.dev database
/// (`harness.cap.providers/modalities-with`), flooring at text where the database
/// knows nothing. So the checkboxes start empty and mean 'not stated' until a person
/// ticks one.
const emptyModel = (id: string): ModelRow => ({
  id,
  input: [],
  output: [],
});
/// WHAT THE FOLD'S SUMMARY READS WHEN IT IS SHUT: the model's facts as a run would get
/// them -- the file's own word where the file spoke, models.dev's where it did not.
/// `TFunction` is loose here because the summary is the only caller and the strings it
/// reaches for all live on this page's face.
type ModelFacts = {
  input: readonly string[];
  output: readonly string[];
  "context-window"?: number;
  "max-output-tokens"?: number;
};

const modelFactsOf = (row: ModelRow): ModelFacts => ({
  input: row.input.length > 0 ? row.input : (row["input-suggested"] ?? []),
  output: row.output.length > 0 ? row.output : (row["output-suggested"] ?? []),
  "context-window": row["context-window"] ?? row["context-window-suggested"],
  "max-output-tokens": row["max-output-tokens"] ?? row["max-output-tokens-suggested"],
});

/// THE COMPACT FACTS LINE for a shut fold: `text · text · 1M · 128K`. Numbers are
/// humanized because that is how models.dev's own UI shows them; a count nobody knows
/// (the file silent, the database too) keeps its place as `—` rather than vanishing,
/// because two items and four items are different shapes to glance at.
const shortCount = (n: number | undefined): string => {
  if (n === undefined) return "—";
  if (n >= 1_000_000) return `${Number((n / 1_000_000).toFixed(1))}M`;
  if (n >= 1_000) return `${Math.round(n / 1_000)}K`;
  return String(n);
};

const modelFactsSummary = (row: ModelRow, t: Translate): string => {
  const f = modelFactsOf(row);
  const modality = (dirs: readonly string[]): string => {
    const known = dirs.filter((d) => d === "text" || d === "image");
    return known.length > 0 ? known.join("+") : t("form.factsUnknown");
  };
  return [
    modality(f.input),
    modality(f.output),
    shortCount(f["context-window"]),
    shortCount(f["max-output-tokens"]),
  ].join(" · ");
};

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
  // WHAT models.dev ANSWERS FOR THE TWO COUNTS, as this row's placeholder state -- what
  // the fold would fill in where the file is silent. The summary above shows the RESOLVED
  // answer (file first, database second); these are the database's half on its own.
  //
  // IT WAS ALSO, until owner, 2026-10-06 ('上下限后面的文字描述直接去掉'), the `title` of
  // a sentence drawn under these boxes explaining where they come from. That sentence is
  // gone; the placeholders still carry the same fact, which is the half that was doing the
  // real work anyway.
  const countFacts: Record<"context-window" | "max-output-tokens", number | undefined> = {
    "context-window": row["context-window-suggested"],
    "max-output-tokens": row["max-output-tokens-suggested"],
  };
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
        {/* THE NAME IS OPTIONAL, and it is the ONE fact the form offers from the
            outside: the database's own name for this id (`name-suggested`) shows as
            the placeholder, and typing over it is the only way a name is written. It is
            also the LAST of the outside world's three suggestions still on screen -- the
            counts' placeholders beside it -- now that the sentence under them is gone.
            Leaving it empty keeps the file silent and the resolution answers the
            database's name -- or the id -- when somebody asks what this model is
            called. */}
        <Input
          aria-label={t("form.modelName")}
          className="h-7 flex-1 text-xs"
          placeholder={row["name-suggested"] ?? t("form.modelNamePlaceholder")}
          value={row.name ?? ""}
          onChange={(e) => {
            const next = { ...row };
            if (e.target.value === "") delete next.name;
            else next.name = e.target.value;
            onChange(next);
          }}
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
      {/* ONE FOLD FOR EVERYTHING THE DATABASE ANSWERS (owner, 2026-10-03: 'set the id,
          make the name optional, fold the limits up together with input/output -- the
          facts come from models.dev'). What stays OUT of the fold is what only this
          person can say: the id and the name. Everything inside is a fact about the
          model that models.dev already answers -- the modalities, the two counts --
          plus the one thing it does NOT (the delivery capability), which is why the
          fold's summary says 'from models.dev' and the delivery control does not
          pretend otherwise.
          
          THE SUMMARY CARRIES THE ANSWER WHEN THE FOLD IS SHUT: what the file says,
          followed by what models.dev would fill in where it is silent. A row that
          declares nothing reads 'text · text · 1M · 128K' -- the RESOLVED answer,
          not an empty pair -- because that is what a run on this model would do.
          Which of the two halves said it stays visible once the fold is open. */}
      <details data-slot="settings-provider-model-facts" className="rounded-md border border-dashed px-2 py-1">
        <summary
          data-slot="settings-provider-model-facts-summary"
          className="text-muted-foreground cursor-pointer text-xs select-none"
        >
          {modelFactsSummary(row, t)}
        </summary>
        <div className="mt-2 flex flex-col gap-2">
          {/* A LABEL BESIDE EVERY CONTROL (owner, 2026-10-06: '改成 label+input 的形式').
              The fold used to be one strip of bare checkboxes whose only names were the
              catalog's own `text` / `image`, then a `输出：文本` SPAN that was not a control
              at all, then a select with no label but a hover title. Four controls, no
              questions attached to them. Each is now a row that can be answered: 输入 /
              输出 / 指令变了怎么送达 / 上下限. */}
          <div className="flex items-center gap-3">
            <FactRow label={t("form.inputLabel")}>
              {(["text", "image"] as const).map((modality) => (
                <label key={modality} className="flex items-center gap-1 text-xs">
                  <input
                    type="checkbox"
                    // THE WORD IS OURS, THE KEY IS NOT: `modality` is the catalog's own
                    // vocabulary ("text" / "image") and is printed as-is, while the
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
            </FactRow>
          </div>
          {/* OUTPUT IS ITS OWN ROW WITH A REAL CHECKBOX. It was a span saying `输出：文本`
              because text is the only output type the catalog carries (`output-types` is
              `#{:text}`) -- so the fact was constant. A constant still belongs beside its
              name, and one day the vocabulary grows a second type: the control is already
              where that fact belongs, and `output-types` is the only place to add the word. */}
          <div className="flex items-center gap-3">
            <FactRow label={t("form.outputLabel")}>
              {(["text"] as const).map((modality) => (
                <label key={modality} className="flex items-center gap-1 text-xs">
                  <input
                    type="checkbox"
                    aria-label={t("form.givesText")}
                    checked
                    disabled
                    onChange={() => undefined}
                  />
                  {modality}
                </label>
              ))}
            </FactRow>
          </div>
          <div className="flex items-center gap-3">
            <FactRow label={t("form.instructionUpdatesLabel")}>
              {/* THREE STATES, NOT TWO. An endpoint no line has spoken for is NOT the same
                  as one whose line says `replace`: the first is silence the server fills
                  with the conservative default, the second is something a person wrote. So
                  the empty option DELETES the key rather than writing `replace`, and a save
                  that never touched this control leaves every other model's line alone. */}
              {/* THE OPTION WORDS ARE THE ENUM NAMES, UNTRANSLATED (owner, 2026-10-06:
                  'select 直接显示英文就行，不用翻译'). They are two short tokens from
                  `cap/providers.clj`, and translating them made the row WIDER than the
                  question it answers -- a select whose label is longer than its longest
                  option reads as a control for something bigger than it is. What each one
                  means is the LABEL's job and the `title`'s; the option only has to be the
                  value a person finds written in config.edn. */}
              <select
                data-slot="settings-provider-model-instruction-updates"
                aria-label={t("form.instructionUpdatesLabel")}
                title={t("form.instructionUpdatesHint")}
                className={`${inputClass} w-auto`}
                value={row["instruction-updates"] ?? ""}
                onChange={(e) => {
                  const next = { ...row };
                  const value = e.target.value;
                  if (value === "") delete next["instruction-updates"];
                  else next["instruction-updates"] = value as "in-place" | "replace";
                  onChange(next);
                }}
              >
                <option value="">{t("form.instructionUpdatesUndeclared")}</option>
                <option value="in-place">in-place</option>
                <option value="replace">replace</option>
              </select>
            </FactRow>
          </div>
          {/* THE TWO COUNTS, LABELLED LIKE THE ROWS ABOVE. They were one row of two
              bare number boxes each preceded by its own word (`上下文 1000000`), which read
              as two fields rather than the pair they are -- and `上下文` is the file's
              `:context-window` under a different name, while the accessible name said the
              raw key. Label and accessible name are the same string here, and the raw key
              is what it says: it names the config.edn key itself. */}
          <div className="flex items-center gap-3">
            <FactRow label={t("form.countsLabel")}>
              {(["context-window", "max-output-tokens"] as const).map((count) => (
                <Input
                  key={count}
                  // The accessible name keeps the field's raw key, which is what the
                  // English aria-label was; it is the one string here that does not
                  // translate, because it names the config.edn key itself.
                  aria-label={
                    count === "context-window"
                      ? t("form.contextWindow")
                      : t("form.maxOutputTokens")
                  }
                  title={
                    count === "context-window"
                      ? t("form.contextWindow")
                      : t("form.maxOutputTokens")
                  }
                  className="h-6 w-20 text-xs"
                  inputMode="numeric"
                  placeholder={countFacts[count] === undefined ? "" : String(countFacts[count])}
                  value={row[count] ?? ""}
                  onChange={(e) => {
                    const raw = e.target.value.trim();
                    const next = { ...row };
                    if (raw === "") delete next[count];
                    else next[count] = Number(raw);
                    onChange(next);
                  }}
                />
              ))}
            </FactRow>
          </div>
          <p className="text-muted-foreground text-[10px]">{t("form.limitsNote")}</p>
        </div>
      </details>
    </div>
  );
};

type Draft = {
  id: string;
  displayName: string;
  baseUrl: string;
  protocol: string;
  apiKey: string;
  /// REPORT ROWS, AS THE DRAFT HOLDS THEM -- not entries, and the difference is the
  /// whole of `lib/model-entry.ts`: the report offers the database's answers beside
  /// the file's, and only the file's may be written. The draft is therefore a draft of
  /// what a SAVE projects, not of what a save sends -- `entryOf` is what crosses.
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
  /// The vendors a person may pick instead of typing an address (the server's
  /// `:known-providers`): models.dev's reachable vendors plus this harness's own.
  known: readonly KnownProvider[];
  /// The provider ids THIS HOME already has, so the picker can say which picks are
  /// already configured. Names only -- an entry is identified by its id.
  existing: readonly string[];
  onCancel: () => void;
  onSaved: () => void;
  onRemoved: () => void;
}> = ({ draft: initial, protocols, known, existing, onCancel, onSaved, onRemoved }) => {
  const { t } = useTranslation("settings");
  const { t: tErrors } = useTranslation("errors");
  const [draft, setDraft] = useState<Draft>(initial);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [offered, setOffered] = useState<ModelSuggestion[] | null>(null);
  const [picked, setPicked] = useState<string[]>([]);
  
  /// WHICH HALF OF THIS FORM A PERSON IS IN: picking a vendor somebody already
  /// described (with the address that vendor publishes), or describing one by hand.
  /// 
  /// A NEW PROVIDER STARTS ON 'known' WHEN THERE IS A LIST TO PICK FROM -- that is the
  /// shorter path, and the custom half is one click away. An EDIT never shows the
  /// switch: the entry already exists, so there is nothing to pick.
  const [mode, setMode] = useState<"known" | "custom">(
    initial.editing || known.length === 0 ? "custom" : "known",
  );
  const taken = new Set(existing);
  const openAiCompatible = protocols.includes("openai-completions")
    ? "openai-completions"
    : (protocols[0] ?? "");

  /// A PICK FILLS THE ENTRY AND CLEARS THE MODELS. The models of a vendor straight
  /// from models.dev are answered by that same document (`harness.cap.model-data`),
  /// so rows typed for a previous pick would be a second, staler answer.
  const pickKnown = (id: string) => {
    const row = known.find((k) => k.id === id);
    if (row === undefined) return;
    set({
      id: row.id,
      displayName: row.name ?? "",
      baseUrl: row["base-url"],
      protocol: openAiCompatible,
      models: [],
    });
  };

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
        //
        // ABSENT when the form has no rows, which is what an endpoint plus a key and no
        // model list IS: the vendor's own /models listing answers it. The `?? ""` this
        // replaced was worse than nothing -- an empty string reached the server as a
        // NAMED model, and came back as "it does not declare one" for a vendor that had
        // never been asked.
        ...(draft.models[0] !== undefined ? { model: draft.models[0].id } : {}),
        // AND THE ROWS CROSS AS ENTRIES, not as the report's rows. The report RIDES five
        // suggested keys beside what the file said (`name-suggested` and the four beside
        // it) so a form can show them; a config.edn model entry may carry none of them,
        // and the server refuses the whole write when one arrives. That refusal is what a
        // person met on a provider the database knows, having changed nothing: "model
        // \"cn:deepseek-v4.1-flash\" of provider :workbuddy carries [:name-suggested], which it
        // does not understand". `entryOf` is the projection, and it is a FUNCTION rather
        // than a type because only a function keeps a key added to the report next month
        // from reaching the writer by accident -- see `lib/model-entry.ts`.
        models: draft.models.map(entryOf),
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
      {/* BUILT-IN OR YOUR OWN, and the choice is only offered when there is something
          to choose: a new provider, with a vendlist to pick from. It is not a mode
          that changes what the fields MEAN -- a picked vendor's address stays
          editable, because a vendor's published URL may carry a placeholder a person
          must fill in (models.dev spells Cloudflare's as
          `…/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/v1`). */}
      {!draft.editing && known.length > 0 && (
        <Field
          label={t("form.source")}
          slot="settings-provider-source"
          hint={t("form.sourceHint")}
        >
          <div className="flex gap-1">
            {(["known", "custom"] as const).map((which) => (
              <Button
                key={which}
                variant={mode === which ? "default" : "outline"}
                size="sm"
                data-slot={`settings-provider-source-${which}`}
                onClick={() => setMode(which)}
              >
                {which === "known" ? t("form.sourceKnown") : t("form.sourceCustom")}
              </Button>
            ))}
          </div>
        </Field>
      )}
      {mode === "known" && known.length > 0 && (
        <Field
          label={t("form.knownVendor")}
          slot="settings-provider-known"
          hint={t("form.knownVendorHint")}
        >
          <select
            aria-label={t("form.knownVendor")}
            data-slot="settings-provider-known-select"
            className={inputClass}
            value={known.some((k) => k.id === draft.id) ? draft.id : ""}
            onChange={(e) => pickKnown(e.target.value)}
          >
            <option value="">{t("form.knownVendorPick")}</option>
            {known.map((k) => (
              <option key={k.id} value={k.id}>
                {k.name ?? k.id} · {k.id} · {t("form.knownVendorModels", { count: k["model-count"] })}
                {/* READY FIRST, at no cost to a person: this environment already holds the
                    key this vendor reads (checked with the SAME lookup a run does --
                    models.dev's own variable name, this harness's derived name, the
                    global). '已配置' stays for an entry THIS HOME already has. */}
                {k["key-ready"]
                  ? ` · ${t("form.knownVendorReady")}`
                  : taken.has(k.id)
                    ? ` · ${t("form.knownVendorTaken")}`
                    : ""}
              </option>
            ))}
          </select>
        </Field>
      )}
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
            // THE KEY IS THE POSITION, NOT THE ID -- the id is what this very row's
            // input edits, so a key carrying it remounts the row on every keystroke
            // and the field drops focus after each character. Rows are added at the
            // tail and removed whole; they are never reordered, so the index is
            // stable for as long as the row is on screen.
            key={i}
            row={row}
            // EVERY ROW MAY GO, including the last: a provider that lists no models
            // of its own is a provider whose ids come from its own /models listing
            // (`harness.cap.providers/vendor-model-tables`), and the form no longer
            // builds something the file refuses.
            canRemove
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
              {offered.map((row) => (
                <label key={row.id} className="flex items-center gap-1.5 font-mono text-xs">
                  <input
                    type="checkbox"
                    checked={picked.includes(row.id)}
                    onChange={(e) =>
                      setPicked(
                        e.target.checked ? [...picked, row.id] : picked.filter((p) => p !== row.id),
                      )
                    }
                  />
                  {row.id}
                  {/* THE RULE'S ANSWER, SHOWN AND NOT APPLIED: the server's prefix table
                      spoke for this family, and the checkbox stays a checkbox. Taking the
                      row prefills the field with it -- a value the person can see and
                      change, which is what keeps a wrong guess survivable. */}
                  {row["instruction-updates"] !== undefined && (
                    <span
                      data-slot="settings-provider-model-suggested"
                      className="text-muted-foreground font-sans"
                    >
                      {t("form.offeredSuggested", { value: row["instruction-updates"] })}
                    </span>
                  )}
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
                  // THE PREFILL COMES FROM THE PROBE'S ANSWER, never from a matching
                  // run here: a second prefix table on this side would be a second answer
                  // free to drift from the server's.
                  const hint = new Map(offered.map((r) => [r.id, r["instruction-updates"]]));
                  const add = picked
                    .filter((id) => !have.has(id))
                    .map((id) => {
                      const row = emptyModel(id);
                      const value = hint.get(id);
                      return value === undefined
                        ? row
                        : { ...row, "instruction-updates": value };
                    });
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
          known={registry["known-providers"]}
          existing={registry.providers.map((p) => p.name)}
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

  // WHAT THIS PAGE DRAWS (see `lib/provider-key.ts`): the providers this home holds a
  // key for, plus every entry a person wrote. The rest of the built-in table is not
  // drawn -- the add form is where a vendor nobody configured is offered.
  const drawn = registry.providers.filter(drawnInSettings);
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
      {/* THE PROVIDERS THIS PAGE IS ABOUT: the ones this home holds a key for, plus the
          entries a person wrote. NOT the whole built-in table -- Ollama sits in it,
          needs no key, and has nothing to do with this home (owner, 2026-10-03: 'if
          there is no API key, do not show it').
          
          THE RULE LIVES IN `lib/provider-key.ts` (`drawnInSettings`), because a
          provider the composer's picker would refuse and a provider this page should
          draw are two different questions that were one function until now.
          
          AND A BUILT-IN NOBODY CONFIGURED IS NOT GONE -- it is offered where it
          belongs, which is the add form: pick a known vendor, get its address, paste
          a key. That is the same list models.dev answers for every vendor it knows
          (`:known-providers`), so nothing this harness ships with became unreachable
          by hiding the keyless rows. */}
      <div data-slot="settings-providers" className="flex flex-col divide-y">
        {drawn.map((p) => (
          <ProviderListRow key={p.name} provider={p} onOpen={open} />
        ))}
      </div>
      {drawn.length === 0 && (
        <p data-slot="settings-providers-empty" className="text-muted-foreground text-xs">
          {t("models.noneYet")}
        </p>
      )}
    </div>
  );
};

// ------------------------------------------------------------------ Subagents

/// What the subagent form holds while it is open.
type SubagentEdit = {
  /// The name in the field. DISABLED while editing rather than merely ignored: the
  /// name IS the row in config.edn's :session, so a "rename" is not an edit at all -- it is a
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

/// THE FORM FOR ONE SUBAGENT, and the only thing in the app that writes config.edn's :session.
///
/// A REFUSAL LEAVES THE FORM OPEN AND THE FILE ALONE, which is the whole promise it
/// makes. The server validates the entire block before it opens the file (see
/// `check-block!`), so the sentence shown here is also the proof that nothing moved --
/// and the note above the buttons is what tells a person which file that is and how to
/// get the previous version back.
const SubagentForm: FC<{
  edit: SubagentEdit;
  /// The user-level config.edn's :session these definitions live in, so the note can NAME the
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
          file is named, so "I broke my config.edn's :session" has an answer before it is asked. */}
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
/// ONE file, config.edn; this page's file is config.edn's :session, and a page that refreshed
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
              config.edn's :session leaves a harness that still runs (the reader is tolerant and
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
// -------------------------------------------------------------------- Sessions

/// ONE VERB, THREE DIRECTIONS: archive and unarchive are the same column write, and delete is
/// the one that takes the conversation back (`lib/projects.ts` has both calls).
type BatchVerb = "archive" | "unarchive" | "delete";

/// THE PAGE: every conversation this home keeps, a checkbox each, and three batch verbs.
///
/// IT PULLS ITS OWN LIST RATHER THAN READING THE SIDEBAR'S, and that is the one thing about it
/// worth saying out loud. The sidebar's listing arrives by push (`events.host`), and this page is
/// not in that conversation: it is a modal somebody opened, so it READS when it opens and READS
/// AGAIN after every write -- a batch just changed rows, and what is on screen has to be the state
/// that came back rather than the one that was asked for. A panel that trusted the push would have
/// to be mounted wherever the push goes; one that trusts nothing but its own read is correct for
/// as long as it is open, at the price of one SELECT.
///
/// THE READ IS `listSidebar`, the same call the sidebar makes -- NOT a second endpoint that could
/// disagree with it about which conversations exist. Its two halves are flattened here because
/// this page's unit is a conversation, and a task is one with no project.
///
/// A REFUSED WRITE IS DRAWN AT TWO SCALES: the sentence for one row lands on that row (the server
/// answers one row per id -- see `lib/projects.ts`), and a request that failed whole lands above
/// the list, where there is no row to put it on. NOTHING IS ROLLED BACK and nothing is retried
/// behind the person's back: the rows that landed are drawn as landed, and what is left ticked
/// after a batch is exactly what did NOT go through.
const SessionsPage: FC = () => {
  const { t } = useTranslation("settings");
  const { t: tErrors } = useTranslation("errors");
  const [listing, setListing] = useState<SidebarListing | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [selected, setSelected] = useState<readonly string[]>([]);
  const [rowErrors, setRowErrors] = useState<Readonly<Record<string, string>>>({});
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  /// WHICH BUCKET THE LIST IS SHOWING. The type is `session-management`'s own `SessionFilter`
  /// rather than a second spelling of the three ids here: the state and the prop it feeds are one
  /// fact, and a copy of it is a copy that can drift.
  const [filter, setFilter] = useState<SessionFilter>("all");

  /// THE PULL, in one place: the read on mount and the read after every write are the same read.
  ///
  /// A FAILED RE-READ LEAVES THE LIST IT HAD, unlike the panel's first read elsewhere on this
  /// page -- and the difference is what is on screen: blanking a list somebody is looking at
  /// because a re-read went wrong would present a failed DELETE as a failed LIST. The sentence
  /// says which it was, and the rows stay readable under it.
  const load = useCallback(async () => {
    try {
      setListing(await listSidebar(tErrors));
      setFailure(null);
    } catch (f: unknown) {
      setFailure(f instanceof Error ? f.message : String(f));
    }
  }, [tErrors]);

  useEffect(() => {
    void load();
  }, [load]);

  /// BOTH HALVES AS ONE LIST, in the listing's own order: the projects (each with its sessions,
  /// as the server ordered them) and then the tasks. THAT ORDER IS THE ENDPOINT'S and not this
  /// page's to normalize -- it is what the sidebar draws, and a second sorting rule here would be
  /// a second answer to "which one is newer".
  const sessions: readonly SessionSummary[] =
    listing === null
      ? []
      : [...listing.projects.flatMap((project) => project.sessions), ...listing.tasks];
  const chosen = sessions.filter((session) => selected.includes(session.threadId));

  /// TOGGLE ONE, BY ID rather than by index: a re-read can reorder the list without changing
  /// which conversations were ticked.
  const toggle = (threadId: string) =>
    setSelected((was) =>
      was.includes(threadId) ? was.filter((id) => id !== threadId) : [...was, threadId],
    );

  /// SELECT EVERY ROW IN VIEW, or untick those -- one control for both, because the box it draws
  /// already says which of the two it is.
  ///
  /// IT TAKES THE IDS RATHER THAN READING `sessions`, and that is the whole of the filter's
  /// integration here: 'all' now means 'all of what is in view'. A box that ticked rows the person
  /// cannot see is how a batch takes conversations nobody looked at -- and unticking is restricted
  /// the same way, so a selection made under one bucket survives a look at another.
  const toggleAll = (threadIds: readonly string[]) =>
    setSelected((was) =>
      threadIds.length > 0 && threadIds.every((id) => was.includes(id))
        ? was.filter((id) => !threadIds.includes(id))
        : [...was, ...threadIds.filter((id) => !was.includes(id))],
    );

  /// THE ONE WRITE. It answers nothing; what it leaves on screen is the state the server came
  /// back with, which is why the re-read is not optional.
  const write = useCallback(
    async (threadIds: readonly string[], verb: BatchVerb) => {
      if (threadIds.length === 0) return;
      setBusy(true);
      setFailure(null);
      setRowErrors({});
      try {
        const results =
          verb === "delete"
            ? await deleteSessions(threadIds, tErrors)
            : await archiveSessions(threadIds, verb === "archive", tErrors);
        // ONE ROW PER ID, in the order they were sent, and a row carrying an error is the
        // SERVER'S OWN SENTENCE about that one conversation -- kept under the id it arrived
        // with, so the row it lands on is the row it belongs to.
        const refused: Record<string, string> = {};
        for (const row of results) {
          if ("error" in row) refused[row.threadId] = row.error;
        }
        setRowErrors(refused);
        // WHAT STAYS TICKED IS WHAT DID NOT GO THROUGH: retrying the whole batch because two
        // rows were refused would ask the server to do the seven that already worked again --
        // which archiving tolerates and deleting does not (the second delete refuses by name).
        setSelected(threadIds.filter((id) => refused[id] !== undefined));
        // AND THE LIST IS READ AGAIN: a deleted row has to LEAVE it, and the sidebar's push is
        // not this page's to rely on (see the header).
        await load();
      } catch (f: unknown) {
        setFailure(f instanceof Error ? f.message : String(f));
      } finally {
        setBusy(false);
      }
    },
    [tErrors, load],
  );

  // TWO ANSWERS BEFORE THERE IS A LIST, and neither is the panel: the server's sentence when the
  // read was refused, and the spinner while it is still out. The empty list is NOT drawn here --
  // `"" conversations"` and `"this home has none"` are different things, and only the panel is
  // in a position to say the second.
  if (listing === null) {
    return failure !== null ? (
      <p
        role="alert"
        data-slot="settings-sessions-error"
        className="text-destructive text-xs break-words"
      >
        {failure}
      </p>
    ) : (
      <p
        data-slot="settings-page-sessions-loading"
        className="text-muted-foreground flex items-center gap-2 text-xs"
      >
        <Loader2Icon className="size-3.5 animate-spin" />
        {t("sessions.loading")}
      </p>
    );
  }

  return (
    <SessionsBatchPanel
      sessions={sessions}
      filter={filter}
      onFilter={setFilter}
      selected={selected}
      errors={rowErrors}
      busy={busy}
      confirming={confirming}
      failure={failure}
      onToggle={toggle}
      onToggleAll={toggleAll}
      onArchive={(archived) =>
        void write(
          chosen.map((session) => session.threadId),
          archived ? "archive" : "unarchive",
        )
      }
      // THE CONFIRMATION IS A STEP, NOT A DECORATION: this opens it, and nothing is destroyed
      // until `onConfirmDelete` below.
      onDelete={() => setConfirming(true)}
      onConfirmDelete={() => {
        setConfirming(false);
        void write(chosen.map((session) => session.threadId), "delete");
      }}
      onCancelDelete={() => setConfirming(false)}
    />
  );
};


// ------------------------------------------------------------------- the rest

// ------------------------------------------------------------------ the panel

/// A PAGE, not a section: the nav is the one place a person looks for something,
/// and "where do I see the servers" should have the same answer as every other
/// question about this session.
///
/// MCP IS NOT A SETTING, and it is here anyway. Nothing on this page writes
/// config.edn's `:mcp` -- what a server IS comes from the files, and this only shows the
/// ledger and switches servers on or off FOR THIS SESSION. It sits beside the
/// others because it is one of the things a person asks about "what is this
/// session running on", which is what this dialog is for.
///
/// SUBAGENTS IS THE FOURTH PAGE, and the only one whose file is config.edn's :session. It is a
/// page rather than a section of General because "where do I change what a session can
/// hand work to" deserves the same answer as every other question about this session,
/// and because a list of definitions plus a form that rewrites a file is not a row in
/// somebody else's report.
///
/// SECURITY IS THE SIXTH, and it was a section of General until it outgrew it: the sensitive
/// list is THIS HOME's (not this session's), and it is the one control here that changes what
/// a tool call may DO rather than what a run talks to. It gets the same answer as the others:
/// "where do I say what is secret here".
///
/// `general` / `models` / `mcp` / `security` / `groups` / `subagents` / `sessions`.
///
/// SESSIONS IS THE FIFTH, and it is the one page about the LIST rather than about the
/// configuration: what conversations this home keeps, and filing them away or taking them back
/// in batches. It is here rather than in the sidebar because a batch is not a row action -- the
/// sidebar's own verb is per row, and it stays there.
type Page = "general" | "models" | "mcp" | "security" | "groups" | "subagents" | "sessions";

const PAGES: { id: Page; label: (t: Translate) => string }[] = [
  { id: "general", label: (t) => t("page.general") },
  { id: "models", label: (t) => t("page.models") },
  { id: "mcp", label: (t) => t("page.mcp") },
  { id: "security", label: (t) => t("page.security") },
  { id: "groups", label: (t) => t("page.groups") },
  { id: "subagents", label: (t) => t("page.subagents") },
  { id: "sessions", label: (t) => t("page.sessions") },
];

/// THE PANEL HAS TWO SHAPES, and `sm` is the whole of the difference. From `sm` up it is
/// two columns -- the nav beside the page you are on. Below it there is no room for both,
/// so it becomes TWO LEVELS: the same `PAGES` drawn as a list, and tapping one REPLACES
/// the list with that page and a way back. One table feeds both shapes, so a page added
/// here appears in both without a second place to remember.

export const SettingsPanel: FC<{
  open: boolean;
  onOpenChange: (open: boolean) => void;
  threadId: string;
}> = ({ open, onOpenChange, threadId }) => {
  const { t } = useTranslation("settings");
  const { t: tErrors } = useTranslation("errors");
  const [page, setPage] = useState<Page>("general");
  /// WHICH LEVEL A NARROW WINDOW IS ON: false is the list, true is a page. WIDE WINDOWS
  /// IGNORE IT -- they draw the nav and the page at once, and the page they are on is
  /// `page` above. It is put back to the list every time the dialog closes (see below), so
  /// reopening on a phone starts at the list rather than wherever the last visit ended.
  const [drilled, setDrilled] = useState(false);
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

  /// NARROW WINDOWS RETURN TO THE LIST WHEN THE DIALOG CLOSES. That is a decision about
  /// the LEVEL rather than about `page`: the narrow shape keeps the page too, and only
  /// forgets which level it was on. The wide shape has no list to return to, so this is
  /// inert there.
  useEffect(() => {
    if (!open) setDrilled(false);
  }, [open]);

  /// After a write: the same read, and stay where the person was. Not a second
  /// implementation of it -- a write that needs a different refresh is a sign the
  /// refresh was wrong.
  const reload = load;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        data-slot="settings-panel"
        // THE TRACK IS `minmax(0,1fr)` RATHER THAN THE IMPLICIT `auto`, and that is a PHONE fix, not a
        // desktop one (measured 2026-09-30 at 390x844): an `auto` track is sized by its items'
        // MAX-content, so this panel sized a 358px dialog to a 705px column -- the third verb sat at
        // x=429, off the right edge of a 390px screen, with nothing clipping it. A `minmax(0,1fr)` track
        // is exactly the dialog's width, which is what lets the inner `min-w-0` chain (the scroller, the
        // rows' truncating titles, the wrapping verb bar) do the job it was written for.
        className="grid-cols-[minmax(0,1fr)] sm:max-w-5xl"
        aria-describedby={undefined}
      >
        <DialogHeader>
          <DialogTitle>{t("panel.title")}</DialogTitle>
        </DialogHeader>

        {/* THE DIALOG DOES NOT GROW WITH ITS CONTENT. A provider form is taller than the panel, and a
            modal that resized around it would move the nav and the buttons while somebody is typing
            in it. So the size is fixed here and the PAGE scrolls inside.

            AND IT IS BIGGER THAN IT WAS (2026-09-30), because one of these pages is a LIST rather
            than a form: the session page draws every conversation this home keeps and shares the
            column with a row of verbs, so 48rem wide and a screenful of height was a handful of rows
            in a mostly empty box. `sm:max-w-5xl` and a share of the window -- with a 40rem ceiling, so
            it never grows past a laptop screen -- is the same rule with a size that fits what these
            pages now hold.

            THE SHARE IS OF THE VISIBLE WINDOW AND IT SUBTRACTS THIS DIALOG'S OWN CHROME, and both
            halves of that sentence are fixes (measured 2026-09-30, at 844x390): `vh` on a phone is
            the LARGEST viewport, so a `78vh` row plus the ~108px of header, footer and padding this
            dialog carries sat 22px past the bottom of what a person can actually see -- and, being
            centred, it was cut at BOTH ends with nothing to scroll, so the close button was off
            screen for good. `dvh` is the height that is there right now, and 9rem covers those 108px
            plus slack: the dialog fits whatever the browser's chrome does. And it still SCROLLS
            rather than grows, which is what the fixed height was for in the first place. */}
        <div className="flex h-[min(40rem,calc(100dvh-9rem))] min-w-0 gap-4">
          {/* TWO SHAPES, ONE `sm` APART. From `sm` up this is two columns. Below it the nav
              is not a column at all (`hidden sm:flex`): the list below is its narrow
              spelling, and a tap on one of its rows swaps the list for that page. */}
          <nav data-slot="settings-nav" className="hidden w-36 shrink-0 flex-col gap-0.5 sm:flex">
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
            {/* NARROW, LEVEL ONE. The SAME `PAGES` the nav above reads, so there is one table
                of pages and not two. `sm:hidden` keeps it out of the wide shape, and `!drilled`
                keeps it up until a narrow window picks a page. It fills the column the page
                will occupy, so the list is the whole screen rather than a second sidebar. */}
            {!drilled && (
              <div data-slot="settings-nav-list" className="flex flex-col gap-0.5 sm:hidden">
                {PAGES.map((p) => (
                  <button
                    key={p.id}
                    type="button"
                    data-slot={`settings-list-${p.id}`}
                    className="hover:bg-accent/40 rounded-md px-2 py-2 text-left text-sm"
                    onClick={() => {
                      setPage(p.id);
                      setDrilled(true);
                    }}
                  >
                    {p.label(t)}
                  </button>
                ))}
              </div>
            )}
            {/* NARROW, LEVEL TWO. Once a page is picked this is the whole screen; `sm:block`
                keeps it on screen in the wide shape whatever `drilled` says, so the two
                columns never lose the page they were reading, and the way back is `sm:hidden`
                because a two-column panel has nowhere to go back TO. */}
            <div className={drilled ? "block" : "hidden sm:block"}>
              {drilled && (
                <Button
                  variant="ghost"
                  size="sm"
                  data-slot="settings-page-back"
                  className="-ml-2 mb-1 sm:hidden"
                  onClick={() => setDrilled(false)}
                >
                  <ArrowLeftIcon /> {t("panel.back")}
                </Button>
              )}
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
              <GeneralPage registry={registry} onChanged={reload} />
            )}
            {page === "models" && (
              <ModelsPage registry={registry} failure={registryFailure} onChanged={reload} />
            )}
            {page === "mcp" && (
              <section data-slot="settings-mcp" className="flex flex-col gap-3">
                <SectionTitle>{t("mcp.heading")}</SectionTitle>
                {/* config.edn's `:mcp` is a filename, not copy: it stays literal and stays in
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
            {/* NO `threadId`: the sensitive list belongs to THIS HOME, not to a session.
                A page of its own now (see SecurityPage); components/security-paths.tsx draws
                the built-in half as not deletable and the custom half as the person's own. */}
            {page === "security" && <SecurityPage />}
            {/* NO `threadId` EITHER: config.edn's :session is this HOME's, and which group a
                given session resolves is the server's business, not this page's. It reads the
                CATALOG from the panel because the model picker names vendors and ids that
                `GET /api/providers` already answered -- a second listing here would be a second
                answer free to disagree with the one a run is served by. */}
            {page === "groups" && <GroupsPage registry={registry} />}
            {/* NO `threadId`, and that is the page's own claim rather than an
                omission: a subagent is defined for the HOME, not for the session
                looking at it. The sidebar's block and this form read the same file
                and must give the same answer -- which is what "the settings form
                writes the user level only" is for (see subagents.clj). */}
            {page === "subagents" && <SubagentsPage />}
            {/* THE SESSIONS PAGE, and it needs nothing from this panel's own state: it reads
                the listing itself (see `SessionsPage` for why a pull rather than the sidebar's
                push) and it takes no `threadId`, because a batch about conversations is not
                about the one on screen. */}
            {page === "sessions" && <SessionsPage />}
            </div>
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

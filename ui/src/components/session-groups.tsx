// The Session behaviour page: config.edn's `:session`, seen as a default group plus
// per-model groups, and the only form in the app that writes it.
//
// THE FILE AND THE KEYS SAY `groups`, THE MENU SAYS "Session behaviour": the identifiers are
// named for the MECHANISM (a default group plus per-model groups), the label for what the
// page DOES (how a session runs) -- which is the thing a person looks for in a menu.
//
// --------------------------------------------------------------- why groups
//
// How a session runs -- which editing implementation it gets, when it compacts, how long
// a silent model call may sit, what the fence frees, which skills and instructions it
// reads -- used to be ONE answer for the whole home. It is per-MODEL now: the default
// group serves every model, and a group names the models it serves and lays its own
// blocks over the default group's, key by key (harness.cap.providers/merge-session-blocks).
//
// ------------------------------------------------------ what this page decides
//
// WHICH GROUP A SESSION GETS IS NOT DECIDED HERE. The server resolves a session's model
// from the ordinary tiers and folds the matching groups itself; this page edits the FILE
// (the default group and the groups list) and shows it as written. A `~` in a path, a
// block the default group does not carry, a group that names two models -- all of it has
// to survive the round trip, so nothing is resolved on this side.
//
// A REFUSAL LEAVES THE FORM OPEN AND THE FILE ALONE: the server checks the whole config
// before it opens the file, so the sentence shown here is also the proof that nothing
// moved.
import type { TFunction } from "i18next";
import { ArrowLeftIcon, Loader2Icon, PlusIcon, TrashIcon } from "lucide-react";
import { useCallback, useEffect, useState, type FC } from "react";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import type { Registry } from "@/lib/providers";
import {
  readSession,
  writeSession,
  type ApprovalBlock,
  type CompactionBlock,
  type EditingBlock,
  type InstructionsBlock,
  type LlmBlock,
  type ModelRef,
  type SessionBlocks,
  type SessionBlocksPatch,
  type SessionConfig,
  type SessionGroup,
  type SkillsBlock,
} from "@/lib/session";

/// The settings catalog, because every string below is drawn in the settings dialog.
type Translate = TFunction<"settings">;

/// The six blocks a group may carry. The order is the page's: the server's
/// `default-group-keys` is a set.
type BlockKey = "editing" | "compaction" | "llm" | "approval" | "skills" | "instructions";

/// A block's name, and the two hints under it. LITERAL KEYS RATHER THAN `t(`block.${key}`)`
/// for the rule the trajectory lanes keep: a built key is one the type gate cannot check,
/// and the type gate is the whole reason the catalogs are typed. Each entry closes over
/// its own literal, so a block added here without a sentence is a compile error.
const BLOCK_LABEL: Record<BlockKey, (t: Translate) => string> = {
  editing: (t) => t("groups.block.editing"),
  compaction: (t) => t("groups.block.compaction"),
  llm: (t) => t("groups.block.llm"),
  approval: (t) => t("groups.block.approval"),
  skills: (t) => t("groups.block.skills"),
  instructions: (t) => t("groups.block.instructions"),
};

/// The hint under a block in the DEFAULT group's form: on is "every model is served by
/// this", off is "the harness default stands".
const BLOCK_HINT_DEFAULT: Record<BlockKey, (t: Translate) => string> = {
  editing: (t) => t("groups.block.editingDefault"),
  compaction: (t) => t("groups.block.compactionDefault"),
  llm: (t) => t("groups.block.llmDefault"),
  approval: (t) => t("groups.block.approvalDefault"),
  skills: (t) => t("groups.block.skillsDefault"),
  instructions: (t) => t("groups.block.instructionsDefault"),
};

/// The hint under a block in a GROUP's form: on is "this group says so for its models",
/// off is "the default group's stands".
const BLOCK_HINT_GROUP: Record<BlockKey, (t: Translate) => string> = {
  editing: (t) => t("groups.block.editingGroup"),
  compaction: (t) => t("groups.block.compactionGroup"),
  llm: (t) => t("groups.block.llmGroup"),
  approval: (t) => t("groups.block.approvalGroup"),
  skills: (t) => t("groups.block.skillsGroup"),
  instructions: (t) => t("groups.block.instructionsGroup"),
};

/// The values a block starts from when a person first turns it on. A FORM STARTER and not
/// the rule: leaving a block off says "the harness default is in force", and these are
/// only what the controls show before anybody types -- the server's defaults live in the
/// namespaces that read them (harness.cap.editing, harness.edge.compaction,
/// harness.edge.llm-timeout).
const STARTERS: {
  editing: EditingBlock;
  compaction: CompactionBlock;
  llm: LlmBlock;
  approval: ApprovalBlock;
  skills: SkillsBlock;
  instructions: InstructionsBlock;
} = {
  editing: {
    mode: "hashline",
    grep: true,
    "require-path": false,
    "strict-input": false,
    "diff-context-lines": 1,
  },
  compaction: {
    "threshold-ratio": 0.7,
    "retain-ratio": 0.16,
    "overflow-retries": 1,
    "max-tokens": 8192,
  },
  llm: { "idle-timeout-ms": 30000, "idle-timeout-retries": 3 },
  approval: { allow: [], strict: false },
  skills: { roots: [] },
  instructions: { files: [] },
};

/// What a form holds while it is open: the six blocks' values, plus WHICH OF THEM ARE ON --
/// the last being the GROUP form's own question (a block it leaves off is served by the
/// default group's). The default group's form edits directly and reads `enabled` not at
/// all; it is kept in one type so both forms share `BlocksForm` and `draftFrom`.
type BlocksDraft = {
  enabled: Record<BlockKey, boolean>;
  values: {
    editing: EditingBlock;
    compaction: CompactionBlock;
    llm: LlmBlock;
    approval: ApprovalBlock;
    skills: SkillsBlock;
    instructions: InstructionsBlock;
  };
};

/// A group being edited. `index` is null for one that is not in the list yet.
type GroupEdit = {
  index: number | null;
  name: string;
  models: readonly ModelRef[];
  blocks: BlocksDraft;
};

/// One block as written -> the form's draft. An ABSENT block is off and shows its
/// starter, which is how the form draws "the harness default is in force" without
/// writing that default into the file.
function draftFrom(blocks: SessionBlocks): BlocksDraft {
  return {
    enabled: {
      editing: blocks.editing !== undefined,
      compaction: blocks.compaction !== undefined,
      llm: blocks.llm !== undefined,
      approval: blocks.approval !== undefined,
      skills: blocks.skills !== undefined,
      instructions: blocks.instructions !== undefined,
    },
    values: {
      editing: blocks.editing ?? STARTERS.editing,
      compaction: blocks.compaction ?? STARTERS.compaction,
      llm: blocks.llm ?? STARTERS.llm,
      approval: blocks.approval ?? STARTERS.approval,
      skills: blocks.skills ?? STARTERS.skills,
      instructions: blocks.instructions ?? STARTERS.instructions,
    },
  };
}

/// A group's draft -> the blocks it CARRIES: a block that is off is left out, so the
/// default group's value stands.
function blocksOf(draft: BlocksDraft): SessionBlocks {
  const out: SessionBlocks = {};
  if (draft.enabled.editing) out.editing = draft.values.editing;
  if (draft.enabled.compaction) out.compaction = draft.values.compaction;
  if (draft.enabled.llm) out.llm = draft.values.llm;
  if (draft.enabled.approval) out.approval = draft.values.approval;
  if (draft.enabled.skills) out.skills = draft.values.skills;
  if (draft.enabled.instructions) out.instructions = draft.values.instructions;
  return out;
}

/// The default group's draft -> the patch a save sends. THE FORM IS DIRECT HERE, so
/// there is no on/off state to read; what a save writes is what the fields hold, and the
/// only question left is which blocks MEAN something:
///
///   :editing / :compaction / :llm   always written -- the fields are numbers, switches and a
///                                   select, and their values are the harness's own
///                                   defaults until somebody changes one (harness.cap.editing's
///                                   `defaults`, pressure/default-ratios, llm's timeout).
///   :approval                       written only when it says something (strict, or a free
///                                   path); an empty fence and no fence are the same fence,
///                                   and writing it would only add noise to the file.
///   :skills / :instructions         written only when NON-EMPTY, and that is the one that
///                                   matters: an empty :roots is a DECISION to read no skills
///                                   (harness.cap.skills checks `(contains? cfg :roots)`), so an
///                                   untouched field must leave the key OUT rather than
///                                   write [] and switch the built-in roots off.
function patchOf(draft: BlocksDraft): SessionBlocksPatch {
  const v = draft.values;
  return {
    editing: v.editing,
    compaction: v.compaction,
    llm: v.llm,
    approval: v.approval.strict || v.approval.allow.length > 0 ? v.approval : null,
    skills: v.skills.roots.length > 0 ? v.skills : null,
    instructions: v.instructions.files.length > 0 ? v.instructions : null,
  };
}

/// One path per line -- how a person ends a list is a newline, not an empty entry.
function linesOf(text: string): string[] {
  return text
    .split("\n")
    .map((s) => s.trim())
    .filter((s) => s !== "");
}

/// The wire's separator for a model choice. A model id may contain most things, so the
/// two halves are joined with a character no id carries.
const MODEL_SEP = "\u0000";
const modelKey = (ref: ModelRef): string => `${ref.provider ?? ""}${MODEL_SEP}${ref.model}`;
const modelRefOf = (key: string): ModelRef => {
  const at = key.indexOf(MODEL_SEP);
  const provider = key.slice(0, at);
  const model = key.slice(at + 1);
  return provider === "" ? { model } : { provider, model };
};

/// A number field's text that will not parse keeps the last good value: an empty box on
/// its way to a digit is not a request to write NaN into config.edn.
const numberOr = (text: string, last: number): number => {
  const n = Number(text);
  return Number.isFinite(n) ? n : last;
};

const inputClass =
  "h-8 w-full rounded-md border bg-transparent px-2 text-xs outline-none focus-visible:border-ring";

const SectionTitle: FC<{ children: React.ReactNode }> = ({ children }) => (
  <h3 className="text-muted-foreground mb-1 text-xs font-semibold tracking-wide uppercase">
    {children}
  </h3>
);

const Field: FC<{ label: string; slot: string; hint?: string; children: React.ReactNode }> = ({
  label,
  slot,
  hint,
  children,
}) => (
  <label data-slot={slot} className="flex flex-col gap-1">
    <span className="text-xs font-medium">{label}</span>
    {children}
    {hint !== undefined && <span className="text-muted-foreground text-xs">{hint}</span>}
  </label>
);

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

/// A block's frame: its name, the hint under it, and the controls below. A GROUP's form also
/// gets a toggle first, and THAT IS THE POINT THERE -- a group that says nothing about :llm
/// is served by the default group's, which is not the same as one that says the starter
/// above. The DEFAULT group's form has no toggle: what every model is served by is the
/// form's own content, edited directly.
const BlockSection: FC<{
  slot: string;
  label: string;
  hint: string;
  /// THE TOGGLE'S STATE, OR NULL FOR NO TOGGLE. The DEFAULT group's form edits every
  /// block directly -- what every model is served by is the form's own content, not
  /// something to switch on first -- while a GROUP's form asks, block by block, whether
  /// it overrides the default group at all. Different question, different control.
  checked: boolean | null;
  busy: boolean;
  onCheck: (on: boolean) => void;
  children: React.ReactNode;
}> = ({ slot, label, hint, checked, busy, onCheck, children }) => (
  <div data-slot={slot} className="rounded-md border p-2">
    {checked === null ? (
      <span className="text-xs font-medium">{label}</span>
    ) : (
      <label className="flex items-center gap-2">
        <input
          type="checkbox"
          data-slot={`${slot}-override`}
          checked={checked}
          disabled={busy}
          onChange={(e) => onCheck(e.target.checked)}
        />
        <span className="text-xs font-medium">{label}</span>
      </label>
    )}
    <p className="text-muted-foreground mt-1 text-xs">{hint}</p>
    {(checked === null || checked) && (
      <div className="mt-2 flex flex-col gap-2">{children}</div>
    )}
  </div>
);

/// THE SIX EDITORS, one per block. They are written out rather than driven off a table
/// because their FIELDS differ in type (a select, a number, a switch, a list of paths),
/// and a generic renderer would have to describe each type anyway -- with the compiler no
/// longer able to say that the editing select cannot take a number.
const BlocksForm: FC<{
  draft: BlocksDraft;
  busy: boolean;
  mode: "default" | "group";
  onChange: (draft: BlocksDraft) => void;
}> = ({ draft, busy, mode, onChange }) => {
  const { t } = useTranslation("settings");
  const set = <K extends BlockKey>(key: K, value: BlocksDraft["values"][K]) =>
    onChange({ ...draft, values: { ...draft.values, [key]: value } });
  const enable = (key: BlockKey, on: boolean) =>
    onChange({ ...draft, enabled: { ...draft.enabled, [key]: on } });
  const section = (key: BlockKey) => ({
    slot: `settings-group-block-${key}`,
    label: BLOCK_LABEL[key](t),
    hint: (mode === "group" ? BLOCK_HINT_GROUP : BLOCK_HINT_DEFAULT)[key](t),
    checked: mode === "group" ? draft.enabled[key] : null,
    busy,
    onCheck: (on: boolean) => enable(key, on),
  });

  return (
    <div className="flex flex-col gap-2">
      <BlockSection {...section("editing")}>
        <Field label={t("groups.editing.mode")} slot="settings-group-editing-mode">
          <select
            aria-label={t("groups.editing.mode")}
            className={inputClass}
            value={draft.values.editing.mode}
            disabled={busy}
            onChange={(e) =>
              set("editing", {
                ...draft.values.editing,
                mode: e.target.value as EditingBlock["mode"],
              })
            }
          >
            <option value="hashline">{t("groups.editing.modeHashline")}</option>
            <option value="str-replace">{t("groups.editing.modeStrReplace")}</option>
          </select>
        </Field>
        <div className="grid grid-cols-2 gap-2">
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={draft.values.editing.grep}
              disabled={busy}
              onChange={(e) => set("editing", { ...draft.values.editing, grep: e.target.checked })}
            />
            <span className="text-xs">{t("groups.editing.grep")}</span>
          </label>
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={draft.values.editing["require-path"]}
              disabled={busy}
              onChange={(e) =>
                set("editing", { ...draft.values.editing, "require-path": e.target.checked })
              }
            />
            <span className="text-xs">{t("groups.editing.requirePath")}</span>
          </label>
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={draft.values.editing["strict-input"]}
              disabled={busy}
              onChange={(e) =>
                set("editing", { ...draft.values.editing, "strict-input": e.target.checked })
              }
            />
            <span className="text-xs">{t("groups.editing.strictInput")}</span>
          </label>
          <Field label={t("groups.editing.diffContext")} slot="settings-group-editing-diff">
            <Input
              className="h-8 text-xs"
              type="number"
              min={0}
              max={10}
              value={draft.values.editing["diff-context-lines"]}
              disabled={busy}
              onChange={(e) =>
                set("editing", {
                  ...draft.values.editing,
                  "diff-context-lines": numberOr(
                    e.target.value,
                    draft.values.editing["diff-context-lines"],
                  ),
                })
              }
            />
          </Field>
        </div>
      </BlockSection>

      <BlockSection {...section("compaction")}>
        <div className="grid grid-cols-2 gap-2">
          <Field label={t("groups.compaction.threshold")} slot="settings-group-compaction-threshold">
            <Input
              className="h-8 text-xs"
              type="number"
              step={0.01}
              min={0}
              value={draft.values.compaction["threshold-ratio"]}
              disabled={busy}
              onChange={(e) =>
                set("compaction", {
                  ...draft.values.compaction,
                  "threshold-ratio": numberOr(
                    e.target.value,
                    draft.values.compaction["threshold-ratio"],
                  ),
                })
              }
            />
          </Field>
          <Field label={t("groups.compaction.retain")} slot="settings-group-compaction-retain">
            <Input
              className="h-8 text-xs"
              type="number"
              step={0.01}
              min={0}
              value={draft.values.compaction["retain-ratio"]}
              disabled={busy}
              onChange={(e) =>
                set("compaction", {
                  ...draft.values.compaction,
                  "retain-ratio": numberOr(
                    e.target.value,
                    draft.values.compaction["retain-ratio"],
                  ),
                })
              }
            />
          </Field>
          <Field
            label={t("groups.compaction.overflowRetries")}
            slot="settings-group-compaction-overflow"
          >
            <Input
              className="h-8 text-xs"
              type="number"
              step={1}
              min={0}
              value={draft.values.compaction["overflow-retries"]}
              disabled={busy}
              onChange={(e) =>
                set("compaction", {
                  ...draft.values.compaction,
                  "overflow-retries": numberOr(
                    e.target.value,
                    draft.values.compaction["overflow-retries"],
                  ),
                })
              }
            />
          </Field>
          <Field
            label={t("groups.compaction.maxTokens")}
            slot="settings-group-compaction-max-tokens"
          >
            <Input
              className="h-8 text-xs"
              type="number"
              step={1}
              min={1}
              value={draft.values.compaction["max-tokens"]}
              disabled={busy}
              onChange={(e) =>
                set("compaction", {
                  ...draft.values.compaction,
                  "max-tokens": numberOr(
                    e.target.value,
                    draft.values.compaction["max-tokens"],
                  ),
                })
              }
            />
          </Field>
        </div>
      </BlockSection>

      <BlockSection {...section("llm")}>
        <div className="grid grid-cols-2 gap-2">
          <Field label={t("groups.llm.idleTimeoutMs")} slot="settings-group-llm-idle-ms">
            <Input
              className="h-8 text-xs"
              type="number"
              min={0}
              value={draft.values.llm["idle-timeout-ms"]}
              disabled={busy}
              onChange={(e) =>
                set("llm", {
                  ...draft.values.llm,
                  "idle-timeout-ms": numberOr(e.target.value, draft.values.llm["idle-timeout-ms"]),
                })
              }
            />
          </Field>
          <Field label={t("groups.llm.idleTimeoutRetries")} slot="settings-group-llm-idle-retries">
            <Input
              className="h-8 text-xs"
              type="number"
              min={0}
              value={draft.values.llm["idle-timeout-retries"]}
              disabled={busy}
              onChange={(e) =>
                set("llm", {
                  ...draft.values.llm,
                  "idle-timeout-retries": numberOr(
                    e.target.value,
                    draft.values.llm["idle-timeout-retries"],
                  ),
                })
              }
            />
          </Field>
        </div>
      </BlockSection>

      <BlockSection {...section("approval")}>
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            checked={draft.values.approval.strict}
            disabled={busy}
            onChange={(e) => set("approval", { ...draft.values.approval, strict: e.target.checked })}
          />
          <span className="text-xs">{t("groups.approval.strict")}</span>
        </label>
        <Field label={t("groups.approval.allow")} slot="settings-group-approval-allow">
          <Textarea
            className="min-h-16 font-mono text-xs"
            value={draft.values.approval.allow.join("\n")}
            disabled={busy}
            onChange={(e) =>
              set("approval", { ...draft.values.approval, allow: linesOf(e.target.value) })
            }
          />
        </Field>
      </BlockSection>

      <BlockSection {...section("skills")}>
        <Field label={t("groups.skills.roots")} slot="settings-group-skills-roots">
          <Textarea
            className="min-h-16 font-mono text-xs"
            value={draft.values.skills.roots.join("\n")}
            disabled={busy}
            onChange={(e) => set("skills", { roots: linesOf(e.target.value) })}
          />
        </Field>
      </BlockSection>

      <BlockSection {...section("instructions")}>
        <Field label={t("groups.instructions.files")} slot="settings-group-instructions-files">
          <Textarea
            className="min-h-16 font-mono text-xs"
            value={draft.values.instructions.files.join("\n")}
            disabled={busy}
            onChange={(e) => set("instructions", { files: linesOf(e.target.value) })}
          />
        </Field>
      </BlockSection>
    </div>
  );
};

/// THE MODEL PICKER: every (vendor, model) the catalog holds, as a multiple select whose
/// values carry both halves. The catalog is the server's own (`GET /api/providers`), so
/// this side never invents an id -- and an empty selection is refused by the server,
/// because a group that serves no model is a row nothing can match.
const ModelPicker: FC<{
  registry: Registry | null;
  chosen: readonly ModelRef[];
  busy: boolean;
  onChange: (models: readonly ModelRef[]) => void;
}> = ({ registry, chosen, busy, onChange }) => {
  const { t } = useTranslation("settings");
  const selected = chosen.map(modelKey);
  return (
    <Field label={t("groups.models")} slot="settings-group-models" hint={t("groups.modelsHint")}>
      {registry === null || registry.providers.length === 0 ? (
        <p data-slot="settings-group-models-empty" className="text-muted-foreground text-xs">
          {t("groups.modelsNone")}
        </p>
      ) : (
        <select
          multiple
          size={8}
          aria-label={t("groups.models")}
          className="w-full rounded-md border bg-transparent p-1 text-xs outline-none focus-visible:border-ring"
          value={selected}
          disabled={busy}
          onChange={(e) =>
            onChange(Array.from(e.target.selectedOptions).map((o) => modelRefOf(o.value)))
          }
        >
          {registry.providers.map((provider) => (
            <optgroup key={provider.name} label={provider["display-name"] ?? provider.name}>
              {provider.models.map((model) => (
                <option
                  key={`${provider.name}${MODEL_SEP}${model.id}`}
                  value={modelKey({ provider: provider.name, model: model.id })}
                >
                  {model.id}
                </option>
              ))}
            </optgroup>
          ))}
        </select>
      )}
    </Field>
  );
};

/// One group row: its name, the models it serves, and where to open it.
const GroupRow: FC<{ group: SessionGroup; onOpen: () => void }> = ({ group, onOpen }) => {
  const { t } = useTranslation("settings");
  return (
    <button
      type="button"
      data-slot="settings-group-row"
      className="hover:bg-accent/40 flex flex-col items-start gap-0.5 rounded-md border px-2 py-1.5 text-left"
      onClick={onOpen}
    >
      <span className="text-xs font-medium">{group.name}</span>
      <span className="text-muted-foreground text-xs break-words">
        {group.models.length === 0
          ? t("groups.servesNothing")
          : group.models
              .map((m) => (m.provider === undefined ? m.model : `${m.provider} · ${m.model}`))
              .join(", ")}
      </span>
    </button>
  );
};

/// THE FORM FOR ONE GROUP. Presentational: the PAGE owns the list and performs the write,
/// because a save replaces the whole list and only the page is showing it.
const GroupForm: FC<{
  draft: GroupEdit;
  registry: Registry | null;
  file: string;
  busy: boolean;
  failure: string | null;
  onChange: (draft: GroupEdit) => void;
  onSave: () => void;
  onCancel: () => void;
  onRemove: () => void;
}> = ({ draft, registry, file, busy, failure, onChange, onSave, onCancel, onRemove }) => {
  const { t } = useTranslation("settings");
  return (
    <div data-slot="settings-group-form" className="flex flex-col gap-3">
      <Field
        label={t("groups.name")}
        slot="settings-group-name"
        hint={draft.index === null ? t("groups.nameHintNew") : t("groups.nameHintEdit")}
      >
        <Input
          className="h-8 text-xs"
          value={draft.name}
          disabled={busy}
          onChange={(e) => onChange({ ...draft, name: e.target.value })}
        />
      </Field>

      <ModelPicker
        registry={registry}
        chosen={draft.models}
        busy={busy}
        onChange={(models) => onChange({ ...draft, models })}
      />

      <BlocksForm
        draft={draft.blocks}
        busy={busy}
        mode="group"
        onChange={(blocks) => onChange({ ...draft, blocks })}
      />

      {failure !== null && (
        <Refusal slot="settings-group-error" message={failure} note={t("groups.errorNote")} />
      )}

      <p data-slot="settings-group-where" className="text-muted-foreground text-xs break-words">
        {t("groups.where", { file })}
      </p>

      <div className="flex items-center gap-2">
        <Button size="sm" data-slot="settings-group-save" disabled={busy} onClick={onSave}>
          {busy && <Loader2Icon className="animate-spin" />}
          {t("groups.save")}
        </Button>
        <Button
          variant="ghost"
          size="sm"
          data-slot="settings-group-cancel"
          disabled={busy}
          onClick={onCancel}
        >
          {t("groups.cancel")}
        </Button>
        {draft.index !== null && (
          <Button
            variant="destructive"
            size="sm"
            data-slot="settings-group-remove"
            title={t("groups.removeTitle")}
            disabled={busy}
            className="ms-auto"
            onClick={onRemove}
          >
            <TrashIcon /> {t("groups.remove")}
          </Button>
        )}
      </div>
    </div>
  );
};

/// THE PAGE. A list -- the default group and the per-model groups -- and, once a row is
/// clicked or Add is pressed, a form. It reads its own endpoint and reloads itself, the
/// discipline the Subagents page keeps: read fresh, show the server's sentence, never
/// cache.
const GroupsPage: FC<{ registry: Registry | null }> = ({ registry }) => {
  const { t } = useTranslation("settings");
  const { t: tErrors } = useTranslation("errors");
  const [listing, setListing] = useState<SessionConfig | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [defaultDraft, setDefaultDraft] = useState<BlocksDraft | null>(null);
  const [busy, setBusy] = useState(false);
  const [writeFailure, setWriteFailure] = useState<string | null>(null);
  const [draft, setDraft] = useState<GroupEdit | null>(null);

  /// A POST-WRITE RELOAD KEEPS THE OLD LIST UNTIL THE NEW ONE ARRIVES, unlike the panel's
  /// first read, which clears -- a full list blinking empty on its way to nearly itself.
  const load = useCallback(async () => {
    try {
      const next = await readSession(tErrors);
      setListing(next);
      setDefaultDraft(draftFrom(next.default));
      setFailure(null);
    } catch (f: unknown) {
      setListing(null);
      setFailure(f instanceof Error ? f.message : String(f));
    }
  }, [tErrors]);

  useEffect(() => {
    void load();
  }, [load]);

  /// ONE WRITE, ONE RELOAD -- and the failure is shown where the buttons are, whether it
  /// came from the default group's save or a group's.
  const write = async (change: Parameters<typeof writeSession>[0]): Promise<boolean> => {
    setBusy(true);
    setWriteFailure(null);
    try {
      await writeSession(change, tErrors);
      await load();
      return true;
    } catch (f: unknown) {
      setWriteFailure(f instanceof Error ? f.message : String(f));
      return false;
    } finally {
      setBusy(false);
    }
  };

  /// A group save replaces the WHOLE list, so the page builds the next one from what it
  /// is showing: this row's new value in place, or appended.
  const saveGroup = async () => {
    if (listing === null || draft === null) return;
    const group: SessionGroup = {
      name: draft.name,
      models: draft.models,
      ...blocksOf(draft.blocks),
    };
    const next =
      draft.index === null
        ? [...listing.groups, group]
        : listing.groups.map((g, i) => (i === draft.index ? group : g));
    if (await write({ groups: next })) setDraft(null);
  };

  const removeGroup = async () => {
    if (listing === null || draft === null || draft.index === null) return;
    if (await write({ groups: listing.groups.filter((_, i) => i !== draft.index) })) {
      setDraft(null);
    }
  };

  if (draft !== null && listing !== null) {
    return (
      <>
        <Button
          variant="ghost"
          size="sm"
          data-slot="settings-group-back"
          className="-ml-2 mb-1"
          onClick={() => setDraft(null)}
        >
          <ArrowLeftIcon /> {t("groups.back")}
        </Button>
        <GroupForm
          key={draft.index ?? "new"}
          draft={draft}
          registry={registry}
          file={listing.path}
          busy={busy}
          failure={writeFailure}
          onChange={setDraft}
          onSave={() => void saveGroup()}
          onCancel={() => setDraft(null)}
          onRemove={() => void removeGroup()}
        />
      </>
    );
  }

  return (
    <div data-slot="settings-page-groups" className="flex flex-col gap-3">
      <div>
        <SectionTitle>{t("groups.title")}</SectionTitle>
        <p className="text-muted-foreground text-xs">{t("groups.intro")}</p>
      </div>

      {failure !== null && <Refusal slot="settings-groups-error" message={failure} />}

      {listing === null && failure === null && (
        <p
          data-slot="settings-groups-loading"
          className="text-muted-foreground flex items-center gap-2 text-xs"
        >
          <Loader2Icon className="size-3.5 animate-spin" />
          {t("groups.loading")}
        </p>
      )}

      {listing !== null && defaultDraft !== null && (
        <>
          <section data-slot="settings-default-group" className="flex flex-col gap-2">
            <SectionTitle>{t("groups.defaultTitle")}</SectionTitle>
            <p className="text-muted-foreground text-xs">{t("groups.defaultHint")}</p>
            <BlocksForm
              draft={defaultDraft}
              busy={busy}
              mode="default"
              onChange={setDefaultDraft}
            />
          </section>

          <section data-slot="settings-model-groups" className="flex flex-col gap-2">
            <div className="flex items-center justify-between">
              <SectionTitle>{t("groups.groupsTitle")}</SectionTitle>
              <Button
                variant="outline"
                size="sm"
                data-slot="settings-group-add"
                onClick={() =>
                  setDraft({ index: null, name: "", models: [], blocks: draftFrom({}) })
                }
              >
                <PlusIcon /> {t("groups.add")}
              </Button>
            </div>
            <p className="text-muted-foreground text-xs">{t("groups.groupsHint")}</p>
            {listing.groups.length === 0 ? (
              <p data-slot="settings-groups-empty" className="text-muted-foreground text-xs">
                {t("groups.empty")}
              </p>
            ) : (
              <div className="flex flex-col gap-1">
                {listing.groups.map((group, index) => (
                  <GroupRow
                    key={`${group.name}-${index}`}
                    group={group}
                    onOpen={() =>
                      setDraft({
                        index,
                        name: group.name,
                        models: group.models,
                        blocks: draftFrom(group),
                      })
                    }
                  />
                ))}
              </div>
            )}
          </section>

          {writeFailure !== null && (
            <Refusal slot="settings-groups-write-error" message={writeFailure} note={t("groups.errorNote")} />
          )}

          <div className="flex flex-wrap items-center gap-2">
            <Button
              size="sm"
              data-slot="settings-default-group-save"
              disabled={busy}
              onClick={() => void write({ default: patchOf(defaultDraft) })}
            >
              {busy && <Loader2Icon className="animate-spin" />}
              {t("groups.saveDefault")}
            </Button>
            <Button
              variant="destructive"
              size="sm"
              data-slot="settings-groups-remove-all"
              disabled={busy || listing.groups.length === 0}
              onClick={() => void write({ groups: [] })}
            >
              <TrashIcon /> {t("groups.removeAll")}
            </Button>
            <p className="text-muted-foreground text-xs break-words">
              {t("groups.where", { file: listing.path })}
            </p>
          </div>
        </>
      )}
    </div>
  );
};

export { GroupsPage };

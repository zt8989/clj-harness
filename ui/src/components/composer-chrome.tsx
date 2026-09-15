// What sits around and inside the composer: the directory and branch above it,
// the model and thinking inside it.
//
// ------------------------------------------------ two slots, and why they are here
//
// `Thread` is a COPY of the assistant-ui element (see thread.aui.tsx), and the
// composer lives inside it with no way to be replaced. Rather than edit that file
// into something we own, it gained two LOCAL: insertion points -- `ComposerFrame`,
// which wraps the composer, and `ComposerTools`, which renders in the composer's
// own action row. Everything visible below is in THIS file; the copied element
// gained two components and no markup, which is the arrangement that keeps it
// byte-comparable with upstream on the next registry pull.
//
// ------------------------------------------------------- above: where, and on what
//
// DIRECTORY AND BRANCH ARE THE SESSION'S CONTEXT, not its content. The directory
// is the project the session is bound to -- the same binding the file tools and
// the shell resolve against -- so switching it here is rebinding the session, with
// everything that carries (the log moves with it; see POST /api/project). The
// branch is that directory's working tree.
//
// THEY ARE HIDDEN ONCE THE CONVERSATION STARTS. On an empty session the composer
// sits mid-screen and the bar reads as part of getting started; once there are
// messages the composer is docked at the bottom and the bar would be permanent
// furniture over the conversation. The rule is exactly that: no messages, show;
// any messages, hide.
//
// ------------------------------------------------------- inside: what answers, and how hard
//
// The model and the reasoning effort are PER SESSION and nothing else is affected:
// the server keeps the override in memory keyed by thread id (see POST /api/model),
// and config.edn, providers.edn and every other thread are left alone. Choosing a
// provider clears the model, because an id that belonged to the old vendor is not
// one the new one serves -- the server enforces that, and the picker just does not
// pretend otherwise.
//
// THE PICKERS ARE NATIVE SELECTS. A dropdown built from the kit would be prettier
// and would also be four hundred lines of state for a list that is at most a few
// dozen entries; a `<select>` is keyboard-navigable and readable by a screen
// reader for free, which is the part that is not worth re-deriving.
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type FC,
  type PropsWithChildren,
} from "react";
import { useAuiState } from "@assistant-ui/react";
import { BrainIcon, FolderIcon, GitBranchIcon, TriangleAlertIcon } from "lucide-react";

import { choicesFor, gitStateFor, setModel, switchBranch } from "@/lib/composer";
import { bindThread, listProjects, projectName } from "@/lib/projects";

/// The thread the composer is composing for. Supplied by `App`, which owns it --
/// see the comment there on why the id's owner is React state rather than the
/// agent.
export const ThreadIdContext = createContext<string | null>(null);

const useThreadId = (): string | null => useContext(ThreadIdContext);

/// One `<option>`-list worth of state: fetch on mount and whenever the id moves,
/// and keep the failure rather than throwing it away. Small enough not to want a
/// library, and explicit enough to read.
function useRemote<T>(load: () => Promise<T>): {
  data: T | null;
  error: string | null;
  reload: () => void;
} {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);
  useEffect(() => {
    let live = true;
    load()
      .then((value) => live && (setData(value), setError(null)))
      .catch((failure: unknown) =>
        live && setError(failure instanceof Error ? failure.message : String(failure)),
      );
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nonce, load]);
  return { data, error, reload: useCallback(() => setNonce((n) => n + 1), []) };
}

const Select: FC<{
  slot: string;
  label: string;
  value: string;
  options: { value: string; label: string; group?: string }[];
  disabled?: boolean;
  title?: string;
  leading?: React.ReactNode;
  onChange: (value: string) => void;
}> = ({ slot, label, value, options, disabled, title, leading, onChange }) => {
  // Options that name a group are gathered under it, so a long catalog reads as
  // a short list of vendors each with its models. Options with no group stand on
  // their own -- and the two are never mixed within one picker.
  const grouped = options.some((o) => o.group !== undefined);
  const groups: { group: string | undefined; options: typeof options }[] = [];
  for (const option of options) {
    const last = groups[groups.length - 1];
    if (last !== undefined && last.group === option.group) last.options.push(option);
    else groups.push({ group: option.group, options: [option] });
  }
  const item = (option: (typeof options)[number]) => (
    <option key={option.value} value={option.value}>
      {option.label}
    </option>
  );
  return (
    <label data-slot={slot} className="flex min-w-0 items-center gap-1.5" title={title}>
      {leading}
      <span className="sr-only">{label}</span>
      <select
        data-slot={`${slot}-select`}
        aria-label={label}
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
        className="text-muted-foreground hover:text-foreground max-w-[16rem] min-w-0 cursor-pointer truncate bg-transparent text-sm outline-none disabled:opacity-50"
      >
        {grouped
          ? groups.map((g) => (
              <optgroup key={g.group} label={g.group}>
                {g.options.map(item)}
              </optgroup>
            ))
          : options.map(item)}
      </select>
    </label>
  );
};

/// The directory and branch strip, shown only before the conversation starts.
const ComposerContextBar: FC<{ threadId: string }> = ({ threadId }) => {
  const projects = useRemote(
    useCallback(() => listProjects(), []),
  );
  const git = useRemote(useCallback(() => gitStateFor(threadId), [threadId]));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const dirs = (projects.data ?? []).map((p) => ({ value: p.path, label: projectName(p.path) }));
  const current = git.data?.dir ?? "";

  const rebind = async (path: string) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await bindThread(threadId, path);
      // The branch belongs to the directory, so the new one has to be read back:
      // keeping the old answer would name a branch the session is no longer on.
      git.reload();
      projects.reload();
    } catch (failure: unknown) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  };

  const switchTo = async (branch: string) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await switchBranch(threadId, branch);
      git.reload();
    } catch (failure: unknown) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  };

  const branch = git.data?.branch ?? null;

  return (
    <div data-slot="composer-context" className="flex flex-col gap-1 px-1.5 pt-1 pb-0.5">
      <div className="flex items-center gap-4">
        {dirs.length > 0 && (
          <Select
            slot="composer-directory"
            label="Project directory"
            title={current}
            value={current}
            disabled={busy}
            leading={<FolderIcon className="text-muted-foreground size-4 shrink-0" />}
            options={
              // A session can be bound to a directory this home does not list (the
              // project was removed, or the id came from elsewhere). It still has to
              // be drawable, or the picker would silently show someone else's answer.
              dirs.some((d) => d.value === current) || current === ""
                ? dirs
                : [{ value: current, label: projectName(current) }, ...dirs]
            }
            onChange={(path) => void rebind(path)}
          />
        )}
        {git.data?.["repo?"] === true && (
          <Select
            slot="composer-branch"
            label="Git branch"
            value={branch ?? ""}
            disabled={busy}
            leading={<GitBranchIcon className="text-muted-foreground size-4 shrink-0" />}
            title={
              git.data.dirty > 0
                ? `${git.data.dirty} uncommitted change${git.data.dirty === 1 ? "" : "s"} — switching may be refused`
                : "The working tree of this session's directory"
            }
            options={
              branch === null
                ? [{ value: "", label: "detached" }, ...git.data.branches.map(branchesOf)]
                : git.data.branches.map(branchesOf)
            }
            onChange={(next) => void switchTo(next)}
          />
        )}
        {git.data?.["repo?"] === true && git.data.dirty > 0 && (
          <TriangleAlertIcon
            data-slot="composer-dirty"
            aria-label="uncommitted changes"
            className="text-muted-foreground size-3.5 shrink-0"
          />
        )}
      </div>
      {error !== null && (
        <p role="alert" data-slot="composer-context-error" className="text-destructive text-xs">
          {error}
        </p>
      )}
    </div>
  );
};

const branchesOf = (name: string) => ({ value: name, label: name });

/// The model and thinking pickers, in the composer's action row.
const ComposerTools: FC = () => {
  const threadId = useThreadId();
  const choices = useRemote(
    useCallback(
      () => (threadId === null ? Promise.resolve(null) : choicesFor(threadId)),
      [threadId],
    ),
  );
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const data = choices.data;

  const change = async (next: { provider?: string; model?: string; "reasoning-effort"?: string }) => {
    if (threadId === null || busy) return;
    setBusy(true);
    setError(null);
    try {
      await setModel(threadId, next);
      choices.reload();
    } catch (failure: unknown) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  };

  if (data === null) {
    return error === null ? null : (
      <p role="alert" data-slot="composer-tools-error" className="text-destructive text-xs">
        {error}
      </p>
    );
  }

  // Grouped by provider, so a long catalog reads as a short list of vendors each
  // with its models -- and the value is the MODEL id alone, because the provider
  // is implied by which group it was chosen from.
  const options = data.providers.flatMap((provider) =>
    provider.models.map((model) => ({
      value: model,
      label: model,
      group: provider.name,
    })),
  );
  const currentModel = data.model ?? options[0]?.value ?? "";

  return (
    <div data-slot="composer-tools" className="flex items-center gap-3">
      <Select
        slot="composer-model"
        label="Model"
        value={currentModel}
        disabled={busy}
        title={data.provider === undefined ? data.model : `${data.provider} / ${data.model}`}
        options={options}
        onChange={(model) => {
          // The provider comes from the group the model was listed under, because
          // an id is only meaningful against the vendor that declares it.
          const owner = data.providers.find((p) => p.models.includes(model));
          void change(owner === undefined ? { model } : { provider: owner.name, model });
        }}
      />
      <Select
        slot="composer-effort"
        label="Reasoning effort"
        value={data["reasoning-effort"] ?? ""}
        disabled={busy}
        leading={<BrainIcon className="text-muted-foreground size-4 shrink-0" />}
        title="Reasoning effort — this session only"
        options={[
          { value: "", label: "default" },
          ...data["reasoning-efforts"].map((effort) => ({ value: effort, label: effort })),
        ]}
        onChange={(effort) => void change({ "reasoning-effort": effort })}
      />
      {error !== null && (
        <p role="alert" data-slot="composer-tools-error" className="text-destructive text-xs">
          {error}
        </p>
      )}
    </div>
  );
};

/// The wrapper Thread renders around the composer. It draws the strip above the
/// composer and then gets out of the way; with no thread id there is nothing to
/// show, so it renders its children alone.
export const ComposerFrame: FC<PropsWithChildren> = ({ children }) => {
  const threadId = useThreadId();
  const started = useAuiState((s) => s.thread.messages.length > 0);

  if (threadId === null) return <>{children}</>;

  return (
    <div
      data-slot="composer-frame"
      data-started={started ? "" : undefined}
      className="bg-muted/40 rounded-(--composer-radius) p-1.5"
    >
      {!started && <ComposerContextBar threadId={threadId} />}
      {children}
    </div>
  );
};

export { ComposerTools };

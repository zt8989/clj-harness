"use client";

// The settings panel's Security row: THIS HOME's sensitive paths, as TWO groups.
//
// ================================================================ why this module
//
// THE CUSTOM HALF IS WRITTEN AND THE BUILT-IN HALF ONLY SHOWN. The built-in list
// (`harness.cap.providers/default-sensitive-paths`) is always in force and can never be
// switched off; what a person edits is the CUSTOM half -- config.edn's
// `:security :sensitive-paths`, ADDED on top. So the row draws the built-in paths with no
// delete button (they are not deletable), the custom paths each with one, and 'clear my own
// list' only when there is something of the person's to clear.
//
// IT LIVES IN A MODULE OF ITS OWN, the way `components/session-management.tsx` does, so a
// run with no browser can render the two groups and read them back (`test/suites/security-paths.tsx`,
// see `vitest.config.ts`): `settings-panel.tsx` imports `lib/i18n.ts`, which touches
// `document`, so nothing in it can be rendered here. The PRESENTATIONAL half --
// `SecurityPathsView`, data and callbacks in, markup out -- is the seam the suite reads;
// the CONTAINER -- `SensitivePathsRow`, which fetches and writes -- is what the panel mounts.
import type { TFunction } from "i18next";
import { PlusIcon, RefreshCwIcon, TrashIcon } from "lucide-react";
import { useCallback, useEffect, useState, type FC } from "react";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { readSecurity, writeSecurity, type Security } from "@/lib/securitySetting";

/// The title face of a settings section -- copied from `settings-panel.tsx` rather than
/// imported, because importing it would drag in the panel's i18n import (and `document`).
const SectionTitle: FC<{ children: React.ReactNode }> = ({ children }) => (
  <h3 className="text-muted-foreground mb-1 text-xs font-semibold tracking-wide uppercase">
    {children}
  </h3>
);

/// The security list, RENDERED FROM WHAT IT IS GIVEN. Every action is a callback, so this
/// has no state, no fetch, and nothing to mock: the container below owns all three.
export const SecurityPathsView: FC<{
  /// The list in force, its two halves, as `GET /api/security` answers it.
  security: Security;
  /// What is typed in the add box, right now.
  draft: string;
  /// A write is in flight: every control that writes is disabled.
  saving: boolean;
  /// The server's sentence when a route refused, or null.
  error: string | null;
  t: TFunction<"settings">;
  onDraft: (value: string) => void;
  onAdd: () => void;
  onRemove: (path: string) => void;
  onClear: () => void;
}> = ({ security, draft, saving, error, t, onDraft, onAdd, onRemove, onClear }) => {
  const builtin = security.builtin;
  const custom = security.custom;
  const trimmed = draft.trim();

  return (
    <section data-slot="settings-security">
      <SectionTitle>{t("security.title")}</SectionTitle>
      <p className="text-muted-foreground text-xs">{t("security.hint")}</p>

      <p className="text-muted-foreground mt-2 text-xs">{t("security.fromBuiltin")}</p>

      <ul data-slot="settings-security-builtin" className="mt-2 flex flex-col gap-1">
        {builtin.map((path) => (
          <li
            key={path}
            data-slot="settings-security-builtin-row"
            className="flex items-center justify-between gap-2"
          >
            <code className="font-mono text-xs break-all">{path}</code>
            <span className="text-muted-foreground text-[10px] uppercase">
              {t("security.builtinTag")}
            </span>
          </li>
        ))}
      </ul>

      {custom.length === 0 ? (
        <p data-slot="settings-security-empty" className="mt-2 text-xs">
          {t("security.empty")}
        </p>
      ) : (
        <ul data-slot="settings-security-paths" className="mt-2 flex flex-col gap-1">
          {custom.map((path) => (
            <li
              key={path}
              data-slot="settings-security-custom-row"
              className="flex items-center justify-between gap-2"
            >
              <code className="font-mono text-xs break-all">{path}</code>
              <Button
                variant="ghost"
                size="icon-xs"
                aria-label={t("security.remove", { path })}
                title={t("security.remove", { path })}
                disabled={saving}
                onClick={() => onRemove(path)}
              >
                <TrashIcon />
              </Button>
            </li>
          ))}
        </ul>
      )}

      <div className="mt-2 flex items-center gap-2">
        <Input
          aria-label={t("security.placeholder")}
          placeholder={t("security.placeholder")}
          value={draft}
          disabled={saving}
          onChange={(event) => onDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") onAdd();
          }}
        />
        <Button variant="secondary" size="sm" disabled={saving || trimmed === ""} onClick={onAdd}>
          <PlusIcon /> {t("security.add")}
        </Button>
      </div>

      {custom.length > 0 && (
        <Button variant="ghost" size="sm" className="mt-2" disabled={saving} onClick={onClear}>
          <RefreshCwIcon /> {t("security.clear")}
        </Button>
      )}

      {error !== null && (
        <p className="text-destructive mt-2 text-xs break-words" role="alert">
          {error}
        </p>
      )}
    </section>
  );
};

/// The container the panel mounts: it reads the list once, writes it back, and hands the
/// answer to the view. THE SERVER OWNS THE LIST -- nothing here keeps a copy of its own.
export const SensitivePathsRow: FC = () => {
  const { t } = useTranslation("settings");
  const { t: tErrors } = useTranslation("errors");
  const [security, setSecurity] = useState<Security | null>(null);
  const [draft, setDraft] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // READ ONCE PER MOUNT, like every other row: the panel is refetched when it opens, and
  // the answer is the server's.
  useEffect(() => {
    let live = true;
    void readSecurity(tErrors)
      .then((next) => {
        if (live) setSecurity(next);
      })
      .catch((reason: unknown) => {
        if (live) setError(reason instanceof Error ? reason.message : String(reason));
      });
    return () => {
      live = false;
    };
  }, [tErrors]);

  // ONE WRITE, THE CUSTOM HALF, AND THE ANSWER IS READ BACK: the row never advances its own
  // state optimistically, so what it draws is always what the server would park on.
  const write = useCallback(
    (paths: readonly string[]) => {
      setError(null);
      setSaving(true);
      void writeSecurity(paths, tErrors)
        .then((next) => setSecurity(next))
        .catch((reason: unknown) => {
          setError(reason instanceof Error ? reason.message : String(reason));
        })
        .finally(() => setSaving(false));
    },
    [tErrors],
  );

  const custom = security?.custom ?? [];

  const add = useCallback(() => {
    const trimmed = draft.trim();
    if (trimmed === "" || security === null) return;
    setDraft("");
    write([...custom, trimmed]);
  }, [draft, security, custom, write]);

  const remove = useCallback(
    (path: string) => write(custom.filter((kept) => kept !== path)),
    [custom, write],
  );

  // 'Clear my own list' writes `[]`: it empties the custom half, and the built-in half is
  // untouched -- the route's `[]` does not turn the guard off.
  const clear = useCallback(() => write([]), [write]);

  if (security === null) {
    return (
      <section data-slot="settings-security">
        <SectionTitle>{t("security.title")}</SectionTitle>
        <p className="text-muted-foreground text-xs">{t("security.hint")}</p>
        {error === null ? (
          <p className="text-muted-foreground mt-2 text-xs">{t("security.loading")}</p>
        ) : (
          <p className="text-destructive mt-2 text-xs break-words" role="alert">
            {error}
          </p>
        )}
      </section>
    );
  }

  return (
    <SecurityPathsView
      security={security}
      draft={draft}
      saving={saving}
      error={error}
      t={t}
      onDraft={setDraft}
      onAdd={add}
      onRemove={remove}
      onClear={clear}
    />
  );
};

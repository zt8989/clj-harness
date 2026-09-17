"use client";

// One picker, used by everything the composer offers a choice of: the project, the
// branch, the model, the reasoning effort.
//
// WHY NOT A `<select>`, WHICH IS WHAT THIS REPLACED. A native select is the right
// control for a short list and the wrong one for these: the lists are long (a home
// with thirty projects, a repository with a year of branches, a catalog of vendors
// each with its own model ids) and a native select cannot be searched -- its only
// keyboard affordance is typeahead, which jumps to a match rather than SHOWING the
// ones that match. Everything else the native control gave away for free is done
// here on purpose: `aria-expanded` / `aria-activedescendant`, the arrow keys, Home
// and End, Enter to pick, Escape to close, and the focus going back to the trigger.
//
// THE LIST IS FLAT AND MAY BE GROUPED. One level: rows under a heading
// (`groupOptions`), never a drill-down from vendor to model -- picking a model
// should be one search, not two menus. The heading is not focusable and not
// pickable; it is a label for the run of rows under it, which is also how it is
// announced (`role="group"` + `aria-label`).
//
// THE SEARCH BOX IS NOT ALWAYS THERE. `searchable` is off for a list short enough
// to read at a glance (the reasoning effort, at three options): a search box over
// four rows is furniture, and the arrow keys already do that job. The KEY HANDLING
// IS THE SAME EITHER WAY, because it lives on the content rather than on the input.
import { type FC, type ReactNode, useEffect, useRef, useState } from "react";
import { Popover as PopoverPrimitive } from "radix-ui";
import { ChevronDownIcon } from "lucide-react";

import {
  filterOptions,
  groupOptions,
  type PickerOption,
} from "@/lib/picker";
import { cn } from "@/lib/utils";

export type PickerProps = {
  /// The `data-slot` prefix, so a test can find the trigger, the search box and
  /// the rows without guessing at a class name.
  slot: string;
  /// The accessible name of the control and of its listbox.
  label: string;
  /// The chosen option's `value`, matched against the list.
  value: string;
  options: readonly PickerOption[];
  disabled?: boolean;
  /// Hover text for the trigger: the fact that did not fit on the row.
  title?: string;
  /// The icon before the label, in the composer's own vocabulary (a folder, a
  /// branch, a brain).
  leading?: ReactNode;
  /// What the trigger says when nothing in the list is the current value.
  placeholder?: string;
  searchable?: boolean;
  onPick: (option: PickerOption) => void;
};

export const Picker: FC<PickerProps> = ({
  slot,
  label,
  value,
  options,
  disabled,
  title,
  leading,
  placeholder,
  searchable = true,
  onPick,
}) => {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const listRef = useRef<HTMLDivElement>(null);

  const current = options.find((option) => option.value === value) ?? null;
  const shown = searchable ? filterOptions(options, query) : options;
  const groups = groupOptions(shown);

  // THE HIGHLIGHT STARTS WHERE THE READER IS. Opening on the current value means
  // Enter twice is a no-op rather than a surprise, and it is where the eye lands
  // anyway. The query is cleared for the same reason: this box is opened to pick,
  // not to continue a search somebody else abandoned.
  const openTo = (next: boolean) => {
    setOpen(next);
    if (!next) return;
    setQuery("");
    const index = options.findIndex((option) => option.value === value);
    setActive(index < 0 ? 0 : index);
  };

  const pick = (option: PickerOption) => {
    onPick(option);
    setOpen(false);
  };

  // The highlight is followed by the list, not the other way round: the rows live
  // in a fixed-height box, so arrowing past its edge has to bring the next row in.
  useEffect(() => {
    const row = listRef.current?.querySelector<HTMLElement>(`[data-index="${active}"]`);
    row?.scrollIntoView({ block: "nearest" });
  }, [active, open, query]);

  // Keys live on the CONTENT, so the two shapes -- with and without a search box --
  // behave identically, and so the typing that filters never has to be told apart
  // from the typing that navigates.
  const onKeyDown = (event: React.KeyboardEvent) => {
    if (shown.length === 0) return;
    const last = shown.length - 1;
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        setActive((index) => Math.min(index + 1, last));
        break;
      case "ArrowUp":
        event.preventDefault();
        setActive((index) => Math.max(index - 1, 0));
        break;
      case "Home":
        event.preventDefault();
        setActive(0);
        break;
      case "End":
        event.preventDefault();
        setActive(last);
        break;
      case "Enter": {
        event.preventDefault();
        const option = shown[active];
        if (option !== undefined) pick(option);
        break;
      }
      default:
        break;
    }
  };

  const activeId = `${slot}-option-${active}`;

  return (
    <PopoverPrimitive.Root open={open} onOpenChange={openTo}>
      <PopoverPrimitive.Trigger asChild disabled={disabled}>
        <button
          type="button"
          data-slot={`${slot}-trigger`}
          aria-label={label}
          aria-haspopup="listbox"
          title={title ?? current?.label}
          disabled={disabled}
          className="text-muted-foreground hover:text-foreground flex min-w-0 items-center gap-1.5 text-sm outline-none disabled:opacity-50"
        >
          {leading}
          <span data-slot={`${slot}-value`} className="max-w-[16rem] min-w-0 truncate text-start">
            {current?.label ?? placeholder ?? ""}
          </span>
          <ChevronDownIcon aria-hidden="true" className="size-3.5 shrink-0" />
        </button>
      </PopoverPrimitive.Trigger>
      <PopoverPrimitive.Portal>
        <PopoverPrimitive.Content
          data-slot={`${slot}-popover`}
          side="top"
          align="start"
          sideOffset={6}
          collisionPadding={8}
          onKeyDown={onKeyDown}
          className="bg-popover text-popover-foreground data-[state=open]:fade-in-0 data-[state=open]:zoom-in-95 data-[state=open]:animate-in data-[state=closed]:fade-out-0 data-[state=closed]:zoom-out-95 data-[state=closed]:animate-out data-[side=bottom]:slide-in-from-top-2 data-[side=top]:slide-in-from-bottom-2 z-50 w-72 max-w-[calc(100vw-2rem)] rounded-xl border p-1.5 shadow-lg outline-none"
        >
          {searchable && (
            <input
              data-slot={`${slot}-search`}
              // The browser's own search affordances (the clear button, the "x"
              // Escape handling) would fight this list's.
              type="text"
              role="combobox"
              aria-label={`Search ${label}`}
              aria-expanded="true"
              aria-controls={`${slot}-list`}
              aria-activedescendant={shown.length === 0 ? undefined : activeId}
              autoFocus
              value={query}
              placeholder="Search…"
              onChange={(event) => {
                setQuery(event.target.value);
                // The list just changed under the highlight: the first row is the
                // only one that cannot be a stale choice.
                setActive(0);
              }}
              className="border-border/60 placeholder:text-muted-foreground/60 focus:border-border mb-1 w-full rounded-lg border bg-transparent px-2 py-1.5 text-sm outline-none"
            />
          )}
          <div
            ref={listRef}
            id={`${slot}-list`}
            role="listbox"
            aria-label={label}
            tabIndex={searchable ? undefined : -1}
            className="max-h-64 overflow-y-auto outline-none"
          >
            {shown.length === 0 ? (
              <p data-slot={`${slot}-empty`} className="text-muted-foreground px-2 py-1.5 text-sm">
                No matches
              </p>
            ) : (
              groups.map((run, runIndex) => (
                <div
                  // A run's own key: the heading plus where the run starts, because
                  // two vendors can appear apart in the list and neither the label
                  // nor the index alone names the run.
                  key={`${run.group ?? ""}-${runIndex}`}
                  // `role="group"` only when there IS a heading: an unnamed group is
                  // a group nothing can be said about, and a run of options that
                  // belongs to nobody (a model the catalog does not list) is a flat
                  // list, not a silent one.
                  {...(run.group === undefined
                    ? {}
                    : { role: "group" as const, "aria-label": run.group })}
                >
                  {run.group !== undefined && (
                    <div
                      aria-hidden="true"
                      data-slot={`${slot}-group`}
                      className="text-muted-foreground px-2 pt-2 pb-1 text-xs font-medium"
                    >
                      {run.group}
                    </div>
                  )}
                  {run.options.map((option) => {
                    const index = shown.indexOf(option);
                    const selected = option.value === value;
                    return (
                      <button
                        key={option.value}
                        type="button"
                        id={`${slot}-option-${index}`}
                        data-slot={`${slot}-option`}
                        data-index={index}
                        role="option"
                        aria-selected={index === active}
                        title={option.hint}
                        onMouseEnter={() => setActive(index)}
                        onClick={() => pick(option)}
                        className={cn(
                          "flex w-full items-baseline gap-2 rounded-lg px-2 py-1.5 text-start text-sm",
                          index === active && "bg-accent text-accent-foreground",
                          selected && "font-medium",
                        )}
                      >
                        <span className="shrink-0">{option.label}</span>
                        {option.hint !== undefined && (
                          <span
                            data-slot={`${slot}-hint`}
                            className="text-muted-foreground min-w-0 flex-1 truncate text-xs"
                          >
                            {option.hint}
                          </span>
                        )}
                      </button>
                    );
                  })}
                </div>
              ))
            )}
          </div>
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  );
};

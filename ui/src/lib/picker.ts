// What a picker's list does with what was typed into it.
//
// The three pickers around the composer -- the project, the branch and the model --
// are lists a reader knows the name of and does not want to read: a home with
// thirty projects, a branch list of a year-old repository, a catalog of vendors
// each with its own model ids. So the list is filtered by what is typed, and that
// filtering is arithmetic, which is why it lives here: ZERO IMPORTS, so the UI
// suite can test it over literal options (see test/suites/picker.ts).
//
// WHAT IS MATCHED, and why more than the label:
//   - the label, because that is what is on screen;
//   - the HINT, because the label is a last path segment and the thing a person
//     remembers may be a parent directory (`workspace` finding
//     `/Users/me/Documents/workspace/clj-harness`);
//   - the GROUP, because a model's id does not name its vendor and "deepseek" has
//     to find every model the vendor declares.
// Substring, case-insensitive, no ranking: the lists are short enough that a
// scored order would be a second opinion about what the reader meant.

/// One row a picker can offer. `group` is the heading it is filed under (a vendor,
/// for the model list); `hint` is secondary text -- shown dimmed next to the label
/// and searched, never the value that gets sent.
export type PickerOption = {
  readonly value: string;
  readonly label: string;
  readonly group?: string | undefined;
  readonly hint?: string | undefined;
};

/// Whether one option answers what was typed. A blank query matches everything,
/// which is what an untouched search box means.
export function matchesOption(option: PickerOption, query: string): boolean {
  const needle = query.trim().toLowerCase();
  if (needle === "") return true;
  return [option.label, option.hint, option.group].some(
    (field) => field !== undefined && field.toLowerCase().includes(needle),
  );
}

/// The options a query keeps, in the order they were given -- the caller's order
/// is the reader's order (a vendor's own list, the branches as git named them).
export function filterOptions(
  options: readonly PickerOption[],
  query: string,
): PickerOption[] {
  return options.filter((option) => matchesOption(option, query));
}

/// The options split into consecutive runs by `group`, ready to be drawn under one
/// heading each. Runs rather than a map: two vendors that appear apart in the list
/// stay apart, so the order the caller chose is never silently rearranged. Options
/// with no group come back as a run whose own group is undefined, and the caller
/// decides whether to draw a heading for it.
export function groupOptions(
  options: readonly PickerOption[],
): { group: string | undefined; options: PickerOption[] }[] {
  const groups: { group: string | undefined; options: PickerOption[] }[] = [];
  for (const option of options) {
    const last = groups[groups.length - 1];
    if (last !== undefined && last.group === option.group) last.options.push(option);
    else groups.push({ group: option.group, options: [option] });
  }
  return groups;
}

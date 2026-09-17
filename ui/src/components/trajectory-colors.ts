// The trajectory's one colour table: what each KIND of item looks like, everywhere it
// is drawn.
//
// THE STRIP AND THE LIST HAVE TO AGREE, and the reason is not decoration. The three
// lanes above are not three abstractions of their own -- an `input` mark IS a user
// message, a `model` mark IS a model call (an assistant item's call), a `tools` mark IS
// a tool call. So a reader who has learnt from the list that orange means "a tool ran"
// must be able to look up at the strip and read the same thing without learning a second
// vocabulary. One table, and LANE_KIND states the correspondence rather than leaving it
// to whoever writes the next lane.
//
// THE CLASSES ARE WRITTEN OUT rather than composed from the kind name: Tailwind scans
// source for literal class strings, so `bg-${hue}-500/70` would be a class that never
// exists in the built stylesheet. Two spellings per kind in one row is the price of
// that, and it is a row -- not two tables that can drift.
import type { TrajectoryItem } from "@/lib/trajectory";

/// The lanes the strip draws.
export type Lane = "input" | "model" | "tool";

/// Which KIND of item a lane is showing. A lane's colour comes from here, so the strip
/// cannot end up speaking a different colour language than the list beneath it.
export const LANE_KIND: Record<Lane, TrajectoryItem["kind"]> = {
  input: "user",
  model: "assistant",
  tool: "tool",
};

/// `chip` is the label on a row and in the detail pane's header; `bar` is a mark on a
/// lane. Both are the same hue per kind.
export const KIND_HUE: Record<TrajectoryItem["kind"], { chip: string; bar: string }> = {
  system: { chip: "bg-primary/10 text-primary", bar: "bg-primary/70" },
  context: {
    chip: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
    bar: "bg-emerald-500/70",
  },
  user: { chip: "bg-sky-500/10 text-sky-600 dark:text-sky-400", bar: "bg-sky-500/70" },
  assistant: {
    chip: "bg-violet-500/10 text-violet-600 dark:text-violet-400",
    bar: "bg-violet-500/70",
  },
  tool: { chip: "bg-amber-500/10 text-amber-600 dark:text-amber-400", bar: "bg-amber-500/70" },
};

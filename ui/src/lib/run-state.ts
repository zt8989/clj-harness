// The two refusals a run in flight earns, and the probe behind them.
//
// They live in a leaf module rather than beside the thing that displays them,
// because TWO sides need them and they must not drift: the thread-list adapter
// refuses the switch (it is the layer the runtime calls), and the sidebar shows
// the reason on the row that was clicked. That is the arrangement the previous
// panel had, with the strings and the probe kept together because two copies of a
// refusal reason are how "refused for the same reason" quietly becomes false.
//
// Why refuse at all: the run belongs to the thread it started on, and abandoning
// the view mid-flight would orphan it. The probe reads the runtime's own
// `isRunning` rather than a local flag, so a run that settles unblocks the list
// without anything having to tell it.
import type { AssistantRuntime } from "@assistant-ui/react";

export const RUN_IN_PROGRESS_REFUSAL =
  "A run is in progress; switching is refused until it settles.";

export const RUN_IN_PROGRESS_NEW_THREAD_REFUSAL =
  "A run is in progress; a new session waits until it settles.";

export const runInProgress = (runtime: AssistantRuntime): boolean =>
  runtime.threads.main.getState().isRunning;

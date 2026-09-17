// The two refusals a run in flight earns, and the probe behind them.
//
// They live in a leaf module rather than beside the thing that displays them,
// because TWO sides need them and they must not drift: the thread-list adapter
// refuses the switch (it is the layer the runtime calls), and the sidebar shows
// the reason on the row that was clicked. That is the arrangement the previous
// panel had, with the strings and the probe kept together because two copies of a
// refusal reason are how "refused for the same reason" quietly becomes false.
//
// THEY ARE FUNCTIONS OF A TRANSLATOR, NOT CONSTANTS, and that is the whole point
// of this feature's visit here: a sentence a person reads has a language, and a
// module-level constant has none. The words live in the `errors` catalog; what
// stays here is the fact that these two are the repo's own sentences (the
// server's are never translated -- see `docs/architecture/client.md`), shared by
// both sides so a refusal cannot be worded two ways.
//
// Why refuse at all: the run belongs to the thread it started on, and abandoning
// the view mid-flight would orphan it. The probe reads the runtime's own
// `isRunning` rather than a local flag, so a run that settles unblocks the list
// without anything having to tell it.
import type { TFunction } from "i18next";

import type { AssistantRuntime } from "@assistant-ui/react";

/// The translator a refusal is worded through. It is the DEFAULT one on purpose:
/// both callers already hold it (the adapter in `app.tsx` and the sidebar), so
/// the alternative -- pinning this to `"errors"` -- would make each of them reach
/// for a second translator just to say why a click was refused. The `errors`
/// catalog is still named explicitly below, so the keys are checked against it.
type Translate = TFunction<"shell">;

export function runInProgressRefusal(t: Translate): string {
  return t("runInProgress.switch", { ns: "errors" });
}

export function runInProgressNewThreadRefusal(t: Translate): string {
  return t("runInProgress.newThread", { ns: "errors" });
}

export const runInProgress = (runtime: AssistantRuntime): boolean =>
  runtime.threads.main.getState().isRunning;

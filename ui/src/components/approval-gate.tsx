// The human end of a parked tool call: the card a run stops on when a tool is
// marked `:requires-approval`, and the two decisions that let the run continue.
//
// -------------------------------------------------------------- which seam
//
// The harness parks a marked call by putting
// `{id, reason: "tool-approval", message, toolCallId}` into
// `RUN_FINISHED.outcome.interrupts`, and reads the answer back out of the next
// run's `resume` array (`resolved` -> approved, `cancelled` -> vetoed). The
// server end is frozen by this ticket, so the question here is only which client
// API reaches that wire.
//
// assistant-ui offers two, and the newer-looking one does NOT fit:
//
//   * the first-class approval seam -- `ToolCallMessagePart.approval` plus the
//     part prop `respondToApproval`. Its ag-ui projection
//     (`projectAgUiToolApprovals`) accepts a gate only when
//     `reason === "tool_call"`; ours is `"tool-approval"`, so the projection
//     returns an empty map and `approval` is never set on our parts. Upstream's
//     own `ToolFallbackApproval` is built on that seam, and would therefore
//     draw nothing here. (Also checked: its `respond` falls back to `resume` /
//     `addResult`, both of which belong to *frontend* tool calls, and its
//     `requires-action` arm is a bare `return`.)
//   * the interrupt seam -- `useAgUiInterrupts()` reading the same snapshot
//     `RUN_FINISHED` produced, and `useAgUiSubmitInterruptResponses()` writing
//     the `resume` array back. This is the one that speaks our reason string.
//
// The interrupt seam has a deprecated twin on the runtime object
// (`unstable_getPendingInterrupts` / `unstable_submitInterruptResponses`); the
// two hooks above are what replaced it, and they are what is used here. See the
// upgrade note in spec.md's known risks.
//
// So the card is written here rather than reused, and it borrows upstream's
// *shape* instead: the same `Button` atoms, the same disabled-while-submitting
// guard, the same inline error paragraph. Nothing in
// `components/assistant-ui/elements/` was edited to get this.
//
// ------------------------------------------------------------- the batch
//
// AG-UI resumes a run with one response per OPEN interrupt, and the runtime
// rejects a partial submission by name ("missing responses for open
// interrupts"). A turn that parks two calls therefore cannot be answered one
// card at a time: each card records its own decision and the last one to arrive
// submits the whole batch. That is the same rule upstream's own approval seam
// states for its gates ("the decision is held on the part until every gate in
// the batch is answered"), reached here through the store below because our
// interrupts are not projected onto message parts.
//
// For the ordinary one-call turn -- every case the acceptance walks -- the
// click that records the decision is also the click that completes the batch,
// so it submits immediately and nothing waits.
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type FC,
  type PropsWithChildren,
} from "react";
import { ShieldAlertIcon } from "lucide-react";
import {
  useAgUiInterrupts,
  useAgUiSubmitInterruptResponses,
  type AgUiInterrupt,
} from "@assistant-ui/react-ag-ui";

import { Button } from "@/components/ui/button";

/// The interrupt reason this card owns. Every other reason on this seam --
/// including the protocol's own `tool_call`, `input_required` and `confirmation`
/// -- is left alone rather than approximated: a card that guesses at a gate it
/// does not understand is worse than a gate with no card, because the guess is
/// what gets submitted.
const APPROVAL_REASON = "tool-approval";

/// Whether an interrupt is one of ours. The one place the reason string is
/// compared; everything else that cares asks this, so the vocabulary cannot
/// drift between the card, the batch and the tool card.
export const isApprovalInterrupt = (candidate: AgUiInterrupt): boolean =>
  candidate.reason === APPROVAL_REASON;

/// The two statuses the resume entry is allowed to carry (`AgUiResumeEntry`),
/// used as the decision's own vocabulary so there is no mapping layer to drift.
type Decision = "resolved" | "cancelled";

/// The payload that rides with the decision.
///
/// Preserved from the gate this replaces, byte for byte: an approval carried
/// `{decision: "approved"}` and a veto carried nothing at all. Neither changes
/// what the server does with it -- the payload reaches the model only through
/// `veto-message`, on the veto path, and that path reads nothing -- but the
/// payload is part of the wire and is not this ticket's to drop. (`undefined`
/// disappears from the JSON request body, which is exactly the shape the old
/// gate sent.)
const payloadFor = (decision: Decision): unknown =>
  decision === "resolved" ? { decision: "approved" } : undefined;

type GateState = {
  decisions: ReadonlyMap<string, Decision>;
  decide: (interruptId: string, decision: Decision) => void;
  submitting: boolean;
  error: string | null;
};

/// What a card sees when it is rendered outside the provider. A card with no
/// gate above it cannot submit anything, and says nothing rather than pretending
/// a click worked.
const EMPTY_GATE: GateState = {
  decisions: new Map(),
  decide: () => {},
  submitting: false,
  error: null,
};

const GateContext = createContext<GateState>(EMPTY_GATE);

/// Holds the decisions of the batch currently parked, and submits them once they
/// cover it. Mounted once, above the thread, because the cards that make up a
/// batch are siblings with no common owner of their own.
///
/// `onHoldChange` reports upward whether a gate is currently holding the run, so
/// the page can close the composer for as long as one is. It is a callback rather
/// than a context the composer could read because the composer is a copied file:
/// the only lever this repo owns is `isSendDisabled`, which is a `useAgUiRuntime`
/// option, and an option has to be known one level up from this provider.
///
/// Why closing the composer is the answer, rather than letting the send fail: a
/// message sent while an interrupt is open is refused by the runtime
/// (`append` -> `assertNoPendingInterrupts`), and the refusal is INVISIBLE -- the
/// composer is cleared, the message never reaches the transcript, no request goes
/// out and nothing is logged. Measured in a real browser, not inferred: the text
/// typed into the composer while a gate was open disappeared without a trace.
/// Blocking the send leaves that text where it is.
export const ApprovalBatchProvider: FC<
  PropsWithChildren<{ onHoldChange?: (held: boolean) => void }>
> = ({ children, onHoldChange }) => {
  const interrupts = useAgUiInterrupts();
  const submitResponses = useAgUiSubmitInterruptResponses();

  const open = interrupts.filter(isApprovalInterrupt);
  // The batch's ids as one string. `interrupts` is a fresh array on every store
  // update, so an array in a dependency list would re-run the reset below on
  // every delta; this key changes only when the batch itself does. NUL is the
  // separator because it cannot occur inside an interrupt id.
  const openKey = open.map((i) => i.id).join("\u0000");

  const [decisions, setDecisions] = useState<ReadonlyMap<string, Decision>>(
    new Map(),
  );
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Reported from an effect, so the page sees "a gate is open" one render after
  // this provider does. `held` is a boolean, so a second render with the same
  // value bails out of the setState above and there is no loop to guard against.
  const held = open.length > 0;
  useEffect(() => {
    onHoldChange?.(held);
  }, [held, onHoldChange]);

  // The batch is the only thing these decisions belong to: when it changes --
  // answered, superseded by a later run, or gone with the thread -- the
  // decisions for ids that are no longer open are dropped, and with them any
  // error about submitting them. Without this, a decision recorded for one gate
  // would still be sitting here for the next gate that happened to reuse an id.
  useEffect(() => {
    const openIds = new Set(openKey === "" ? [] : openKey.split("\u0000"));

    setDecisions((prev) => {
      const kept = new Map([...prev].filter(([id]) => openIds.has(id)));
      return kept.size === prev.size ? prev : kept;
    });
    setSubmitting((prev) => (prev ? false : prev));
    setError((prev) => (prev === null ? prev : null));
  }, [openKey]);

  const decide = useCallback(
    (interruptId: string, decision: Decision) => {
      if (submitting) return;

      const next = new Map(decisions).set(interruptId, decision);
      setDecisions(next);

      const openIds = open.map((i) => i.id);
      // Nothing parked, or still waiting on a sibling's decision. Either way
      // this click only records.
      if (openIds.length === 0 || !openIds.every((id) => next.has(id))) return;

      setSubmitting(true);
      setError(null);
      void submitResponses(
        openIds.map((id) => {
          const decided = next.get(id) as Decision;
          return {
            interruptId: id,
            status: decided,
            payload: payloadFor(decided),
          };
        }),
      )
        .catch((failure: unknown) => {
          // The decision stays recorded, so the same click submits again: a
          // refused response leaves the gate open and retryable rather than
          // spent on a decision the runtime never recorded.
          setError(failure instanceof Error ? failure.message : String(failure));
        })
        .finally(() => {
          setSubmitting(false);
        });
    },
    [decisions, open, submitResponses, submitting],
  );

  return (
    <GateContext.Provider value={{ decisions, decide, submitting, error }}>
      {children}
    </GateContext.Provider>
  );
};

/// One parked call, as the human decides it.
///
/// The tool name and the arguments are the part's OWN -- the card this sits
/// under reads them off the client's message, so they are by construction the
/// call the interrupt names rather than a re-parsing of the server's
/// `message`. That line is a sentence written for a person to read ("Approve
/// `write`? arguments: {...}"), and it is drawn below as exactly that, a
/// sentence, never as the source of a field.
const ApprovalCard: FC<{
  interrupt: AgUiInterrupt;
  toolName: string;
  argsText: string;
}> = ({ interrupt, toolName, argsText }) => {
  const { decisions, decide, submitting, error } = useContext(GateContext);
  const decision = decisions.get(interrupt.id);

  return (
    <div
      data-slot="approval-card"
      className="aui-approval-card border-border/60 bg-card text-card-foreground mb-1 flex flex-col gap-2 rounded-lg border p-3"
    >
      <p className="aui-approval-card-title flex items-center gap-2 text-sm font-semibold">
        <ShieldAlertIcon
          data-slot="approval-card-icon"
          className="aui-approval-card-icon size-4 shrink-0"
        />
        This tool call needs your approval
      </p>

      <div className="aui-approval-card-call flex flex-col">
        <code className="aui-approval-card-tool-name bg-muted w-fit rounded px-1.5 py-0.5 font-mono text-xs">
          {toolName}
        </code>
        {argsText !== "" && (
          <pre className="aui-approval-card-args bg-muted/50 text-foreground/90 mt-1 rounded-md p-2.5 text-xs whitespace-pre-wrap">
            {argsText}
          </pre>
        )}
      </div>

      {interrupt.message !== undefined && (
        <p className="aui-approval-card-message text-muted-foreground text-xs">
          {interrupt.message}
        </p>
      )}

      <div className="aui-approval-card-actions flex items-center gap-2">
        <Button
          size="sm"
          className="aui-approval-card-approve active:scale-[0.98]"
          disabled={submitting}
          onClick={() => {
            decide(interrupt.id, "resolved");
          }}
        >
          {decision === "resolved" ? "Approved" : "Approve"}
        </Button>
        <Button
          size="sm"
          variant="outline"
          className="aui-approval-card-deny active:scale-[0.98]"
          disabled={submitting}
          onClick={() => {
            decide(interrupt.id, "cancelled");
          }}
        >
          {decision === "cancelled" ? "Denied" : "Deny"}
        </Button>
      </div>

      {error !== null && (
        <p
          role="alert"
          className="aui-approval-card-error text-destructive text-xs"
        >
          {error}
        </p>
      )}

      <p className="aui-approval-card-note text-muted-foreground text-xs">
        Approving runs the call as usual. Denying skips it: the model is handed a
        tool result saying a human vetoed it, and the run carries on.
      </p>

      <p className="aui-approval-card-hold text-muted-foreground text-xs">
        The message box stays closed until this is decided -- a message sent now
        would be refused rather than queued.
      </p>
    </div>
  );
};

/// The gate for one parked call.
///
/// `ToolCallCard` mounts this for every part whose status is `requires-action`
/// -- the signal the protocol names for "a human has to decide before this run
/// can continue" -- and for every part a pending interrupt names by
/// `toolCallId`, because a parked call in a finalized message carries neither
/// status nor promise of one (see the argument on `ToolCallCard`). The gate
/// then decides for itself whether it has anything to say: a `requires-action`
/// part whose interrupts are none of ours (a pending client-side tool call, or
/// an interrupt of another reason) draws nothing at all.
export const ApprovalGate: FC<{
  toolCallId: string;
  toolName: string;
  argsText: string;
}> = ({ toolCallId, toolName, argsText }) => {
  const interrupts = useAgUiInterrupts();
  // Bound by the interrupt's own `toolCallId` -- the id both the parked record
  // and the AG-UI `TOOL_CALL_START` frame carry -- so the card cannot be drawn
  // against a neighbouring call's name or arguments.
  const interrupt = interrupts.find(
    (candidate) =>
      isApprovalInterrupt(candidate) && candidate.toolCallId === toolCallId,
  );

  if (interrupt === undefined) return null;

  return (
    <ApprovalCard
      interrupt={interrupt}
      toolName={toolName}
      argsText={argsText}
    />
  );
};

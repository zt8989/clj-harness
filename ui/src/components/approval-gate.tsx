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
import { MessageCircleQuestionIcon, ShieldAlertIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import {
  useAgUiInterrupts,
  useAgUiSubmitInterruptResponses,
  type AgUiInterrupt,
} from "@assistant-ui/react-ag-ui";

import { Button } from "@/components/ui/button";
import { API_BASE } from "@/lib/threads";
import { answersFor, fieldSpecs, inputKindFor } from "@/lib/elicitation";
import type { FieldSpec, FieldValue } from "@/lib/elicitation";

/// The interrupt reason this card owns. Every other reason on this seam --
/// including the protocol's own `tool_call`, `input_required` and `confirmation`
/// -- is left alone rather than approximated: a card that guesses at a gate it
/// does not understand is worse than a gate with no card, because the guess is
/// what gets submitted.
const APPROVAL_REASON = "tool-approval";

/// The OTHER kind of stop, and the only other one this harness makes: a server
/// asked the user something and the call is waiting for the answer. Its card is
/// a form rather than two buttons, but it rides the same seam, the same batch and
/// the same resume array -- which is the whole point of the reason being a value
/// rather than a second mechanism.
const ELICITATION_REASON = "elicitation";

/// Whether an interrupt is one of ours. The one place the reason string is
/// compared; everything else that cares asks this, so the vocabulary cannot
/// drift between the card, the batch and the tool card.
export const isApprovalInterrupt = (candidate: AgUiInterrupt): boolean =>
  candidate.reason === APPROVAL_REASON;

/// Whether an interrupt is a server asking a question.
export const isElicitationInterrupt = (candidate: AgUiInterrupt): boolean =>
  candidate.reason === ELICITATION_REASON;

/// Whether an interrupt is one this file draws a card for at all. The seam
/// carries other reasons -- the protocol's own among them -- and a card that
/// guessed at one it does not understand would be worse than no card, because
/// the guess is what gets submitted.
export const isParkedInterrupt = (candidate: AgUiInterrupt): boolean =>
  isApprovalInterrupt(candidate) || isElicitationInterrupt(candidate);

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

/// One recorded decision: HOW the gate was answered, and what rides with the
/// answer. The payload belongs to the CARD rather than to the status, because a
/// form's answer is the form (`{name: "Ada"}`) while an approval's is the fixed
/// sentence the wire has always carried.
type Recorded = { decision: Decision; payload: unknown };

type GateState = {
  decisions: ReadonlyMap<string, Recorded>;
  decide: (interruptId: string, decision: Decision, payload?: unknown) => void;
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

  const open = interrupts.filter(isParkedInterrupt);
  // The batch's ids as one string. `interrupts` is a fresh array on every store
  // update, so an array in a dependency list would re-run the reset below on
  // every delta; this key changes only when the batch itself does. NUL is the
  // separator because it cannot occur inside an interrupt id.
  const openKey = open.map((i) => i.id).join("\u0000");

  const [decisions, setDecisions] = useState<ReadonlyMap<string, Recorded>>(
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
    (interruptId: string, decision: Decision, payload?: unknown) => {
      if (submitting) return;

      const next = new Map(decisions).set(interruptId, {
        decision,
        // The default is what an approval has always sent; a card with something
        // else to say passes it here.
        payload: payload === undefined ? payloadFor(decision) : payload,
      });
      setDecisions(next);

      const openIds = open.map((i) => i.id);
      // Nothing parked, or still waiting on a sibling's decision. Either way
      // this click only records.
      if (openIds.length === 0 || !openIds.every((id) => next.has(id))) return;

      setSubmitting(true);
      setError(null);
      void submitResponses(
        openIds.map((id) => {
          const decided = next.get(id) as Recorded;
          return {
            interruptId: id,
            status: decided.decision,
            payload: decided.payload,
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
///
/// WHAT THIS CARD TRANSLATES IS ONLY ITS OWN WORDS. `interrupt.message` is the
/// server's sentence and the tool name and arguments are the model's
/// vocabulary; both are drawn verbatim in every language, because a record that
/// changes with the reader is not one record (spec decision 3).
const ApprovalCard: FC<{
  interrupt: AgUiInterrupt;
  toolName: string;
  argsText: string;
}> = ({ interrupt, toolName, argsText }) => {
  const { t } = useTranslation("approval");
  const { decisions, decide, submitting, error } = useContext(GateContext);
  const decision = decisions.get(interrupt.id)?.decision;

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
        {t("card.title")}
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
          {decision === "resolved" ? t("card.approved") : t("card.approve")}
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
          {decision === "cancelled" ? t("card.denied") : t("card.deny")}
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
        {t("card.note")}
      </p>

      <p className="aui-approval-card-hold text-muted-foreground text-xs">
        {t("card.hold")}
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

/// ---------------------------------------------------------------- the question
///
/// Something asked the user a question and the call is waiting. The card is a
/// FORM. WHAT it renders comes from `lib/elicitation.ts`, which owns the
/// schema-to-fields rules and their tests; WHERE the question comes from is the
/// harness's own edge rather than the interrupt:
///
///   - the interrupt carries the question and a reason, and NOT the schema. Its
///     shape belongs to AG-UI and a client's validator refuses extra fields, so
///     stuffing a form into it would be a protocol change this repo has no
///     business making.
///   - `GET /api/elicitation?interruptId=` answers with what was asked, WHO asked,
///     and the JSON Schema to fill in. The asker is on the card because the person
///     answering deserves to know who wants to know.
///
/// NOTHING IS ASSUMED ABOUT THE SCHEMA. `requestedSchema` is a JSON Schema object
/// with primitive properties; this renders the kinds it knows as the inputs they
/// are, and anything else as a text field WITH ITS TYPE NAMED. A field is never
/// dropped for being unfamiliar -- dropping one would send the server a form that
/// looked answered and was missing half the answers.

/// WHO IS ASKING -- three titles, because two of them would be a lie.
///
/// `server` is the outside program's name, and the endpoint answers it for the
/// questions harness.cap.mcp brings in. `askedBy` is who asked when it is NOT a
/// server: `"model"` for the questions this harness's own `ask` tool puts up, where
/// the card's old words ("a server is asking you") were false about the one thing a
/// person answering wants to know. With NEITHER, the card says the only thing left
/// that is true -- somebody is asking and it will not name itself -- rather than
/// guessing a server, which is what a question with no name used to look like.
///
/// It is its own component rather than a paragraph inside the card so that it can be
/// RENDERED to a string and read back: the failure this guards against is a title
/// that renders nothing at all, which no assertion about a translation key would
/// catch (see suites/sidebar.tsx for the same seam and the bug that paid for it).
export const ElicitationCardTitle: FC<{ server?: string; askedBy?: string }> = ({
  server,
  askedBy,
}) => {
  const { t } = useTranslation("approval");
  return (
    <p
      data-slot="elicitation-card-title"
      className="aui-elicitation-card-title flex items-center gap-2 text-sm font-semibold"
    >
      <MessageCircleQuestionIcon
        data-slot="elicitation-card-icon"
        className="aui-elicitation-card-icon size-4 shrink-0"
      />
      {server !== undefined
        ? t("elicitation.named", { server })
        : askedBy === "model"
          ? t("elicitation.model")
          : t("elicitation.title")}
    </p>
  );
};

/// ONE FIELD, as the control its kind calls for.
///
/// IT DRAWS ITS OWN WRAPPER, because the wrapper is not the same element for every
/// kind and that is a fact about labels rather than a preference. A label wraps ONE
/// control, so a scalar field keeps the `<label>` it has always had -- the words and
/// the box are one target, and clicking the sentence focuses the box, which is what
/// every field of a server's form already is. A row of tick boxes cannot be a label:
/// wrapping four of them in one would make the FIRST the target of a click on the
/// question. Neither can a choice that also offers "write your own", for the same
/// reason -- two controls, one label. Those two get a plain `<div>`, and the shapes
/// that existed before this are untouched.
///
/// WHAT IT TRANSLATES IS ONLY ITS OWN WORDS. The field's name, kind, description and
/// candidates are the asking side's, drawn verbatim (spec decision 3); the only
/// sentence this file owns is the placeholder for the person's own words.
///
/// EXPORTED FOR THE SAME REASON `ElicitationCardTitle` IS: which control a field gets
/// is a claim about what a person can click, and the only way to hold it is to render
/// the field and read the `data-slot`s back (`suites/elicitation-card.tsx`).
export const ElicitationField: FC<{
  field: FieldSpec;
  value: FieldValue | undefined;
  own: string;
  onValue: (value: FieldValue) => void;
  onOwn: (own: string) => void;
}> = ({ field, value, own, onValue, onOwn }) => {
  const { t } = useTranslation("approval");
  const kind = inputKindFor(field);
  const ticks = Array.isArray(value) ? value : [];
  const scalar = typeof value === "string" ? value : "";

  const words = (
    <>
      <span className="font-medium">
        {field.name}
        {/* A kind with no input of its own is still SHOWN, with its name
            spelled out -- the alternative is a form that quietly drops a
            field the server asked for. */}
        {field.kind !== "string" && (
          <span className="text-muted-foreground ml-1 font-normal">
            ({field.kind})
          </span>
        )}
      </span>
      {field.description !== undefined && (
        <span className="text-muted-foreground">{field.description}</span>
      )}
    </>
  );

  /// The way out of a list that does not hold their answer. Offered only where
  /// there IS a list and only when the schema asked for it: `ask` never sends the
  /// key otherwise, and a server's own enum must not grow one.
  const other =
    field.allowOther === true && (kind === "select" || kind === "checkboxes") ? (
      <input
        data-slot="elicitation-other"
        className="aui-elicitation-other border-input bg-background rounded-md border px-2 py-1"
        type="text"
        value={own}
        placeholder={t("elicitation.other")}
        aria-label={t("elicitation.other")}
        onChange={(e) => {
          // A PICK AND THEIR OWN WORDS ARE ONE ANSWER, so typing clears the pick --
          // and picking below clears the typing. Leaving both standing would put two
          // answers to one question in the payload and give the person no way to see
          // which of them they had sent.
          if (kind === "select" && e.target.value !== "") onValue("");
          onOwn(e.target.value);
        }}
      />
    ) : null;

  const wrapper = { "data-slot": "elicitation-field", "data-field-kind": field.kind };

  /// A CONTROL THAT LOST ITS `<label>` STILL NEEDS A NAME. The wrapper is what names a
  /// bare field to anything that cannot see the screen, and a field with an own-words
  /// box has two controls and so gets a `<div>` instead (see above). The question goes
  /// on the control itself in that case -- and ONLY in that case, because inside a
  /// label an `aria-label` would REPLACE the words already sitting next to the box.
  /// The own-words box names itself differently: it is not the answer to the question,
  /// it is the way out of the list.
  const named = other !== null ? { "aria-label": field.description ?? field.name } : {};

  if (kind === "checkboxes") {
    return (
      <div {...wrapper} className="aui-elicitation-field flex flex-col gap-1 text-xs">
        {words}
        <div className="aui-elicitation-choices flex flex-wrap gap-x-3 gap-y-1">
          {(field.itemValues ?? []).map((option) => (
            <label
              key={option}
              className="aui-elicitation-choice flex items-center gap-1"
            >
              <input
                data-slot="elicitation-checkbox"
                type="checkbox"
                value={option}
                checked={ticks.includes(option)}
                onChange={(e) =>
                  onValue(
                    e.target.checked
                      ? [...ticks, option]
                      : ticks.filter((tick) => tick !== option),
                  )
                }
              />
              <span>{option}</span>
            </label>
          ))}
        </div>
        {other}
      </div>
    );
  }

  const control =
    kind === "select" ? (
      <select
        {...named}
        data-slot="elicitation-select"
        className="aui-elicitation-select border-input bg-background rounded-md border px-2 py-1"
        value={scalar}
        onChange={(e) => {
          if (e.target.value !== "") onOwn("");
          onValue(e.target.value);
        }}
      >
        <option value="">—</option>
        {(field.enumValues ?? ["true", "false"]).map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    ) : (
      <input
        {...named}
        data-slot="elicitation-input"
        className="aui-elicitation-input border-input bg-background rounded-md border px-2 py-1"
        type={kind === "number" ? "number" : "text"}
        value={scalar}
        onChange={(e) => onValue(e.target.value)}
      />
    );

  return other === null ? (
    <label {...wrapper} className="aui-elicitation-field flex flex-col gap-1 text-xs">
      {words}
      {control}
    </label>
  ) : (
    <div {...wrapper} className="aui-elicitation-field flex flex-col gap-1 text-xs">
      {words}
      {control}
      {other}
    </div>
  );
};

/// The question, and the form that answers it.
///
/// THE QUESTION IS THE ASKER'S, AND SO IS THE FORM. `asked.prompt` is the asker's
/// own sentence, and the field names, kinds, descriptions and enum values come out
/// of the schema; all of them are drawn verbatim (spec decision 3), which is why
/// only the card's frame and buttons read from a catalog here.
const ElicitationCard: FC<{ interrupt: AgUiInterrupt }> = ({ interrupt }) => {
  const { t } = useTranslation("approval");
  const { t: tErrors } = useTranslation("errors");
  const { decisions, decide, submitting, error } = useContext(GateContext);
  const [asked, setAsked] = useState<{
    server?: string;
    askedBy?: string;
    prompt?: string;
    schema?: unknown;
  } | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [values, setValues] = useState<Record<string, FieldValue>>({});
  // THE PERSON'S OWN WORDS, kept apart from `values` because they mean two different
  // things to `answersFor`: for a single choice they stand where the pick would have,
  // and for a multiple choice they are one more item beside the ticks.
  const [own, setOwn] = useState<Record<string, string>>({});

  useEffect(() => {
    let live = true;
    setAsked(null);
    setLoadError(null);
    fetch(`${API_BASE}elicitation?interruptId=${encodeURIComponent(interrupt.id)}`)
      .then(async (res) => {
        if (!res.ok) {
          throw new Error(
            // A sentence THIS SIDE raises (the server said nothing usable), so it
            // follows the interface's language -- and its key lives with the other
            // fetch failures, in `errors`.
            tErrors("http.askingQuestion", { status: res.status }),
          );
        }
        return (await res.json()) as {
          server?: string;
          askedBy?: string;
          prompt?: string;
          schema?: unknown;
        };
      })
      .then((body) => {
        if (live) setAsked(body);
      })
      .catch((failure: unknown) => {
        if (live) {
          setLoadError(failure instanceof Error ? failure.message : String(failure));
        }
      });
    return () => {
      live = false;
    };
  }, [interrupt.id]);

  const fields = asked === null ? [] : fieldSpecs(asked.schema);
  const recorded = decisions.get(interrupt.id)?.decision;

  const settle = (decision: Decision) => {
    if (decision === "resolved") {
      decide(interrupt.id, "resolved", answersFor(fields, values, own));
    } else {
      decide(interrupt.id, "cancelled", { action: "decline" });
    }
  };

  return (
    <div
      data-slot="elicitation-card"
      className="aui-elicitation-card border-border/60 bg-card text-card-foreground mb-1 flex flex-col gap-2 rounded-lg border p-3"
    >
      <ElicitationCardTitle server={asked?.server} askedBy={asked?.askedBy} />

      <p className="aui-elicitation-card-prompt text-xs">
        {asked?.prompt ?? interrupt.message ?? ""}
      </p>

      {loadError !== null && (
        <p className="aui-elicitation-card-error text-destructive text-xs">
          {loadError}
        </p>
      )}

      {asked !== null &&
        fields.map((field) => (
          <ElicitationField
            key={field.name}
            field={field}
            value={values[field.name]}
            own={own[field.name] ?? ""}
            onValue={(value) =>
              setValues((prev) => ({ ...prev, [field.name]: value }))
            }
            onOwn={(text) => setOwn((prev) => ({ ...prev, [field.name]: text }))}
          />
        ))}

      <div className="aui-elicitation-card-actions flex items-center gap-2">
        <Button
          size="sm"
          className="aui-elicitation-card-submit active:scale-[0.98]"
          disabled={submitting || asked === null || recorded === "resolved"}
          onClick={() => settle("resolved")}
        >
          {recorded === "resolved" ? t("elicitation.sent") : t("elicitation.send")}
        </Button>
        <Button
          size="sm"
          variant="outline"
          className="aui-elicitation-card-decline active:scale-[0.98]"
          disabled={submitting || recorded !== undefined}
          onClick={() => settle("cancelled")}
        >
          {recorded === "cancelled" ? t("elicitation.declined") : t("elicitation.decline")}
        </Button>
      </div>

      {error !== null && (
        <p className="aui-elicitation-card-error text-destructive text-xs">{error}</p>
      )}
    </div>
  );
};

/// Mounted from the tool card, by the parked call's own `toolCallId` -- the same
/// binding the approval card uses, so a card can never be drawn against a
/// neighbouring call's question.
export const ElicitationGate: FC<{ toolCallId: string }> = ({ toolCallId }) => {
  const interrupt = useAgUiInterrupts().find(
    (candidate) =>
      isElicitationInterrupt(candidate) && candidate.toolCallId === toolCallId,
  );
  if (interrupt === undefined) return null;
  return <ElicitationCard interrupt={interrupt} />;
};

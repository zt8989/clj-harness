// The human end of pre-tool approval.
//
// A run that parks a call for approval ends with RUN_FINISHED carrying
// outcome.interrupts. `useInterrupt` receives that, and `resolve` / `cancel`
// send the answer back as a spec `resume` array -- the client re-runs the same
// thread and the server replays the decision. Nothing here talks to the server
// directly, and nothing here knows what an interrupt id means.
//
// renderInChat stays at its default, so the card is published into <CopilotChat>
// rather than hand-placed: there is no UI kit in this project to hang a dialog
// on, and the chat stream is where the parked call belongs anyway.
import type { Interrupt, Message, ToolCall } from "@ag-ui/client";
import { UseAgentUpdate, useAgent, useInterrupt } from "@copilotkit/react-core/v2";
import { useEffect, useRef, type CSSProperties } from "react";

/// The interrupt reason this gate owns; any other interrupt is left alone.
const REASON = "tool-approval";

const cardStyle: CSSProperties = {
  border: "1px solid #d9d9d9",
  borderRadius: 8,
  padding: "12px 14px",
  margin: "8px 0",
  background: "#fffdf5",
  color: "#1f1f1f",
  fontSize: 14,
};

const titleStyle: CSSProperties = { fontWeight: 600, marginBottom: 6 };
const codeStyle: CSSProperties = { background: "#f0f0f0", padding: "2px 6px", borderRadius: 4 };
const argsStyle: CSSProperties = {
  margin: "6px 0 0",
  whiteSpace: "pre-wrap",
  wordBreak: "break-all",
  background: "#f7f7f7",
  padding: 8,
  borderRadius: 4,
  fontSize: 12,
};
const messageStyle: CSSProperties = { color: "#595959", marginBottom: 10 };
const rowStyle: CSSProperties = { display: "flex", gap: 8 };
const approveStyle: CSSProperties = {
  border: "1px solid #1677ff",
  background: "#1677ff",
  color: "#fff",
  padding: "6px 14px",
  borderRadius: 6,
  cursor: "pointer",
};
const vetoStyle: CSSProperties = {
  border: "1px solid #d9d9d9",
  background: "#fff",
  color: "#1f1f1f",
  padding: "6px 14px",
  borderRadius: 6,
  cursor: "pointer",
};
const noteStyle: CSSProperties = { color: "#8c8c8c", marginTop: 8, fontSize: 12 };

/// The call an interrupt is about, read from the client's own message list: the
/// tool-call frames already carry the name and the arguments, so the card can
/// show the exact command without the server echoing it back.
function toolCallFor(messages: readonly Message[], toolCallId: string | undefined): ToolCall | undefined {
  if (toolCallId === undefined) return undefined;

  for (const message of messages) {
    if (message.role !== "assistant") continue;
    for (const call of message.toolCalls ?? []) {
      if (call.id === toolCallId) return call;
    }
  }
  return undefined;
}

/// One parked call, drawn as an element. A plain function, not a component: it
/// has no state and no reason to be re-mounted on its own.
function approvalCard(
  interrupt: Interrupt,
  call: ToolCall | undefined,
  approve: () => void,
  veto: () => void,
) {
  return (
    <div style={cardStyle}>
      <div style={titleStyle}>需要你批准这次工具调用</div>
      <div style={{ marginBottom: 6 }}>
        <code style={codeStyle}>{call?.function.name ?? "tool"}</code>
        {call !== undefined && <pre style={argsStyle}>{call.function.arguments}</pre>}
      </div>
      {interrupt.message !== undefined && <div style={messageStyle}>{interrupt.message}</div>}
      <div style={rowStyle}>
        <button
          style={approveStyle}
          onClick={() => {
            approve();
          }}
        >
          批准
        </button>
        <button
          style={vetoStyle}
          onClick={() => {
            veto();
          }}
        >
          否决
        </button>
      </div>
      <div style={noteStyle}>批准则照常执行；否决则工具不执行，模型会收到一条被人工否决的工具结果并继续。</div>
    </div>
  );
}

export function ApprovalGate() {
  const { agent } = useAgent({
    agentId: "default",
    // `UseAgentUpdate` is a real TS enum on this side, so the member is a
    // property access rather than the string lookup the JS enum needed.
    updates: [UseAgentUpdate.OnRunStatusChanged],
  });

  // A run waiting on a decision is a run holding a thread on the server. If this
  // gate unmounts mid-executing the interrupt renderer goes with it and nothing
  // would ever answer, so abort. The ref keeps the cleanup reading the latest
  // status without re-firing on every flip.
  const running = useRef(false);

  useEffect(() => {
    running.current = agent.isRunning;
  }, [agent, agent.isRunning]);

  useEffect(() => {
    return () => {
      if (running.current) agent.abortRun();
    };
  }, [agent]);

  useInterrupt({
    agentId: "default",
    // Another renderer may own other interrupts; this gate answers only
    // approvals. `event.value` arrives as `any` -- it is named here as what it
    // actually is, once, at the boundary.
    enabled: (event) => {
      const value: Interrupt | undefined = event.value;
      return value?.reason === REASON;
    },
    render: ({ interrupt, resolve, cancel }) => {
      // The hook only calls render once an interrupt has arrived, but the slot
      // type still carries `null`; drawing nothing is the honest answer.
      if (interrupt === null) return <></>;

      return approvalCard(
        interrupt,
        toolCallFor(agent.messages, interrupt.toolCallId),
        () => void resolve({ decision: "approved" }),
        () => void cancel(),
      );
    },
  });

  // It renders inside CopilotChat; there is nothing to place.
  return null;
}

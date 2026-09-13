import { useAgent, useInterrupt, UseAgentUpdate } from "@copilotkit/react-core/v2";
import { useEffect, useRef } from "react";

/**
 * The human end of pre-tool approval.
 *
 * A run that parks a call for approval ends with RUN_FINISHED carrying
 * outcome.interrupts. `useInterrupt` receives that, and `resolve` / `cancel`
 * send the answer back as a spec `resume` array -- the client re-runs the same
 * thread and the server replays the decision. Nothing here talks to the server
 * directly, and nothing here knows what an interrupt id means.
 *
 * renderInChat stays at its default, so the card is published into <CopilotChat>
 * rather than hand-placed: there is no UI kit in this project to hang a dialog
 * on, and the chat stream is where the parked call belongs anyway.
 */

/** The interrupt reason this gate owns; any other interrupt is left alone. */
const REASON = "tool-approval";

type ToolCall = { id: string; function?: { name?: string; arguments?: string } };

/** The call an interrupt is about, read from the client's own message list: the
 *  tool-call frames already carry the name and the arguments, so the card can
 *  show the exact command without the server echoing it back. */
function toolCallFor(messages: unknown, toolCallId?: string): ToolCall | undefined {
  if (!toolCallId) return undefined;
  for (const message of (messages ?? []) as { toolCalls?: ToolCall[] }[]) {
    const hit = message.toolCalls?.find((tc) => tc.id === toolCallId);
    if (hit) return hit;
  }
  return undefined;
}

export function ApprovalGate() {
  const { agent } = useAgent({
    agentId: "default",
    updates: [UseAgentUpdate.OnRunStatusChanged],
  });

  // A run waiting on a decision is a run holding a thread on the server. If this
  // gate unmounts mid-executing the interrupt renderer goes with it and nothing
  // would ever answer, so abort. The ref keeps the cleanup reading the latest
  // status without re-firing on every flip.
  const runningRef = useRef(false);
  useEffect(() => {
    runningRef.current = agent.isRunning;
  }, [agent, agent.isRunning]);
  useEffect(
    () => () => {
      if (runningRef.current) agent.abortRun();
    },
    [agent],
  );

  useInterrupt({
    agentId: "default",
    // Another renderer may own other interrupts; this gate answers only approvals.
    enabled: (event) => (event?.value as { reason?: string } | undefined)?.reason === REASON,
    render: ({ interrupt, resolve, cancel }) => {
      const call = toolCallFor(agent.messages, interrupt?.toolCallId);
      return (
        <div
          style={{
            border: "1px solid #d9d9d9",
            borderRadius: 8,
            padding: "12px 14px",
            margin: "8px 0",
            background: "#fffdf5",
            color: "#1f1f1f",
            fontSize: 14,
          }}
        >
          <div style={{ fontWeight: 600, marginBottom: 6 }}>需要你批准这次工具调用</div>
          <div style={{ marginBottom: 6 }}>
            <code style={{ background: "#f0f0f0", padding: "2px 6px", borderRadius: 4 }}>
              {call?.function?.name ?? "tool"}
            </code>
            {call?.function?.arguments ? (
              <pre
                style={{
                  margin: "6px 0 0",
                  whiteSpace: "pre-wrap",
                  wordBreak: "break-all",
                  background: "#f7f7f7",
                  padding: 8,
                  borderRadius: 4,
                  fontSize: 12,
                }}
              >
                {call.function.arguments}
              </pre>
            ) : null}
          </div>
          {interrupt?.message ? (
            <div style={{ color: "#595959", marginBottom: 10 }}>{interrupt.message}</div>
          ) : null}
          <div style={{ display: "flex", gap: 8 }}>
            <button
              onClick={() => void resolve({ decision: "approved" })}
              style={{
                border: "1px solid #1677ff",
                background: "#1677ff",
                color: "#fff",
                padding: "6px 14px",
                borderRadius: 6,
                cursor: "pointer",
              }}
            >
              批准
            </button>
            <button
              onClick={() => void cancel()}
              style={{
                border: "1px solid #d9d9d9",
                background: "#fff",
                color: "#1f1f1f",
                padding: "6px 14px",
                borderRadius: 6,
                cursor: "pointer",
              }}
            >
              否决
            </button>
          </div>
          <div style={{ color: "#8c8c8c", marginTop: 8, fontSize: 12 }}>
            批准则照常执行；否决则工具不执行，模型会收到一条"被人工否决"的工具结果并继续。
          </div>
        </div>
      );
    },
  });

  return null;
}

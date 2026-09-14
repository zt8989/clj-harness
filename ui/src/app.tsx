// The page: one AG-UI agent wired straight to the harness, presented by CopilotKit.
//
// The browser talks to the harness directly -- there is no runtime in between, which is
// why the server carries CORS. One agent, registered as "default" so CopilotChat picks
// it up with no agentId.
//
// Two rendering slots are wired beyond CopilotKit's defaults:
//
// - `renderToolCalls` gets the built-in wildcard renderer so every tool call shows up
//   as a card (name, arguments, status, result). Without a registered renderer
//   CopilotChat renders NOTHING for tool calls -- the details ride the wire just fine,
//   the default renderer registry is simply empty.
// - `messageView` wraps CopilotChatMessageView solely to pass it our reasoning
//   component. CopilotChat does NOT forward a reasoningMessage prop down to the
//   message view (the only slot it forwards is messageView itself), so a bare
//   reasoningMessage prop here ends up spread onto a div and React rejects it. The
//   wrapper is the one seam where the fine-grained slots are reachable.
//
// Above the chat sit the project panel (the session's project-directory binding)
// and the session panel (the conversations the harness log directory holds).
// The thread id comes off the agent itself -- the agent mints one per conversation
// and holds it, so a new conversation after a stop reads as a fresh, unbound thread.
//
// Restoring a session (ticket 06) is deliberately AGENT-ONLY: the rebuilt thread
// id and message list are written onto the agent, and nothing else is touched.
// That works because AbstractAgent builds its RunAgentInput from its own state
// (prepareRunAgentInput: threadId + messages off the agent), so the next input
// continues the restored thread as an ordinary AG-UI run. The CopilotKit-level
// explicit-thread path (setActiveThreadId) is avoided on purpose -- it drags in
// connectAgent handshakes and message-clearing rules that a client talking
// straight to the harness has no use for.
import { HttpAgent, type AbstractAgent, type Message } from "@ag-ui/client";
import {
  CopilotChat,
  CopilotChatMessageView,
  CopilotKit,
  UseAgentUpdate,
  WildcardToolCallRender,
  useAgent,
  type CopilotChatMessageViewProps,
} from "@copilotkit/react-core/v2";
import { useEffect, useState, type CSSProperties } from "react";

import { ApprovalGate } from "./approval-gate";
import { ReasoningMessage } from "./reasoning-message";

const agent = new HttpAgent({ url: "http://localhost:8080/" });

/// The management edge lives on the same origin as the AG-UI endpoint.
const HARNESS_URL = "http://localhost:8080";

/// `renderToolCalls` must be a stable array (CopilotKit warns on per-render
/// churn), so it is built once here, not inside the component.
const TOOL_RENDERERS = [WildcardToolCallRender];

// ------------------------------------------------------------------ json edges
//
// Every reply below arrives as `JSON.parse` output, which is `unknown` until it is
// named. These three readers are that naming step; nothing else in this file casts.

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function readString(source: Record<string, unknown>, key: string): string | null {
  const value = source[key];
  return typeof value === "string" ? value : null;
}

/// The server reports refusals as `{error}`; a network failure has no body at all.
function readError(data: unknown, fallback: string): string {
  return (isRecord(data) ? readString(data, "error") : null) ?? fallback;
}

/// A thrown value is not necessarily an Error; a non-Error has no `.message` to read.
function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

// ---------------------------------------------------------------- project panel

/// Styles are plain CSSProperties objects: React camel-cases them for us.
const panelStyle: CSSProperties = {
  borderBottom: "1px solid #e8e8e8",
  padding: "10px 16px",
  display: "flex",
  gap: 8,
  flexWrap: "wrap",
  alignItems: "center",
  fontSize: 13,
  color: "#1f1f1f",
  background: "#fafafa",
};

const mutedStyle: CSSProperties = { color: "#8c8c8c" };

const inputStyle: CSSProperties = {
  flex: 1,
  minWidth: 240,
  border: "1px solid #d9d9d9",
  borderRadius: 6,
  padding: "5px 8px",
  fontSize: 13,
};

const bindStyle: CSSProperties = {
  border: "1px solid #1677ff",
  background: "#1677ff",
  color: "#fff",
  padding: "5px 12px",
  borderRadius: 6,
  cursor: "pointer",
};

const errorStyle: CSSProperties = { color: "#cf1322", flexBasis: "100%" };

/// A flex row whose children act as the PARENT's flex items: `display: contents`
/// drops the box and leaves the controls as siblings of the label, which is what
/// a fragment would do. It is a real element, so it cannot go missing the way a
/// fragment expression can (below).
const controlsStyle: CSSProperties = { display: "contents" };

/// Shared by both panels: the quiet button (session 刷新/新建会话, project
/// 选择文件夹…). Defined here because the project panel uses it first.
const ghostStyle: CSSProperties = {
  border: "1px solid #d9d9d9",
  background: "#fff",
  color: "#1677ff",
  padding: "2px 10px",
  borderRadius: 6,
  cursor: "pointer",
  fontSize: 12,
};

/// GET /api/project for THREAD-ID. onOk receives the dir string (null = the
/// unbound answer, not an error); onError receives a message string.
async function fetchBinding(
  threadId: string,
  onOk: (dir: string | null) => void,
  onError: (message: string) => void,
): Promise<void> {
  try {
    const response = await fetch(`${HARNESS_URL}/api/project?threadId=${encodeURIComponent(threadId)}`);
    const data: unknown = await response.json();
    if (response.ok) {
      onOk(isRecord(data) ? readString(data, "dir") : null);
    } else {
      onError(readError(data, "读取绑定失败"));
    }
  } catch (error) {
    onError(messageOf(error));
  }
}

/// POST /api/project {threadId, dir}. onOk receives the ABSOLUTE path the
/// server stored; onError the validation message (no such directory, ...).
async function bindDir(
  threadId: string,
  dir: string,
  onOk: (dir: string | null) => void,
  onError: (message: string) => void,
): Promise<void> {
  try {
    const response = await fetch(`${HARNESS_URL}/api/project`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ threadId, dir }),
    });
    const data: unknown = await response.json();
    if (response.ok) {
      onOk(isRecord(data) ? readString(data, "dir") : null);
    } else {
      onError(readError(data, "绑定失败"));
    }
  } catch (error) {
    onError(messageOf(error));
  }
}

/// POST /api/project/pick -- opens the OS folder dialog on the machine the
/// harness runs on and answers the chosen absolute path, or null when the human
/// cancels. The browser cannot supply this itself: a web file input hands back a
/// File with no real location, so the dialog has to belong to the process.
///
/// Nothing binds here. The path lands in the input for the human to see and
/// confirm, so picking a folder is a way to FILL the field, not a second way to
/// mutate a binding.
async function pickDir(
  onOk: (dir: string | null) => void,
  onError: (message: string) => void,
): Promise<void> {
  try {
    const response = await fetch(`${HARNESS_URL}/api/project/pick`, { method: "POST" });
    const data: unknown = await response.json();
    if (response.ok) {
      onOk(isRecord(data) ? readString(data, "dir") : null);
    } else {
      onError(readError(data, "打开目录选择器失败"));
    }
  } catch (error) {
    onError(messageOf(error));
  }
}

/// One row above the chat: the thread's bound project directory, and the ways to
/// bind one. The thread id is read off the AGENT -- an empty id means no thread
/// yet, so there is nothing to bind and the panel says so.
///
/// Binding is one route with two ways to fill it: type a path, or pick one from
/// the native dialog. Both end at the same POST, so the padlock -- one route that
/// mutates a binding -- stays a single route.
function ProjectPanel() {
  // Named `panelAgent` rather than `agent`: it is the module-level singleton
  // coming back out of the registry, and shadowing the name would hide that.
  const { agent: panelAgent } = useAgent({
    agentId: "default",
    // OnMessagesChanged so a session restore (`setMessages`) re-renders the panel
    // and re-reads the thread binding.
    updates: [UseAgentUpdate.OnRunStatusChanged, UseAgentUpdate.OnMessagesChanged],
  });
  const threadId = panelAgent.threadId;

  const [bound, setBound] = useState<string | null>(null);
  const [path, setPath] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [picking, setPicking] = useState(false);

  // A new threadId (first run, or a fresh conversation after a stop) is a
  // different session with its own binding -- re-read, never guess.
  useEffect(() => {
    setError(null);
    if (threadId) {
      void fetchBinding(threadId, setBound, setError);
    } else {
      setBound(null);
    }
  }, [threadId]);

  // One bind, from either route. The reply carries the ABSOLUTE path the
  // server stored, so the display is set from the answer rather than from a
  // re-read -- and an in-progress path that does not match is never shown.
  function bind(): void {
    if (!path) {
      setError("请先输入项目目录路径，或点「选择文件夹…」");
      return;
    }
    void bindDir(
      threadId,
      path,
      (dir) => {
        setError(null);
        setBound(dir);
        setPath("");
      },
      setError,
    );
  }

  function pick(): void {
    setPicking(true);
    setError(null);
    void pickDir(
      (dir) => {
        setPicking(false);
        if (dir) setPath(dir);
        else setError("已取消选择");
      },
      (message) => {
        setPicking(false);
        setError(message);
      },
    );
  }

  // The wrapper is what gives the input and its buttons a single child position;
  // `display: contents` keeps them laid out as the panel's own flex items. (The
  // ClojureScript original needed it because `when` returns only its last form,
  // silently dropping the input; keeping the wrapper keeps the layout identical.)
  return (
    <div style={panelStyle}>
      <span style={{ fontWeight: 600 }}>项目目录</span>
      {threadId ? (
        <span>{bound ?? "未绑定（相对路径按进程工作目录解析）"}</span>
      ) : (
        <span style={mutedStyle}>会话开始后可绑定项目目录</span>
      )}
      {threadId ? (
        <div style={controlsStyle}>
          <input
            style={inputStyle}
            placeholder="项目目录的绝对路径，例如 /Users/me/my-project"
            value={path}
            onChange={(event) => {
              setPath(event.target.value);
            }}
          />
          <button
            style={bindStyle}
            disabled={picking}
            onClick={() => {
              bind();
            }}
          >
            绑定
          </button>
          <button
            style={ghostStyle}
            disabled={picking}
            onClick={() => {
              pick();
            }}
          >
            {picking ? "选择中…" : "选择文件夹…"}
          </button>
        </div>
      ) : null}
      {error !== null && <span style={errorStyle}>{error}</span>}
    </div>
  );
}

// ---------------------------------------------------------------- session panel

const rowStyle: CSSProperties = {
  display: "flex",
  gap: 10,
  alignItems: "center",
  fontSize: 12,
  flexBasis: "100%",
};

function fmtBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1048576) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1048576).toFixed(1)} MB`;
}

interface ThreadRow {
  threadId: string;
  lastActivity: number;
  bytes: number;
}

/// The log directory's own summary of a conversation; a row that does not match
/// is dropped rather than rendered as blanks.
function isThreadRow(value: unknown): value is ThreadRow {
  return (
    isRecord(value) &&
    typeof value.threadId === "string" &&
    typeof value.lastActivity === "number" &&
    typeof value.bytes === "number"
  );
}

/// GET /api/threads -- onOk receives the rows (a JSON array at the top level, so
/// there is no field to unwrap); onError a message string.
async function fetchThreads(
  onOk: (rows: ThreadRow[]) => void,
  onError: (message: string) => void,
): Promise<void> {
  try {
    const response = await fetch(`${HARNESS_URL}/api/threads`);
    const data: unknown = await response.json();
    if (response.ok) {
      onOk(Array.isArray(data) ? data.filter(isThreadRow) : []);
    } else {
      onError(readError(data, "读取会话列表失败"));
    }
  } catch (error) {
    onError(messageOf(error));
  }
}

/// POST /api/threads/<stem>/rebuild, then hand the conversation back to the
/// CLIENT: the rebuilt thread id and message list both land on the AGENT, which
/// is exactly what the next run reads. A 400 (truncated or corrupt log) carries
/// the server's named reason to onError -- the panel stays alive either way.
async function restoreThread(
  target: AbstractAgent,
  threadId: string,
  onOk: (threadId: string) => void,
  onError: (message: string) => void,
): Promise<void> {
  try {
    const response = await fetch(`${HARNESS_URL}/api/threads/${encodeURIComponent(threadId)}/rebuild`, {
      method: "POST",
    });
    const data: unknown = await response.json();

    if (!response.ok) {
      onError(readError(data, "恢复失败"));
      return;
    }

    // The rebuild endpoint's contract: a thread id plus the replayed history.
    // Both are checked for presence before anything is written onto the agent,
    // so a malformed reply reports the same way a refusal does rather than
    // leaving the agent holding `undefined`.
    const rebuiltId = isRecord(data) ? readString(data, "threadId") : null;
    const rebuiltMessages = isRecord(data) && Array.isArray(data.messages) ? (data.messages as Message[]) : null;
    if (rebuiltId === null || rebuiltMessages === null) {
      onError("恢复失败");
      return;
    }

    target.threadId = rebuiltId;
    target.setMessages(rebuiltMessages);
    onOk(rebuiltId);
  } catch (error) {
    onError(messageOf(error));
  }
}

/// The conversations the log directory holds, and the way back into one.
///
/// 恢复 hands the rebuilt history to the agent (thread id + messages) -- the
/// client re-owns the conversation, and the next input continues it as an
/// ordinary AG-UI run that appends to the SAME log. A refused rebuild shows the
/// named reason inline while every other row stays clickable. 新建会话 starts a
/// fresh thread the same agent-owned way: a new id, empty messages.
///
/// Subscribed to OnMessagesChanged as well as OnRunStatusChanged so the panel
/// re-renders when a restore lands (setMessages is a message change, not a run
/// status change).
function SessionPanel() {
  const { agent: panelAgent } = useAgent({
    agentId: "default",
    updates: [UseAgentUpdate.OnRunStatusChanged, UseAgentUpdate.OnMessagesChanged],
  });

  const [sessions, setSessions] = useState<ThreadRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const current = panelAgent.threadId;

  function refresh(): void {
    void fetchThreads((rows) => {
      setSessions(rows);
      setError(null);
    }, setError);
  }

  function restore(threadId: string): void {
    if (panelAgent.isRunning) {
      setError("有正在进行的运行，等它结束再恢复。");
      return;
    }

    setBusy(threadId);
    void restoreThread(
      panelAgent,
      threadId,
      () => {
        setBusy(null);
        setError(null);
        // the rebuild just appended its audit line to the log -- re-list so sizes
        // and order stay honest
        void fetchThreads(setSessions, setError);
      },
      (message) => {
        setBusy(null);
        setError(message);
      },
    );
  }

  function newSession(): void {
    panelAgent.threadId = crypto.randomUUID();
    panelAgent.setMessages([]);
    setError(null);
  }

  useEffect(() => {
    refresh();
    // Once, on mount. `refresh` is rebuilt every render, so listing it here would
    // re-list the directory on every keystroke elsewhere on the page.
  }, []);

  return (
    <div style={panelStyle}>
      <span style={{ fontWeight: 600 }}>会话</span>
      <button
        style={ghostStyle}
        onClick={() => {
          refresh();
        }}
      >
        刷新
      </button>
      <button
        style={ghostStyle}
        onClick={() => {
          newSession();
        }}
      >
        新建会话
      </button>
      <span style={mutedStyle}>恢复后历史归本页持有，续聊照常走 AG-UI</span>
      {error !== null && <span style={errorStyle}>{error}</span>}
      {sessions === null ? null : sessions.length === 0 ? (
        <span style={mutedStyle}>日志目录还没有会话</span>
      ) : (
        sessions.map((session) => (
          <span key={session.threadId} style={rowStyle}>
            <span style={session.threadId === current ? { color: "#1677ff", fontWeight: 600 } : mutedStyle}>
              {session.threadId}
            </span>
            <span style={mutedStyle}>
              {`${new Date(session.lastActivity).toLocaleString()} · ${fmtBytes(session.bytes)}`}
            </span>
            <button
              style={ghostStyle}
              disabled={busy === session.threadId}
              onClick={() => {
                restore(session.threadId);
              }}
            >
              {busy === session.threadId ? "恢复中…" : "恢复"}
            </button>
          </span>
        ))
      )}
    </div>
  );
}

// --------------------------------------------------------------------- the page

/// CopilotChat's messageView slot. CopilotKit hands down the message view's own
/// props -- pass them through untouched and add our reasoning slot. The wrapper
/// carries `Cursor` because the slot type is `typeof CopilotChatMessageView` and
/// that static is structurally required; it is the same component the default
/// view renders, so re-publishing it is accurate rather than a cast.
function MessageViewBase(props: CopilotChatMessageViewProps) {
  return <CopilotChatMessageView {...props} reasoningMessage={ReasoningMessage} />;
}

const MessageView = Object.assign(MessageViewBase, {
  Cursor: CopilotChatMessageView.Cursor,
});

/// enableInspector false: that dev tool is a fixed-position overlay pinned to the
/// viewport's top-right corner, which is exactly where the panels above the chat
/// live. It swallows clicks aimed at whatever it floats over -- the
/// 选择文件夹… button, when the window has room for one -- and this app never
/// uses it. Off, rather than moved: it is CopilotKit's, not ours to place.
export function App() {
  return (
    <CopilotKit
      agents__unsafe_dev_only={{ default: agent }}
      renderToolCalls={TOOL_RENDERERS}
      enableInspector={false}
    >
      {/* Column layout: the project panel takes its natural height, the chat
          takes the rest. ApprovalGate renders nothing itself -- it MOUNTS here
          so its useInterrupt registers inside the CopilotKit context, and the
          parked-call card is published into CopilotChat from there. */}
      <div style={{ height: "100vh", display: "flex", flexDirection: "column" }}>
        <ApprovalGate />
        <ProjectPanel />
        <SessionPanel />
        <div style={{ flex: 1, minHeight: 0 }}>
          <CopilotChat messageView={MessageView} />
        </div>
      </div>
    </CopilotKit>
  );
}

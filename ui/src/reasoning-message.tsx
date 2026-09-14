// The reasoning slot of CopilotChat, replaced for one behavior: the default
// component collapses the content to "Thought for Xs" the moment streaming
// ends, and the content is only visible again by clicking. Here the content
// STAYS EXPANDED after the run finishes; manual collapse still works, and a
// new thinking phase re-opens it exactly like the default does.
//
// Everything else mirrors the default component: the same static Header /
// Toggle / Content children off CopilotChatReasoningMessage, the same
// "Thinking…" label while streaming, the same elapsed-time format once it stops.
//
// The statics are re-published onto this component rather than re-implemented:
// the slot type CopilotChatMessageView declares for `reasoningMessage` is
// `typeof CopilotChatReasoningMessage`, which structurally REQUIRES Header,
// Content and Toggle. They are the same three components the default renders,
// so publishing them is accurate, not a cast.
import {
  CopilotChatReasoningMessage,
  type CopilotChatReasoningMessageProps,
} from "@copilotkit/react-core/v2";
import { useEffect, useRef, useState } from "react";

/// Same shape as the default's formatter: "a few seconds" below one second,
/// whole seconds below a minute, then minutes-and-seconds.
function formatDuration(seconds: number): string {
  const total = Math.round(seconds);
  if (total < 1) return "a few seconds";
  if (total < 60) return `${total} seconds`;

  const minutes = Math.floor(total / 60);
  const remainder = total % 60;
  if (remainder === 0) return `${minutes} ${minutes === 1 ? "minute" : "minutes"}`;
  return `${minutes}m ${remainder}s`;
}

function ReasoningMessageBase({ message, messages, isRunning }: CopilotChatReasoningMessageProps) {
  // `messages` is the whole conversation; "latest" is what makes this the
  // message currently being streamed. Absent means "not told", which reads as
  // not-latest -- the same answer the original gave.
  const latest = message.id === messages?.[messages.length - 1]?.id;
  const streaming = Boolean(isRunning) && latest;

  // `content` is declared as a required string on ReasoningMessage, but the value
  // here is whatever the adapter has assembled so far: widened to `undefined` so
  // the absent case is handled rather than crashing the card mid-stream. The
  // ClojureScript original guarded the same way (`(string? content)`).
  const content: string | undefined = message.content;
  const hasContent = typeof content === "string" && content.length > 0;

  const [open, setOpen] = useState(true);
  const start = useRef<number | null>(null);
  const [elapsed, setElapsed] = useState(0);

  // A new thinking phase opens up, like the default. The END of one does not
  // close it -- that is the whole point of this component: the close path here
  // is manual only (the Header's onClick).
  useEffect(() => {
    if (streaming) setOpen(true);
  }, [streaming]);

  // Elapsed timer: starts with streaming, freezes when it stops.
  useEffect(() => {
    if (!streaming) {
      if (start.current !== null) setElapsed((Date.now() - start.current) / 1000);
      return;
    }

    let from = start.current;
    if (from === null) {
      from = Date.now();
      start.current = from;
    }
    const timer = window.setInterval(() => setElapsed((Date.now() - from) / 1000), 1000);
    return () => window.clearInterval(timer);
  }, [streaming]);

  return (
    <div className="cpk:my-1" data-message-id={message.id}>
      <ReasoningMessage.Header
        isOpen={open}
        label={streaming ? "Thinking…" : `Thought for ${formatDuration(elapsed)}`}
        hasContent={hasContent}
        isStreaming={streaming}
        onClick={
          hasContent
            ? () => {
                setOpen((wasOpen) => !wasOpen);
              }
            : undefined
        }
      />
      <ReasoningMessage.Toggle isOpen={open}>
        <ReasoningMessage.Content isStreaming={streaming} hasContent={hasContent}>
          {content}
        </ReasoningMessage.Content>
      </ReasoningMessage.Toggle>
    </div>
  );
}

export const ReasoningMessage = Object.assign(ReasoningMessageBase, {
  Header: CopilotChatReasoningMessage.Header,
  Content: CopilotChatReasoningMessage.Content,
  Toggle: CopilotChatReasoningMessage.Toggle,
});

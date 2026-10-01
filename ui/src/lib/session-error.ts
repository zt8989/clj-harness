// THE FAILURE A SESSION ITSELF RAISED, in the two pieces the screen draws: the SHORT
// sentence the card says while it is folded, and the whole error as JSON for the detail
// underneath it.
//
// WHY THE TWO ARE BUILT HERE AND NOT IN THE COMPONENT, and both halves of that are real:
//
//   * THE SHORT SENTENCE IS NOT THE ERROR'S OWN MESSAGE. A vendor's refusal reaches this
//     page as `harness.kernel.llm` words it -- `HTTP 500: {"error":{"message":..,"type":..}}`
//     -- which is a status, a colon, and a wall of JSON. The card is ONE line above the
//     composer, so what it says has to be the SENTENCE INSIDE that (owner, 2026-10-01):
//     `HTTP 500: the vendor exploded mid-turn`. The JSON wall is still one click away, in
//     the detail, which is what the detail is for.
//   * AND `JSON.stringify(error)` IS NOT THE DETAIL. An Error's `name`, `message` and
//     `stack` are NOT own enumerable properties, so the obvious call answers `{}` and the
//     "full JSON" would be empty for every failure.
//
// AND IT IS BUILT AT THE MOMENT OF FAILURE, not when the detail is opened: the value a
// component holds is a plain `{ message, detail }`, which is what keeps an `Error` object
// (and whatever it holds) out of React state and out of a re-render.
export type SessionFailure = {
  /// The SHORT sentence, drawn on the folded line -- the vendor's own message where the
  /// error carried one in JSON, and the error's message as it stands otherwise.
  message: string;
  /// The whole error as pretty JSON, drawn when the detail is opened.
  detail: string;
};

/// THE ONE READER OF AN ERROR A SESSION RAISED, for both of the two places one arrives
/// (`app.tsx`): the runtime's `onError` callback hands an `Error`, and a rejected
/// promise (the registration a minted session gets before its first run) hands
/// anything at all -- the second is wrapped here rather than at the call site so that
/// "what a failure looks like" is one thing.
export function sessionFailureOf(error: unknown): SessionFailure {
  const raised = error instanceof Error ? error : new Error(String(error));
  return { message: shortMessage(raised.message), detail: errorJson(raised) };
}

/// THE ONE LINE THE CARD SAYS, cut down from what the error carried. A message that is not
/// the `<prefix>: <json>` shape is left exactly as it stands: most failures are already a
/// sentence, and re-wording one would be this file having an opinion about somebody else's
/// words.
function shortMessage(raw: string): string {
  const split = splitPrefix(raw);
  if (split === null) return raw;
  const inner = innerMessage(parseJson(split.body));
  return inner === null ? raw : `${split.prefix}: ${inner}`;
}

/// `<prefix>: <rest>`, the shape `harness.kernel.llm` words a vendor's refusal in
/// (`HTTP 500: {..}`). THE PREFIX IS KEPT -- a status code is the one thing about a
/// refusal a person can act on without opening anything, which is why the folded line
/// reads `HTTP 500: <sentence>` rather than the sentence alone.
function splitPrefix(raw: string): { prefix: string; body: string } | null {
  const at = raw.indexOf(": ");
  if (at <= 0) return null;
  return { prefix: raw.slice(0, at), body: raw.slice(at + 2).trim() };
}

/// The parsed body, or null when there is nothing to parse. Objects and arrays only: a
/// sentence that merely follows a colon is the error's own wording, and is left alone.
function parseJson(text: string): unknown {
  if (!text.startsWith("{") && !text.startsWith("[")) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/// THE SENTENCE INSIDE A REFUSAL BODY, in the three shapes that arrive: the
/// OpenAI-shaped `{"error": {"message": ..}}` (what a provider gateway sends), a flat
/// `{"error": ".."}`, and a bare `{"message": ".."}`. Anything else answers null and the
/// caller falls back to the error's own message -- there is no fourth shape to guess at.
function innerMessage(body: unknown): string | null {
  if (body === null || typeof body !== "object" || Array.isArray(body)) return null;
  const record = body as Record<string, unknown>;
  const nested = record.error;
  if (nested !== null && typeof nested === "object" && !Array.isArray(nested)) {
    const message = (nested as Record<string, unknown>).message;
    if (isSentence(message)) return message.trim();
  }
  if (isSentence(nested)) return nested.trim();
  if (isSentence(record.message)) return record.message.trim();
  return null;
}

function isSentence(value: unknown): value is string {
  return typeof value === "string" && value.trim() !== "";
}

/// THE ERROR AS JSON, NAME AND STACK INCLUDED. The replacer is what does the two jobs a
/// plain `stringify` cannot: it EXPANDS an `Error` into the fields that are not own
/// properties (and does the same for a nested `cause`), and it NAMES a cycle instead of
/// throwing -- serialising a failure must never be the second failure.
function errorJson(error: Error): string {
  const seen = new WeakSet<object>();
  try {
    return JSON.stringify(
      error,
      (_key, value: unknown) => {
        if (typeof value === "object" && value !== null) {
          if (seen.has(value)) return "[circular]";
          seen.add(value);
        }
        return value instanceof Error ? errorShape(value) : value;
      },
      2,
    );
  } catch {
    // A value that cannot be serialised at all (a getter that throws, a BigInt in a
    // field) still leaves the reader the sentence rather than an empty box.
    return `${error.name}: ${error.message}`;
  }
}

/// WHAT AN ERROR LOOKS LIKE ONCE IT IS A VALUE: the three fields it always has, plus the
/// own properties a vendor hung on it (`code`, `status` -- exactly what somebody opening
/// the detail wants) and its `cause`, which the replacer expands in turn.
function errorShape(error: Error): Record<string, unknown> {
  return {
    name: error.name,
    message: error.message,
    ...(error.stack === undefined ? {} : { stack: error.stack }),
    ...ownProperties(error),
    ...(error.cause === undefined ? {} : { cause: error.cause }),
  };
}

/// The error's own enumerable properties, minus the four this module already places.
/// `Object.keys` on an `Error` is the ordinary answer (`[]`) for an error somebody just
/// constructed, and is where a vendor's `code` shows up.
function ownProperties(error: Error): Record<string, unknown> {
  const extra: Record<string, unknown> = {};
  for (const key of Object.keys(error)) {
    if (key === "name" || key === "message" || key === "stack" || key === "cause") continue;
    extra[key] = (error as unknown as Record<string, unknown>)[key];
  }
  return extra;
}

// The composer's attachment capability: the adapter, the gate in front of it, and
// the one fact the gate has to know.
//
// ------------------------------------------- this file IS the capability flag
//
// `capabilities.attachments` is `!!adapters.attachments` -- upstream's own
// expression, in the external-store runtime this page is built on -- and every
// path into the composer asks that flag FIRST: the input's paste handler, the
// dropzone's drop handler, and `+`. Hand the runtime no adapter and all three go
// quiet in the same way: the paste is never consumed, the drop is refused, `+`
// opens a file dialog and nothing lands. That is why this adapter is not a
// feature of the composer but the thing that turns it on at all.
//
// -------------------------------------------------- images only, and no upload
//
// The kit's own adapter rather than a hand-rolled one, because its two methods are
// exactly the two this interface needs: `add` KEEPS THE FILE (which is what draws
// the thumbnail while the attachment is still a draft) and `send` reads those
// bytes into a data URL. THE DATA URL IS THE WIRE, not a stop on the way to one:
// the AG-UI client turns an attachment's image part back into
// `{type: "image", source: {type: "data", value: <base64>, mimeType}}` (upstream's
// `buildUserContent`), and the server hands the vendor
// `{type: "image_url", image_url: {url: "data:image/png;base64,..."}}`
// (harness.edge.ag-ui/provider-image-url). Nothing is uploaded anywhere: there is
// no endpoint for the bytes, and they ride in the message.
//
// `image/*` is the accept string, so a pasted or dropped non-image is refused by
// the composer before this adapter is ever asked. The two reasons a perfectly good
// image may still be turned away -- a model that does not take images, a file
// above the cap -- are `attachment-rules.ts`'s, and they are answered HERE,
// inside `add`, for one reason: `add` is the single place all three ways in meet
// (paste, drop, `+`), so a gate here is the only gate that cannot be walked
// around by the next one of them.
//
// ------------------------------------------------- refusing without losing work
//
// A refused file THROWS, which is upstream's own contract for `add` -- all three
// callers catch their own rejection and the composer runtime emits
// `composer.attachmentAddError`. Nothing has been added when it throws, so the
// attachment row is untouched, and nothing in the composer's text is either: the
// discipline is that a refusal costs the person nothing they had already put on
// the page. The sentence travels back out on the rejection AND into the store
// below, which is what lets the composer draw it.
import { SimpleImageAttachmentAdapter } from "@assistant-ui/react";

import { refusalFor } from "@/lib/attachment-rules";

/// What the guard knows about the session's model, and what it last refused.
///
/// `input` ABSENT and `input: []` are different answers and the rule reads them
/// differently -- see attachment-rules.ts's header. `model` is only ever used to
/// name the model in the refusal sentence.
export type AttachmentGuard = {
  model?: string;
  input?: string[];
  /// The sentence for the last file this composer turned away, or null. Cleared
  /// by the next file that is accepted, and by the model's answer arriving (a
  /// refusal about the OLD model must not outlive the change that fixed it).
  refusal: string | null;
};

/// Answering /api/model, which is the endpoint that exists for this question
/// (`model-get`'s own docstring: "for a client deciding whether to offer an image
/// picker"). Both fields are optional because both may be absent.
///
/// The shape is spelled out rather than imported from lib/composer.ts so this
/// module depends on nothing that talks to the network: the store is written by
/// whoever holds an answer, and needs no opinion about where it came from.
export type ModelAnswer = { model?: string; input?: string[] };

let guard: AttachmentGuard = { refusal: null };
const listeners = new Set<() => void>();
const emit = (): void => {
  for (const listener of listeners) listener();
};
const set = (next: Partial<AttachmentGuard>): void => {
  guard = { ...guard, ...next };
  emit();
};

/// The guard, as a store a component can subscribe to. It is a plain
/// subscribe/getSnapshot pair rather than anything React's, so this module stays
/// free of React and the copied element's neighbours can use it without importing
/// a component.
///
/// WHY A STORE AT ALL, when the composer's own state is a hook away: the adapter
/// is called from upstream's event handlers, which are ordinary functions with no
/// React tree in reach. The store is the seam between them -- the adapter writes,
/// the interface reads.
export const attachmentGuard = {
  subscribe: (listener: () => void): (() => void) => {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },
  /// Immutable and replaced on every write, which is what `useSyncExternalStore`
  /// requires: the same object identity means "nothing changed".
  current: (): AttachmentGuard => guard,
  /// The session's model, as /api/model just answered it. The refusal is dropped
  /// on the way in: a sentence about the model that has just been replaced would
  /// be pointing at a model the session is no longer on.
  noteModel: (answer: ModelAnswer): void => {
    set({ model: answer.model, input: answer.input, refusal: null });
  },
};

/// The kit's image adapter with one gate in front of `add`. `send` is inherited
/// untouched: by the time an attachment is sent the gate has already spoken, and a
/// second opinion there could only disagree with the first.
class GuardedImageAttachments extends SimpleImageAttachmentAdapter {
  public override async add({ file }: { file: File }) {
    const refusal = refusalFor(file, guard.input, guard.model);
    if (refusal !== null) {
      set({ refusal });
      throw new Error(refusal);
    }
    set({ refusal: null });
    return super.add({ file });
  }
}

/// One instance for the page. It holds a File per pending attachment and no other
/// state, so sharing it across threads is not a cache: the attachments themselves
/// belong to the composer, and the composer belongs to the thread in front of you.
/// The gate's own state is not per-thread for the same reason the adapter is not:
/// it is rewritten from `GET /api/model` the moment the session changes.
export const imageAttachments = new GuardedImageAttachments();

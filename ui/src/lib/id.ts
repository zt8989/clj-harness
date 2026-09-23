// THE ID THE PAGE MINTS FOR ITSELF, in a browser that may not have `crypto.randomUUID`.
//
// ------------------------------------------------------------- what went wrong
//
// `crypto.randomUUID` IS NOT A UNIVERSAL FUNCTION. It is defined only in a SECURE
// CONTEXT -- https, or localhost -- and the phone that reported this was reading the
// dev server over `http://192.168.x.x:<port>`, which is neither. There the page threw
// `TypeError: crypto.randomUUID is not a function` before it drew anything, and the
// desktop that wrote the call never saw it: on this machine 127.0.0.1 IS secure, so
// the same bundle is fine on the machine it was built on. That is exactly the class of
// bug a LAN address is needed to find.
//
// WHAT IS STILL AVAILABLE THERE is the rest of `crypto`: `getRandomValues` is NOT
// restricted to secure contexts, and it is the same CSPRNG. So the fix is a fallback
// chain rather than a new dependency -- `randomUUID` when the browser has it, sixteen
// bytes out of `getRandomValues` when it does not, and `Math.random` only for an
// environment with no WebCrypto at all (there the id is still a name nothing else on
// the page will repeat, which is all these ids were ever asked to be; see below).
//
// ------------------------------------------------------------- what these ids are
//
// A NAME THIS PAGE GIVES A THING BEFORE ANYBODY ELSE HAS NAMED IT -- a session before
// its first send (`app.tsx`'s `pendingBinds`, `components/sidebar.tsx`'s
// `onShowFresh`), a message that arrived without one. It is not a secret and not a
// token: nothing is authenticated by it, and the server has its own
// (`harness.kernel.tools`'s `java.util.UUID/randomUUID`). What it has to be is unique
// among the ids this page is holding at once, and a version-4 UUID is the shape the
// rest of the conversation already speaks -- the one a `randomUUID` would have made.
//
// WHY IT IMPORTS NOTHING, like `lib/relative-time.ts` and `lib/session-title.ts`: the
// SOURCE of the randomness is a parameter, so a suite can pin both branches with a
// literal byte array instead of a browser (`test/suites/id.ts`). What that suite cannot
// see -- that a phone on a LAN address gets a working page out of it -- is the browser
// walkthrough's half, and this module exists because that half was missed.

/// The two functions this module will use off `Crypto`, both OPTIONAL: that is the
/// whole point of the module. `randomUUID` is the secure-context one a phone on a LAN
/// address does not have; `getRandomValues` is the one it does.
export type IdSource = {
  randomUUID?: (() => string) | undefined;
  getRandomValues?: ((bytes: Uint8Array) => Uint8Array) | undefined;
};

/// `0123456789abcdef`, indexed by a nibble. Above the functions because it is the
/// alphabet they both spell in.
const HEX = "0123456789abcdef";

/// SIXTEEN BYTES AS A VERSION-4 UUID: `xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx`, lower
/// case, where `y` carries the RFC 4122 variant bits. The two nibbles this OVERWRITES
/// are the reason the shape is rebuilt here rather than left as the bytes happened to
/// be: a UUID that is not version 4 is a legal string in a field that only wants a
/// name, and it would be wrong in a way nothing on screen reports.
///
/// IT NEEDS EXACTLY SIXTEEN BYTES and reads no further; a shorter array reads as
/// zeroes rather than throwing, because the caller that handed it a short one has a
/// worse problem than this sentence.
export function uuidFromBytes(bytes: Uint8Array): string {
  const digits: string[] = [];
  for (let i = 0; i < 16; i += 1) {
    const byte = bytes[i] & 0xff;
    digits.push(HEX[byte >> 4], HEX[byte & 0x0f]);
  }
  // THE VERSION NIBBLE, then the VARIANT one. `digits[12]` is the first nibble of the
  // third group; `digits[16]` is the first nibble of the fourth, and its top two bits
  // become `10` (the `& 0x3` keeps what was there under the `| 0x8`).
  digits[12] = "4";
  digits[16] = HEX[(parseInt(digits[16], 16) & 0x3) | 0x8];
  return [
    digits.slice(0, 8).join(""),
    digits.slice(8, 12).join(""),
    digits.slice(12, 16).join(""),
    digits.slice(16, 20).join(""),
    digits.slice(20, 32).join(""),
  ].join("-");
}

/// A UUID FOR THIS PAGE, off `source` -- `globalThis.crypto` unless a caller says
/// otherwise, which only a test does.
///
/// THE CHAIN IS THE WHOLE FIX, and each step is `typeof === "function"` rather than a
/// truthiness test: the reported failure is a property that is THERE and not callable,
/// which `if (crypto.randomUUID)` would have walked straight into.
///
/// A SOURCE WITH NEITHER is filled from `Math.random` rather than refused. It is the
/// weak branch, and it is deliberate: the alternative is the page not starting at all
/// in an environment ancient enough to lack `getRandomValues`, which is a worse
/// outcome for an id that only has to be a name (see the header). No browser this page
/// supports takes it -- it is the floor, not the path.
export function newId(source: IdSource | undefined = globalThis.crypto as IdSource | undefined): string {
  if (typeof source?.randomUUID === "function") return source.randomUUID();
  const bytes = new Uint8Array(16);
  if (typeof source?.getRandomValues === "function") {
    source.getRandomValues(bytes);
  } else {
    for (let i = 0; i < 16; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  }
  return uuidFromBytes(bytes);
}

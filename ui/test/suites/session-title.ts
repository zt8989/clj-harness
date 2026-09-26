// A session's title, as arithmetic: which message it comes from, how it is cleaned
// up, where it is cut, and what the browser tab says around it.
//
// BOTH CASES ARE PURE. Nothing here boots the harness and nothing renders: the rules
// live in `src/lib/session-title.ts`, a module whose only import is a type, so the
// thing a reader can see only as "the tab says the first thing I typed" can be pinned
// as strings over literal message lists -- the same shape as the `turns` and
// `injections` suites, and for the same reason (see `vitest.config.ts`).
//
// WHAT THIS SUITE CANNOT SEE, and it is most of the feature: that the title is DRAWN
// where the spec says (leading in the conversation's top bar), that `document.title`
// is really written, that only the session on screen writes it, and that the row's
// clip leaves room for the two view tabs. `useAuiState` and `document` both need a
// DOM. Those are the walkthrough's cells in `.scratch/brand-header/`.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { translator } from "../support/locale";
import {
  PRODUCT_NAME,
  TITLE_MAX,
  documentTitle,
  firstUserText,
  sessionTitle,
  titleOf,
} from "../../src/lib/session-title";

/// The two languages the fallback is written in, bound to the REAL catalogs: the word
/// a session with no messages wears is a sentence like any other, and a catalog edit
/// that dropped one of them should fail here rather than in a browser.
const en = translator("en", "shell");
const zh = translator("zh", "shell");

/// A text part, and the two other shapes the walk has to get past: a picture (a user
/// message with no text at all) and an injected-context card (`data`, which the
/// adapter adds to rebuilt messages -- see `lib/injections.ts`).
const say = (text: string) => ({ type: "text", text });
const picture = { type: "image", image: "data:image/png;base64,AAAA" };
const card = { type: "data", data: {} };

/// One message, in the shape the runtime hands the selector.
function message(role: string, parts: readonly { type: string; text?: string }[]) {
  return { role, parts };
}

/// The one-message thread whose only user turn is the text given, as the two forms
/// below need it over and over.
function only(text: string) {
  return [message("user", [say(text)])];
}

const cases: Case[] = [
  {
    name: "the-one-rule-both-copies-of-a-title-go-through",
    run: async () => {
      // `titleOf`: the arithmetic the STORE's copy and the RUNTIME's copy are both put
      // through. It has two callers and therefore two ways to be wrong -- a row showing
      // the server's raw 200 code points, and a bar showing something the row would not
      // -- so the rule itself is pinned here rather than through either one.
      //
      // WHAT IT ANSWERS FOR NOTHING SAID: null, and not an empty string. The callers
      // need to tell "no title" from "a title that happens to be blank", because the
      // row's fallback is its id and the bar's is a word from the catalog.
      expect(titleOf(null)).toBe(null);
      expect(titleOf(undefined)).toBe(null);
      expect(titleOf("")).toBe(null);
      expect(titleOf("\n\t  \n")).toBe(null);

      // WHITESPACE IS COLLAPSED, and this is what a first message typed over several
      // lines meets: without it a row's single line would hold the newlines.
      expect(titleOf("  第一行\n\n第二行  ")).toBe("第一行 第二行");

      // THE CLIP IS BY CODE POINT AND THE ELLIPSIS SAYS IT HAPPENED. `TITLE_MAX` code
      // points are kept and a `…` follows, so the answer is one character LONGER than
      // the limit -- which is why the two callers below assert `TITLE_MAX + 1`.
      expect(titleOf("好".repeat(TITLE_MAX))).toBe("好".repeat(TITLE_MAX));
      expect(titleOf("好".repeat(TITLE_MAX + 1))).toBe(`${"好".repeat(TITLE_MAX)}…`);
      expect([...(titleOf("好".repeat(200)) ?? "")].length).toBe(TITLE_MAX + 1);

      // AND IT IS IDEMPOTENT, which is not decoration: the live copy this page keeps is
      // already a title, and a row that put it through the rule again (`titleOf(live)`)
      // must not shave a character off the end of it every render.
      const once = titleOf("好".repeat(200)) ?? "";
      expect(titleOf(once)).toBe(once);
    },
  },
  {
    name: "the-title-is-the-first-thing-the-user-said",
    run: async () => {
      // THE ORDINARY CASE, and the one the feature exists for: the first thing the
      // person typed, and nothing the model said.
      expect(
        firstUserText([
          message("user", [say("这个仓库的测试怎么跑？")]),
          message("assistant", [say("先跑 vitest。")]),
        ]),
      ).toBe("这个仓库的测试怎么跑？");

      // A REBUILT CONVERSATION IS THE SAME ANSWER. `fromAgUiMessages` opens a message
      // per LLM round, so a session that thought before answering arrives as several
      // assistant messages -- none of which is a title.
      expect(
        firstUserText([
          message("system", [say("you are a harness")]),
          message("assistant", [say("让我看看。")]),
          message("user", [say("第二轮的问题")]),
        ]),
      ).toBe("第二轮的问题");

      // A PICTURE IS NOT A TITLE, AND IT DOES NOT END THE SEARCH. The composer takes
      // pasted images, so a message whose only part is an image is ordinary -- and a
      // conversation that opened with one is a conversation that has a title, one
      // message later.
      expect(firstUserText([message("user", [picture])])).toBeNull();
      expect(
        firstUserText([message("user", [picture]), message("user", [say("这是截图里的报错")])]),
      ).toBe("这是截图里的报错");

      // THE INJECTED CARD IS SKIPPED BY THE SAME TEST as the picture: it is a `data`
      // part, so a rebuilt first message that carries one still answers with its text.
      expect(firstUserText([message("user", [card, say("帮我看一下这个文件")])])).toBe(
        "帮我看一下这个文件",
      );

      // WHITESPACE IS NOT A TITLE. A message that is only spaces, and a text part that
      // is only a newline, both leave the session where it was.
      expect(firstUserText([message("user", [say("   \n\t ")])])).toBeNull();
      expect(firstUserText([])).toBeNull();

      // AND WHAT IS LEFT IS TIDIED: a first line that opens with newlines, and a
      // paragraph typed with a stray double space, are both one line by the time a tab
      // sees them -- internal runs collapse, the ends are trimmed.
      expect(firstUserText([message("user", [say("\n\n  你好，   世界  ")])])).toBe("你好， 世界");

      // `sessionTitle` IS THE SAME ANSWER plus the words for "not yet", and the words
      // come from the caller's translator (this module holds none). BOTH LANGUAGES are
      // pinned: the fallback is the only part of a title this product writes itself.
      expect(sessionTitle([message("user", [say("你好")])], en("session.untitled"))).toBe("你好");
      expect(sessionTitle([], en("session.untitled"))).toBe("New session");
      expect(sessionTitle([], zh("session.untitled"))).toBe("新会话");

      // A FIRST MESSAGE LONGER THAN THE LIMIT IS CUT HERE, not by CSS: the tab has no
      // ellipsis, so the string itself has to be bounded. 60 characters survive and the
      // mark says there was more.
      const sixty = "好".repeat(TITLE_MAX);
      const cut = firstUserText(only(`${sixty}好`));
      expect(cut).toBe(`${sixty}…`);
      expect([...(cut ?? "")].length).toBe(TITLE_MAX + 1);
      // AND A TITLE THAT FITS IS LEFT ALONE -- no ellipsis on an ordinary sentence.
      expect(firstUserText(only(sixty))).toBe(sixty);
    },
  },
  {
    name: "the-tab-title-says-whose-page-it-is",
    run: async () => {
      // THE TAIL IS THE PRODUCT, because a window is where several pages sit side by
      // side -- and the name is a CONSTANT, not a catalog key: a proper noun cannot be
      // translated, so it must not appear in a translation table (see the module).
      expect(PRODUCT_NAME).toBe("clj-harness");
      expect(documentTitle("你好")).toBe("你好 · clj-harness");
      expect(documentTitle(en("session.untitled"))).toBe("New session · clj-harness");
      expect(documentTitle(zh("session.untitled"))).toBe("新会话 · clj-harness");

      // THE CLIP COUNTS CHARACTERS, NOT UTF-16 UNITS, and this is the case that proves
      // it: an emoji is TWO code units, so a `slice` at the limit would cut one in half
      // and the tab would end in a replacement character (U+FFFD). The tail of the
      // title is an emoji mark, whole, and the count is the limit's.
      const emoji = "😀".repeat(TITLE_MAX + 5);
      const cut = firstUserText(only(emoji)) ?? "";
      expect([...cut].length).toBe(TITLE_MAX + 1);
      expect(cut.endsWith("😀…")).toBe(true);
      expect(cut.includes("\uFFFD")).toBe(false);
      // The other direction: a surrogate pair is ONE character here, so a title of 60
      // emoji is exactly at the limit and is NOT cut.
      expect(firstUserText(only("😀".repeat(TITLE_MAX)))).toBe("😀".repeat(TITLE_MAX));
    },
  },
];

export const sessionTitleSuite: Suite = { name: "session-title", cases };

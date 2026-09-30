// THE STRIP ABOVE THE COMPOSER, WHILE THE SESSION DOES NOT EXIST YET -- the two questions it
// has to get right while the id is one this page minted and nothing has been sent
// (`.scratch/composer-new-session-bar`).
//
// ================================================================ what is assertable here
//
// READ AS SOURCE, the idiom `suites/composer-todos.tsx` uses for its two structural cases:
// `ComposerContextBar` is not exported and fetches on mount, so there is nothing here to render
// it with. What these pin is the SHAPE of two fixes whose whole effect is a layout -- one is a
// URL, the other a class string. The effects themselves are the browser walkthrough's, and both
// were measured there: a branch that was not drawn at all, and 8px of jump.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import composerChromeSource from "../../src/components/composer-chrome.tsx?raw";
import composerSource from "../../src/lib/composer.ts?raw";
import threadSource from "../../src/components/assistant-ui/elements/thread.aui.tsx?raw";

const cases: readonly Case[] = [
  {
    name: "a-session-this-page-is-holding-asks-about-its-directory-and-not-about-itself",
    run: async () => {
      // ONE LOADER, TWO QUESTIONS, and which one is asked is the session's existence: a session
      // with a row asks about ITSELF, a held one about the DIRECTORY it will be bound to. Asking
      // the server about a minted id answers `{dir: nil}` however real the repository in front
      // of it, which is why this strip drew no branch before the first send.
      expect(composerChromeSource).toContain(
        "() => (heldDir === null ? gitStateFor(threadId, tErrors) : gitStateIn(heldDir, tErrors)),",
      );
      // AND IT RE-ASKS WHEN THAT DIRECTORY CHANGES -- the loader is the effect's dependency, so a
      // pick is what makes the branch appear.
      expect(composerChromeSource).toContain("[heldDir, threadId, tErrors],");
      // The switch is addressed the same way, so a branch can be picked before the first send:
      // a real checkout in that directory (that is the verb), for both halves of the bar.
      expect(composerChromeSource).toContain("? switchBranch(threadId, branch, tErrors)");
      expect(composerChromeSource).toContain(": switchBranchIn(heldDir, branch, tErrors));");
      // The two calls the bar may reach for, and they are the `dir` forms of the pair above.
      expect(composerSource).toContain("git?dir=${encodeURIComponent(dir)}");
      expect(composerSource).toContain("body: JSON.stringify({ dir, branch })");
    },
  },
  {
    name: "nothing-a-late-answer-does-shoves-the-new-session-composer",
    run: async () => {
      // THE ROW HOLDS ITS HEIGHT: the projects listing that fills these pickers lands a moment
      // after this bar first draws, and a row that GROWS moves the whole composer by half of it
      // (the composer is centred while the session is new). The rule is the status strip's.
      expect(composerChromeSource).toContain('<div className="flex min-h-5 items-center gap-4">');
      // AND THE EMPTY SUGGESTIONS ROW IS `display: none`, not a zero-high child of a `gap-4`
      // column: it is in the DOM whenever the composer is empty, whether or not the server ever
      // sent a suggestion, and it costs that column 16px of gap -- which the first typed
      // character took away with it (measured: the composer jumps 8px, and back when cleared).
      expect(threadSource).toContain("justify-center gap-2 px-4 empty:hidden");
    },
  },
];

export const composerContextBarSuite: Suite = { name: "composer-context-bar", cases };

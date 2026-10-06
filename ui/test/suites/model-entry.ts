// THE PROJECTION A SAVE MAKES: what the report answered about a model, minus everything
// only a form may SHOW.
//
// PURE, in the shape `session-title.ts` and `relative-time.ts` established --
// `src/lib/model-entry.ts` imports one TYPE and nothing else, so every case here is a
// row literal in and a key list out. That is available precisely because the two shapes
// were put in a module of their own; `components/settings-panel.tsx` reaches it through
// `lib/i18n.ts`, which touches `document`, and so cannot be imported by this run at all
// (see `vitest.config.ts`).
//
// WHY THE SUITE EXISTS. The five `-suggested` keys are a REPORT's answer (what models.dev
// says, offered beside what the file said), and a config.edn model entry may carry none of
// them. The form read a report, kept it as its draft, and POSTED it back verbatim: a
// person who opened a vendor the database knows and pressed save without touching
// anything was refused with `model "cn:deepseek-v4.1-flash" of provider :workbuddy carries
// [:name-suggested], which it does not understand`. Every case below is a way that key
// could have reached the writer, and the machine gate cannot see a POST.
//
// WHAT THIS RUN CANNOT SEE: that the form's save goes through this function (that is the
// one call in `settings-panel.tsx`, and a reader of the save is a reader of the line above
// it), and what the page looks like. The refusal itself is the server's sentence and is
// pinned in `test/harness/cap/providers_test.clj`; what is pinned HERE is that no key the
// report rides can cross into a write, and that nothing the file DOES say is dropped on
// the way.
import { expect } from "vitest";

import { type Case, type Suite } from "../e2e";
import { entryOf, type ReportedModel } from "../../src/lib/model-entry";

/// A report row exactly as `GET /api/providers` writes one: the file's own words, and
/// the database's answers BESIDE them. `cn:` prefixes are the shape the report is read
/// in practice, where every id carries a vendor tag the catalog resolves by name.
const REPORTED: ReportedModel = {
  id: "cn:deepseek-v4.1-flash",
  input: ["image", "text"],
  output: ["text"],
  "name-suggested": "DeepSeek V4.1 Flash",
  "input-suggested": ["text"],
  "output-suggested": ["image", "text"],
  "context-window-suggested": 1000000,
  "max-output-tokens-suggested": 384000,
};

/// EVERY KEY A config.edn MODEL ENTRY MAY CARRY, and NOTHING ELSE. This is the list the
/// server enforces (`harness.cap.providers/model-keys`); a suite that repeated it would
/// go green the day a key is added there, which is the wrong day for this suite to stop
/// reading. What it does instead is assert the SHAPE below -- that the crossed row's keys
/// are a subset of what a fresh row declares -- and name the five suggestions as the
/// known suspects, so a NEW one is caught by the subset assertion rather than by a list
/// somebody has to remember to extend.
const MAY_DECLARE = [
  "id",
  "input",
  "output",
  "name",
  "context-window",
  "max-output-tokens",
  "instruction-updates",
];

/// THE FIVE KEYS THE REPORT RIDES AND THE WRITER MAY NOT CARRY, spelled out because they
/// are the ones this fix is about; a sixth one added to the report is caught by the
/// subset assertion above rather than by this list, which is deliberate -- this list is
/// the failure being pinned, not the definition.
const SUGGESTED = [
  "name-suggested",
  "input-suggested",
  "output-suggested",
  "context-window-suggested",
  "max-output-tokens-suggested",
];

const keysOf = (m: object): string[] => Object.keys(m).sort();

const cases: Case[] = [
  {
    name: "the-suggestions-a-report-rides-never-cross-into-a-write",
    run: async () => {
      const entry = entryOf(REPORTED);

      // NOT ONE OF THE FIVE, and not merely undefined-valued: absent. The server reads
      // absence as silence, and JSON would turn a null into a value it has to refuse.
      for (const key of SUGGESTED) {
        expect(entry).not.toHaveProperty(key);
      }

      // AND BY NAME, NOT BY EXCLUSION: every key the crossed row carries is one a fresh
      // config.edn entry declares, which is the same list the server refuses anything
      // outside of. This is the assertion a sixth `-suggested` key fails.
      expect(keysOf(entry).every((k) => MAY_DECLARE.includes(k))).toBe(true);
    },
  },
  {
    name: "the-empty-row-a-person-added-stays-empty",
    run: async () => {
      // A row made of NOTHING BUT ITS ID is a complete entry, and the form's own
      // `emptyModel` builds exactly this one. Every optional key is ABSENT -- the server
      // fills the modalities from the database and floors at text, and a `null` here
      // would read as a claim rather than as silence.
      expect(entryOf({ id: "model-1", input: [], output: [] })).toEqual({
        id: "model-1",
        input: [],
        output: [],
      });
    },
  },
  {
    name: "what-the-file-said-crosses-untouched",
    run: async () => {
      // THE OTHER HALF OF THE RULE, and the half a "just strip the suggestions" fix gets
      // wrong: a person who DID type something must have it written down. The report's
      // suggestion for the same field must not win over the file's word -- the file is
      // the person's, and the whole of `registry-report` rests on the report showing it
      // as written.
      const written = entryOf({
        id: "cn:hy3",
        input: ["text"],
        output: ["text"],
        name: "Hunyuan 3",
        "context-window": 256000,
        "max-output-tokens": 128000,
        "instruction-updates": "in-place",
        "name-suggested": "Hunyuan 3 (something else)",
        "context-window-suggested": 999000,
        "max-output-tokens-suggested": 999000,
      });
      expect(written).toEqual({
        id: "cn:hy3",
        input: ["text"],
        output: ["text"],
        name: "Hunyuan 3",
        "context-window": 256000,
        "max-output-tokens": 128000,
        "instruction-updates": "in-place",
      });
    },
  },
  {
    name: "the-row-is-copied-not-handed-over",
    run: async () => {
      // THE DRAFT'S ARRAYS ARE NOT THE WRITE'S. The form edits rows in place through
      // `onChange` (`{ ...row, input: next }`), so a payload aliasing the draft's arrays
      // would be a second reference to the same mutable list -- and the tick box builds
      // its next one by concatenation anyway, so this is a cheap thing to get right and
      // an expensive thing to debug.
      const row: ReportedModel = { id: "m", input: ["text"], output: [] };
      const entry = entryOf(row);
      expect(entry.input).not.toBe(row.input);
      expect(entry.output).not.toBe(row.output);
      expect(entry.input).toEqual(row.input);
    },
  },
  {
    name: "one-row-can-be-projected-a-hundred-times-with-the-same-answer",
    run: async () => {
      // A save is not the only projection, and neither is an edit: the row is projected
      // on every save and the same row is read back into a fresh draft afterwards. Two
      // projections of one row are EQUAL and neither is the row -- which is what makes
      // the projection safe to run again, and what a memoized draft must not change.
      const once = entryOf(REPORTED);
      const twice = entryOf(REPORTED);
      expect(twice).toEqual(once);
      expect(twice).not.toBe(once);
    },
  },
];

export const modelEntrySuite: Suite = { name: "model-entry", cases };

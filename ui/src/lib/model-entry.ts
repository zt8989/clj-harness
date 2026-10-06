// THE REPORT'S ROW IS NOT THE ROW THE FORM WRITES, and the difference is one function.
//
// `lib/providers.ts` has two shapes for a model and they used to be one shape. What
// `GET /api/providers` answers for a row is a REPORT: what the file said, plus the
// database's answers BESIDE it under their own keys (`name-suggested`,
// `input-suggested`, `output-suggested`, `context-window-suggested`,
// `max-output-tokens-suggested`) -- offered so the form can show them, never applied
// (`.scratch/model-row-fold`). What a write carries is a config.edn model ENTRY: the
// id plus what a person typed. The five suggested keys are in the first and must not
// be in the second -- a key no model entry may carry is a named failure at the far
// end, not a harmless extra (`harness.cap.providers/model-keys`).
//
// THE HOUR THAT PROVED IT: opening a provider whose ids the database knows, ticking
// nothing and pressing save sent the report's rows straight back, and the write was
// refused with `model "cn:deepseek-v4.1-flash" of provider :workbuddy carries
// [:name-suggested], which it does not understand`. A person who changed nothing could
// not save. The form's draft is a draft of the FILE, so the projection happens ONCE,
// here, where the two shapes are named -- and a suggested key added to the report
// tomorrow is dropped by this function rather than reaching the writer by accident.
//
// KEYS WITH NOTHING TO SAY ARE ABSENT, never `undefined`-valued: the writer is told
// 'the file is silent' by a missing key, and JSON turns an absent key and a null into
// two different things.
import type { ModelRow } from "./providers";

/// One model as a config.edn entry is written: the id, the modalities as the file
/// speaks them (an EMPTY pair is silence, which the server reads as such), and the
/// four optional facts a person may state. This is the row type `ProviderPayload`
/// carries, and it is what the server's `model-keys` accepts -- nothing more.
export type ModelEntry = {
  id: string;
  input: readonly string[];
  output: readonly string[];
  name?: string;
  "context-window"?: number;
  "max-output-tokens"?: number;
  "instruction-updates"?: "in-place" | "replace";
};

/// A model as the report ANSWERS it, where nothing is missing and the file's silence
/// is spelled as an empty pair. `providers.ts` owns the full shape; this is the
/// alias that says which of the two worlds the draft is in.
export type ReportedModel = ModelRow;

/// REPORT ROW -> THE ENTRY A WRITE CARRIES.
///
/// Every field is copied BY NAME rather than spread, and that is the whole point: a
/// spread would carry the suggestions along (which is the bug this module exists to
/// fix) and would also carry a key added to the report next month without anybody
/// reading this file. A name here is a decision to write it down, and a key nobody
/// wrote here cannot reach config.edn.
export function entryOf(row: ReportedModel): ModelEntry {
  const entry: ModelEntry = {
    id: row.id,
    input: [...row.input],
    output: [...row.output],
  };
  if (row.name !== undefined) entry.name = row.name;
  if (row["context-window"] !== undefined) entry["context-window"] = row["context-window"];
  if (row["max-output-tokens"] !== undefined) {
    entry["max-output-tokens"] = row["max-output-tokens"];
  }
  if (row["instruction-updates"] !== undefined) {
    entry["instruction-updates"] = row["instruction-updates"];
  }
  return entry;
}

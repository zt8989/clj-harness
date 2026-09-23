// The rules that turn a server's `requestedSchema` into something a person can
// fill in -- and the answer back into the JSON the server asked for.
//
// WHY THESE ARE A MODULE AND NOT PART OF THE CARD. They are the part of the
// elicitation card that can be WRONG in a way nobody sees: a field silently
// dropped, or a number arriving as the string "42". Both are invisible in a
// screenshot and both are a form the server thinks it asked correctly. Pulled out
// here they are plain functions with plain tests, and the card is left with
// nothing but drawing.
//
// THE ONE RULE THAT IS NOT NEGOTIABLE: a field is never dropped. A property whose
// type this client has no input for still gets a row, labelled with the type the
// schema declared, so the answer is at worst the string a person typed and never
// a missing key.

/// One property of `requestedSchema.properties`, as something to draw.
export type FieldSpec = {
  name: string;
  /// The JSON Schema type, VERBATIM -- including types with no input of their
  /// own. The card shows it in that case rather than pretending it is a string.
  kind: string;
  description?: string;
  /// The candidates a SINGLE choice is between: a property-level `enum`.
  enumValues?: readonly string[];
  /// The candidates a MULTIPLE choice is between: the `enum` of an `array`'s
  /// `items`. Kept apart from `enumValues` rather than folded together because the
  /// two draw different controls, and a spec that carried only one field could not
  /// say which of them it was looking at.
  itemValues?: readonly string[];
  /// The person may type their own answer instead of picking one.
  ///
  /// AN EXTENSION KEY RATHER THAN JSON SCHEMA (`x-allow-other`), because the
  /// permission belongs to the FORM and the standard has no word for it. It matters
  /// that this is opt-in rather than assumed: the same rules draw a server's own
  /// elicitation form from the server's schema, and growing an extra input under
  /// every enum somebody else declared would be this client editing their question.
  allowOther?: boolean;
};

/// Which input a field gets.
///
/// `number` and `integer` are the browser's number input; `boolean` and an enum
/// are selects (a boolean rendered as free text is how "yes" becomes `true`);
/// everything else -- including types this client has never heard of -- is text.
export type InputKind = "number" | "select" | "text" | "checkboxes";

export function inputKindFor(spec: FieldSpec): InputKind {
  // AN ARRAY IS CHECKED BEFORE THE ENUM, and that order is the whole of this
  // branch: a multiple-choice field's `items` carries an enum too (`fieldSpecs`
  // reads it into `itemValues`, not `enumValues`), so a spec that had both would
  // otherwise be drawn as a select and lose every answer but one.
  if (spec.kind === "array") return "checkboxes";
  if (spec.enumValues !== undefined) return "select";
  switch (spec.kind) {
    case "number":
    case "integer":
      return "number";
    case "boolean":
      return "select";
    default:
      return "text";
  }
}

/// The extension key that says a question may be answered in the person's own
/// words. Not JSON Schema -- see `FieldSpec.allowOther`.
const ALLOW_OTHER_KEY = "x-allow-other";

/// The properties of a JSON Schema object, as field specs.
///
/// Tolerant on purpose: a schema that is missing, not an object, or has no
/// `properties` yields NO fields rather than an exception. A question with an
/// empty form is still a question, and a card that crashed on an odd schema would
/// leave a person with a parked run and nothing to click.
export function fieldSpecs(schema: unknown): readonly FieldSpec[] {
  if (typeof schema !== "object" || schema === null) return [];
  const properties = (schema as { properties?: unknown }).properties;
  if (typeof properties !== "object" || properties === null) return [];
  return Object.entries(properties as Record<string, unknown>).map(
    ([name, raw]) => {
      const spec = (typeof raw === "object" && raw !== null ? raw : {}) as {
        type?: unknown;
        description?: unknown;
        enum?: unknown;
        items?: unknown;
      };
      const kind = typeof spec.type === "string" ? spec.type : "unknown";
      const enumValues = stringsOf(spec.enum);
      // The candidates of a multiple choice live one level down, on `items`.
      const itemValues =
        kind === "array" && typeof spec.items === "object" && spec.items !== null
          ? stringsOf((spec.items as { enum?: unknown }).enum)
          : undefined;
      return {
        name,
        kind,
        ...(typeof spec.description === "string"
          ? { description: spec.description }
          : {}),
        ...(enumValues === undefined ? {} : { enumValues }),
        ...(itemValues === undefined ? {} : { itemValues }),
        // Read off whatever the property carries, including a property this client
        // has no input for: a field is never dropped, and neither is what it said
        // about itself.
        ...((raw as Record<string, unknown> | null)?.[ALLOW_OTHER_KEY] === true
          ? { allowOther: true }
          : {}),
      };
    },
  );
}

/// An array of JSON scalars as strings, or undefined when it is not one. A single
/// element is stringified for the same reason the candidates are kept verbatim
/// elsewhere: a model may write `1` where it means the string "1".
function stringsOf(raw: unknown): readonly string[] | undefined {
  return Array.isArray(raw) ? raw.map((v) => String(v)) : undefined;
}

/// One field's raw string, as the value the schema declared.
///
/// A number arrives as a number and a boolean as a boolean, because the server
/// wrote a schema saying so and sending "42" would be answering a different
/// question. A boolean that was never chosen is NOT coerced to false: an
/// unanswered select sends the empty string it holds, which is the honest
/// "this field was left alone".
///
/// A MULTIPLE CHOICE DOES NOT COME THROUGH HERE -- it has no single raw string to
/// coerce, and `answersFor` builds its list itself. Leaving this signature alone is
/// what keeps every scalar answer byte-identical to what it was.
export function coerce(spec: FieldSpec, raw: string): unknown {
  switch (spec.kind) {
    case "number":
    case "integer": {
      const n = Number(raw);
      return raw.trim() !== "" && !Number.isNaN(n) ? n : raw;
    }
    case "boolean":
      return raw === "" ? raw : raw === "true";
    default:
      return raw;
  }
}

/// A field's value as the CARD holds it: the string a scalar input holds, or -- for
/// a multiple choice -- the options that were ticked.
///
/// A LIST IS NOT A STRING THAT HAPPENS TO CONTAIN COMMAS. That is the whole reason
/// this is a union rather than a joined string: a candidate may itself contain a
/// comma, so a value that had been through a join-then-split round trip would come
/// back as two answers nobody gave.
export type FieldValue = string | readonly string[];

/// What a MULTIPLE choice's answer is: the options they ticked, in the order the
/// list holds them, with the words they typed themselves -- if any -- appended last.
///
/// ORDER COMES FROM THE OPTIONS, NOT FROM THE CLICKS, so the same set of ticks is
/// the same answer whichever way round they were clicked: an answer that depended on
/// the order of somebody's mouse would be a different answer for the same decision.
/// A tick that is not one of the options is dropped rather than passed through --
/// ticks come from the rendered list, so the only way to hold one is to hold a stale
/// render.
///
/// AN EMPTY LIST IS AN ANSWER, and it is not the same answer as no answer at all:
/// "none of these" is something to act on, which is why it is sent rather than
/// omitted. `ask` says the two apart in the result the model reads.
function chosenFor(
  field: FieldSpec,
  ticks: readonly string[],
  typed: string,
): string[] {
  const picked = (field.itemValues ?? []).filter((option) =>
    ticks.includes(option),
  );
  return typed === "" ? picked : [...picked, typed];
}

/// Every field's value, keyed by the name the schema used. The keys are the
/// schema's own, so what comes back is the form the server asked for rather than
/// one this client designed.
///
/// `typed` is the person's OWN words per field, and it only means anything for a
/// field whose schema allowed them. For a single choice it IS the answer, standing
/// where the pick would have; for a multiple choice it is one more item, because
/// "these, and also this" is a thing a person means. A field that never allowed it
/// ignores whatever is in there, so a stray entry cannot answer a question that did
/// not offer to be answered that way.
export function answersFor(
  fields: readonly FieldSpec[],
  values: Readonly<Record<string, FieldValue>>,
  typed: Readonly<Record<string, string>> = {},
): Record<string, unknown> {
  const content: Record<string, unknown> = {};
  for (const field of fields) {
    const value = values[field.name];
    const own = (typed[field.name] ?? "").trim();
    const kind = inputKindFor(field);

    if (kind === "checkboxes") {
      content[field.name] = chosenFor(
        field,
        Array.isArray(value) ? value : [],
        field.allowOther === true ? own : "",
      );
      continue;
    }

    // THE OWN WORDS WIN, for a field that offered them and holds some: the person
    // who typed instead of picking said what they meant, and sending both would be
    // asking the server to choose between two answers to one question.
    content[field.name] =
      field.allowOther === true && own !== ""
        ? coerce(field, own)
        : coerce(field, typeof value === "string" ? value : "");
  }
  return content;
}

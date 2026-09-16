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
  enumValues?: readonly string[];
};

/// Which input a field gets.
///
/// `number` and `integer` are the browser's number input; `boolean` and an enum
/// are selects (a boolean rendered as free text is how "yes" becomes `true`);
/// everything else -- including types this client has never heard of -- is text.
export type InputKind = "number" | "select" | "text";

export function inputKindFor(spec: FieldSpec): InputKind {
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
      };
      const kind = typeof spec.type === "string" ? spec.type : "unknown";
      const enumValues = Array.isArray(spec.enum)
        ? spec.enum.map((v) => String(v))
        : undefined;
      return {
        name,
        kind,
        ...(typeof spec.description === "string"
          ? { description: spec.description }
          : {}),
        ...(enumValues === undefined ? {} : { enumValues }),
      };
    },
  );
}

/// One field's raw string, as the value the schema declared.
///
/// A number arrives as a number and a boolean as a boolean, because the server
/// wrote a schema saying so and sending "42" would be answering a different
/// question. A boolean that was never chosen is NOT coerced to false: an
/// unanswered select sends the empty string it holds, which is the honest
/// "this field was left alone".
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

/// Every field's value, keyed by the name the schema used. The keys are the
/// schema's own, so what comes back is the form the server asked for rather than
/// one this client designed.
export function answersFor(
  fields: readonly FieldSpec[],
  values: Readonly<Record<string, string>>,
): Record<string, unknown> {
  const content: Record<string, unknown> = {};
  for (const field of fields) {
    content[field.name] = coerce(field, values[field.name] ?? "");
  }
  return content;
}

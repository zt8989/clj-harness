// How the sidebar's two disk facts are written for a person.
//
// Both are about a log FILE, and both answer a question a reader actually has:
// "which one was this" and "is it big enough to be worth opening". Neither is a
// measurement, which is why nothing here carries more precision than a glance
// needs -- a size to the byte and a timestamp to the millisecond would both be
// noise, and the raw numbers stay available to anyone who wants them by looking
// at the file.

/// Bytes in the units a person reads: whole KB under a megabyte, whole MB above.
/// Zero is spelled out rather than rounded away, because a zero-byte log is a
/// fact worth noticing.
export function formatBytes(bytes: number | null): string {
  if (bytes === null) return "no log yet";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${Math.round(bytes / (1024 * 1024))} MB`;
}

/// The log file's mtime, in the reader's own timezone and punctuation. Epoch
/// millis are for machines; a session list is read by a person looking for "the
/// one from this morning".
export function formatTime(ms: number | null): string {
  if (ms === null) return "never run";
  return new Date(ms).toLocaleString();
}

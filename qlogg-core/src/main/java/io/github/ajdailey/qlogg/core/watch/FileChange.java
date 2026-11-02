package io.github.ajdailey.qlogg.core.watch;

/** How the file at a document's path differs from what was indexed. */
public enum FileChange {
  /** Nothing to do. */
  UNCHANGED,
  /** Same file, longer: index the new bytes. */
  APPENDED,
  /** Same file, shorter than the indexed bytes: reopen and index again. */
  TRUNCATED,
  /** Another file at the path, or the same file rewritten from its start: reopen. */
  REPLACED,
  /** Same size, but the indexed tail differs: reopen. */
  MODIFIED,
  /** No regular file at the path; the open file stays readable. */
  MISSING
}

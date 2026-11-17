package io.github.ajdailey.qlogg.app.config;

/** The outcome of a settings or session write. */
public enum SaveResult {
  /** The file holds the content, or already held it. */
  WRITTEN,
  /** The write failed; a later write of the same content can succeed. */
  FAILED,
  /** Saving is disabled or the content is over the size cap; nothing was written. */
  REFUSED
}

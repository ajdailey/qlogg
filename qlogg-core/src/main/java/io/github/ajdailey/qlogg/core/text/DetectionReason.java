package io.github.ajdailey.qlogg.core.text;

/** Why {@link EncodingDetector} chose an encoding, in detection order. */
public enum DetectionReason {
  /** Set by the user or the session. */
  FORCED,
  /** A UTF-8 or UTF-16 byte order mark. */
  BOM,
  /** Zero bytes concentrated at odd or at even offsets. */
  UTF16_PATTERN,
  /** The sample is valid UTF-8. */
  UTF8_VALID,
  /** The configured default. */
  FALLBACK
}

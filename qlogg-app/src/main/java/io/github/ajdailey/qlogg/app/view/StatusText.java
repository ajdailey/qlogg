package io.github.ajdailey.qlogg.app.view;

import io.github.ajdailey.qlogg.app.i18n.UiText;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The document fields: file size, its tooltip's modification time and encoding for the top row, and
 * the indexing, follow, missing-file, stopped-loading, sparse-index and failure notes for the
 * status bar.
 */
public final class StatusText {
  /** Separates the notes. */
  public static final String GAP = "    ";

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

  private StatusText() {}

  /**
   * Formats the size field's tooltip: the modification time in a zone.
   *
   * @param state the document's state
   * @param zone the zone the time shows in
   * @return for example {@code Last modified 2026-11-01 14:05:09}, or empty when unknown
   */
  public static String sizeTip(DocumentViewState state, ZoneId zone) {
    return state.modifiedMillis() > 0
        ? UiText.STATUS_MODIFIED_TIP.format(modified(state.modifiedMillis(), zone))
        : "";
  }

  /**
   * Formats the encoding.
   *
   * @param state the document's state
   * @return the charset name, marked when the user chose it
   */
  public static String encoding(DocumentViewState state) {
    return state.encodingForced()
        ? UiText.STATUS_ENCODING_FORCED.format(state.encodingName())
        : state.encodingName();
  }

  /**
   * Lists the notes that apply.
   *
   * @param state the document's state
   * @return the notes separated by {@link #GAP}, or empty
   */
  public static String notes(DocumentViewState state) {
    StringBuilder text = new StringBuilder();
    if (state.indexing()) {
      note(text, UiText.STATUS_INDEXING.format(state.indexPercent()));
    }
    if (state.following()) {
      note(text, UiText.STATUS_FOLLOWING.text());
    }
    if (state.missing()) {
      note(text, UiText.STATUS_MISSING.text());
    }
    if (state.loadingStopped()) {
      note(text, UiText.STATUS_LOADING_STOPPED.text());
    }
    if (state.sparseIndex()) {
      note(text, UiText.STATUS_SPARSE_INDEX.text());
    }
    if (state.error() != null) {
      note(text, state.error());
    }
    return text.toString();
  }

  private static void note(StringBuilder text, String note) {
    if (text.length() > 0) {
      text.append(GAP);
    }
    text.append(note);
  }

  /**
   * Formats a size in bytes, KiB, MiB or GiB, whichever keeps the number under 1,024.
   *
   * @param bytes the size
   * @return for example {@code 12.3 MiB}
   */
  public static String size(long bytes) {
    if (bytes < 1024) {
      return UiText.SIZE_BYTES.format(Math.max(0, bytes));
    }
    if (bytes < 1L << 20) {
      return UiText.SIZE_KIB.format(bytes / 1024.0);
    }
    if (bytes < 1L << 30) {
      return UiText.SIZE_MIB.format(bytes / (double) (1L << 20));
    }
    return UiText.SIZE_GIB.format(bytes / (double) (1L << 30));
  }

  /**
   * Formats a modification time.
   *
   * @param millis milliseconds since the epoch
   * @param zone the zone it shows in
   * @return for example {@code 2026-11-01 14:05:09}
   */
  public static String modified(long millis, ZoneId zone) {
    return TIME.format(Instant.ofEpochMilli(millis).atZone(zone));
  }
}

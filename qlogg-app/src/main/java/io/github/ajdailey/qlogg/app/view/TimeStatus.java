package io.github.ajdailey.qlogg.app.view;

import io.github.ajdailey.qlogg.core.time.TimestampParser;

/**
 * A view's caret time and selection times as read from the file, before the time offset.
 *
 * @param caret epoch nanoseconds of the caret line's time, or {@link TimestampParser#NONE}
 * @param caretDigits fraction digits the caret line's timestamp had
 * @param first time of the first selected line, or {@link TimestampParser#NONE} without a selection
 *     over more than one line
 * @param last time of the last selected line, or {@link TimestampParser#NONE}
 * @param rangeDigits the larger fraction-digit count of the first and last line
 */
public record TimeStatus(long caret, int caretDigits, long first, long last, int rangeDigits) {
  /** No time read. */
  public static final TimeStatus NONE =
      new TimeStatus(TimestampParser.NONE, 0, TimestampParser.NONE, TimestampParser.NONE, 0);
}

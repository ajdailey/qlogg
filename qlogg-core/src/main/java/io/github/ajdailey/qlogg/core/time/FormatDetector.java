package io.github.ajdailey.qlogg.core.time;

import io.github.ajdailey.qlogg.core.text.LineDecoder;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;

/**
 * Picks the built-in format that parses the most of a file's first {@link #SAMPLE_LINES} non-empty
 * lines. The winner needs at least {@link #MIN_LINES} lines and {@link #MIN_PERCENT}% of the
 * sample; ties go to {@link BuiltInFormat} order, except that {@link BuiltInFormat#MDY_SLASH} beats
 * {@link BuiltInFormat#DMY_SLASH}. The two tie unless a first field is over 12, which only {@code
 * dd/MM} parses.
 */
public final class FormatDetector {
  /** Non-empty lines sampled. */
  public static final int SAMPLE_LINES = 200;

  /** Fewest parsed lines that choose a format. */
  public static final int MIN_LINES = 3;

  /** Smallest parsed share of the sample that chooses a format. */
  public static final int MIN_PERCENT = 20;

  private FormatDetector() {}

  /**
   * Detects the format of a file head.
   *
   * @param head the file's first bytes from offset 0, BOM included, between position and limit; not
   *     modified
   * @param encoding the detected encoding
   * @param context zone and file date for the parsers
   * @return the format, or null when none qualifies
   */
  public static BuiltInFormat detect(ByteBuffer head, TextEncoding encoding, TimeContext context) {
    BuiltInFormat[] formats = BuiltInFormat.values();
    TimestampParser[] parsers = new TimestampParser[formats.length];
    for (int f = 0; f < formats.length; f++) {
      parsers[f] = formats[f].parser(context);
    }
    int[] counts = new int[formats.length];
    ByteBuffer bytes = head.duplicate();
    int unit = encoding.unitWidth();
    byte[] lineFeed = encoding.lineFeed();
    LineDecoder decoder = new LineDecoder(encoding);
    CharBuffer chars = CharBuffer.allocate(TimeReader.PREFIX_CHARS);
    int origin = bytes.position();
    int end = bytes.limit();
    int start = Math.min(end, origin + encoding.bomLength());
    int sampled = 0;
    while (start < end && sampled < SAMPLE_LINES) {
      int lineEnd = start;
      while (lineEnd + unit <= end && !matches(bytes, lineEnd, lineFeed)) {
        lineEnd += unit;
      }
      if (lineEnd + unit > end) {
        lineEnd = end;
      }
      bytes.limit(Math.min(lineEnd, start + TimeReader.PREFIX_CHARS * unit));
      bytes.position(start);
      chars.clear();
      decoder.decodeLine(bytes, chars);
      bytes.limit(end);
      chars.flip();
      if (chars.hasRemaining()) {
        sampled++;
        for (int f = 0; f < formats.length; f++) {
          if (parsers[f].parse(chars) != TimestampParser.NONE) {
            counts[f]++;
          }
        }
      }
      start = lineEnd + unit;
    }
    int best = -1;
    for (int f = 0; f < formats.length; f++) {
      boolean mdyTie =
          formats[f] == BuiltInFormat.MDY_SLASH
              && best >= 0
              && formats[best] == BuiltInFormat.DMY_SLASH
              && counts[f] == counts[best];
      if (best < 0 || counts[f] > counts[best] || mdyTie) {
        best = f;
      }
    }
    if (counts[best] < MIN_LINES || counts[best] * 100L < (long) MIN_PERCENT * sampled) {
      return null;
    }
    return formats[best];
  }

  private static boolean matches(ByteBuffer bytes, int at, byte[] lineFeed) {
    for (int i = 0; i < lineFeed.length; i++) {
      if (bytes.get(at + i) != lineFeed[i]) {
        return false;
      }
    }
    return true;
  }
}

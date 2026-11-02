package io.github.ajdailey.qlogg.core.index;

import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Finds LF units in a buffer that starts on a unit boundary. One-byte and two-byte units are
 * compared eight bytes at a time; four-byte units one at a time. Only whole units at offsets that
 * are multiples of the unit width count, so a 0x0A byte inside a UTF-16 unit is not an LF.
 */
final class LineScanner {
  private static final long LOW7_BYTES = 0x7f7f7f7f7f7f7f7fL;
  private static final long LOW15_LANES = 0x7fff7fff7fff7fffL;

  private final int width;
  private final byte[] lineFeed;
  private final long pattern;

  LineScanner(TextEncoding encoding) {
    this.width = encoding.unitWidth();
    this.lineFeed = encoding.lineFeed();
    if (width == 1) {
      this.pattern = 0x0101010101010101L * (lineFeed[0] & 0xffL);
    } else if (width == 2) {
      long lane = (lineFeed[0] & 0xffL) | ((lineFeed[1] & 0xffL) << 8);
      this.pattern = 0x0001000100010001L * lane;
    } else {
      this.pattern = 0;
    }
  }

  /**
   * Marks the offset of every LF unit in {@code [0, length)} of the buffer in {@code bits}, one bit
   * per byte offset. A trailing partial unit is ignored.
   *
   * @param buffer little-endian, read with absolute gets
   * @param length bytes to scan
   * @param bits cleared and filled; at least {@code length / 64 + 1} words
   * @return LF units found
   */
  int scan(ByteBuffer buffer, int length, long[] bits) {
    if (buffer.order() != ByteOrder.LITTLE_ENDIAN) {
      throw new IllegalArgumentException("buffer must be little-endian");
    }
    int words = (length >>> 6) + 1;
    for (int i = 0; i < words; i++) {
      bits[i] = 0;
    }
    int whole = length - length % width;
    int count = 0;
    int at = 0;
    if (width <= 2) {
      long low = width == 1 ? LOW7_BYTES : LOW15_LANES;
      int shift = width == 1 ? 3 : 4;
      int stop = whole - 8;
      while (at <= stop) {
        long x = buffer.getLong(at) ^ pattern;
        long found = ~(((x & low) + low) | x | low);
        while (found != 0) {
          int offset = at + ((Long.numberOfTrailingZeros(found) >>> shift) * width);
          bits[offset >>> 6] |= 1L << offset;
          count++;
          found &= found - 1;
        }
        at += 8;
      }
    }
    while (at < whole) {
      if (isLineFeed(buffer, at)) {
        bits[at >>> 6] |= 1L << at;
        count++;
      }
      at += width;
    }
    return count;
  }

  /**
   * Reports whether a whole LF unit starts at an offset.
   *
   * @param buffer any byte order
   * @param at offset of a unit
   * @return true for LF
   */
  boolean isLineFeed(ByteBuffer buffer, int at) {
    for (int i = 0; i < width; i++) {
      if (buffer.get(at + i) != lineFeed[i]) {
        return false;
      }
    }
    return true;
  }

  int width() {
    return width;
  }
}

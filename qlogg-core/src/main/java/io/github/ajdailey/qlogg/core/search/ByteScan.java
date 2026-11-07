package io.github.ajdailey.qlogg.core.search;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Finds a byte, or a pair of bytes a fixed distance apart, in a buffer eight offsets at a time:
 * each {@code getLong} word is compared with the byte repeated, and an exact per-byte zero test (no
 * carry between bytes) locates the first equal byte in buffer order. A folded byte is an ASCII
 * lowercase letter that also matches its uppercase form. Absolute reads; the buffer's position and
 * limit are not used or changed.
 */
final class ByteScan {
  private static final long LOW7 = 0x7F7F7F7F7F7F7F7FL;
  private static final long ONES = 0x0101010101010101L;
  private static final long CASE_BITS = 0x2020202020202020L;

  private ByteScan() {}

  /**
   * Returns the offset of the first byte equal to {@code value} in {@code [from, to)}.
   *
   * @param bytes the buffer
   * @param from the first offset searched
   * @param to the exclusive end
   * @param value the byte
   * @return the offset, or -1
   */
  static int indexOf(ByteBuffer bytes, int from, int to, byte value) {
    long repeated = (value & 0xFFL) * ONES;
    boolean little = bytes.order() == ByteOrder.LITTLE_ENDIAN;
    int i = from;
    for (; i <= to - Long.BYTES; i += Long.BYTES) {
      long zero = zeroBytes(bytes.getLong(i) ^ repeated);
      if (zero != 0) {
        return i + first(zero, little);
      }
    }
    for (; i < to; i++) {
      if (bytes.get(i) == value) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Returns the first offset {@code i} in {@code [from, to)} where the byte at {@code i} equals
   * {@code lead} and the byte at {@code i + distance} equals {@code trail}. The caller keeps {@code
   * to + distance} within the buffer.
   *
   * @param bytes the buffer
   * @param from the first offset searched
   * @param to the exclusive end of the offsets searched
   * @param lead the byte at the offset
   * @param foldLead true when {@code lead} is a folded letter
   * @param trail the byte {@code distance} after it
   * @param foldTrail true when {@code trail} is a folded letter
   * @param distance at least 0
   * @return the offset, or -1
   */
  static int indexOfPair(
      ByteBuffer bytes,
      int from,
      int to,
      byte lead,
      boolean foldLead,
      byte trail,
      boolean foldTrail,
      int distance) {
    long leads = (lead & 0xFFL) * ONES;
    long trails = (trail & 0xFFL) * ONES;
    long leadCase = foldLead ? CASE_BITS : 0;
    long trailCase = foldTrail ? CASE_BITS : 0;
    boolean little = bytes.order() == ByteOrder.LITTLE_ENDIAN;
    int i = from;
    for (; i <= to - Long.BYTES; i += Long.BYTES) {
      long zero =
          zeroBytes((bytes.getLong(i) | leadCase) ^ leads)
              & zeroBytes((bytes.getLong(i + distance) | trailCase) ^ trails);
      if (zero != 0) {
        return i + first(zero, little);
      }
    }
    for (; i < to; i++) {
      if (equal(bytes.get(i), lead, foldLead) && equal(bytes.get(i + distance), trail, foldTrail)) {
        return i;
      }
    }
    return -1;
  }

  private static boolean equal(byte b, byte value, boolean fold) {
    return b == value || fold && (b | 0x20) == value;
  }

  /** Sets the high bit of each zero byte and clears every other bit. */
  private static long zeroBytes(long x) {
    return ~(((x & LOW7) + LOW7) | x | LOW7);
  }

  /** Returns the buffer-order index of the first byte flagged by {@link #zeroBytes}. */
  private static int first(long zero, boolean little) {
    int bit = little ? Long.numberOfTrailingZeros(zero) : Long.numberOfLeadingZeros(zero);
    return bit >>> 3;
  }
}

package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class ByteScanTest {
  /** Bytes next to the case bit, the sign bit and zero, where a word-wide compare could err. */
  private static final byte[] ALPHABET = {
    0,
    1,
    0x7F,
    (byte) 0x80,
    (byte) 0x81,
    (byte) 0xFF,
    '@',
    'A',
    'Z',
    '[',
    '`',
    'a',
    'z',
    '{',
    'q',
    'Q',
    (byte) 0xC1,
    (byte) 0xE1
  };

  private static boolean equal(byte b, byte value, boolean fold) {
    return b == value || fold && b >= 'A' && b <= 'Z' && b + 32 == value;
  }

  private static int naive(
      ByteBuffer bytes,
      int from,
      int to,
      byte lead,
      boolean foldLead,
      byte trail,
      boolean foldTrail,
      int distance) {
    for (int i = from; i < to; i++) {
      if (equal(bytes.get(i), lead, foldLead) && equal(bytes.get(i + distance), trail, foldTrail)) {
        return i;
      }
    }
    return -1;
  }

  @Test
  void agreesWithAByteLoopInEveryBufferKindAndRange() {
    SplittableRandom random = new SplittableRandom(20261107);
    ByteBuffer[] kinds = {
      ByteBuffer.allocate(64),
      ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN),
      ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN),
      ByteBuffer.allocateDirect(64)
    };
    for (int round = 0; round < 500; round++) {
      for (ByteBuffer buffer : kinds) {
        for (int i = 0; i < buffer.capacity(); i++) {
          buffer.put(i, ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        int distance = random.nextInt(0, 12);
        int from = random.nextInt(0, 40);
        int to = random.nextInt(from, buffer.capacity() - distance + 1);
        byte value = ALPHABET[random.nextInt(ALPHABET.length)];
        byte trail = ALPHABET[random.nextInt(ALPHABET.length)];
        byte letter = (byte) "azqm".charAt(random.nextInt(4));
        String context = buffer + " from=" + from + " to=" + to + " distance=" + distance;
        assertEquals(
            naive(buffer, from, to, value, false, value, false, 0),
            ByteScan.indexOf(buffer, from, to, value),
            context + " value=" + value);
        assertEquals(
            naive(buffer, from, to, letter, true, trail, false, distance),
            ByteScan.indexOfPair(buffer, from, to, letter, true, trail, false, distance),
            context + " letter=" + (char) letter + " trail=" + trail);
        assertEquals(
            naive(buffer, from, to, value, false, letter, true, distance),
            ByteScan.indexOfPair(buffer, from, to, value, false, letter, true, distance),
            context + " value=" + value + " letter=" + (char) letter);
      }
    }
  }
}

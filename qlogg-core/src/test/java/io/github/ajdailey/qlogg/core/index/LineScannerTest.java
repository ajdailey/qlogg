package io.github.ajdailey.qlogg.core.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LineScannerTest {
  /** Bytes drawn mostly from LF, CR, 0x00 and 0x0B so lanes next to an LF are exercised. */
  @ParameterizedTest
  @ValueSource(strings = {"UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "IBM037"})
  void matchesAByteByByteScan(String name) {
    assumeTrue(Charset.isSupported(name), name + " not available");
    TextEncoding encoding = TextEncoding.of(Charset.forName(name), 0);
    LineScanner scanner = new LineScanner(encoding);
    byte[] lineFeed = encoding.lineFeed();
    byte[] alphabet = {0x0a, 0x0d, 0x00, 0x0b, 0x25, 0x15, 'a', (byte) 0x8a};
    Random random = new Random(7);
    for (int trial = 0; trial < 2000; trial++) {
      int length = random.nextInt(200);
      ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
      for (int i = 0; i < length; i++) {
        buffer.put(i, alphabet[random.nextInt(alphabet.length)]);
      }
      long[] bits = new long[length / 64 + 1];
      Arrays.fill(bits, -1L);
      int count = scanner.scan(buffer, length, bits);
      long[] expected = new long[length / 64 + 1];
      int expectedCount = 0;
      int width = encoding.unitWidth();
      for (int at = 0; at + width <= length; at += width) {
        if (Arrays.equals(buffer.array(), at, at + width, lineFeed, 0, width)) {
          expected[at >>> 6] |= 1L << at;
          expectedCount++;
        }
      }
      assertEquals(expectedCount, count, "count, trial " + trial);
      for (int i = 0; i < expected.length; i++) {
        assertEquals(expected[i], bits[i], "word " + i + ", trial " + trial);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTF-16LE", "UTF-16BE"})
  void lineFeedByteInsideAUnitIsNotALineFeed(String name) {
    Charset charset = Charset.forName(name);
    byte[] bytes = "\u0a0a\u0a00\n".getBytes(charset);
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    long[] bits = new long[1];
    assertEquals(1, new LineScanner(TextEncoding.of(charset, 0)).scan(buffer, bytes.length, bits));
    assertEquals(1L << 4, bits[0]);
  }
}

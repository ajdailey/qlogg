package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class LineTextTest {
  /**
   * A line in a direct buffer decodes through the heap window; sequences split at a window edge,
   * malformed bytes there and lines several windows long decode as a one-shot heap decode does.
   */
  @Test
  void directBufferDecodesAsAHeapDecode() {
    byte[][] pieces = {
      "a".getBytes(StandardCharsets.UTF_8),
      "\u00e9".getBytes(StandardCharsets.UTF_8),
      "\u4e2d".getBytes(StandardCharsets.UTF_8),
      "\ud83d\ude00".getBytes(StandardCharsets.UTF_8),
      {(byte) 0xE4, (byte) 0xB8},
      {(byte) 0xF0, (byte) 0x9F},
      {(byte) 0xFF},
      {(byte) 0x80}
    };
    SplittableRandom random = new SplittableRandom(20261107);
    TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_8, 0);
    LineText text = new LineText(encoding, new CancellationToken());
    for (int round = 0; round < 40; round++) {
      ByteArrayOutputStream line = new ByteArrayOutputStream();
      int target = random.nextInt(LineText.WINDOW_BYTES - 8, 3 * LineText.WINDOW_BYTES + 8);
      while (line.size() < target) {
        line.writeBytes(pieces[random.nextInt(pieces.length)]);
      }
      assertDecodes(text, line.toByteArray(), StandardCharsets.UTF_8);
    }
    TextEncoding utf16 = TextEncoding.of(StandardCharsets.UTF_16LE, 0);
    String wide = "\u4e2da\ud83d\ude00".repeat(LineText.WINDOW_BYTES / 3);
    assertDecodes(
        new LineText(utf16, new CancellationToken()),
        wide.getBytes(StandardCharsets.UTF_16LE),
        StandardCharsets.UTF_16LE);
  }

  /**
   * UTF-16 lines decode as the charset decoder does, through the unit copy and through the decoder
   * it leaves odd lengths, surrogates and U+FFFE to.
   */
  @Test
  void utf16DecodesAsTheCharsetDecoder() {
    String[] lines = {
      "", "plain \u4e2d\ufeff text", "pair \ud83d\ude00", "lone \ud83d x", "\ufffe"
    };
    Charset[] charsets = {StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE};
    for (Charset charset : charsets) {
      LineText text = new LineText(TextEncoding.of(charset, 0), new CancellationToken());
      for (String line : lines) {
        byte[] bytes = units(line, charset.equals(StandardCharsets.UTF_16LE));
        assertDecodes(text, bytes, charset);
        byte[] odd = Arrays.copyOf(bytes, bytes.length + 1);
        odd[bytes.length] = 'x';
        assertDecodes(text, odd, charset);
        ByteBuffer heap = ByteBuffer.wrap(bytes);
        text.set(heap, 0, bytes.length);
        assertEquals(StandardMatchersTest.decode(bytes, charset), text.chars().toString());
      }
    }
  }

  /** Writes each char as one unit, lone surrogates included. */
  private static byte[] units(String line, boolean little) {
    byte[] out = new byte[line.length() * 2];
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      out[2 * i + (little ? 0 : 1)] = (byte) c;
      out[2 * i + (little ? 1 : 0)] = (byte) (c >>> 8);
    }
    return out;
  }

  private static void assertDecodes(LineText text, byte[] bytes, Charset charset) {
    ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length + 3).order(ByteOrder.LITTLE_ENDIAN);
    direct.position(3);
    direct.put(bytes);
    text.set(direct, 3, 3 + bytes.length);
    assertEquals(
        StandardMatchersTest.decode(bytes, charset),
        text.chars().toString(),
        "length " + bytes.length);
  }
}

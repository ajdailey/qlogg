package io.github.ajdailey.qlogg.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LineDecoderTest {
  private static String decode(TextEncoding encoding, byte[] bytes, int capacity) {
    CharBuffer out = CharBuffer.allocate(capacity);
    int written = new LineDecoder(encoding).decodeLine(ByteBuffer.wrap(bytes), out);
    out.flip();
    assertEquals(written, out.remaining());
    return out.toString();
  }

  private static String decode(Charset charset, String text) {
    return decode(TextEncoding.of(charset, 0), text.getBytes(charset), 256);
  }

  @Test
  void dropsOneTrailingCarriageReturn() {
    assertEquals("line", decode(StandardCharsets.UTF_8, "line\r"));
    assertEquals("line\r", decode(StandardCharsets.UTF_8, "line\r\r"));
    assertEquals("a\rb", decode(StandardCharsets.UTF_8, "a\rb"));
    assertEquals("", decode(StandardCharsets.UTF_8, "\r"));
    assertEquals("", decode(StandardCharsets.UTF_8, ""));
  }

  @Test
  void utf16CarriageReturnUnit() {
    assertEquals("x\u0d0a", decode(StandardCharsets.UTF_16LE, "x\u0d0a\r"));
    assertEquals("x\u0d0a", decode(StandardCharsets.UTF_16BE, "x\u0d0a\r"));
  }

  @Test
  void malformedInputBecomesReplacement() {
    TextEncoding utf8 = TextEncoding.of(StandardCharsets.UTF_8, 0);
    assertEquals("a\ufffdb", decode(utf8, new byte[] {'a', (byte) 0xff, 'b'}, 16));
  }

  @Test
  void stopsWhenOutputIsFull() {
    TextEncoding utf8 = TextEncoding.of(StandardCharsets.UTF_8, 0);
    assertEquals("abc", decode(utf8, "abcdef".getBytes(StandardCharsets.UTF_8), 3));
  }
}

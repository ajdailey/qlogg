package io.github.ajdailey.qlogg.core.text;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Windows1252Test {
  private static final Charset JDK = Charset.forName("windows-1252");

  private static final int[] UNDEFINED = {0x81, 0x8d, 0x8f, 0x90, 0x9d};

  private static byte[] everyByte() {
    byte[] all = new byte[256];
    for (int b = 0; b < 256; b++) {
      all[b] = (byte) b;
    }
    return all;
  }

  @Test
  void everyByteDecodesToOneCharAndRoundTrips() throws CharacterCodingException {
    byte[] all = everyByte();
    String text = Windows1252.INSTANCE.newDecoder().decode(ByteBuffer.wrap(all)).toString();
    assertEquals(256, text.length());
    assertEquals(-1, text.indexOf('�'));
    String jdk = new String(all, JDK);
    for (int b = 0; b < 256; b++) {
      boolean undefined = false;
      for (int u : UNDEFINED) {
        undefined |= u == b;
      }
      assertEquals(undefined ? (char) b : jdk.charAt(b), text.charAt(b), Integer.toHexString(b));
    }
    ByteBuffer encoded = Windows1252.INSTANCE.newEncoder().encode(CharBuffer.wrap(text));
    byte[] back = new byte[encoded.remaining()];
    encoded.get(back);
    assertArrayEquals(all, back);
    assertArrayEquals(all, text.getBytes(Windows1252.INSTANCE));
  }

  @Test
  void anUnmappedCharDoesNotEncode() {
    CharsetEncoder encoder = Windows1252.INSTANCE.newEncoder();
    assertTrue(encoder.canEncode('€'));
    assertTrue(encoder.canEncode('\u0081'));
    assertFalse(encoder.canEncode('Ā'));
    assertFalse(encoder.canEncode('\u0080'));
    assertFalse(encoder.canEncode('�'));
    assertEquals(1.0f, encoder.maxBytesPerChar());
  }

  @Test
  void replacesTheJdkCharsetByName() {
    assertEquals(JDK, Windows1252.INSTANCE);
    assertSame(Windows1252.INSTANCE, Windows1252.substitute(JDK));
    assertSame(StandardCharsets.ISO_8859_1, Windows1252.substitute(StandardCharsets.ISO_8859_1));
    assertSame(Windows1252.INSTANCE, TextEncoding.of(JDK, 0).charset());
    assertTrue(TextEncoding.of(JDK, 0).asciiCompatible());
    assertNotEquals(
        JDK.newDecoder().getClass(), TextEncoding.of(JDK, 0).charset().newDecoder().getClass());
  }

  @Test
  void charsetsResolvesEveryAliasToTheWrapper() {
    assertEquals(JDK.aliases(), Windows1252.INSTANCE.aliases());
    assertSame(Windows1252.INSTANCE, Charsets.forName("windows-1252"));
    for (String alias : JDK.aliases()) {
      assertSame(Windows1252.INSTANCE, Charsets.forName(alias), alias);
    }
    assertSame(Windows1252.INSTANCE, Charsets.available().get("windows-1252"));
    assertSame(StandardCharsets.UTF_8, Charsets.forName("utf-8"));
    assertEquals(Charset.availableCharsets().keySet(), Charsets.available().keySet());
  }

  @Test
  void theFallbackDecodesUndefinedBytesAsC1() {
    byte[] line = {'a', (byte) 0x81, (byte) 0x8d, (byte) 0x8f, (byte) 0x90, (byte) 0x9d, 'b', '\r'};
    TextEncoding encoding = EncodingDetector.detect(ByteBuffer.wrap(line), JDK).encoding();
    CharBuffer out = CharBuffer.allocate(16);
    new LineDecoder(encoding).decodeLine(ByteBuffer.wrap(line), out);
    out.flip();
    assertEquals("a\u0081\u008d\u008f\u0090\u009db", out.toString());
  }
}

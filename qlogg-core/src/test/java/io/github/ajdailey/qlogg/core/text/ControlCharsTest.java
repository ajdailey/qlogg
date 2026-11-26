package io.github.ajdailey.qlogg.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.model.LineReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import org.junit.jupiter.api.Test;

/** The boxed ranges, TAB left out, the five undefined windows-1252 bytes and the box width. */
class ControlCharsTest {
  @Test
  void c0ExceptTabDelAndC1AreBoxed() {
    for (int c = 0; c <= 0xffff; c++) {
      boolean expected = c < 0x20 && c != 0x09 || c >= 0x7f && c <= 0x9f;
      assertEquals(expected, ControlChars.boxed((char) c), Integer.toHexString(c));
    }
  }

  @Test
  void aLabelIsTwoUppercaseHexDigits() {
    assertEquals("00", ControlChars.label('\u0000'));
    assertEquals("1B", ControlChars.label('\u001b'));
    assertEquals("7F", ControlChars.label('\u007f'));
    assertEquals("9D", ControlChars.label('\u009d'));
  }

  @Test
  void undefinedWindows1252BytesDecodeToBoxedChars() throws Exception {
    byte[] bytes = {(byte) 0x81, (byte) 0x8d, (byte) 0x8f, (byte) 0x90, (byte) 0x9d};
    CharBuffer chars = Windows1252.INSTANCE.newDecoder().decode(ByteBuffer.wrap(bytes));
    assertEquals("\u0081\u008d\u008f\u0090\u009d", chars.toString());
    for (int i = 0; i < chars.length(); i++) {
      assertTrue(ControlChars.boxed(chars.charAt(i)));
    }
  }

  @Test
  void aBoxedCharTakesTwoColumnsAndATabKeepsItsStop() {
    assertEquals(3, LineReader.advance(1, '\u0001'));
    assertEquals(4, LineReader.advance(2, '\u0085'));
    assertEquals(LineReader.TAB_WIDTH, LineReader.advance(1, '\t'));
    assertEquals(2, LineReader.advance(1, ' '));
  }
}

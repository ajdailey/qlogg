package io.github.ajdailey.qlogg.core.text;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class EncodingDetectorTest {
  private static final Charset LATIN1 = StandardCharsets.ISO_8859_1;

  private static EncodingDetection detect(byte[] head) {
    return EncodingDetector.detect(ByteBuffer.wrap(head), LATIN1);
  }

  private static byte[] withBom(int[] bom, byte[] body) {
    byte[] bytes = new byte[bom.length + body.length];
    for (int i = 0; i < bom.length; i++) {
      bytes[i] = (byte) bom[i];
    }
    System.arraycopy(body, 0, bytes, bom.length, body.length);
    return bytes;
  }

  @Test
  void byteOrderMarks() {
    EncodingDetection utf8 =
        detect(withBom(new int[] {0xef, 0xbb, 0xbf}, "x\n".getBytes(StandardCharsets.UTF_8)));
    assertEquals(DetectionReason.BOM, utf8.reason());
    assertEquals(StandardCharsets.UTF_8, utf8.encoding().charset());
    assertEquals(3, utf8.encoding().bomLength());
    EncodingDetection le =
        detect(withBom(new int[] {0xff, 0xfe}, "x\n".getBytes(StandardCharsets.UTF_16LE)));
    assertEquals(StandardCharsets.UTF_16LE, le.encoding().charset());
    assertEquals(ByteOrder.LITTLE_ENDIAN, le.encoding().order());
    assertEquals(2, le.encoding().bomLength());
    EncodingDetection be =
        detect(withBom(new int[] {0xfe, 0xff}, "x\n".getBytes(StandardCharsets.UTF_16BE)));
    assertEquals(StandardCharsets.UTF_16BE, be.encoding().charset());
    assertEquals(ByteOrder.BIG_ENDIAN, be.encoding().order());
  }

  /** Regression: the UTF-32LE BOM matched the UTF-16LE rule and the UTF-32BE BOM matched none. */
  @Test
  void utf32ByteOrderMarksComeBeforeUtf16() {
    Charset le32 = Charset.forName("UTF-32LE");
    Charset be32 = Charset.forName("UTF-32BE");
    EncodingDetection le = detect(withBom(new int[] {0xff, 0xfe, 0, 0}, "x\n".getBytes(le32)));
    assertEquals(DetectionReason.BOM, le.reason());
    assertEquals(le32, le.encoding().charset());
    assertEquals(4, le.encoding().unitWidth());
    assertEquals(ByteOrder.LITTLE_ENDIAN, le.encoding().order());
    assertEquals(4, le.encoding().bomLength());
    EncodingDetection be = detect(withBom(new int[] {0, 0, 0xfe, 0xff}, "x\n".getBytes(be32)));
    assertEquals(DetectionReason.BOM, be.reason());
    assertEquals(be32, be.encoding().charset());
    assertEquals(4, be.encoding().bomLength());
    ByteBuffer leBom = ByteBuffer.wrap(withBom(new int[] {0xff, 0xfe, 0, 0}, "x\n".getBytes(le32)));
    EncodingDetection sensing = EncodingDetector.forced(Charset.forName("UTF-32"), leBom);
    assertEquals(le32, sensing.encoding().charset());
    assertEquals(4, sensing.encoding().bomLength());
    EncodingDetection utf16 = EncodingDetector.forced(StandardCharsets.UTF_16LE, leBom);
    assertEquals(StandardCharsets.UTF_16LE, utf16.encoding().charset());
    assertEquals(2, utf16.encoding().bomLength());
  }

  @Test
  void utf16PatternWithoutBom() {
    String text = "2026-11-01 mock event\nsecond line\n";
    EncodingDetection le = detect(text.getBytes(StandardCharsets.UTF_16LE));
    assertEquals(DetectionReason.UTF16_PATTERN, le.reason());
    assertEquals(StandardCharsets.UTF_16LE, le.encoding().charset());
    assertEquals(0, le.encoding().bomLength());
    EncodingDetection be = detect(text.getBytes(StandardCharsets.UTF_16BE));
    assertEquals(StandardCharsets.UTF_16BE, be.encoding().charset());
  }

  @Test
  void zerosAtBothParitiesAreNotUtf16() {
    EncodingDetection zeros = detect(new byte[64]);
    assertEquals(DetectionReason.UTF8_VALID, zeros.reason());
  }

  @Test
  void validUtf8IncludingTruncatedEnd() {
    byte[] text = "caf\u00e9 \u65e5\u672c\n".getBytes(StandardCharsets.UTF_8);
    assertEquals(DetectionReason.UTF8_VALID, detect(text).reason());
    byte[] truncated = Arrays.copyOf(text, text.length - 2);
    assertEquals(DetectionReason.UTF8_VALID, detect(truncated).reason());
    assertEquals(DetectionReason.UTF8_VALID, detect(new byte[0]).reason());
  }

  @Test
  void malformedUtf8FallsBack() {
    byte[] latin1 = "caf\u00e9 cr\u00e8me\n".getBytes(LATIN1);
    EncodingDetection detection = detect(latin1);
    assertEquals(DetectionReason.FALLBACK, detection.reason());
    assertEquals(LATIN1, detection.encoding().charset());
  }

  @Test
  void headIsNotModified() {
    ByteBuffer head = ByteBuffer.wrap("abc\n".getBytes(StandardCharsets.US_ASCII));
    head.position(1);
    EncodingDetector.detect(head, LATIN1);
    assertEquals(1, head.position());
    assertEquals(4, head.limit());
  }

  @Test
  void forcedCharsetSkipsItsOwnBomOnly() {
    ByteBuffer utf8Bom =
        ByteBuffer.wrap(withBom(new int[] {0xef, 0xbb, 0xbf}, new byte[] {'a', '\n'}));
    EncodingDetection utf8 = EncodingDetector.forced(StandardCharsets.UTF_8, utf8Bom);
    assertEquals(DetectionReason.FORCED, utf8.reason());
    assertEquals(3, utf8.encoding().bomLength());
    assertEquals(0, EncodingDetector.forced(LATIN1, utf8Bom).encoding().bomLength());
    ByteBuffer leBom =
        ByteBuffer.wrap(withBom(new int[] {0xff, 0xfe}, "a\n".getBytes(StandardCharsets.UTF_16LE)));
    EncodingDetection sensing = EncodingDetector.forced(StandardCharsets.UTF_16, leBom);
    assertEquals(StandardCharsets.UTF_16LE, sensing.encoding().charset());
    assertEquals(2, sensing.encoding().bomLength());
    EncodingDetection plain =
        EncodingDetector.forced(StandardCharsets.UTF_16, ByteBuffer.wrap(new byte[] {0, 'a'}));
    assertEquals(StandardCharsets.UTF_16BE, plain.encoding().charset());
    assertEquals(0, plain.encoding().bomLength());
  }

  @Test
  void textEncodingUnits() {
    TextEncoding utf16 = TextEncoding.of(StandardCharsets.UTF_16, 0);
    assertEquals(StandardCharsets.UTF_16BE, utf16.charset());
    assertEquals(2, utf16.unitWidth());
    assertArrayEquals(new byte[] {0, '\n'}, utf16.lineFeed());
    assertArrayEquals(
        new byte[] {'\r', 0}, TextEncoding.of(StandardCharsets.UTF_16LE, 0).carriageReturn());
    assertEquals(4, TextEncoding.of(Charset.forName("UTF-32"), 0).unitWidth());
    assertTrue(TextEncoding.of(StandardCharsets.UTF_8, 0).asciiCompatible());
    assertTrue(TextEncoding.of(LATIN1, 0).asciiCompatible());
    assertFalse(utf16.asciiCompatible());
    if (Charset.isSupported("IBM037")) {
      TextEncoding ebcdic = TextEncoding.of(Charset.forName("IBM037"), 0);
      assertFalse(ebcdic.asciiCompatible());
      assertEquals(1, ebcdic.unitWidth());
    }
  }
}

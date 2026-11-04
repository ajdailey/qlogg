package io.github.ajdailey.qlogg.core.text;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Chooses a file's encoding from its first bytes. Order: a forced charset, then a BOM (UTF-8,
 * UTF-32 LE/BE, UTF-16 LE/BE), then the UTF-16 pattern, then valid UTF-8, then the fallback. The
 * UTF-32LE BOM {@code FF FE 00 00} is checked before the UTF-16LE BOM it starts with, so a UTF-16LE
 * file whose first char is U+0000 is detected as UTF-32LE.
 *
 * <p>UTF-16 pattern: at least {@link #UTF16_ZERO_PERCENT}% of the bytes at one parity are zero and
 * under {@link #UTF16_OTHER_PERCENT}% at the other; zeros at odd offsets mean little-endian. Valid
 * UTF-8 allows a sequence truncated by the end of the sample. All-ASCII text is valid UTF-8.
 */
public final class EncodingDetector {
  /** Bytes of the file head to pass in. */
  public static final int SAMPLE_BYTES = 64 * 1024;

  /** Least share of zero bytes, in percent, at the parity that marks UTF-16. */
  public static final int UTF16_ZERO_PERCENT = 30;

  /** Greatest share of zero bytes, in percent, at the other parity. */
  public static final int UTF16_OTHER_PERCENT = 5;

  private static final int DECODE_CHARS = 8192;

  private EncodingDetector() {}

  /**
   * Detects the encoding of a file head.
   *
   * @param head the first {@link #SAMPLE_BYTES} bytes, or the whole file when shorter, between
   *     position and limit; not modified
   * @param fallback used when no rule matches; ISO-8859-1 maps every byte to a char
   * @return the encoding and the rule that chose it
   */
  public static EncodingDetection detect(ByteBuffer head, Charset fallback) {
    ByteBuffer sample = head.duplicate();
    TextEncoding bom = bom(sample);
    if (bom != null) {
      return new EncodingDetection(bom, DetectionReason.BOM);
    }
    TextEncoding utf16 = utf16Pattern(sample);
    if (utf16 != null) {
      return new EncodingDetection(utf16, DetectionReason.UTF16_PATTERN);
    }
    if (validUtf8(sample)) {
      return new EncodingDetection(
          TextEncoding.of(StandardCharsets.UTF_8, 0), DetectionReason.UTF8_VALID);
    }
    return new EncodingDetection(TextEncoding.of(fallback, 0), DetectionReason.FALLBACK);
  }

  /**
   * Applies a charset the user chose. A BOM for that charset is skipped; a BOM-sensing {@code
   * UTF-16} or {@code UTF-32} follows the BOM's byte order. For a UTF-16 charset only the 2-byte
   * BOMs count, so {@code FF FE 00 00} is a UTF-16LE BOM and a U+0000.
   *
   * @param charset the chosen charset
   * @param head as for {@link #detect}
   * @return the encoding, reason {@link DetectionReason#FORCED}
   */
  public static EncodingDetection forced(Charset charset, ByteBuffer head) {
    TextEncoding chosen = TextEncoding.of(charset, 0);
    ByteBuffer sample = head.duplicate();
    TextEncoding bom = chosen.unitWidth() == 2 ? utf16Bom(sample) : bom(sample);
    if (bom != null) {
      boolean sensing =
          (charset.equals(StandardCharsets.UTF_16) || charset.name().equals("UTF-32"))
              && bom.unitWidth() == chosen.unitWidth();
      if (sensing || bom.charset().equals(chosen.charset())) {
        chosen = bom;
      }
    }
    return new EncodingDetection(chosen, DetectionReason.FORCED);
  }

  private static TextEncoding bom(ByteBuffer sample) {
    int at = sample.position();
    int length = sample.remaining();
    if (length >= 3
        && (sample.get(at) & 0xff) == 0xef
        && (sample.get(at + 1) & 0xff) == 0xbb
        && (sample.get(at + 2) & 0xff) == 0xbf) {
      return TextEncoding.of(StandardCharsets.UTF_8, 3);
    }
    if (length >= 4) {
      int first = sample.get(at) & 0xff;
      int second = sample.get(at + 1) & 0xff;
      int third = sample.get(at + 2) & 0xff;
      int fourth = sample.get(at + 3) & 0xff;
      if (first == 0xff && second == 0xfe && third == 0 && fourth == 0) {
        return TextEncoding.of(Charset.forName("UTF-32LE"), 4);
      }
      if (first == 0 && second == 0 && third == 0xfe && fourth == 0xff) {
        return TextEncoding.of(Charset.forName("UTF-32BE"), 4);
      }
    }
    return utf16Bom(sample);
  }

  private static TextEncoding utf16Bom(ByteBuffer sample) {
    int at = sample.position();
    int length = sample.remaining();
    if (length >= 2) {
      int first = sample.get(at) & 0xff;
      int second = sample.get(at + 1) & 0xff;
      if (first == 0xff && second == 0xfe) {
        return TextEncoding.of(StandardCharsets.UTF_16LE, 2);
      }
      if (first == 0xfe && second == 0xff) {
        return TextEncoding.of(StandardCharsets.UTF_16BE, 2);
      }
    }
    return null;
  }

  private static TextEncoding utf16Pattern(ByteBuffer sample) {
    int at = sample.position();
    long units = sample.remaining() / 2;
    if (units == 0) {
      return null;
    }
    long evenZeros = 0;
    long oddZeros = 0;
    for (int i = 0; i < units; i++) {
      if (sample.get(at + 2 * i) == 0) {
        evenZeros++;
      }
      if (sample.get(at + 2 * i + 1) == 0) {
        oddZeros++;
      }
    }
    if (oddZeros * 100 >= units * UTF16_ZERO_PERCENT
        && evenZeros * 100 < units * UTF16_OTHER_PERCENT) {
      return TextEncoding.of(StandardCharsets.UTF_16LE, 0);
    }
    if (evenZeros * 100 >= units * UTF16_ZERO_PERCENT
        && oddZeros * 100 < units * UTF16_OTHER_PERCENT) {
      return TextEncoding.of(StandardCharsets.UTF_16BE, 0);
    }
    return null;
  }

  private static boolean validUtf8(ByteBuffer sample) {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer in = sample.duplicate();
    CharBuffer out = CharBuffer.allocate(DECODE_CHARS);
    while (true) {
      CoderResult result = decoder.decode(in, out, false);
      if (result.isError()) {
        return false;
      }
      if (result.isUnderflow()) {
        return true;
      }
      out.clear();
    }
  }
}

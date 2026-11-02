package io.github.ajdailey.qlogg.core.text;

import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A charset as the line index sees it: the width of the unit LF is encoded in and where the text
 * starts after a byte order mark.
 *
 * @param charset decodes the text; never a BOM-sensing {@code UTF-16} or {@code UTF-32}
 * @param unitWidth bytes in the encoded LF: 1, 2 for UTF-16, 4 for UTF-32
 * @param order byte order of a multi-byte unit; {@code BIG_ENDIAN} when the width is 1
 * @param bomLength bytes of byte order mark before the first line
 */
public record TextEncoding(Charset charset, int unitWidth, ByteOrder order, int bomLength) {
  private static final String ASCII_SAMPLE;

  static {
    StringBuilder sample = new StringBuilder("\t\n\r");
    for (char c = 0x20; c < 0x7f; c++) {
      sample.append(c);
    }
    ASCII_SAMPLE = sample.toString();
  }

  /** Checks the components. */
  public TextEncoding {
    if (unitWidth != 1 && unitWidth != 2 && unitWidth != 4) {
      throw new IllegalArgumentException("unitWidth must be 1, 2 or 4");
    }
    if (bomLength < 0 || bomLength % unitWidth != 0) {
      throw new IllegalArgumentException("bomLength must be a whole number of units");
    }
  }

  /**
   * Derives the unit width and byte order from a charset. {@code UTF-16} and {@code UTF-32}, which
   * write a BOM when encoding, are taken as their big-endian forms.
   *
   * @param charset the charset
   * @param bomLength bytes of byte order mark before the first line
   * @return the encoding
   * @throws IllegalArgumentException when the charset cannot encode LF
   */
  public static TextEncoding of(Charset charset, int bomLength) {
    Charset plain = charset;
    if (charset.equals(StandardCharsets.UTF_16)) {
      plain = StandardCharsets.UTF_16BE;
    } else if (charset.name().equals("UTF-32")) {
      plain = Charset.forName("UTF-32BE");
    }
    if (!plain.canEncode()) {
      throw new IllegalArgumentException(plain.name() + " cannot encode");
    }
    byte[] one = "\n".getBytes(plain);
    byte[] two = "\n\n".getBytes(plain);
    int width = two.length - one.length;
    ByteOrder order = ByteOrder.BIG_ENDIAN;
    if (width > 1 && one[one.length - 1] != '\n') {
      order = ByteOrder.LITTLE_ENDIAN;
    }
    return new TextEncoding(plain, width, order, bomLength);
  }

  /**
   * Returns LF encoded in this charset, without a BOM.
   *
   * @return {@code unitWidth} bytes
   */
  public byte[] lineFeed() {
    return unit("\n");
  }

  /**
   * Returns CR encoded in this charset, without a BOM.
   *
   * @return {@code unitWidth} bytes
   */
  public byte[] carriageReturn() {
    return unit("\r");
  }

  /**
   * Reports whether every printable ASCII character, tab, CR and LF encodes as its own byte (UTF-8,
   * ISO-8859-x, windows-125x).
   *
   * @return true for an ASCII-compatible charset
   */
  public boolean asciiCompatible() {
    if (unitWidth != 1) {
      return false;
    }
    byte[] encoded = ASCII_SAMPLE.getBytes(charset);
    return Arrays.equals(encoded, ASCII_SAMPLE.getBytes(StandardCharsets.US_ASCII));
  }

  private byte[] unit(String text) {
    byte[] encoded = text.getBytes(charset);
    return Arrays.copyOfRange(encoded, encoded.length - unitWidth, encoded.length);
  }
}

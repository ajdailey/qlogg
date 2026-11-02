package io.github.ajdailey.qlogg.core.text;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/**
 * Decodes one line's bytes for display. Malformed and unmappable input becomes U+FFFD. Not
 * thread-safe: one instance per thread.
 */
public final class LineDecoder {
  private final CharsetDecoder decoder;
  private final byte[] carriageReturn;
  private boolean droppedCarriageReturn;

  /**
   * Creates a decoder.
   *
   * @param encoding the file's encoding
   */
  public LineDecoder(TextEncoding encoding) {
    this.decoder =
        encoding
            .charset()
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    this.carriageReturn = encoding.carriageReturn();
  }

  /**
   * Decodes a line without its LF, dropping one trailing CR unit. A lone CR elsewhere stays in the
   * text. Output stops when {@code out} is full.
   *
   * @param bytes the line between position and limit; its position advances past what was decoded
   * @param out receives the chars
   * @return chars written
   */
  public int decodeLine(ByteBuffer bytes, CharBuffer out) {
    int limit = bytes.limit();
    droppedCarriageReturn = endsWithCarriageReturn(bytes);
    int end = droppedCarriageReturn ? limit - carriageReturn.length : limit;
    int start = out.position();
    bytes.limit(end);
    try {
      decoder.reset();
      decoder.decode(bytes, out, true);
      decoder.flush(out);
    } finally {
      bytes.limit(limit);
    }
    if (end < limit && bytes.position() == end) {
      bytes.position(limit);
    }
    return out.position() - start;
  }

  /**
   * Reports whether the last {@link #decodeLine} dropped a trailing CR.
   *
   * @return true for a line that ended with CR
   */
  public boolean droppedCarriageReturn() {
    return droppedCarriageReturn;
  }

  private boolean endsWithCarriageReturn(ByteBuffer bytes) {
    int width = carriageReturn.length;
    if (bytes.remaining() < width) {
      return false;
    }
    int at = bytes.limit() - width;
    for (int i = 0; i < width; i++) {
      if (bytes.get(at + i) != carriageReturn[i]) {
        return false;
      }
    }
    return true;
  }
}

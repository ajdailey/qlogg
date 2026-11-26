package io.github.ajdailey.qlogg.core.columns;

import io.github.ajdailey.qlogg.core.text.EncodingDetector;
import io.github.ajdailey.qlogg.core.text.LineDecoder;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;

/**
 * A file head decoded after its BOM, with its first non-empty lines. A head as long as {@link
 * EncodingDetector#SAMPLE_BYTES} is cut from a longer file, so its unterminated last line is left
 * out.
 */
final class HeadText {
  private final CharBuffer chars;
  private final boolean cut;
  private final int[] starts;
  private final int[] ends;
  private int lines;

  HeadText(ByteBuffer head, TextEncoding encoding, int maxLines) {
    ByteBuffer bytes = head.duplicate();
    bytes.position(Math.min(bytes.limit(), bytes.position() + encoding.bomLength()));
    cut = head.remaining() >= EncodingDetector.SAMPLE_BYTES;
    chars = CharBuffer.allocate(bytes.remaining());
    new LineDecoder(encoding).decodeLine(bytes, chars);
    chars.flip();
    starts = new int[maxLines];
    ends = new int[maxLines];
    int start = 0;
    int length = chars.length();
    while (start < length && lines < maxLines) {
      int end = start;
      while (end < length && chars.charAt(end) != '\n') {
        end++;
      }
      if (end == length && cut) {
        break;
      }
      int contentEnd = end > start && chars.charAt(end - 1) == '\r' ? end - 1 : end;
      if (contentEnd > start) {
        starts[lines] = start;
        ends[lines] = contentEnd;
        lines++;
      }
      start = end + 1;
    }
  }

  /** Returns the decoded head. */
  CharSequence chars() {
    return chars;
  }

  /** Reports whether the head is cut from a longer file. */
  boolean cut() {
    return cut;
  }

  /** Returns the number of non-empty lines sampled. */
  int lines() {
    return lines;
  }

  /** Returns a sampled line's first index. */
  int start(int line) {
    return starts[line];
  }

  /** Returns the index after a sampled line's last char, CR excluded. */
  int end(int line) {
    return ends[line];
  }
}

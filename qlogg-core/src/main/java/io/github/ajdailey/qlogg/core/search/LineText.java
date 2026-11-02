package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.text.AnsiCodes;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;

/**
 * One line as a matcher sees it: its encoded bytes in a shared buffer, terminator excluded, and its
 * decoded chars on demand. Malformed input decodes to U+FFFD. The chars are read through a {@link
 * CharSequence} that calls {@link CancellationToken#check} every {@link #CHECK_INTERVAL} {@code
 * charAt} calls, so a regex that backtracks without end stops when the search is canceled.
 *
 * <p>Not thread-safe: one instance per worker thread, reused for every line.
 */
public final class LineText {
  /** {@code charAt} calls between cancellation checks. */
  public static final int CHECK_INTERVAL = 4096;

  /** Char scratch kept between lines; a longer line's scratch is dropped by {@link #trim}. */
  static final int KEPT_CHARS = 1 << 16;

  private final CharsetDecoder decoder;
  private final CancellationToken token;
  private final boolean hideAnsi;
  private final Chars chars = new Chars();
  private ByteBuffer buffer = ByteBuffer.allocate(0);
  private int start;
  private int end;
  private char[] scratch = new char[256];
  private int length;
  private boolean decoded;
  private int calls;

  /**
   * Creates an empty line.
   *
   * @param encoding decodes the bytes
   * @param token checked while the chars are read
   */
  public LineText(TextEncoding encoding, CancellationToken token) {
    this(encoding, token, false);
  }

  /**
   * Creates an empty line whose chars may have ANSI color sequences removed.
   *
   * @param encoding decodes the bytes
   * @param token checked while the chars are read
   * @param hideAnsi true to remove ANSI SGR sequences from {@link #chars}
   */
  public LineText(TextEncoding encoding, CancellationToken token, boolean hideAnsi) {
    this.hideAnsi = hideAnsi;
    this.decoder =
        encoding
            .charset()
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    this.token = token;
  }

  /**
   * Points the line at bytes of a buffer. The buffer's position and limit are not used or changed.
   *
   * @param bytes holds the line
   * @param from the line's first byte
   * @param to the line's exclusive end, terminator excluded
   */
  public void set(ByteBuffer bytes, int from, int to) {
    buffer = bytes;
    start = from;
    end = to;
    decoded = false;
  }

  /**
   * Returns the buffer holding the line's bytes.
   *
   * @return the buffer; read it with absolute gets between {@link #start} and {@link #end}
   */
  public ByteBuffer buffer() {
    return buffer;
  }

  /**
   * Returns the line's first byte in {@link #buffer}.
   *
   * @return an index
   */
  public int start() {
    return start;
  }

  /**
   * Returns the line's exclusive end in {@link #buffer}, terminator excluded.
   *
   * @return an index
   */
  public int end() {
    return end;
  }

  /**
   * Returns the decoded line, decoding it on the first call for this line.
   *
   * @return chars valid until the next {@link #set}
   */
  public CharSequence chars() {
    if (!decoded) {
      decode();
      decoded = true;
    }
    return chars;
  }

  /** Drops a char scratch grown past {@link #KEPT_CHARS} by a long line. */
  void trim() {
    if (scratch.length > KEPT_CHARS) {
      scratch = new char[256];
    }
  }

  private void decode() {
    int bytes = end - start;
    int need =
        (int) Math.min(Integer.MAX_VALUE - 8L, (long) (bytes * decoder.maxCharsPerByte()) + 1);
    ByteBuffer view = buffer.duplicate();
    while (true) {
      if (scratch.length < need) {
        scratch = new char[need];
      }
      view.limit(end).position(start);
      CharBuffer out = CharBuffer.wrap(scratch);
      decoder.reset();
      CoderResult result = decoder.decode(view, out, true);
      if (!result.isOverflow()) {
        result = decoder.flush(out);
      }
      if (!result.isOverflow()) {
        length = hideAnsi ? AnsiCodes.strip(scratch, out.position()) : out.position();
        return;
      }
      need = (int) Math.min(Integer.MAX_VALUE - 8L, scratch.length * 2L);
    }
  }

  /** The decoded chars, checking the token as a regex reads them. */
  private final class Chars implements CharSequence {
    @Override
    public int length() {
      return length;
    }

    @Override
    public char charAt(int index) {
      if (++calls >= CHECK_INTERVAL) {
        calls = 0;
        token.check();
      }
      if (index < 0 || index >= length) {
        throw new IndexOutOfBoundsException(index);
      }
      return scratch[index];
    }

    @Override
    public CharSequence subSequence(int from, int to) {
      return new String(scratch, from, to - from);
    }

    @Override
    public String toString() {
      return new String(scratch, 0, length);
    }
  }
}

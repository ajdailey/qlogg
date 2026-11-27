package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.text.AnsiCodes;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

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

  /** Heap bytes a line in a direct buffer is copied through while it decodes. */
  static final int WINDOW_BYTES = 8192;

  /** Char scratch kept between lines; a longer line's scratch is dropped by {@link #trim}. */
  static final int KEPT_CHARS = 1 << 16;

  private final CharsetDecoder decoder;
  private final ByteOrder utf16;
  private final boolean ascii;
  private final CancellationToken token;
  private final boolean hideAnsi;
  private final Chars chars = new Chars();
  private final Checked checked = new Checked();
  private final ByteBuffer window = ByteBuffer.allocate(WINDOW_BYTES);
  private ByteBuffer buffer = ByteBuffer.allocate(0);
  private ByteBuffer units;
  private int start;
  private int end;
  private char[] scratch = new char[256];
  private CharBuffer view;
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
    Charset charset = encoding.charset();
    if (charset.equals(StandardCharsets.UTF_16LE)) {
      utf16 = ByteOrder.LITTLE_ENDIAN;
    } else if (charset.equals(StandardCharsets.UTF_16BE)) {
      utf16 = ByteOrder.BIG_ENDIAN;
    } else {
      utf16 = null;
    }
    ascii =
        charset.equals(StandardCharsets.UTF_8)
            || charset.equals(StandardCharsets.US_ASCII)
            || charset.equals(StandardCharsets.ISO_8859_1);
  }

  /**
   * Points the line at bytes of a buffer. The buffer's position and limit are not used or changed.
   *
   * @param bytes holds the line
   * @param from the line's first byte
   * @param to the line's exclusive end, terminator excluded
   */
  public void set(ByteBuffer bytes, int from, int to) {
    if (bytes != buffer) {
      units = null;
    }
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

  /**
   * Returns the decoded line as an array-backed buffer without cancellation checks, for a read that
   * takes time linear in the line, such as a {@link
   * io.github.ajdailey.qlogg.core.columns.FieldParser} parse.
   *
   * @return the chars from position 0 to the limit, valid until the next {@link #set}
   */
  CharBuffer charBuffer() {
    chars();
    if (view == null || view.array() != scratch) {
      view = CharBuffer.wrap(scratch);
    }
    view.clear().limit(length);
    return view;
  }

  /**
   * Returns a value read from this line, such as a parsed column value, through a view that checks
   * the cancellation token every {@link #CHECK_INTERVAL} {@code charAt} calls, as {@link #chars}
   * does. The view is reused: the next call points it at another value.
   *
   * @param value the chars
   * @return the checked view of {@code value}
   */
  CharSequence checked(CharSequence value) {
    checked.value = value;
    return checked;
  }

  /** Drops a char scratch grown past {@link #KEPT_CHARS} by a long line. */
  void trim() {
    if (scratch.length > KEPT_CHARS) {
      scratch = new char[256];
    }
  }

  /**
   * Decodes into the char scratch, doubling it on overflow. A heap buffer decodes in place; a
   * direct buffer is copied through {@link #WINDOW_BYTES} of heap scratch, because the charset
   * decoders' array loops (with their ASCII intrinsics) run only on heap buffers.
   */
  private void decode() {
    if (utf16 != null && decodeUtf16()) {
      return;
    }
    if (ascii && decodeAscii()) {
      return;
    }
    int bytes = end - start;
    int need =
        (int) Math.min(Integer.MAX_VALUE - 8L, (long) (bytes * decoder.maxCharsPerByte()) + 1);
    while (true) {
      if (scratch.length < need) {
        scratch = new char[need];
      }
      CharBuffer out = CharBuffer.wrap(scratch);
      decoder.reset();
      CoderResult result = buffer.hasArray() ? decodeHeap(out) : decodeDirect(out);
      if (!result.isOverflow()) {
        length = hideAnsi ? AnsiCodes.strip(scratch, out.position()) : out.position();
        return;
      }
      need = (int) Math.min(Integer.MAX_VALUE - 8L, scratch.length * 2L);
    }
  }

  /**
   * Widens ASCII bytes to chars, which UTF-8, US-ASCII and ISO-8859-1 decode to themselves. Returns
   * false, leaving the line to the decoder, at a byte of 0x80 or more.
   */
  private boolean decodeAscii() {
    int bytes = end - start;
    if (scratch.length < bytes) {
      scratch = new char[bytes];
    }
    for (int i = 0; i < bytes; i++) {
      byte b = buffer.get(start + i);
      if (b < 0) {
        return false;
      }
      scratch[i] = (char) b;
    }
    length = hideAnsi ? AnsiCodes.strip(scratch, bytes) : bytes;
    return true;
  }

  /**
   * Copies UTF-16 units to chars. Returns false, leaving the line to the decoder, at an odd byte
   * count, a surrogate or U+FFFE, which the decoder pairs or replaces.
   */
  private boolean decodeUtf16() {
    int bytes = end - start;
    if ((bytes & 1) != 0) {
      return false;
    }
    int count = bytes >>> 1;
    if (scratch.length < count) {
      scratch = new char[count];
    }
    if (units == null) {
      units = buffer.duplicate().clear().order(utf16);
    }
    for (int i = 0; i < count; i++) {
      char c = units.getChar(start + 2 * i);
      if (Character.isSurrogate(c) || c == '\ufffe') {
        return false;
      }
      scratch[i] = c;
    }
    length = hideAnsi ? AnsiCodes.strip(scratch, count) : count;
    return true;
  }

  private CoderResult decodeHeap(CharBuffer out) {
    ByteBuffer view = buffer.duplicate();
    view.limit(end).position(start);
    CoderResult result = decoder.decode(view, out, true);
    return result.isOverflow() ? result : decoder.flush(out);
  }

  /** Feeds the line through the window; bytes of a split sequence carry to the next pass. */
  private CoderResult decodeDirect(CharBuffer out) {
    ByteBuffer in = window.clear().limit(0);
    int next = start;
    while (true) {
      in.compact();
      int n = Math.min(in.remaining(), end - next);
      buffer.get(next, in.array(), in.position(), n);
      in.position(in.position() + n);
      next += n;
      in.flip();
      boolean last = next == end;
      CoderResult result = decoder.decode(in, out, last);
      if (result.isOverflow()) {
        return result;
      }
      if (last) {
        return decoder.flush(out);
      }
    }
  }

  /** A value's chars, checking the token as a regex reads them. */
  private final class Checked implements CharSequence {
    private CharSequence value = "";

    @Override
    public int length() {
      return value.length();
    }

    @Override
    public char charAt(int index) {
      if (++calls >= CHECK_INTERVAL) {
        calls = 0;
        token.check();
      }
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int from, int to) {
      return value.subSequence(from, to).toString();
    }

    @Override
    public String toString() {
      return value.toString();
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

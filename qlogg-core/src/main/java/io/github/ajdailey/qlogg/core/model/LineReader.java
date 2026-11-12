package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.text.AnsiCodes;
import io.github.ajdailey.qlogg.core.text.LineDecoder;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.function.BooleanSupplier;

/**
 * Reads and decodes lines of one document through the block cache. Only the first {@link
 * #MAX_LINE_BYTES} of a line are read, so a longer line shows and copies its first MiB. Display
 * columns count each char as one column, except a tab, which advances to the next multiple of
 * {@link #TAB_WIDTH}, and the low half of a surrogate pair, which takes none. A column window or
 * selection keeps or drops a surrogate pair whole.
 *
 * <p>While {@code hideAnsi} reports true, ANSI SGR color sequences are removed at decode ({@link
 * AnsiCodes}), so display, copy, highlighting and columns all see the stripped text.
 *
 * <p>Not thread-safe: one thread uses an instance at a time. Its scratch buffers grow to the
 * longest line read, at most {@link #MAX_LINE_BYTES} bytes and as many chars.
 */
public final class LineReader {
  /** Columns per tab stop. */
  public static final int TAB_WIDTH = 8;

  /** Bytes of a line read for display and copy. */
  public static final int MAX_LINE_BYTES = 1 << 20;

  private static final int INITIAL_BYTES = 4096;

  private final ByteSource source;
  private final BlockCache cache;
  private final TextEncoding encoding;
  private final LineDecoder decoder;
  private final BooleanSupplier hideAnsi;
  private ByteBuffer bytes = ByteBuffer.allocate(INITIAL_BYTES);
  private CharBuffer chars = CharBuffer.allocate(INITIAL_BYTES);

  LineReader(ByteSource source, BlockCache cache, TextEncoding encoding) {
    this(
        source,
        cache,
        encoding,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return false;
          }
        });
  }

  LineReader(ByteSource source, BlockCache cache, TextEncoding encoding, BooleanSupplier hideAnsi) {
    this.source = source;
    this.cache = cache;
    this.encoding = encoding;
    this.decoder = new LineDecoder(encoding);
    this.hideAnsi = hideAnsi;
  }

  /**
   * Returns the column after a char.
   *
   * @param column the char's column
   * @param c the char
   * @return the next char's column
   */
  public static long advance(long column, char c) {
    if (c == '\t') {
      return (column / TAB_WIDTH + 1) * TAB_WIDTH;
    }
    return Character.isLowSurrogate(c) ? column : column + 1;
  }

  /**
   * Decodes lines cut to a column window. Lines past the end of the index are left out.
   *
   * @param index the snapshot to read
   * @param first the first 0-based line
   * @param count lines wanted
   * @param firstColumn first display column to keep
   * @param columns width of the window
   * @return the block
   * @throws IOException on a read error, including after the document closes
   */
  public LineBlock read(LineIndex index, long first, int count, long firstColumn, int columns)
      throws IOException {
    long available = Math.max(0, index.lineCount() - first);
    int n = (int) Math.min(Math.max(0, count), available);
    String[] lines = new String[n];
    long[] widths = new long[n];
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < n; i++) {
      widths[i] = cut(decode(index, first + i), firstColumn, firstColumn + columns, out);
      lines[i] = out.toString();
    }
    return new LineBlock(first, firstColumn, columns, lines, widths, null, null);
  }

  /**
   * Decodes listed lines as consecutive rows, cut to a column window, for the filtered view.
   *
   * @param index the snapshot to read
   * @param firstRow the row of {@code sourceLines[0]}
   * @param sourceLines ascending 0-based lines; lines past the end of the index are left out
   * @param count lines used from the start of the array
   * @param firstColumn first display column to keep
   * @param columns width of the window
   * @return the block, whose {@link LineBlock#sourceLine} gives each row's line
   * @throws IOException on a read error, including after the document closes
   */
  public LineBlock readRows(
      LineIndex index, long firstRow, long[] sourceLines, int count, long firstColumn, int columns)
      throws IOException {
    return readRows(index, firstRow, sourceLines, count, firstColumn, columns, null);
  }

  /**
   * Decodes listed lines as consecutive rows, cut to a column window, and colors each row from its
   * whole decoded line.
   *
   * @param index the snapshot to read
   * @param firstRow the row of {@code sourceLines[0]}
   * @param sourceLines ascending 0-based lines; lines past the end of the index are left out
   * @param count lines used from the start of the array
   * @param firstColumn first display column to keep
   * @param columns width of the window
   * @param highlights colors the rows, or null for none
   * @return the block, whose {@link LineBlock#sourceLine} gives each row's line
   * @throws IOException on a read error, including after the document closes
   */
  public LineBlock readRows(
      LineIndex index,
      long firstRow,
      long[] sourceLines,
      int count,
      long firstColumn,
      int columns,
      Highlighter.LineMatcher highlights)
      throws IOException {
    return readRows(index, firstRow, sourceLines, count, firstColumn, columns, highlights, false);
  }

  /**
   * Decodes listed lines as consecutive rows, cut to a column window, colors each row from its
   * whole decoded line and, when asked, records whitespace markers and line terminators ({@link
   * LineBlock#whitespace}, {@link LineBlock#terminator}). With markers a lone CR shows as a space.
   *
   * @param index the snapshot to read
   * @param firstRow the row of {@code sourceLines[0]}
   * @param sourceLines ascending 0-based lines; lines past the end of the index are left out
   * @param count lines used from the start of the array
   * @param firstColumn first display column to keep
   * @param columns width of the window
   * @param highlights colors the rows, or null for none
   * @param markers true to record whitespace markers and terminators
   * @return the block, whose {@link LineBlock#sourceLine} gives each row's line
   * @throws IOException on a read error, including after the document closes
   */
  public LineBlock readRows(
      LineIndex index,
      long firstRow,
      long[] sourceLines,
      int count,
      long firstColumn,
      int columns,
      Highlighter.LineMatcher highlights,
      boolean markers)
      throws IOException {
    long lineCount = index.lineCount();
    int n = 0;
    while (n < count && sourceLines[n] < lineCount) {
      n++;
    }
    String[] lines = new String[n];
    long[] widths = new long[n];
    long[] numbers = new long[n];
    HighlightSpans[] spans = highlights == null ? null : new HighlightSpans[n];
    String[] whitespace = markers ? new String[n] : null;
    byte[] terminators = markers ? new byte[n] : null;
    StringBuilder out = new StringBuilder();
    StringBuilder marks = markers ? new StringBuilder() : null;
    for (int i = 0; i < n; i++) {
      numbers[i] = sourceLines[i];
      CharBuffer text = decode(index, sourceLines[i]);
      if (spans != null) {
        spans[i] = highlights.spans(text, firstColumn, columns);
      }
      if (markers) {
        terminators[i] = (byte) terminator(index, sourceLines[i]).ordinal();
        widths[i] = cutMarked(text, firstColumn, firstColumn + columns, out, marks);
        whitespace[i] = marks.toString();
      } else {
        widths[i] = cut(text, firstColumn, firstColumn + columns, out);
      }
      lines[i] = out.toString();
    }
    return new LineBlock(
        firstRow, firstColumn, columns, lines, widths, numbers, spans, whitespace, terminators);
  }

  /**
   * Decodes a line, at most its first {@link #MAX_LINE_BYTES}, with tabs kept and a trailing CR
   * dropped.
   *
   * @param index the snapshot to read
   * @param line a 0-based line in the index
   * @return chars valid until the next call on this reader
   * @throws IOException on a read error
   */
  public CharSequence decodedLine(LineIndex index, long line) throws IOException {
    return decode(index, line);
  }

  /** Puts the window {@code [from, to)} of a line into out; returns the line's width. */
  private static long cut(CharBuffer text, long from, long to, StringBuilder out) {
    out.setLength(0);
    long column = 0;
    for (int k = text.position(); k < text.limit(); k++) {
      char c = text.get(k);
      long next = advance(column, c);
      if (c == '\t') {
        for (long s = Math.max(column, from); s < Math.min(next, to); s++) {
          out.append(' ');
        }
      } else {
        long at = charColumn(text, k, column);
        if (at >= from && at < to) {
          out.append(c);
        }
      }
      column = next;
    }
    return column;
  }

  /**
   * Returns the display column of the char at {@code k}: a low surrogate after its high surrogate
   * takes the high surrogate's column, so a column edge never splits the pair.
   */
  private static long charColumn(CharBuffer text, int k, long column) {
    boolean paired =
        k > text.position()
            && Character.isLowSurrogate(text.get(k))
            && Character.isHighSurrogate(text.get(k - 1));
    return paired ? column - 1 : column;
  }

  /** How the line last decoded ends; the CR check needs the whole line read. */
  private LineTerminator terminator(LineIndex index, long line) {
    if (line == index.lineCount() - 1 && !index.lastLineComplete()) {
      return LineTerminator.NONE;
    }
    return decoder.droppedCarriageReturn() ? LineTerminator.CRLF : LineTerminator.LF;
  }

  /** As {@link #cut}, and puts one whitespace marker per char put into out into marks. */
  private static long cutMarked(
      CharBuffer text, long from, long to, StringBuilder out, StringBuilder marks) {
    out.setLength(0);
    marks.setLength(0);
    int trailing = text.limit();
    while (trailing > text.position()
        && (text.get(trailing - 1) == ' ' || text.get(trailing - 1) == '\t')) {
      trailing--;
    }
    long column = 0;
    for (int k = text.position(); k < text.limit(); k++) {
      char c = text.get(k);
      long next = advance(column, c);
      if (c == '\t') {
        for (long s = Math.max(column, from); s < Math.min(next, to); s++) {
          out.append(' ');
          marks.append(s == column ? LineBlock.TAB_MARK : ' ');
        }
      } else {
        long at = charColumn(text, k, column);
        if (at >= from && at < to) {
          if (c == '\r') {
            out.append(' ');
            marks.append(LineBlock.CR_MARK);
          } else {
            out.append(c);
            marks.append(c == ' ' && k >= trailing ? LineBlock.SPACE_MARK : ' ');
          }
        }
      }
      column = next;
    }
    return column;
  }

  /**
   * Appends the chars of a line whose display column lies in {@code [fromColumn, toColumn)}. Tabs
   * are kept as tabs.
   *
   * @param index the snapshot to read
   * @param line a 0-based line in the index
   * @param fromColumn first column kept
   * @param toColumn first column dropped; {@code Long.MAX_VALUE} for the line end
   * @param out receives the text
   * @throws IOException on a read error
   */
  public void appendText(
      LineIndex index, long line, long fromColumn, long toColumn, StringBuilder out)
      throws IOException {
    CharBuffer text = decode(index, line);
    long column = 0;
    for (int k = text.position(); k < text.limit(); k++) {
      char c = text.get(k);
      long at = charColumn(text, k, column);
      if (at >= toColumn) {
        break;
      }
      if (at >= fromColumn) {
        out.append(c);
      }
      column = advance(column, c);
    }
  }

  /**
   * Appends a block selection's part of a line: the chars whose first display column lies in {@code
   * [fromColumn, toColumn)}, with a tab partly inside replaced by a space per covered column.
   * Nothing is padded.
   *
   * @param index the snapshot to read
   * @param line a 0-based line in the index
   * @param fromColumn first column kept
   * @param toColumn first column dropped
   * @param out receives the text
   * @throws IOException on a read error
   */
  public void appendColumns(
      LineIndex index, long line, long fromColumn, long toColumn, StringBuilder out)
      throws IOException {
    CharBuffer text = decode(index, line);
    long column = 0;
    for (int k = text.position(); k < text.limit(); k++) {
      char c = text.get(k);
      long at = charColumn(text, k, column);
      if (at >= toColumn) {
        break;
      }
      long next = advance(column, c);
      if (c == '\t' && (column < fromColumn || next > toColumn)) {
        for (long s = Math.max(column, fromColumn); s < Math.min(next, toColumn); s++) {
          out.append(' ');
        }
      } else if (at >= fromColumn) {
        out.append(c);
      }
      column = next;
    }
  }

  /** Decodes a line, at most {@link #MAX_LINE_BYTES} of it, into the char scratch buffer. */
  private CharBuffer decode(LineIndex index, long line) throws IOException {
    long start = index.lineStart(line);
    long length = index.lineEnd(line) - start;
    int unit = encoding.unitWidth();
    int want = (int) Math.min(length, MAX_LINE_BYTES);
    want -= want % unit;
    if (bytes.capacity() < want) {
      bytes = ByteBuffer.allocate(Math.min(MAX_LINE_BYTES, Math.max(want, bytes.capacity() * 2)));
      chars = CharBuffer.allocate(bytes.capacity());
    }
    bytes.clear();
    int read = cache.read(source, start, want, bytes);
    bytes.flip();
    bytes.limit(read - read % unit);
    chars.clear();
    decoder.decodeLine(bytes, chars);
    chars.flip();
    if (hideAnsi.getAsBoolean()) {
      AnsiCodes.strip(chars);
    }
    return chars;
  }
}

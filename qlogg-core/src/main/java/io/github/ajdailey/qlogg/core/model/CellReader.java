package io.github.ajdailey.qlogg.core.model;

import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.columns.FieldParser;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.search.RecordLines;
import io.github.ajdailey.qlogg.core.search.RecordScan;
import io.github.ajdailey.qlogg.core.time.TimeContext;
import java.io.IOException;
import java.nio.CharBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads column-view rows through a {@link LineReader}. A row's line reads as the whole record
 * holding it, from the record's first line ({@link ColumnRead#records}), at most {@link
 * LineReader#MAX_LINE_BYTES} chars; the format's {@link FieldParser} parses it and the row is
 * composed into the cells of a {@link ColumnLayout}:
 *
 * <ul>
 *   <li>A parsed record shows each cell's value cut to the cell's width less one column, padded
 *       with spaces; a tab in a value shows as spaces to the cell's next tab stop and a line feed
 *       as its control box.
 *   <li>A record that does not parse shows its raw text from column 0 across every cell, as wide as
 *       it is ({@link LineBlock#unparsed}).
 *   <li>The Time cell shows the instant in the display zone with the time offset ({@link
 *       io.github.ajdailey.qlogg.core.columns.TimeCell}).
 * </ul>
 *
 * <p>Only the listed rows are read and parsed. Finished rows are kept in a cache of at most {@link
 * #MAX_CACHED_ROWS} rows and {@link #MAX_CACHED_CHARS} chars of text and span data, not charged to
 * a share. A row is reused while its record has the same lines and byte range and the read has the
 * same {@link ColumnRead}, window, highlighter, syntax-color setting and ANSI setting; any other
 * read empties the cache.
 *
 * <p>Not thread-safe: one thread uses an instance at a time, like its {@link LineReader}.
 */
public final class CellReader {
  /** Most rows the cache keeps. */
  public static final int MAX_CACHED_ROWS = 512;

  /** Most chars the cached rows hold, a span counting {@link #SPAN_CHARS}. */
  public static final long MAX_CACHED_CHARS = 1 << 19;

  private static final int SPAN_CHARS = 8;
  private static final int[] RAW_CELLS = {0};

  private final LineReader reader;
  private final CellRecord record = new CellRecord();
  private final StringBuilder joined = new StringBuilder();
  private final StringBuilder composed = new StringBuilder();
  private final StringBuilder out = new StringBuilder();
  private final ControlCells boxes = new ControlCells();
  private final Map<Long, Row> cache = new LinkedHashMap<Long, Row>(64, 0.75f, true);
  private long cachedChars;
  private Object[] cacheKey = new Object[0];
  private ColumnSchema parserSchema;
  private TimeContext parserContext;
  private FieldParser parser;
  private int[] shown = new int[16];
  private int[] tokenData = new int[3 * 16];

  /**
   * Creates a reader.
   *
   * @param reader reads and decodes the lines; used only through this instance from now on
   */
  public CellReader(LineReader reader) {
    this.reader = reader;
  }

  /**
   * Reads and parses the record holding a line.
   *
   * @param index the snapshot to read
   * @param line a 0-based line in the index
   * @param view the format and records
   * @return the record, valid until the next call
   * @throws IOException on a read error
   */
  public CellRecord record(LineIndex index, long line, ColumnRead view) throws IOException {
    ColumnSchema schema = view.layout().schema();
    if (parser == null || schema != parserSchema || !view.context().equals(parserContext)) {
      parser = schema.parser(view.context());
      parserSchema = schema;
      parserContext = view.context();
    }
    long count = index.lineCount();
    RecordLines records = view.records();
    long first = records == null ? line : records.startOf(line);
    long end = records == null ? line + 1 : Math.min(records.nextStart(first + 1), count);
    end = Math.max(end, first + 1);
    CharSequence text;
    boolean cut = false;
    if (end - first == 1) {
      text = reader.decodedLine(index, first);
    } else {
      joined.setLength(0);
      for (long at = first; at < end && !cut; at++) {
        if (at > first) {
          joined.append('\n');
        }
        CharSequence part = reader.decodedLine(index, at);
        int room = LineReader.MAX_LINE_BYTES - joined.length();
        cut = part.length() > room;
        joined.append(part, 0, Math.min(part.length(), Math.max(0, room)));
      }
      text = joined;
    }
    if (records != null) {
      cut |=
          end - first >= RecordScan.MAX_RECORD_LINES
              || end < count
                  && index.lineStart(end) - index.lineStart(first) >= RecordScan.MAX_RECORD_BYTES;
    }
    record.set(
        view,
        parser,
        text,
        first,
        end,
        cut,
        (first == 0 && schema.header()) || view.headers().contains(first));
    return record;
  }

  /**
   * Reads listed lines as consecutive column-view rows, cut to the frozen cells and a scrolled
   * window, and colors each row from its record.
   *
   * @param index the snapshot to read
   * @param firstRow the row of {@code sourceLines[0]}
   * @param sourceLines ascending 0-based lines; lines past the end of the index are left out
   * @param count lines used from the start of the array
   * @param firstColumn first composed column of the scrolled window, after the frozen cells
   * @param columns width of the window
   * @param view the cells, format and records
   * @param colors colors the rows, or null for none
   * @param tokens true to tokenize each row whose highlight has no line color
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
      ColumnRead view,
      Highlighter colors,
      boolean tokens)
      throws IOException {
    keyCache(view, firstColumn, columns, colors, tokens);
    long lineCount = index.lineCount();
    int n = 0;
    while (n < count && sourceLines[n] < lineCount) {
      n++;
    }
    Highlighter.LineMatcher highlights =
        colors == null || colors.isEmpty() ? null : colors.matcher();
    LogTokens.LineMatcher kinds = tokens ? LogTokens.matcher() : null;
    ColumnLayout layout = view.layout();
    long frozenEnd = layout.frozenWidth();
    long from = frozenEnd + firstColumn;
    long to = from + columns;
    String[] lines = new String[n];
    long[] widths = new long[n];
    long[] numbers = new long[n];
    HighlightSpans[] spans = highlights == null ? null : new HighlightSpans[n];
    TokenSpans[] kindSpans = kinds == null ? null : new TokenSpans[n];
    String[] controls = new String[n];
    int[][] cells = new int[n][];
    byte[] flags = new byte[n];
    for (int i = 0; i < n; i++) {
      numbers[i] = sourceLines[i];
      Row row = cached(index, sourceLines[i], view);
      if (row == null) {
        CellRecord read = record(index, sourceLines[i], view);
        row = compose(read, layout, frozenEnd, from, to, highlights, kinds);
        keep(sourceLines[i], index, row);
      }
      lines[i] = row.text();
      widths[i] = row.width();
      controls[i] = row.controls();
      cells[i] = row.cells();
      flags[i] = row.flags();
      if (spans != null) {
        spans[i] = row.spans();
      }
      if (kindSpans != null) {
        kindSpans[i] = row.tokens();
      }
    }
    return new LineBlock(
        firstRow,
        firstColumn,
        columns,
        lines,
        widths,
        numbers,
        spans,
        kindSpans,
        null,
        null,
        controls,
        cells,
        flags,
        (int) frozenEnd);
  }

  /** One finished row and the record range it was read from. */
  private record Row(
      long first,
      long end,
      long startByte,
      long endByte,
      String text,
      long width,
      HighlightSpans spans,
      TokenSpans tokens,
      String controls,
      int[] cells,
      byte flags) {
    long chars() {
      long spanCount = (spans == null ? 0 : spans.count()) + (tokens == null ? 0 : tokens.count());
      return text.length() + (controls == null ? 0 : controls.length()) + SPAN_CHARS * spanCount;
    }
  }

  private Row compose(
      CellRecord read,
      ColumnLayout layout,
      long frozenEnd,
      long from,
      long to,
      Highlighter.LineMatcher highlights,
      LogTokens.LineMatcher kinds) {
    boolean parsed = read.parsed();
    if (parsed) {
      composeCells(read, layout);
    } else {
      composed.setLength(0);
      composed.append(read.text());
    }
    CharBuffer text = CharBuffer.wrap(composed);
    out.setLength(0);
    boxes.reset();
    if (frozenEnd > 0) {
      LineReader.appendWindow(text, 0, frozenEnd, out, boxes);
    }
    long width = LineReader.appendWindow(text, from, to, out, boxes);
    HighlightSpans spans =
        highlights == null ? null : highlights.spans(read, layout, shown, frozenEnd, from, to);
    TokenSpans tokens = null;
    boolean lineColored =
        spans != null && (spans.lineForeground() != 0 || spans.lineBackground() != 0);
    if (kinds != null && !lineColored) {
      tokens = tokens(read, layout, kinds, frozenEnd, from, to);
    }
    byte flags =
        (byte)
            ((parsed ? 0 : LineBlock.UNPARSED)
                | (read.header() ? LineBlock.HEADER : 0)
                | (read.cut() ? LineBlock.CUT : 0));
    return new Row(
        read.first(),
        read.end(),
        0,
        0,
        out.toString(),
        parsed ? layout.width() : Math.max(width, layout.width()),
        spans,
        tokens,
        boxes.take(out.length()),
        parsed ? layout.starts() : RAW_CELLS,
        flags);
  }

  /** Writes each cell's shown value and padding into {@link #composed}; fills {@link #shown}. */
  private void composeCells(CellRecord read, ColumnLayout layout) {
    composed.setLength(0);
    if (shown.length < layout.count()) {
      shown = new int[layout.count()];
    }
    for (int cell = 0; cell < layout.count(); cell++) {
      CharSequence value = read.value(layout.column(cell));
      long limit = layout.width(cell) - 1;
      long column = 0;
      for (int k = 0; k < value.length(); k++) {
        char c = value.charAt(k);
        long next = LineReader.advance(column, c);
        if (next > limit) {
          break;
        }
        if (c == '\t') {
          for (long s = column; s < next; s++) {
            composed.append(' ');
          }
        } else {
          composed.append(c);
        }
        column = next;
      }
      shown[cell] = (int) column;
      for (long s = column; s < layout.width(cell); s++) {
        composed.append(' ');
      }
    }
  }

  /** Tokenizes each cell's value, or the raw text of a record that did not parse. */
  private TokenSpans tokens(
      CellRecord read,
      ColumnLayout layout,
      LogTokens.LineMatcher kinds,
      long frozenEnd,
      long from,
      long to) {
    int count = 0;
    if (!read.parsed()) {
      if (frozenEnd > 0) {
        count = addTokens(kinds.spans(read.text(), 0, (int) frozenEnd), 0, 0, frozenEnd, count);
      }
      count = addTokens(kinds.spans(read.text(), from, (int) (to - from)), 0, from, to, count);
    } else {
      int level = layout.schema().levelColumn();
      for (int cell = 0; cell < layout.count(); cell++) {
        long start = layout.start(cell);
        boolean frozen = cell < layout.frozen();
        long lo = frozen ? start : Math.max(start, from);
        long hi = Math.min(start + shown[cell], frozen ? frozenEnd : to);
        if (hi > lo) {
          CharSequence text = read.value(layout.column(cell));
          TokenKind named = layout.column(cell) == level ? LevelNames.kind(text) : null;
          TokenSpans value =
              named != null
                  ? LevelNames.span(named, shown[cell])
                  : kinds.spans(text, 0, shown[cell]);
          count = addTokens(value, start, lo, hi, count);
        }
      }
    }
    if (count == 0) {
      return TokenSpans.NONE;
    }
    int[] data = new int[3 * count];
    System.arraycopy(tokenData, 0, data, 0, data.length);
    return new TokenSpans(data, count);
  }

  /** Appends spans moved right by {@code offset} and clipped to {@code [lo, hi)}. */
  private int addTokens(TokenSpans spans, long offset, long lo, long hi, int count) {
    for (int i = 0; i < spans.count() && count < Highlighter.MAX_SPANS; i++) {
      long a = Math.max(lo, offset + spans.start(i));
      long b = Math.min(hi, offset + spans.end(i));
      if (b <= a) {
        continue;
      }
      if (tokenData.length < 3 * (count + 1)) {
        int[] grown = new int[tokenData.length * 2];
        System.arraycopy(tokenData, 0, grown, 0, tokenData.length);
        tokenData = grown;
      }
      tokenData[3 * count] = (int) a;
      tokenData[3 * count + 1] = (int) b;
      tokenData[3 * count + 2] = spans.kind(i).ordinal();
      count++;
    }
    return count;
  }

  /** Empties the cache when a read's settings differ from the last read's. */
  private void keyCache(
      ColumnRead view, long firstColumn, int columns, Highlighter colors, boolean tokens) {
    Object[] key = {
      view, firstColumn, columns, colors, tokens, reader.hidesAnsi(),
    };
    boolean same = key.length == cacheKey.length;
    for (int i = 0; same && i < key.length; i++) {
      same = key[i] == cacheKey[i] || key[i].equals(cacheKey[i]);
    }
    if (!same) {
      cache.clear();
      cachedChars = 0;
      cacheKey = key;
    }
  }

  /** The cached row of a line while its record has the same lines and bytes, else null. */
  private Row cached(LineIndex index, long line, ColumnRead view) {
    Row row = cache.get(line);
    if (row == null) {
      return null;
    }
    RecordLines records = view.records();
    long first = records == null ? line : records.startOf(line);
    long end =
        records == null ? line + 1 : Math.min(records.nextStart(first + 1), index.lineCount());
    end = Math.max(end, first + 1);
    if (first != row.first()
        || end != row.end()
        || end > index.lineCount()
        || index.lineStart(first) != row.startByte()
        || index.lineEnd(end - 1) != row.endByte()) {
      cache.remove(line);
      cachedChars -= row.chars();
      return null;
    }
    return row;
  }

  private void keep(long line, LineIndex index, Row composedRow) {
    Row row =
        new Row(
            composedRow.first(),
            composedRow.end(),
            index.lineStart(composedRow.first()),
            index.lineEnd(composedRow.end() - 1),
            composedRow.text(),
            composedRow.width(),
            composedRow.spans(),
            composedRow.tokens(),
            composedRow.controls(),
            composedRow.cells(),
            composedRow.flags());
    Row old = cache.put(line, row);
    if (old != null) {
      cachedChars -= old.chars();
    }
    cachedChars += row.chars();
    Iterator<Row> eldest = cache.values().iterator();
    while (eldest.hasNext() && (cache.size() > MAX_CACHED_ROWS || cachedChars > MAX_CACHED_CHARS)) {
      cachedChars -= eldest.next().chars();
      eldest.remove();
    }
  }
}

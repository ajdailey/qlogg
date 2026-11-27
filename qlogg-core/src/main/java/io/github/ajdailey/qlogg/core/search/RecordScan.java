package io.github.ajdailey.qlogg.core.search;

import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.ReadBuffers;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * Finds the records of a {@link ColumnFormat#multiLine} format in one pass over the file's complete
 * lines, and extends the pass from where it stopped when the index grows. The set ({@link #lines})
 * holds:
 *
 * <table>
 *   <caption>Record set by format</caption>
 *   <tr><th>Format</th><th>Set</th><th>A line continues the record when</th></tr>
 *   <tr><td>CSV, TSV</td><td>Continuation lines, usually none</td><td>It starts inside a quoted
 *       field, read with {@link ColumnSchema#delimiter} as the parser reads it</td></tr>
 *   <tr><td>log4j XML</td><td>Record starts, line 0 included</td><td>Its first chars other than
 *       spaces and tabs are not {@code <log4j:event}</td></tr>
 * </table>
 *
 * <p>A record holds at most {@link #MAX_RECORD_LINES} lines, and a line starting {@link
 * #MAX_RECORD_BYTES} or more after its record's first byte starts a new record, so a stray quote
 * cannot join the rest of the file into one record. A partial last line is left for the next pass.
 *
 * <p>The pass reads the source by positional reads into one buffer from the caller's pool, a block
 * of whole lines at a time. A CSV block with no quote byte, starting outside a quoted field, is
 * counted at byte-search speed: every line in it starts a record. A line whose leading blanks fill
 * a block does not start a log4j record. The scanner compares encoded units; in a charset where the
 * quote, delimiter, space, tab or {@code <log4j:event} do not encode to one unit per char, every
 * line is a record and nothing is read.
 *
 * <p>Memory: the set reserves from {@link MemoryShare#RESULTS}. A refusal stops the scan for good
 * ({@link #refused}); lines from the refused entry on stay unclassified, each a record of its own.
 *
 * <p>One thread scans at a time. {@link #snapshot} and the progress getters are thread-safe.
 */
public final class RecordScan {
  /** Most lines in one record. */
  public static final int MAX_RECORD_LINES = 10_000;

  /** Bytes from a record's first byte after which a line starts a new record. */
  public static final int MAX_RECORD_BYTES = 1 << 20;

  private static final int BATCH = 4096;
  private static final int OUTSIDE = 0;
  private static final int QUOTED = 1;
  private static final int QUOTE_SEEN = 2;
  private static final String EVENT_START = "<log4j:event";

  private final boolean events;
  private final MutableLineSet lines;
  private final int width;
  private final byte[] lineFeed;
  private final byte[] quote;
  private final byte[] delimiter;
  private final byte[] space;
  private final byte[] tab;
  private final byte[] eventStart;
  private final boolean bytewise;
  private final long[] batch = new long[BATCH];
  private int batched;
  private long refusedAt = -1;

  private long offset = -1;
  private long line;
  private long lineStart;
  private boolean atLineStart = true;
  private long recordLine;
  private long recordStart;
  private int state = OUTSIDE;
  private boolean afterDelimiter;
  private volatile long classified;
  private volatile long scannedBytes;
  private volatile boolean refused;

  /**
   * Creates a scan that has read nothing.
   *
   * @param schema a {@link ColumnFormat#multiLine} format's schema
   * @param encoding the document's encoding
   * @param budget charged for the set
   * @throws IllegalArgumentException for a single-line format
   */
  public RecordScan(ColumnSchema schema, TextEncoding encoding, MemoryBudget budget) {
    if (!schema.format().multiLine()) {
      throw new IllegalArgumentException(schema.format().id() + " records are single lines");
    }
    Charset charset = encoding.charset();
    width = encoding.unitWidth();
    lineFeed = encoding.lineFeed();
    quote = units("\"", charset);
    delimiter =
        schema.delimiter() == 0 ? new byte[0] : units(String.valueOf(schema.delimiter()), charset);
    space = units(" ", charset);
    tab = units("\t", charset);
    eventStart = units(EVENT_START, charset);
    boolean log4j = schema.format() == ColumnFormat.LOG4J_XML;
    bytewise =
        log4j
            ? space.length > 0 && tab.length > 0 && eventStart.length > 0
            : quote.length > 0 && delimiter.length > 0;
    events = log4j && bytewise;
    lines = new MutableLineSet(budget);
  }

  /** Encodes text; empty when a char does not encode to exactly one unit. */
  private byte[] units(String text, Charset charset) {
    if (!charset.canEncode()) {
      return new byte[0];
    }
    byte[] bytes = text.getBytes(charset);
    return bytes.length == text.length() * width ? bytes : new byte[0];
  }

  /**
   * Returns the set the scan fills.
   *
   * @return record starts for log4j XML, else continuation lines
   */
  public MutableLineSet lines() {
    return lines;
  }

  /**
   * Returns the records classified so far. The view stays valid while the scan goes on.
   *
   * @return the records
   */
  public RecordLines snapshot() {
    long end = classified;
    return events ? RecordLines.starts(lines, end) : RecordLines.continuations(lines, end);
  }

  /**
   * Returns the lines classified so far.
   *
   * @return a line count
   */
  public long classifiedLines() {
    return classified;
  }

  /**
   * Returns the bytes read so far, from offset 0.
   *
   * @return a byte offset
   */
  public long scannedBytes() {
    return Math.max(0, scannedBytes);
  }

  /**
   * Reports whether {@code RESULTS} refused the set's growth, which stops the scan.
   *
   * @return true once refused
   */
  public boolean refused() {
    return refused;
  }

  /**
   * Reads from where the last call stopped to the end of the index's last complete line. A call
   * that ends with an exception leaves the scan where its last whole block left it.
   *
   * @param source the document's bytes; read with positional reads, not closed
   * @param index a snapshot of the document's index, at least as long as the last call's
   * @param buffers the pool one read buffer comes from
   * @param token checked before each block
   * @throws IOException when a read fails or the file is shorter than its index
   * @throws InterruptedException when interrupted while waiting for a buffer
   * @throws java.util.concurrent.CancellationException when the token is canceled
   */
  public void scan(ByteSource source, LineIndex index, ReadBuffers buffers, CancellationToken token)
      throws IOException, InterruptedException {
    long complete = index.lastLineComplete() ? index.lineCount() : index.lineCount() - 1;
    if (refused || complete <= 0) {
      return;
    }
    if (!bytewise) {
      classified = Math.max(classified, complete);
      return;
    }
    long limit = index.lineEnd(complete - 1) + width;
    if (offset < 0) {
      offset = index.lineStart(0);
      lineStart = offset;
      recordStart = offset;
    }
    if (offset >= limit) {
      return;
    }
    ByteBuffer buffer = buffers.acquire(token);
    try {
      while (offset < limit) {
        token.check();
        int n = (int) Math.min(buffer.capacity() - buffer.capacity() % width, limit - offset);
        fill(source, buffer, n);
        int usable = n;
        if (offset + n < limit) {
          int last = lastUnit(buffer, n, lineFeed);
          if (last >= 0) {
            usable = last + width;
          }
        }
        if (events) {
          events(buffer, usable);
        } else {
          delimited(buffer, usable);
        }
        offset += usable;
        flush();
        if (refused) {
          classified = Math.min(line, refusedAt);
          return;
        }
        classified = line;
        scannedBytes = offset;
      }
    } finally {
      buffers.release(buffer);
    }
  }

  private void fill(ByteSource source, ByteBuffer buffer, int n) throws IOException {
    buffer.clear();
    buffer.limit(n);
    while (buffer.hasRemaining()) {
      if (source.read(offset + buffer.position(), buffer) < 0) {
        throw new IOException("File is shorter than its line index");
      }
    }
  }

  /** CSV and TSV: tracks the quote state and adds each line that starts inside a quoted field. */
  private void delimited(ByteBuffer buffer, int n) {
    int pos = 0;
    while (pos < n) {
      if (atLineStart) {
        startDelimitedLine();
      }
      if (state == QUOTE_SEEN) {
        state = OUTSIDE;
        if (SearchEngine.unitAt(buffer, pos, quote)) {
          state = QUOTED;
          pos += width;
          continue;
        }
      }
      if (state == OUTSIDE && width == 1 && ByteScan.indexOf(buffer, pos, n, quote[0]) < 0) {
        pos = plain(buffer, pos, n);
        continue;
      }
      int hit = findEither(buffer, pos, n);
      if (hit < 0) {
        afterDelimiter = state == OUTSIDE && SearchEngine.unitAt(buffer, n - width, delimiter);
        pos = n;
      } else if (SearchEngine.unitAt(buffer, hit, lineFeed)) {
        endLine(hit);
        pos = hit + width;
      } else if (state == OUTSIDE) {
        if (offset + hit == lineStart
            || (hit > 0 ? SearchEngine.unitAt(buffer, hit - width, delimiter) : afterDelimiter)) {
          state = QUOTED;
        }
        pos = hit + width;
      } else if (hit + width >= n) {
        state = QUOTE_SEEN;
        afterDelimiter = false;
        pos = n;
      } else if (SearchEngine.unitAt(buffer, hit + width, quote)) {
        pos = hit + 2 * width;
      } else {
        state = OUTSIDE;
        pos = hit + width;
      }
    }
  }

  /** Decides the line at {@link #lineStart}: a continuation inside quotes, else a record start. */
  private void startDelimitedLine() {
    atLineStart = false;
    if (line > 0 && state == QUOTED && withinBounds()) {
      add(line);
      return;
    }
    recordLine = line;
    recordStart = lineStart;
    state = OUTSIDE;
  }

  /**
   * Counts the lines of {@code [pos, n)}, which holds no quote and starts outside quotes: each line
   * in it starts a record.
   */
  private int plain(ByteBuffer buffer, int pos, int n) {
    int count = ByteScan.count(buffer, pos, n, lineFeed[0]);
    if (count > 0) {
      int last = lastUnit(buffer, n, lineFeed);
      line += count;
      lineStart = offset + last + 1;
      recordLine = line;
      recordStart = lineStart;
      atLineStart = last + 1 == n;
    }
    afterDelimiter = buffer.get(n - 1) == delimiter[0];
    return n;
  }

  /** log4j XML: adds each line that leads with the event start tag, or that a bound forces. */
  private void events(ByteBuffer buffer, int n) {
    int pos = 0;
    while (pos < n) {
      if (atLineStart) {
        atLineStart = false;
        if (line == 0 || !withinBounds() || leadsWithEvent(buffer, pos, n)) {
          add(line);
          recordLine = line;
          recordStart = lineStart;
        }
      }
      int hit = SearchEngine.findUnit(buffer, pos, n, lineFeed);
      if (hit < 0) {
        return;
      }
      endLine(hit);
      pos = hit + width;
    }
  }

  private boolean leadsWithEvent(ByteBuffer buffer, int pos, int n) {
    int at = pos;
    while (at < n
        && (SearchEngine.unitAt(buffer, at, space) || SearchEngine.unitAt(buffer, at, tab))) {
      at += width;
    }
    if (n - at < eventStart.length) {
      return false;
    }
    return SearchEngine.unitAt(buffer, at, eventStart);
  }

  /** True when the line at {@link #lineStart} may join the record that started before it. */
  private boolean withinBounds() {
    return line - recordLine < MAX_RECORD_LINES && lineStart - recordStart < MAX_RECORD_BYTES;
  }

  /** Moves past the LF at buffer offset {@code hit} to the next line's start. */
  private void endLine(int hit) {
    line++;
    lineStart = offset + hit + width;
    atLineStart = true;
  }

  /** Returns the first quote or LF unit in {@code [from, to)}, or -1. */
  private int findEither(ByteBuffer buffer, int from, int to) {
    if (width == 1) {
      return ByteScan.indexOfEither(buffer, from, to, quote[0], lineFeed[0]);
    }
    for (int at = from; at + width <= to; at += width) {
      if (SearchEngine.unitAt(buffer, at, quote) || SearchEngine.unitAt(buffer, at, lineFeed)) {
        return at;
      }
    }
    return -1;
  }

  /** Returns the offset of the last unit equal to {@code unit} in {@code [0, n)}, or -1. */
  private int lastUnit(ByteBuffer buffer, int n, byte[] unit) {
    for (int at = n - width; at >= 0; at -= width) {
      if (SearchEngine.unitAt(buffer, at, unit)) {
        return at;
      }
    }
    return -1;
  }

  private void add(long value) {
    if (refused) {
      return;
    }
    batch[batched++] = value;
    if (batched == BATCH) {
      flush();
    }
  }

  private void flush() {
    if (batched == 0 || refused) {
      return;
    }
    if (!lines.addSorted(batch, batched)) {
      refused = true;
      refusedAt = batch[0];
    }
    batched = 0;
  }
}

package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.GrowableLineIndex;
import io.github.ajdailey.qlogg.core.index.IndexListener;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.MemoryByteSource;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The record rules of {@link RecordScan} applied to decoded text, line by line and char by char, as
 * the reference for the scan and record search tests; and an in-memory document to search.
 */
final class RecordReference {
  private RecordReference() {}

  /** An indexed in-memory document. */
  record Source(LineIndex index, ByteSource bytes, TextEncoding encoding) implements LineSource {}

  /** Indexes content; a small {@code indexBudget} makes the index sparse. */
  static Source index(Workers workers, byte[] content, Charset charset, MemoryBudget indexBudget)
      throws Exception {
    TextEncoding encoding = TextEncoding.of(charset, 0);
    MemoryByteSource bytes = new MemoryByteSource(content, 0);
    final AtomicReference<OperationOutcome> outcome = new AtomicReference<OperationOutcome>();
    try (GrowableLineIndex index =
        new GrowableLineIndex(encoding, indexBudget, bytes, new BlockCache(4096, 1 << 20))) {
      OperationHandle handle =
          new Indexer(workers, new IndexSettings(64 * 1024, 2, MemoryBudget.of(1L << 30)))
              .index(
                  bytes,
                  index,
                  index.resumeOffset(),
                  new IndexListener() {
                    @Override
                    public void progressed(IndexProgress progress) {}

                    @Override
                    public void finished(OperationOutcome result, IOException failure) {
                      outcome.set(result);
                    }
                  });
      assertTrue(handle.await(SearchFixture.BOUND.toMillis()));
      assertEquals(OperationOutcome.COMPLETED, outcome.get());
      return new Source(index.snapshot(), bytes, encoding);
    }
  }

  /**
   * Splits text at LF, a CR before it kept.
   *
   * @return the lines; the last one is partial when the text does not end with LF
   */
  static List<String> lines(String text) {
    List<String> lines = new ArrayList<String>();
    int from = 0;
    while (from < text.length()) {
      int lf = text.indexOf('\n', from);
      if (lf < 0) {
        lines.add(text.substring(from));
        break;
      }
      lines.add(text.substring(from, lf));
      from = lf + 1;
    }
    return lines;
  }

  /**
   * Classifies the complete lines.
   *
   * @param complete the lines ending with LF
   * @return true for each line that starts a record
   */
  static boolean[] starts(List<String> complete, ColumnSchema schema, Charset charset) {
    boolean[] starts = new boolean[complete.size()];
    int lineFeed = "\n".getBytes(charset).length;
    boolean events = schema.format() == ColumnFormat.LOG4J_XML;
    char delimiter = schema.delimiter();
    boolean quoted = false;
    long recordLine = 0;
    long recordByte = 0;
    long lineByte = 0;
    for (int line = 0; line < complete.size(); line++) {
      String text = complete.get(line);
      boolean bounded =
          line - recordLine < RecordScan.MAX_RECORD_LINES
              && lineByte - recordByte < RecordScan.MAX_RECORD_BYTES;
      boolean start;
      if (events) {
        start = line == 0 || !bounded || leadsWithEvent(text);
      } else {
        start = line == 0 || !quoted || !bounded;
      }
      if (start) {
        recordLine = line;
        recordByte = lineByte;
        quoted = false;
      }
      starts[line] = start;
      if (!events) {
        quoted = quotedAtEnd(text, delimiter, quoted);
      }
      lineByte += text.getBytes(charset).length + lineFeed;
    }
    return starts;
  }

  private static boolean leadsWithEvent(String text) {
    int at = 0;
    while (at < text.length() && (text.charAt(at) == ' ' || text.charAt(at) == '\t')) {
      at++;
    }
    return text.startsWith("<log4j:event", at);
  }

  /** Runs the CSV quote rules over one line from a state; returns whether its end is quoted. */
  private static boolean quotedAtEnd(String text, char delimiter, boolean quoted) {
    boolean fieldStart = !quoted;
    boolean inside = quoted;
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (inside) {
        if (c == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            i += 2;
            continue;
          }
          inside = false;
        }
        fieldStart = false;
      } else if (c == '"' && fieldStart) {
        inside = true;
        fieldStart = false;
      } else {
        fieldStart = c == delimiter;
      }
      i++;
    }
    return inside;
  }

  /**
   * Returns the record starts of every line: the classified ones, then each partial or unclassified
   * line on its own.
   */
  static List<Long> recordStarts(
      List<String> lines, boolean complete, ColumnSchema schema, Charset charset) {
    List<String> classified = complete ? lines : lines.subList(0, lines.size() - 1);
    boolean[] starts = starts(classified, schema, charset);
    List<Long> result = new ArrayList<Long>();
    for (int line = 0; line < lines.size(); line++) {
      if (line >= starts.length || starts[line]) {
        result.add((long) line);
      }
    }
    return result;
  }

  /** Joins a record's lines with LF, dropping a CR at its end. */
  static String record(List<String> lines, long first, long end) {
    StringBuilder text = new StringBuilder();
    for (long line = first; line < end; line++) {
      if (line > first) {
        text.append('\n');
      }
      text.append(lines.get((int) line));
    }
    if (text.length() > 0 && text.charAt(text.length() - 1) == '\r') {
      text.setLength(text.length() - 1);
    }
    return text.toString();
  }

  /** Returns every line's record-start flag as the scan's snapshot classifies it. */
  static List<Long> snapshotStarts(RecordLines records, long lineCount) {
    List<Long> result = new ArrayList<Long>();
    for (long line = 0; line < lineCount; line++) {
      if (records.starts(line)) {
        result.add(line);
      }
    }
    return result;
  }

  /** Asserts equal start lists, reporting the first difference. */
  static void assertSameStarts(List<Long> expected, List<Long> actual, String message) {
    int n = Math.min(expected.size(), actual.size());
    for (int i = 0; i < n; i++) {
      if (!expected.get(i).equals(actual.get(i))) {
        assertEquals(expected.get(i), actual.get(i), message + ": start " + i);
      }
    }
    assertEquals(expected.size(), actual.size(), message + ": start count");
  }
}

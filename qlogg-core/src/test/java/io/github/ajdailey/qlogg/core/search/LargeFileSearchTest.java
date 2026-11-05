package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Searches sparse files past 2^31 bytes; holes read as zero bytes. The first file has 2,100 lines
 * of 1 MiB ended by LF, with text at the start of line 0, inside line 2,048 just past 2^31, and in
 * the partial last line. The second has one line longer than {@link SearchEngine#MAX_LINE_BYTES}
 * between short lines. A range search runs across the 2^31 offset, and the range finder reads lines
 * past it.
 */
class LargeFileSearchTest {
  private static final long MIB = 1L << 20;
  private static final long TWO_GIB = 1L << 31;
  private static final int LINES = 2100;

  @TempDir Path dir;

  @Test
  void searchesPastTwoGigabytes() throws Exception {
    Path file = dir.resolve("large.log");
    writeSparse(file);
    try (SearchFixture fixture = new SearchFixture()) {
      try (LogDocument document = fixture.open(file, null)) {
        assertEquals(LINES + 1, document.index().lineCount());
        SearchEngine engine = fixture.services.search();
        assertEquals(List.of(0L, 2048L, 2100L), run(fixture, engine, document, "flagword", true));
        assertEquals(List.of(2048L), run(fixture, engine, document, "BETA FLAG", false));
        MutableLineSet range = new MutableLineSet();
        SearchFixture.Recording ranged =
            fixture.search(engine, document, range("alpha", "beta", 2040), range);
        assertEquals(OperationOutcome.COMPLETED, ranged.outcome);
        assertEquals(9, range.size(), "open from line 2040 through line 2048");
        assertEquals(2048, range.select(8));
        assertEquals(
            new RangeFinder.Range(2048, 2100),
            RangeFinder.around(
                document,
                document.index(),
                range("beta", "gamma", 0),
                StandardMatchers.INSTANCE,
                2100,
                new CancellationToken()));
      }
    }
    Files.delete(file);
  }

  @Test
  void skipsALineLongerThanTheLargestBufferAndSearchesOn() throws Exception {
    Path file = dir.resolve("wide.log");
    long longEnd = 16 + TWO_GIB;
    try (FileChannel channel =
        FileChannel.open(
            file,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            StandardOpenOption.SPARSE)) {
      write(channel, 0, "alpha flagword\n");
      write(channel, longEnd, "\nbeta flagword\ngamma flagword\n");
    }
    try (SearchFixture fixture = new SearchFixture()) {
      try (LogDocument document = fixture.open(file, null)) {
        assertEquals(4, document.index().lineCount());
        assertTrue(
            document.index().lineEnd(1) - document.index().lineStart(1)
                > SearchEngine.MAX_LINE_BYTES);
        MutableLineSet set = new MutableLineSet();
        SearchFixture.Recording recording =
            fixture.search(
                fixture.services.search(),
                document,
                SearchQuery.of("flagword", MatchMode.LITERAL),
                set);
        assertEquals(OperationOutcome.COMPLETED, recording.outcome);
        assertEquals(SearchLimit.NONE, recording.limit);
        assertEquals(1, recording.skipped);
        assertEquals(4, recording.searched);
        assertEquals(3, set.size());
        assertEquals(0, set.select(0));
        assertEquals(2, set.select(1));
        assertEquals(3, set.select(2));
      }
    }
    Files.delete(file);
  }

  /** A range query; from a first line past 0, the range is open there. */
  private static SearchQuery range(String start, String end, long first) {
    return new SearchQuery(
        new SearchExpression.Term(start, MatchMode.LITERAL),
        true,
        false,
        false,
        first == 0 ? null : new LineRange(first, Long.MAX_VALUE),
        false,
        new SearchExpression.Term(end, MatchMode.LITERAL),
        first > 0);
  }

  private static List<Long> run(
      SearchFixture fixture,
      SearchEngine engine,
      LogDocument document,
      String pattern,
      boolean caseSensitive)
      throws InterruptedException {
    MutableLineSet set = new MutableLineSet();
    SearchQuery query =
        new SearchQuery(
            new SearchExpression.Term(pattern, MatchMode.LITERAL),
            caseSensitive,
            false,
            false,
            null);
    SearchFixture.Recording recording = fixture.search(engine, document, query, set);
    assertEquals(OperationOutcome.COMPLETED, recording.outcome);
    assertEquals(LINES + 1, recording.searched);
    assertEquals(0, recording.skipped);
    List<Long> lines = new ArrayList<Long>();
    for (long i = 0; i < set.size(); i++) {
      lines.add(set.select(i));
    }
    assertTrue(lines.size() == recording.matches);
    return lines;
  }

  private static void writeSparse(Path file) throws IOException {
    try (FileChannel channel =
        FileChannel.open(
            file,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            StandardOpenOption.SPARSE)) {
      write(channel, 0, "alpha flagword");
      for (long line = 1; line <= LINES; line++) {
        write(channel, line * MIB - 1, "\n");
      }
      write(channel, TWO_GIB + 10, "beta flagword");
      write(channel, LINES * MIB, "tail flagword");
    }
  }

  private static void write(FileChannel channel, long position, String text) throws IOException {
    ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    while (bytes.hasRemaining()) {
      channel.write(bytes, position + bytes.position());
    }
  }
}

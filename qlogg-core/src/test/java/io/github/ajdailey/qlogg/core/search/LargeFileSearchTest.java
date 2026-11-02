package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Searches a file past 2^31 bytes. The file is sparse: 2,100 lines of 1 MiB (holes read as zero
 * bytes) ended by LF, with text at the start of line 0, inside line 2,048 just past 2^31, and in
 * the partial last line.
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
      }
    }
    Files.delete(file);
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

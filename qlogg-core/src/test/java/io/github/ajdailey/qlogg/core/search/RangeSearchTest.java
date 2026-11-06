package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Range search ({@link SearchQuery#rangeEnd}) against a sequential reference scan, for chunk sizes
 * that cut ranges at every partition boundary, with inverse, scope and an initially open range; and
 * {@link RangeFinder} around a line.
 */
class RangeSearchTest {
  private static final String START = "\"BEGIN\"";
  private static final String END = "\"END\"";

  @TempDir Path dir;

  private SearchFixture fixture;

  @BeforeEach
  void create() {
    fixture = new SearchFixture();
  }

  @AfterEach
  void close() {
    fixture.close();
  }

  /** Lines with starts, ends, lines matching both, repeated starts and an unclosed last range. */
  private static List<String> corpus() {
    List<String> lines = new ArrayList<String>();
    Random random = new Random(7);
    for (int i = 0; i < 2000; i++) {
      int roll = random.nextInt(20);
      if (roll == 0) {
        lines.add("BEGIN job " + i);
      } else if (roll == 1) {
        lines.add("END job " + i);
      } else if (roll == 2) {
        lines.add("BEGIN and END " + i);
      } else {
        lines.add("line " + i);
      }
    }
    lines.add("BEGIN unclosed");
    lines.add("tail 1");
    lines.add("tail 2");
    return lines;
  }

  /** The sequential reference: a start opens, the first later end closes, inclusive. */
  private static List<Long> reference(
      List<String> lines, long first, long end, boolean inverse, boolean openAtStart) {
    List<Long> result = new ArrayList<Long>();
    boolean open = openAtStart;
    for (long i = first; i < end; i++) {
      String line = lines.get((int) i);
      boolean in;
      if (open) {
        in = true;
        if (line.contains("END")) {
          open = false;
        }
      } else if (line.contains("BEGIN")) {
        in = true;
        open = true;
      } else {
        in = false;
      }
      if (in != inverse) {
        result.add(i);
      }
    }
    return result;
  }

  private Path write(List<String> lines) throws IOException {
    Path file = dir.resolve("range.log");
    Files.write(file, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    return file;
  }

  private static SearchQuery range(boolean inverse, LineRange scope, boolean open)
      throws ExpressionException {
    return new SearchQuery(
        ExpressionParser.parse(START, MatchMode.LITERAL, true),
        true,
        false,
        inverse,
        scope,
        false,
        ExpressionParser.parse(END, MatchMode.LITERAL, true),
        open);
  }

  private static List<Long> toList(LineSet set) {
    List<Long> result = new ArrayList<Long>();
    for (long i = 0; i < set.size(); i++) {
      result.add(set.select(i));
    }
    return result;
  }

  /** Records the range state the engine reports. */
  private static final class RangeRecording implements SearchListener {
    volatile Boolean openBeforeLast;

    @Override
    public void progressed(long searchedLines, long totalLines, long matchCount) {
      // Only the range state matters here.
    }

    @Override
    public void rangeOpenBeforeLast(boolean open) {
      openBeforeLast = open;
    }

    @Override
    public void finished(
        OperationOutcome outcome, SearchLimit limit, long skippedLines, Exception failure) {
      // The handle reports the end.
    }
  }

  private List<Long> run(SearchEngine engine, LogDocument document, SearchQuery query)
      throws InterruptedException {
    MutableLineSet set = new MutableLineSet();
    SearchFixture.Recording recording = fixture.search(engine, document, query, set);
    assertEquals(OperationOutcome.COMPLETED, recording.outcome, String.valueOf(recording.failure));
    assertEquals(SearchLimit.NONE, recording.limit);
    assertEquals(set.size(), recording.matches);
    return toList(set);
  }

  @Test
  void partitionedSearchMatchesTheSequentialScan() throws Exception {
    List<String> lines = corpus();
    try (LogDocument document = fixture.open(write(lines), null)) {
      long count = lines.size();
      int[][] sizes = {
        {1, 1 << 20, 1}, {2, 64, 3}, {3, 1 << 20, 8}, {7, 100, 5}, {10_000, 1 << 20, 3}
      };
      for (int[] size : sizes) {
        SearchEngine engine =
            fixture.engine(new SearchSettings(size[0], size[1], size[2], 0, fixture.budget));
        String context = size[0] + "/" + size[1] + "/" + size[2];
        assertEquals(
            reference(lines, 0, count, false, false),
            run(engine, document, range(false, null, false)),
            context);
        assertEquals(
            reference(lines, 0, count, true, false),
            run(engine, document, range(true, null, false)),
            context + " inverse");
        assertEquals(
            reference(lines, 333, 1501, false, false),
            run(engine, document, range(false, new LineRange(333, 1501), false)),
            context + " scope");
        assertEquals(
            reference(lines, 500, count, false, true),
            run(engine, document, range(false, new LineRange(500, Long.MAX_VALUE), true)),
            context + " open at the scope start");
      }
    }
  }

  @Test
  void aRangeCrossingEveryChunkBoundaryStaysWhole() throws Exception {
    List<String> lines = new ArrayList<String>();
    lines.add("before");
    lines.add("BEGIN");
    for (int i = 0; i < 20; i++) {
      lines.add("BEGIN inside " + i);
    }
    lines.add("END");
    lines.add("after");
    try (LogDocument document = fixture.open(write(lines), null)) {
      for (int chunk = 1; chunk <= 5; chunk++) {
        SearchEngine engine =
            fixture.engine(new SearchSettings(chunk, 1 << 20, 4, 0, fixture.budget));
        List<Long> found = run(engine, document, range(false, null, false));
        assertEquals(22, found.size(), "chunk " + chunk);
        assertEquals(1L, found.get(0));
        assertEquals(22L, found.get(21));
      }
    }
  }

  @Test
  void theEngineReportsTheStateBeforeTheLastLine() throws Exception {
    List<String> lines = List.of("x", "BEGIN", "y", "z");
    try (LogDocument document = fixture.open(write(lines), null)) {
      SearchEngine engine = fixture.engine(new SearchSettings(2, 1 << 20, 2, 0, fixture.budget));
      RangeRecording open = new RangeRecording();
      assertTrue(
          engine
              .search(document, range(false, null, false), new MutableLineSet(), open)
              .await(SearchFixture.BOUND.toMillis()));
      assertEquals(Boolean.TRUE, open.openBeforeLast);
      RangeRecording opening = new RangeRecording();
      assertTrue(
          engine
              .search(
                  document, range(false, new LineRange(0, 2), false), new MutableLineSet(), opening)
              .await(SearchFixture.BOUND.toMillis()));
      assertEquals(Boolean.FALSE, opening.openBeforeLast, "the last line opens the range");
      RangeRecording plain = new RangeRecording();
      assertTrue(
          engine
              .search(document, SearchQuery.of("y", MatchMode.LITERAL), new MutableLineSet(), plain)
              .await(SearchFixture.BOUND.toMillis()));
      assertNull(plain.openBeforeLast, "a line search reports no range state");
    }
  }

  @Test
  void theFinderSelectsTheRangeAroundALine() throws Exception {
    List<String> lines =
        List.of(
            "a", "BEGIN 1", "b", "END 1", "c", "BEGIN 2", "BEGIN 3", "d", "END 2", "e", "BEGIN 4");
    try (LogDocument document = fixture.open(write(lines), null)) {
      SearchQuery query = range(false, null, false);
      assertNull(around(document, query, 0), "no start above");
      assertEquals(new RangeFinder.Range(1, 3), around(document, query, 1));
      assertEquals(new RangeFinder.Range(1, 3), around(document, query, 3));
      assertNull(around(document, query, 4), "the range ended above the line");
      assertEquals(new RangeFinder.Range(6, 8), around(document, query, 7), "nearest start");
      assertEquals(new RangeFinder.Range(10, 10), around(document, query, 10), "unclosed");
    }
  }

  @Test
  void theFinderReadsAcrossBlocks() throws Exception {
    List<String> lines = new ArrayList<String>();
    lines.add("BEGIN");
    for (int i = 0; i < RangeFinder.BLOCK_LINES * 2 + 17; i++) {
      lines.add("filler " + i);
    }
    lines.add("END");
    lines.add("after");
    try (LogDocument document = fixture.open(write(lines), null)) {
      long last = lines.size() - 2;
      assertEquals(
          new RangeFinder.Range(0, last),
          around(document, range(false, null, false), RangeFinder.BLOCK_LINES + 5));
      assertNull(around(document, range(false, null, false), last + 1));
    }
  }

  @Test
  void aFileCutBelowTheIndexFailsTheSearchAndTheFinder() throws Exception {
    final List<String> lines = List.of("BEGIN", "filler", "END", "after");
    Path file = write(lines);
    try (LogDocument opened = fixture.open(file, null)) {
      final LogDocument document = opened;
      try (FileChannel cut = FileChannel.open(file, StandardOpenOption.WRITE)) {
        cut.truncate(3);
      }
      final SearchQuery query = range(false, null, false);
      SearchFixture.Recording result =
          fixture.search(
              fixture.engine(new SearchSettings(2, 1 << 20, 2, 0, fixture.budget)),
              document,
              query);
      assertEquals(OperationOutcome.FAILED, result.outcome);
      assertTrue(result.failure instanceof IOException, String.valueOf(result.failure));
      assertThrows(
          IOException.class,
          new Executable() {
            @Override
            public void execute() throws Throwable {
              around(document, query, lines.size() - 1);
            }
          });
    }
  }

  @Test
  void aCanceledFinderStops() throws Exception {
    try (LogDocument opened = fixture.open(write(List.of("BEGIN", "END")), null)) {
      final LogDocument document = opened;
      final SearchQuery query = range(false, null, false);
      final CancellationToken token = new CancellationToken();
      token.cancel();
      assertThrows(
          CancellationException.class,
          new Executable() {
            @Override
            public void execute() throws Throwable {
              RangeFinder.around(
                  document, document.index(), query, StandardMatchers.INSTANCE, 1, token);
            }
          });
    }
  }

  private RangeFinder.Range around(LogDocument document, SearchQuery query, long line)
      throws IOException {
    return RangeFinder.around(
        document,
        document.index(),
        query,
        StandardMatchers.INSTANCE,
        line,
        new CancellationToken());
  }
}

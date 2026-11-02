package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.testkit.SyntheticLog;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchEngineTest {
  private static final int MIB = 1 << 20;

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

  /** What a line must satisfy; the reference the engine is checked against. */
  private interface Expected {
    boolean test(String line);
  }

  private Path write(String name, byte[] content) throws IOException {
    Path file = dir.resolve(name);
    Files.write(file, content);
    return file;
  }

  /** Splits as the line index does: at LF, one trailing CR dropped, no line after a final LF. */
  private static List<String> lines(String text) {
    List<String> lines = new ArrayList<String>();
    int start = 0;
    while (start < text.length()) {
      int lf = text.indexOf('\n', start);
      int end = lf < 0 ? text.length() : lf;
      String line = text.substring(start, end);
      lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
      start = lf < 0 ? text.length() : lf + 1;
    }
    return lines;
  }

  private static List<Long> expected(List<String> lines, Expected expected) {
    List<Long> result = new ArrayList<Long>();
    for (int i = 0; i < lines.size(); i++) {
      if (expected.test(lines.get(i))) {
        result.add((long) i);
      }
    }
    return result;
  }

  private static List<Long> toList(LineSet set) {
    List<Long> result = new ArrayList<Long>();
    for (long i = 0; i < set.size(); i++) {
      result.add(set.select(i));
    }
    return result;
  }

  private List<Long> run(SearchEngine engine, LineSource source, SearchQuery query)
      throws InterruptedException {
    MutableLineSet set = new MutableLineSet();
    SearchFixture.Recording recording = fixture.search(engine, source, query, set);
    assertEquals(OperationOutcome.COMPLETED, recording.outcome, String.valueOf(recording.failure));
    assertEquals(SearchLimit.NONE, recording.limit);
    assertEquals(set.size(), recording.matches);
    assertEquals(recording.total, recording.searched);
    return toList(set);
  }

  private static String corpus() {
    StringBuilder text = new StringBuilder(SyntheticLog.text(700, 11, "\n"));
    text.append(SyntheticLog.text(100, 12, "\r\n"));
    text.append("x".repeat(700)).append(" flagword at the end of a long line\n");
    text.append("\n\r\n");
    text.append("batch flagword\tbatched BATCH\n");
    text.append(SyntheticLog.text(100, 13, "\n"));
    text.append("last line without newline flagword");
    return text.toString();
  }

  @Test
  void matchesTheReferenceForEveryChunkSize() throws Exception {
    String text = corpus();
    final List<String> lines = lines(text);
    try (LogDocument document = fixture.open(write("corpus.log", bytes(text)), null)) {
      assertEquals(lines.size(), document.index().lineCount());
      final Pattern regex = Pattern.compile("worker-1[0-5]\\] #\\d*7( |$)");
      final Pattern batchWord = Pattern.compile("(?<![A-Za-z0-9_])batch(?![A-Za-z0-9_])");
      int[][] sizes = {
        {1, 1, 1},
        {2, 2, 3},
        {1, MIB, 4},
        {2, 64, 2},
        {7, 100, 5},
        {10_000, MIB, 3},
        {10_000, 300, 8}
      };
      for (int[] size : sizes) {
        SearchEngine engine =
            fixture.engine(new SearchSettings(size[0], size[1], size[2], 0, fixture.budget));
        String context = size[0] + "/" + size[1] + "/" + size[2];
        assertEquals(
            expected(
                lines,
                new Expected() {
                  @Override
                  public boolean test(String line) {
                    return line.contains("flagword");
                  }
                }),
            run(engine, document, SearchQuery.of("flagword", MatchMode.LITERAL)),
            context);
        assertEquals(
            expected(
                lines,
                new Expected() {
                  @Override
                  public boolean test(String line) {
                    return line.toLowerCase(Locale.ROOT).contains("blockterm");
                  }
                }),
            run(engine, document, query("blockterm", MatchMode.LITERAL, false, false, false)),
            context);
        assertEquals(
            expected(
                lines,
                new Expected() {
                  @Override
                  public boolean test(String line) {
                    return regex.matcher(line).find();
                  }
                }),
            run(engine, document, SearchQuery.of(regex.pattern(), MatchMode.REGEX)),
            context);
        assertEquals(
            expected(
                lines,
                new Expected() {
                  @Override
                  public boolean test(String line) {
                    return !line.contains("mock");
                  }
                }),
            run(engine, document, query("mock", MatchMode.LITERAL, true, false, true)),
            context);
        assertEquals(
            expected(
                lines,
                new Expected() {
                  @Override
                  public boolean test(String line) {
                    return batchWord.matcher(line).find();
                  }
                }),
            run(engine, document, query("batch", MatchMode.LITERAL, true, true, false)),
            context);
      }
    }
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static SearchQuery query(
      String pattern, MatchMode mode, boolean caseSensitive, boolean wholeWord, boolean inverse) {
    return new SearchQuery(
        new SearchExpression.Term(pattern, mode), caseSensitive, wholeWord, inverse, null);
  }

  @Test
  void matchesOnTheFirstAndLastLineOfEachChunk() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      text.append(i % 10 == 0 || i % 10 == 9 ? "hit " : "miss ").append(i).append('\n');
    }
    try (LogDocument document = fixture.open(write("edges.log", bytes(text.toString())), null)) {
      SearchEngine engine = fixture.engine(new SearchSettings(10, MIB, 2, 0, fixture.budget));
      assertEquals(
          List.of(0L, 9L, 10L, 19L, 20L, 29L, 30L, 39L),
          run(engine, document, SearchQuery.of("hit", MatchMode.LITERAL)));
    }
  }

  @Test
  void decodesEachEncodingAndLineEnding() throws Exception {
    String text = "café one\r\n中文 two\nCAFÉ three\r\nlast café";
    final List<String> lines = lines(text);
    Object[][] cases = {
      {StandardCharsets.UTF_8, new byte[0]},
      {StandardCharsets.UTF_8, new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}},
      {StandardCharsets.UTF_16LE, new byte[] {(byte) 0xff, (byte) 0xfe}},
      {StandardCharsets.UTF_16BE, new byte[] {(byte) 0xfe, (byte) 0xff}}
    };
    int n = 0;
    for (Object[] c : cases) {
      Charset charset = (Charset) c[0];
      byte[] bom = (byte[]) c[1];
      byte[] body = text.getBytes(charset);
      byte[] content = new byte[bom.length + body.length];
      System.arraycopy(bom, 0, content, 0, bom.length);
      System.arraycopy(body, 0, content, bom.length, body.length);
      try (LogDocument document = fixture.open(write("enc" + n++ + ".log", content), null)) {
        assertEquals(charset, document.encoding().charset());
        for (int chunkLines : new int[] {1, 2, 10_000}) {
          SearchEngine engine =
              fixture.engine(new SearchSettings(chunkLines, 64, 2, 0, fixture.budget));
          assertEquals(
              expected(
                  lines,
                  new Expected() {
                    @Override
                    public boolean test(String line) {
                      return line.contains("café");
                    }
                  }),
              run(engine, document, SearchQuery.of("café", MatchMode.LITERAL)),
              charset.name());
          assertEquals(
              List.of(0L, 2L, 3L),
              run(engine, document, query("café", MatchMode.LITERAL, false, false, false)));
          assertEquals(
              List.of(0L, 2L),
              run(engine, document, SearchQuery.of("(?i)^caf. \\w+$", MatchMode.REGEX)));
          assertEquals(
              List.of(1L), run(engine, document, SearchQuery.of("文 two", MatchMode.LITERAL)));
          assertEquals(
              List.of(3L), run(engine, document, SearchQuery.of("café$", MatchMode.REGEX)));
        }
      }
    }
  }

  @Test
  void scopeLimitsTheSearchedLines() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 30; i++) {
      text.append("line ").append(i).append('\n');
    }
    try (LogDocument document = fixture.open(write("scope.log", bytes(text.toString())), null)) {
      SearchEngine engine = fixture.engine(new SearchSettings(4, MIB, 2, 0, fixture.budget));
      SearchExpression line = new SearchExpression.Term("line", MatchMode.LITERAL);
      List<Long> scoped =
          run(engine, document, new SearchQuery(line, true, false, false, new LineRange(5, 15)));
      assertEquals(10, scoped.size());
      assertEquals(5L, scoped.get(0));
      assertEquals(14L, scoped.get(9));
      assertEquals(
          List.of(28L, 29L),
          run(engine, document, new SearchQuery(line, true, false, false, new LineRange(28, 99))));
      assertEquals(
          List.of(),
          run(engine, document, new SearchQuery(line, true, false, false, new LineRange(40, 50))));
      SearchExpression one = new SearchExpression.Term("line 1", MatchMode.LITERAL);
      assertEquals(
          List.of(9L),
          run(engine, document, new SearchQuery(one, true, false, true, new LineRange(9, 12))));
    }
  }

  @Test
  void maxResultsStopsAtTheLimit() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      text.append(i % 3 == 0 ? "match " : "other ").append(i).append('\n');
    }
    try (LogDocument document = fixture.open(write("max.log", bytes(text.toString())), null)) {
      SearchEngine engine =
          fixture.engine(new SearchSettings(7, MIB, 3, 0, fixture.budget).withMaxResults(5));
      MutableLineSet set = new MutableLineSet();
      SearchFixture.Recording recording =
          fixture.search(engine, document, SearchQuery.of("match", MatchMode.LITERAL), set);
      assertEquals(OperationOutcome.COMPLETED, recording.outcome);
      assertEquals(SearchLimit.MAX_RESULTS, recording.limit);
      assertEquals(List.of(0L, 3L, 6L, 9L, 12L), toList(set));
      assertEquals(5, recording.matches);
      assertEquals(14, recording.searched);
    }
  }

  @Test
  void resultShareCapStopsWithTheResultsKept() throws Exception {
    Path file = dir.resolve("dense.log");
    SyntheticLog.write(file, 20 * MIB, 5, "\n");
    try (LogDocument document = fixture.open(file, null)) {
      MemoryBudget small = MemoryBudget.of(100_000);
      long limit = small.limit(MemoryShare.RESULTS);
      MutableLineSet set = new MutableLineSet(small);
      SearchFixture.Recording recording =
          fixture.search(
              fixture.services.search(), document, SearchQuery.of("2026", MatchMode.LITERAL), set);
      assertEquals(OperationOutcome.COMPLETED, recording.outcome);
      assertEquals(SearchLimit.MEMORY, recording.limit);
      assertTrue(set.size() > 0);
      assertTrue(recording.searched < recording.total);
      assertEquals(set.size(), recording.matches);
      assertEquals(recording.searched, set.select(set.size() - 1) + 1);
      assertTrue(small.reserved(MemoryShare.RESULTS) <= limit);
      set.clear();
      assertEquals(0, small.reserved(MemoryShare.RESULTS));
    }
  }

  @Test
  void longLineTheBufferShareRefusesStopsTheSearch() throws Exception {
    String text = "hit 0\nhit 1\n" + "x".repeat(200_000) + " hit\nhit 3\n";
    try (LogDocument document = fixture.open(write("long.log", bytes(text)), null)) {
      MemoryBudget small = MemoryBudget.of(1_000_000);
      SearchEngine engine = fixture.engine(new SearchSettings(10_000, 4096, 2, 0, small));
      MutableLineSet set = new MutableLineSet();
      SearchFixture.Recording recording =
          fixture.search(engine, document, SearchQuery.of("hit", MatchMode.LITERAL), set);
      assertEquals(OperationOutcome.COMPLETED, recording.outcome);
      assertEquals(SearchLimit.MEMORY, recording.limit);
      assertEquals(List.of(0L, 1L), toList(set));
      assertEquals(2, recording.searched);
      SearchEngine roomy = fixture.engine(new SearchSettings(10_000, 4096, 2, 0, fixture.budget));
      assertEquals(
          List.of(0L, 1L, 2L, 3L), run(roomy, document, SearchQuery.of("hit", MatchMode.LITERAL)));
      assertTrue(small.reserved(MemoryShare.IO_BUFFERS) <= 2 * 4096);
    }
  }

  @Test
  void invalidRegexFails() throws Exception {
    try (LogDocument document = fixture.open(write("a.log", bytes("a\nb\n")), null)) {
      SearchFixture.Recording recording =
          fixture.search(
              fixture.services.search(), document, SearchQuery.of("(a", MatchMode.REGEX));
      assertEquals(OperationOutcome.FAILED, recording.outcome);
      assertInstanceOf(PatternSyntaxException.class, recording.failure);
      assertEquals(SearchLimit.NONE, recording.limit);
    }
  }

  @Test
  void emptyDocumentCompletesWithNoResults() throws Exception {
    try (LogDocument document = fixture.open(write("empty.log", new byte[0]), null)) {
      assertEquals(
          List.of(),
          run(fixture.services.search(), document, SearchQuery.of("", MatchMode.LITERAL)));
    }
  }

  @Test
  void closedSourceEndsCanceled() throws Exception {
    LogDocument document = fixture.open(write("closed.log", bytes("a\nb\n")), null);
    document.close();
    SearchFixture.Recording recording =
        fixture.search(fixture.services.search(), document, SearchQuery.of("a", MatchMode.LITERAL));
    assertEquals(OperationOutcome.CANCELED, recording.outcome);
  }

  @Test
  void cancelEndsACatastrophicRegexWithinABound() throws Exception {
    String text = "a".repeat(60) + "\n";
    try (LogDocument document = fixture.open(write("evil.log", bytes(text)), null)) {
      final CountDownLatch matching = new CountDownLatch(1);
      MatcherFactory gated =
          new MatcherFactory() {
            @Override
            public LineMatcher create(
                SearchQuery query, TextEncoding encoding, CancellationToken token) {
              final LineMatcher inner = StandardMatchers.INSTANCE.create(query, encoding, token);
              return new LineMatcher() {
                @Override
                public boolean matches(LineText line) {
                  matching.countDown();
                  return inner.matches(line);
                }
              };
            }
          };
      SearchEngine engine =
          new SearchEngine(fixture.workers, gated, SearchSettings.defaults(2, fixture.budget));
      SearchFixture.Recording recording = new SearchFixture.Recording();
      OperationHandle handle =
          engine.search(
              document,
              SearchQuery.of("(.*a){25}x", MatchMode.REGEX),
              new MutableLineSet(),
              recording);
      assertTrue(matching.await(SearchFixture.BOUND.toMillis(), TimeUnit.MILLISECONDS));
      long canceled = System.nanoTime();
      handle.cancel();
      assertTrue(handle.await(5000));
      assertTrue(System.nanoTime() - canceled < 5_000_000_000L);
      assertEquals(OperationOutcome.CANCELED, recording.outcome);
    }
  }
}

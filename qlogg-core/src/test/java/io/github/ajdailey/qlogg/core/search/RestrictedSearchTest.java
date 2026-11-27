package io.github.ajdailey.qlogg.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.index.GrowableLineIndex;
import io.github.ajdailey.qlogg.core.index.IndexListener;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.FileIdentity;
import io.github.ajdailey.qlogg.core.io.MemoryByteSource;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A search restricted to a {@link LineSet} ({@link SearchQuery#restriction}) against a full search
 * intersected with the set, and a range search against a sequential scan of the member lines, over
 * random sets, chunk sizes that cut at every member and gap, chains of restrictions, UTF-16 and a
 * sparse index; and the bytes a restricted search reads.
 */
class RestrictedSearchTest {
  private static final int LINES = 3000;
  private static final int MIB = 1 << 20;
  private static final int GAP = SearchSettings.DEFAULT_GAP_BYTES;

  /**
   * Chunk lines, chunk bytes, chunks in flight and, when given, the gap; the last is the default.
   */
  private static final int[][] SIZES = {
    {1, 1, 1, 0},
    {2, 64, 2},
    {3, 300, 2, 150},
    {7, 100, 3, 1000},
    {20, 700, 2, 1},
    {400, 2000, 3, 300},
    {50, 4096, 4, 0},
    {10_000, MIB, 3, 1000},
    {10_000, MIB, 3}
  };

  private SearchFixture fixture;

  @BeforeEach
  void create() {
    fixture = new SearchFixture();
  }

  @AfterEach
  void close() {
    fixture.close();
  }

  /** An indexed in-memory document that counts the bytes read from it. */
  private record Source(LineIndex index, Counting bytes, TextEncoding encoding)
      implements LineSource {}

  /** Counts the bytes read through it. */
  private static final class Counting implements ByteSource {
    final MemoryByteSource delegate;
    final AtomicLong read = new AtomicLong();

    /** Thrown by every read from the second on when set; null reads normally. */
    volatile IOException failure;

    /** True ends every read from the second on at end of file. */
    volatile boolean truncated;

    private final AtomicLong reads = new AtomicLong();

    Counting(byte[] content) {
      this.delegate = new MemoryByteSource(content, 0);
    }

    @Override
    public Path path() {
      return delegate.path();
    }

    @Override
    public long size() throws IOException {
      return delegate.size();
    }

    @Override
    public int read(long position, ByteBuffer dst) throws IOException {
      if (reads.incrementAndGet() > 1) {
        if (failure != null) {
          throw failure;
        }
        if (truncated) {
          return -1;
        }
      }
      int n = delegate.read(position, dst);
      if (n > 0) {
        read.addAndGet(n);
      }
      return n;
    }

    @Override
    public FileIdentity identity() {
      return delegate.identity();
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  /** Indexes content; a small {@code indexBudget} makes the index sparse. */
  private Source source(byte[] content, Charset charset, MemoryBudget indexBudget)
      throws Exception {
    TextEncoding encoding = TextEncoding.of(charset, 0);
    Counting bytes = new Counting(content);
    final AtomicReference<OperationOutcome> outcome = new AtomicReference<OperationOutcome>();
    try (GrowableLineIndex index =
        new GrowableLineIndex(encoding, indexBudget, bytes, new BlockCache(4096, 1 << 20))) {
      OperationHandle handle =
          new Indexer(fixture.workers, new IndexSettings(64 * 1024, 2, fixture.budget))
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
      bytes.read.set(0);
      bytes.reads.set(0);
      return new Source(index.snapshot(), bytes, encoding);
    }
  }

  /** Lines of 0 to 200 bytes with tokens, CRLF endings, range markers and one 5,000-byte line. */
  private static List<String> corpus() {
    List<String> lines = new ArrayList<String>();
    SplittableRandom random = new SplittableRandom(23);
    String[] words = {"alpha", "beta", "hit", "HIT", "mock", "BEGIN", "END", "x7", "café"};
    for (int i = 0; i < LINES; i++) {
      if (i == 1700) {
        lines.add("hit " + "y".repeat(5000));
        continue;
      }
      StringBuilder line = new StringBuilder();
      int length = random.nextInt(200);
      while (line.length() < length) {
        line.append(words[random.nextInt(words.length)]).append(' ').append(random.nextInt(100));
        line.append(' ');
      }
      lines.add(line.toString());
    }
    return lines;
  }

  private static byte[] encode(List<String> lines, Charset charset) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      text.append(lines.get(i)).append(i % 5 == 0 ? "\r\n" : "\n");
    }
    return text.toString().getBytes(charset);
  }

  /** Random sets: empty, every line, dense, sparse, clusters split by gaps up to 3 x 50 lines. */
  private static List<MutableLineSet> sets(long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    List<MutableLineSet> sets = new ArrayList<MutableLineSet>();
    sets.add(new MutableLineSet());
    double[] densities = {1.0, 0.5, 0.02};
    for (double density : densities) {
      MutableLineSet set = new MutableLineSet();
      for (long line = 0; line < LINES; line++) {
        if (random.nextDouble() < density) {
          set.add(line);
        }
      }
      sets.add(set);
    }
    MutableLineSet clusters = new MutableLineSet();
    long line = random.nextInt(10);
    while (line < LINES) {
      int run = 1 + random.nextInt(20);
      for (int i = 0; i < run && line < LINES; i++) {
        clusters.add(line++);
      }
      line += random.nextInt(150);
    }
    sets.add(clusters);
    MutableLineSet edges = new MutableLineSet();
    edges.add(0);
    edges.add(1700);
    edges.add(LINES - 1);
    sets.add(edges);
    return sets;
  }

  private static SearchQuery query(String pattern, MatchMode mode, boolean inverse) {
    return new SearchQuery(new SearchExpression.Term(pattern, mode), false, false, inverse, null);
  }

  private static List<SearchQuery> lineQueries() {
    List<SearchQuery> queries = new ArrayList<SearchQuery>();
    queries.add(SearchQuery.of("hit", MatchMode.LITERAL));
    queries.add(query("HIT", MatchMode.LITERAL, false));
    queries.add(SearchQuery.of("a[lp]+ha \\d*7", MatchMode.REGEX));
    queries.add(query("beta", MatchMode.LITERAL, true));
    queries.add(
        new SearchQuery(
            new SearchExpression.Term("café", MatchMode.LITERAL),
            true,
            true,
            false,
            new LineRange(100, 2900)));
    return queries;
  }

  private static List<Long> toList(LineSet set) {
    List<Long> result = new ArrayList<Long>();
    for (long i = 0; i < set.size(); i++) {
      result.add(set.select(i));
    }
    return result;
  }

  private static List<Long> intersect(List<Long> lines, LineSet set) {
    List<Long> result = new ArrayList<Long>();
    for (long line : lines) {
      if (set.contains(line)) {
        result.add(line);
      }
    }
    return result;
  }

  private MutableLineSet run(SearchEngine engine, LineSource source, SearchQuery query)
      throws InterruptedException {
    MutableLineSet set = new MutableLineSet();
    SearchFixture.Recording recording = fixture.search(engine, source, query, set);
    assertEquals(OperationOutcome.COMPLETED, recording.outcome, String.valueOf(recording.failure));
    assertEquals(SearchLimit.NONE, recording.limit);
    assertEquals(set.size(), recording.matches);
    assertEquals(recording.total, recording.searched);
    assertEquals(0, recording.skipped);
    return set;
  }

  private SearchEngine engine(int[] size) {
    int gap = size.length > 3 ? size[3] : GAP;
    return fixture.engine(new SearchSettings(size[0], size[1], gap, size[2], 0, fixture.budget));
  }

  /** Restricted search equals the full search intersected with the set. */
  private void assertIntersects(Source source, int[][] sizes, long seed)
      throws InterruptedException {
    SearchEngine reference = engine(SIZES[SIZES.length - 1]);
    for (SearchQuery query : lineQueries()) {
      List<Long> full = toList(run(reference, source, query));
      for (MutableLineSet set : sets(seed)) {
        for (int[] size : sizes) {
          assertEquals(
              intersect(full, set),
              toList(run(engine(size), source, query.restrictedTo(set))),
              query.expression() + " " + set.size() + " members, " + size[0] + "/" + size[1]);
        }
      }
    }
  }

  @Test
  void restrictedEqualsFullIntersectedForRandomSets() throws Exception {
    List<String> lines = corpus();
    Source source =
        source(encode(lines, StandardCharsets.UTF_8), StandardCharsets.UTF_8, fixture.budget);
    assertIntersects(source, SIZES, 5);
  }

  @Test
  void restrictedEqualsFullIntersectedInUtf16AndOverASparseIndex() throws Exception {
    List<String> lines = corpus();
    int[][] sizes = {{1, 1, 1}, {7, 100, 3}, {50, 4096, 4}};
    Source utf16 =
        source(encode(lines, StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE, fixture.budget);
    assertIntersects(utf16, sizes, 6);
    Source sparse =
        source(
            encode(lines, StandardCharsets.UTF_8),
            StandardCharsets.UTF_8,
            MemoryBudget.of(150_000));
    assertTrue(sparse.index().sparse());
    assertIntersects(sparse, new int[][] {{50, 4096, 4}}, 7);
  }

  @Test
  void aChainOfRestrictionsEqualsTheIntersectionOfEachSearch() throws Exception {
    List<String> lines = corpus();
    Source source =
        source(encode(lines, StandardCharsets.UTF_8), StandardCharsets.UTF_8, fixture.budget);
    SearchEngine reference = engine(SIZES[SIZES.length - 1]);
    SearchQuery[] chain = {
      query("beta", MatchMode.LITERAL, true),
      SearchQuery.of("\\d7 ", MatchMode.REGEX),
      query("hit", MatchMode.LITERAL, false)
    };
    for (MutableLineSet set : sets(8)) {
      for (int[] size : SIZES) {
        SearchEngine engine = engine(size);
        LineSet level = set;
        List<Long> expected = toList(set);
        for (SearchQuery query : chain) {
          expected = intersect(toList(run(reference, source, query)), toSet(expected));
          level = run(engine, source, query.restrictedTo(level));
          assertEquals(expected, toList(level), size[0] + "/" + size[1]);
        }
      }
    }
  }

  private static MutableLineSet toSet(List<Long> lines) {
    MutableLineSet set = new MutableLineSet();
    for (long line : lines) {
      set.add(line);
    }
    return set;
  }

  /**
   * The sequential reference over the member lines: a start opens, the first later end closes,
   * inclusive; a line that opens is not tested against the end.
   */
  private static List<Long> rangeReference(
      List<String> lines, LineSet set, boolean inverse, boolean openAtStart) {
    List<Long> result = new ArrayList<Long>();
    boolean open = openAtStart;
    for (long line = set.ceiling(0); line >= 0; line = set.ceiling(line + 1)) {
      String text = lines.get((int) line);
      boolean in;
      if (open) {
        in = true;
        open = !text.contains("END");
      } else {
        in = text.contains("BEGIN");
        open = in;
      }
      if (in != inverse) {
        result.add(line);
      }
    }
    return result;
  }

  @Test
  void aRestrictedRangeOpensAndClosesOnMembers() throws Exception {
    List<String> lines = corpus();
    Source source =
        source(encode(lines, StandardCharsets.UTF_8), StandardCharsets.UTF_8, fixture.budget);
    for (MutableLineSet set : sets(9)) {
      for (int[] size : SIZES) {
        for (boolean inverse : new boolean[] {false, true}) {
          for (boolean open : new boolean[] {false, true}) {
            SearchQuery query =
                new SearchQuery(
                    new SearchExpression.Term("BEGIN", MatchMode.LITERAL),
                    true,
                    false,
                    inverse,
                    null,
                    false,
                    new SearchExpression.Term("END", MatchMode.LITERAL),
                    open,
                    set);
            assertEquals(
                rangeReference(lines, set, inverse, open),
                toList(run(engine(size), source, query)),
                set.size() + " members, " + size[0] + "/" + size[1] + " " + inverse + open);
          }
        }
      }
    }
  }

  @Test
  void membersFarApartReadOnlyTheirOwnBytes() throws Exception {
    List<String> lines = corpus();
    Source source =
        source(encode(lines, StandardCharsets.UTF_8), StandardCharsets.UTF_8, fixture.budget);
    MutableLineSet set = new MutableLineSet();
    long expected = 0;
    for (long line = 3; line < LINES; line += 97) {
      set.add(line);
      expected += source.index().lineEnd(line) - source.index().lineStart(line);
    }
    SearchEngine engine = engine(new int[] {50, MIB, 2});
    run(engine, source, SearchQuery.of("hit", MatchMode.LITERAL).restrictedTo(set));
    assertEquals(expected, source.bytes().read.get());
    source.bytes().read.set(0);
    run(engine, source, SearchQuery.of("hit", MatchMode.LITERAL));
    assertTrue(source.bytes().read.get() > 50 * expected);
  }

  /**
   * Members 0, 2 and 4: a 100-byte line between 0 and 2, and a filler between 2 and 4 that starts
   * line 4 {@code gap - 1}, {@code gap} or {@code gap + 1} bytes after line 2's end. Up to the gap
   * the search reads one span; past it, 0 and 2 read together and 4 alone.
   */
  @Test
  void aGapOverTheLimitIsNotRead() throws Exception {
    int gap = 1000;
    SearchEngine engine =
        fixture.engine(new SearchSettings(10_000, MIB, gap, 2, 0, fixture.budget));
    for (int delta = -1; delta <= 1; delta++) {
      String filler = "f".repeat(gap + delta - 2);
      String text = "hit a\n" + "g".repeat(100) + "\nhit b\n" + filler + "\nhit c\nhit d\n";
      Source source =
          source(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, fixture.budget);
      LineIndex index = source.index();
      assertEquals(gap + delta, index.lineStart(4) - index.lineEnd(2));
      MutableLineSet set = toSet(List.of(0L, 2L, 4L));
      assertEquals(
          List.of(0L, 2L, 4L),
          toList(run(engine, source, SearchQuery.of("hit", MatchMode.LITERAL).restrictedTo(set))));
      long joined = index.lineEnd(4) - index.lineStart(0);
      long split = index.lineEnd(2) - index.lineStart(0) + index.lineEnd(4) - index.lineStart(4);
      assertEquals(delta <= 0 ? joined : split, source.bytes().read.get(), "delta " + delta);
    }
  }

  /**
   * Members 0 and 2 within the gap, in a chunk of their span's bytes or one byte fewer: the span
   * reads whole, or each member alone without the line between them.
   */
  @Test
  void aRunEndsWhereTheChunkBytesEnd() throws Exception {
    String text = "hit a\n" + "g".repeat(100) + "\nhit b\n";
    Source source =
        source(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, fixture.budget);
    LineIndex index = source.index();
    int joined = (int) (index.lineEnd(2) - index.lineStart(0));
    long split = index.lineEnd(0) - index.lineStart(0) + index.lineEnd(2) - index.lineStart(2);
    MutableLineSet set = toSet(List.of(0L, 2L));
    for (int bytes = joined - 1; bytes <= joined; bytes++) {
      source.bytes().read.set(0);
      SearchEngine engine =
          fixture.engine(new SearchSettings(10_000, bytes, 1000, 2, 0, fixture.budget));
      assertEquals(
          List.of(0L, 2L),
          toList(run(engine, source, SearchQuery.of("hit", MatchMode.LITERAL).restrictedTo(set))));
      assertEquals(bytes == joined ? joined : split, source.bytes().read.get(), "bytes " + bytes);
    }
  }

  /**
   * Members 97 lines apart at a zero gap, so a chunk holds several runs: a closed channel on the
   * second read ends the search {@code CANCELED}, a file shorter than its index {@code FAILED}.
   */
  @Test
  void aFailedRunReadEndsTheSearch() throws Exception {
    MutableLineSet set = new MutableLineSet();
    for (long line = 3; line < LINES; line += 97) {
      set.add(line);
    }
    SearchQuery query = SearchQuery.of("hit", MatchMode.LITERAL).restrictedTo(set);
    SearchEngine engine = fixture.engine(new SearchSettings(10_000, MIB, 0, 2, 0, fixture.budget));
    byte[] content = encode(corpus(), StandardCharsets.UTF_8);
    Source closed = source(content, StandardCharsets.UTF_8, fixture.budget);
    closed.bytes().failure = new ClosedChannelException();
    SearchFixture.Recording recording = fixture.search(engine, closed, query, new MutableLineSet());
    assertEquals(OperationOutcome.CANCELED, recording.outcome);
    Source shorter = source(content, StandardCharsets.UTF_8, fixture.budget);
    shorter.bytes().truncated = true;
    recording = fixture.search(engine, shorter, query, new MutableLineSet());
    assertEquals(OperationOutcome.FAILED, recording.outcome);
    assertTrue(recording.failure instanceof IOException, String.valueOf(recording.failure));
  }

  @Test
  void aZeroGapReadsEachMemberAlone() throws Exception {
    String text = "hit 0\nhit 1\nmiss 2\nhit 3\n";
    Source source =
        source(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, fixture.budget);
    SearchEngine engine = fixture.engine(new SearchSettings(10_000, MIB, 0, 2, 0, fixture.budget));
    MutableLineSet set = toSet(List.of(0L, 1L, 3L));
    assertEquals(
        List.of(0L, 1L, 3L),
        toList(run(engine, source, SearchQuery.of("hit", MatchMode.LITERAL).restrictedTo(set))));
    assertEquals(15, source.bytes().read.get());
  }

  @Test
  void aLongLineOutsideTheSetIsNotReadAndOneInsideIsSkipped() throws Exception {
    String text = "hit 0\n" + "x".repeat(200_000) + " hit\nhit 2\nhit 3\n";
    Source source =
        source(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, fixture.budget);
    MemoryBudget small = MemoryBudget.of(1_000_000);
    SearchEngine engine = fixture.engine(new SearchSettings(10_000, 4096, 2, 0, small));
    SearchQuery hit = SearchQuery.of("hit", MatchMode.LITERAL);
    assertEquals(
        List.of(0L, 2L, 3L),
        toList(run(engine, source, hit.restrictedTo(toSet(List.of(0L, 2L, 3L))))));
    MutableLineSet set = new MutableLineSet();
    SearchFixture.Recording recording =
        fixture.search(engine, source, hit.restrictedTo(toSet(List.of(0L, 1L, 3L))), set);
    assertEquals(OperationOutcome.COMPLETED, recording.outcome);
    assertEquals(List.of(0L, 3L), toList(set));
    assertEquals(1, recording.skipped);
    assertEquals(recording.total, recording.searched);
  }

  @Test
  void maxResultsStopsARestrictedSearchAtTheLimit() throws Exception {
    List<String> lines = corpus();
    Source source =
        source(encode(lines, StandardCharsets.UTF_8), StandardCharsets.UTF_8, fixture.budget);
    MutableLineSet members = sets(10).get(2);
    SearchQuery query = query("hit", MatchMode.LITERAL, false).restrictedTo(members);
    List<Long> all = toList(run(engine(SIZES[SIZES.length - 1]), source, query));
    SearchEngine limited = fixture.engine(new SearchSettings(7, 100, 3, 5, fixture.budget));
    MutableLineSet set = new MutableLineSet();
    SearchFixture.Recording recording = fixture.search(limited, source, query, set);
    assertEquals(SearchLimit.MAX_RESULTS, recording.limit);
    assertEquals(all.subList(0, 5), toList(set));
    assertEquals(all.get(4) + 1, recording.searched);
  }
}

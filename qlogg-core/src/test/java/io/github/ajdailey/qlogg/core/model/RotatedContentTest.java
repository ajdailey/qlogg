package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.rotate.LeftOut;
import io.github.ajdailey.qlogg.core.rotate.LineMap;
import io.github.ajdailey.qlogg.core.rotate.RotatedSet;
import io.github.ajdailey.qlogg.core.rotate.SetFiles;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.RecordLines;
import io.github.ajdailey.qlogg.core.search.RecordScan;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import io.github.ajdailey.qlogg.core.watch.FileMonitor;
import io.github.ajdailey.qlogg.core.watch.MonitorSettings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A rotated set followed through roll-over on generated files: rename chains, copy-truncate, a new
 * dated member, exclusions after a roll-over, and the line map of a roll-over that changes line
 * numbers. Each test polls for the state it checks.
 */
class RotatedContentTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final long HOUR_MILLIS = 3_600_000L;

  @TempDir Path dir;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;
  private MemoryBudget budget;
  private DocumentServices services;
  private FileMonitor monitor;

  /** Records file changes and the first index outcome. */
  private static final class Events implements DocumentListener {
    final List<FileChange> changes = new CopyOnWriteArrayList<FileChange>();
    final CountDownLatch firstIndex = new CountDownLatch(1);

    @Override
    public void indexProgressed(IndexProgress progress) {}

    @Override
    public void indexFinished(OperationOutcome outcome, IOException failure) {
      firstIndex.countDown();
    }

    @Override
    public void fileChanged(FileChange change) {
      changes.add(change);
    }

    @Override
    public void closed() {}
  }

  @BeforeEach
  void createServices() {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    budget = MemoryBudget.of(1L << 28);
    services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(2, budget)),
            new SearchEngine(
                workers, StandardMatchers.INSTANCE, SearchSettings.defaults(2, budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.ISO_8859_1);
    monitor = new FileMonitor(workers, new MonitorSettings(20, true));
  }

  @AfterEach
  void closeServices() {
    assertTrue(workers.close(Workers.CLOSE_BOUND));
    assertEquals(0, workers.liveThreads());
    assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX));
    assertTrue(failures.isEmpty(), failures.toString());
  }

  /** Writes {@code count} lines named after the file, with the modification time hours ago. */
  private Path member(String name, int count, int hoursAgo) throws IOException {
    Path file = dir.resolve(name);
    Files.writeString(file, lines(name, 0, count), StandardCharsets.US_ASCII);
    age(file, hoursAgo);
    return file;
  }

  private static void age(Path file, int hoursAgo) throws IOException {
    Files.setLastModifiedTime(
        file, FileTime.fromMillis(System.currentTimeMillis() - hoursAgo * HOUR_MILLIS));
  }

  private static String lines(String prefix, int from, int count) {
    StringBuilder text = new StringBuilder();
    for (int i = from; i < from + count; i++) {
      text.append(prefix).append(" line ").append(i).append('\n');
    }
    return text.toString();
  }

  private static void append(Path file, String text) throws IOException {
    Files.writeString(file, text, StandardCharsets.US_ASCII, StandardOpenOption.APPEND);
  }

  private RotatedContent open(Path given, boolean keepClosed) throws IOException {
    return RotatedContent.open(given, null, services, true, keepClosed, false);
  }

  private LogDocument watched(RotatedContent content, Events events) throws Exception {
    LogDocument document = new LogDocument(content, services);
    document.addListener(events);
    document.startIndexing();
    assertTrue(events.firstIndex.await(BOUND.toMillis(), TimeUnit.MILLISECONDS));
    document.startWatching(monitor);
    return document;
  }

  private static BooleanSupplier lines(final LogDocument document, final long count) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return document.index().lineCount() == count && !document.status().indexing();
      }
    };
  }

  private static BooleanSupplier reported(final Events events, final FileChange change) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return events.changes.contains(change);
      }
    };
  }

  /** Reads the whole stream. */
  private static String text(ByteSource bytes) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteBuffer buffer = ByteBuffer.allocate(4096);
    long at = 0;
    long size = bytes.size();
    while (at < size) {
      buffer.clear();
      int read = bytes.read(at, buffer);
      if (read < 0) {
        break;
      }
      out.write(buffer.array(), 0, read);
      at += read;
    }
    return out.toString(StandardCharsets.US_ASCII);
  }

  private static String concat(Path... files) throws IOException {
    StringBuilder text = new StringBuilder();
    for (Path file : files) {
      text.append(Files.readString(file, StandardCharsets.US_ASCII));
    }
    return text.toString();
  }

  private static void assertQuiet(Events events) {
    for (FileChange change : events.changes) {
      assertTrue(
          change == FileChange.APPENDED || change == FileChange.UNCHANGED,
          events.changes.toString());
    }
  }

  @Test
  void aCsvSetTakesItsColumnsFromTheNewestMemberAndStartsARecordAtEachMember() throws Exception {
    Path old = dir.resolve("app.csv.1");
    Files.writeString(old, "id,level,message\n1,INFO,\"open\n", StandardCharsets.US_ASCII);
    age(old, 2);
    Path active = dir.resolve("app.csv");
    Files.writeString(active, "id,level,message\n2,WARN,next\n", StandardCharsets.US_ASCII);
    age(active, 1);
    LogDocument document = watched(open(active, false), new Events());
    try {
      ColumnSchema schema = document.columns();
      assertEquals(ColumnFormat.CSV, schema.format());
      assertTrue(schema.header());
      assertEquals("level", schema.columns().get(1).name());
      LineIndex index = document.index();
      LineSet starts = document.memberStarts(index);
      assertEquals(List.of(0L, 2L), List.of(starts.select(0), starts.select(1)));
      RecordScan scan = new RecordScan(schema, document.encoding(), budget);
      document.scanRecords(scan, index, new CancellationToken());
      RecordLines records = scan.snapshot();
      assertEquals(4, records.end());
      for (long line = 0; line < 4; line++) {
        assertTrue(records.starts(line), "line " + line);
      }
      scan.lines().clear();
    } finally {
      document.close();
    }
  }

  @Test
  void opensMembersOldestFirstAsOneDocument() throws Exception {
    Path two = member("app.log.2", 3, 3);
    Path one = member("app.log.1", 2, 2);
    Path active = member("app.log", 4, 1);
    Events events = new Events();
    LogDocument document = watched(open(one, false), events);
    try {
      assertEquals("app.log", document.name());
      assertEquals(active, document.path());
      assertEquals(9, document.index().lineCount());
      assertEquals(concat(two, one, active), text(document.bytes()));
      assertEquals(3, document.index().lineStart(3) / "app.log.2 line 0\n".length());
    } finally {
      document.close();
    }
  }

  @Test
  void filesAndMemberBasesBeforeIndexing() throws Exception {
    member("app.log.2", 3, 3);
    Path one = dir.resolve("app.log.1");
    Files.writeString(one, "no final line feed", StandardCharsets.US_ASCII);
    age(one, 2);
    Path active = member("app.log", 4, 1);
    Files.writeString(dir.resolve("app.log.3.gz"), "z", StandardCharsets.US_ASCII);
    LogDocument document = new LogDocument(open(active, false), services);
    try {
      SetFiles files = document.files();
      assertEquals(3, files.members().size());
      assertEquals(dir.resolve("app.log.1"), files.members().get(1).path());
      assertEquals(18, files.members().get(1).size());
      assertEquals(document.bytes().identity().headDigest(), files.members().get(2).headDigest());
      assertEquals(1, files.leftOut(LeftOut.Reason.COMPRESSED));
      long[] bases =
          document.memberBases(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return false;
                }
              });
      assertArrayEquals(new long[] {0, 3, 4}, bases);
    } finally {
      document.close();
    }
  }

  @Test
  void growthOfTheActiveMemberIsAppended() throws Exception {
    member("app.log.1", 2, 2);
    Path active = member("app.log", 2, 1);
    Events events = new Events();
    LogDocument document = watched(open(active, false), events);
    try {
      append(active, lines("app.log", 2, 5));
      assertTrue(Poll.until(lines(document, 9), BOUND), events.changes.toString());
      assertTrue(events.changes.contains(FileChange.APPENDED));
      assertQuiet(events);
    } finally {
      document.close();
    }
  }

  /**
   * A rename chain: every member moves one name up, a new active file starts empty and joins once
   * it has bytes. A writer still appending to the renamed file appends to the last member
   * meanwhile.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void renameChainKeepsLineNumbers(boolean keepClosed) throws Exception {
    member("app.log.1", 3, 3);
    Path active = member("app.log", 2, 2);
    Events events = new Events();
    LogDocument document = watched(open(active, keepClosed), events);
    try {
      Files.move(dir.resolve("app.log.1"), dir.resolve("app.log.2"));
      Files.move(active, dir.resolve("app.log.1"));
      Files.createFile(active);
      append(dir.resolve("app.log.1"), lines("late", 0, 1));
      assertTrue(Poll.until(lines(document, 6), BOUND), events.changes.toString());
      append(active, lines("new", 0, 4));
      assertTrue(Poll.until(lines(document, 10), BOUND), events.changes.toString());
      assertQuiet(events);
      assertEquals(
          concat(dir.resolve("app.log.2"), dir.resolve("app.log.1"), active),
          text(document.bytes()));
      append(active, lines("new", 4, 1));
      assertTrue(Poll.until(lines(document, 11), BOUND), events.changes.toString());
    } finally {
      document.close();
    }
  }

  /**
   * A writer still on the renamed file after the new active file joined: the members after it move
   * down by the lines it gained, counted from the file.
   */
  @Test
  void growthBeforeTheLastMemberRolls() throws Exception {
    member("app.log.1", 3, 3);
    Path active = member("app.log", 2, 2);
    Events events = new Events();
    LogDocument document = watched(open(active, false), events);
    try {
      Files.move(dir.resolve("app.log.1"), dir.resolve("app.log.2"));
      Files.move(active, dir.resolve("app.log.1"));
      Files.writeString(active, lines("new", 0, 4), StandardCharsets.US_ASCII);
      assertTrue(Poll.until(lines(document, 9), BOUND), events.changes.toString());
      append(dir.resolve("app.log.1"), lines("late", 0, 2));
      assertTrue(Poll.until(reported(events, FileChange.ROLLED), BOUND), events.changes.toString());
      LineMap map = document.lineMap();
      assertEquals(List.<Path>of(), map.dropped());
      assertEquals(3, map.map(3));
      assertEquals(7, map.map(5));
      assertEquals(10, map.map(8));
    } finally {
      document.close();
    }
  }

  /** logrotate with {@code rotate 2}: the oldest file goes, the others move up a name. */
  @Test
  void renameChainDroppingTheOldestRolls() throws Exception {
    member("app.log.2", 3, 3);
    member("app.log.1", 2, 2);
    Path active = member("app.log", 4, 1);
    Events events = new Events();
    LogDocument document = watched(open(active, false), events);
    try {
      Files.delete(dir.resolve("app.log.2"));
      Files.move(dir.resolve("app.log.1"), dir.resolve("app.log.2"));
      Files.move(active, dir.resolve("app.log.1"));
      Files.writeString(active, lines("new", 0, 1), StandardCharsets.US_ASCII);
      assertTrue(Poll.until(reported(events, FileChange.ROLLED), BOUND), events.changes.toString());
      LineMap map = document.lineMap();
      assertNotNull(map);
      assertEquals(List.of(dir.resolve("app.log.2")), map.dropped());
      assertEquals(LineMap.DROPPED, map.map(2));
      assertEquals(0, map.map(3));
      assertEquals(2, map.map(5));
      assertEquals(5, map.map(8));
    } finally {
      document.close();
    }
  }

  /**
   * logrotate {@code copytruncate}: the older file moves up a name, the active file is copied to
   * {@code app.log.1} and truncated, and the writer goes on in the truncated file.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void copyTruncateKeepsLineNumbers(boolean keepClosed) throws Exception {
    member("app.log.1", 3, 3);
    Path active = member("app.log", 4, 2);
    Events events = new Events();
    LogDocument document = watched(open(active, keepClosed), events);
    try {
      Files.move(dir.resolve("app.log.1"), dir.resolve("app.log.2"));
      Files.copy(active, dir.resolve("app.log.1"));
      try (FileChannel channel = FileChannel.open(active, StandardOpenOption.WRITE)) {
        channel.truncate(0);
      }
      append(active, lines("new", 0, 2));
      assertTrue(Poll.until(lines(document, 9), BOUND), events.changes.toString());
      assertQuiet(events);
      assertEquals(
          concat(dir.resolve("app.log.2"), dir.resolve("app.log.1"), active),
          text(document.bytes()));
    } finally {
      document.close();
    }
  }

  /** A copy seen part-way is ignored while its size moves, and rolls the set once it holds. */
  @Test
  void copyInProgressWaitsForItsSizeToHold() throws Exception {
    member("app.log.1", 3, 3);
    Path active = member("app.log", 4, 2);
    RotatedContent content = open(active, false);
    Events events = new Events();
    LogDocument document = new LogDocument(content, services);
    try {
      document.addListener(events);
      document.startIndexing();
      assertTrue(events.firstIndex.await(BOUND.toMillis(), TimeUnit.MILLISECONDS));
      String head = Files.readString(active, StandardCharsets.US_ASCII);
      Path copy = dir.resolve("app.log.0");
      RollOver planner = new RollOver(content.detection().encoding(), null, never());
      List<SetMember> members = ((SetBytes) content.bytes()).layout().members();
      for (int i = 1; i <= 3; i++) {
        Files.writeString(copy, head.substring(0, i * 5), StandardCharsets.US_ASCII);
        age(copy, 1);
        assertEquals(RollOver.Kind.UNCHANGED, planner.plan(members, 2, rescan(active)).kind());
      }
      assertEquals(RollOver.Kind.UNCHANGED, planner.plan(members, 2, rescan(active)).kind());
      assertEquals(RollOver.Kind.ROLLED, planner.plan(members, 2, rescan(active)).kind());
    } finally {
      document.close();
    }
  }

  private RotatedSet rescan(Path given) throws IOException {
    return RotatedSet.rescan(
        given,
        null,
        StandardCharsets.ISO_8859_1,
        null,
        false,
        RotatedSet.discover(given, null, StandardCharsets.ISO_8859_1, null, false).detection());
  }

  private static BooleanSupplier never() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return false;
      }
    };
  }

  /** Date-named files with no active file: a new day's file joins after the newest. */
  @Test
  void newDatedMemberJoins() throws Exception {
    member("app-2026-11-24.log", 2, 3);
    Path newest = member("app-2026-11-25.log", 3, 2);
    Events events = new Events();
    LogDocument document = watched(open(newest, false), events);
    try {
      assertEquals("app.log", document.name());
      Files.writeString(
          dir.resolve("app-2026-11-26.log"), lines("day", 0, 4), StandardCharsets.US_ASCII);
      assertTrue(Poll.until(lines(document, 9), BOUND), events.changes.toString());
      assertQuiet(events);
    } finally {
      document.close();
    }
  }

  @Test
  void middleMemberDeletedRolls() throws Exception {
    member("app.log.2", 3, 3);
    member("app.log.1", 2, 2);
    Path active = member("app.log", 4, 1);
    Events events = new Events();
    LogDocument document = watched(open(active, false), events);
    try {
      Files.delete(dir.resolve("app.log.1"));
      assertTrue(Poll.until(reported(events, FileChange.ROLLED), BOUND), events.changes.toString());
      LineMap map = document.lineMap();
      assertEquals(1, map.map(1));
      assertEquals(LineMap.DROPPED, map.map(4));
      assertEquals(3, map.map(5));
    } finally {
      document.close();
    }
  }

  @Test
  void missingActiveFileIsReportedAndReturns() throws Exception {
    member("app.log.1", 2, 2);
    Path active = member("app.log", 2, 1);
    Events events = new Events();
    LogDocument document = watched(open(active, false), events);
    try {
      Path aside = dir.resolve("aside.txt");
      Files.move(active, aside);
      assertTrue(
          Poll.until(reported(events, FileChange.MISSING), BOUND), events.changes.toString());
      assertTrue(document.status().missing());
      Files.move(aside, active);
      append(active, lines("app.log", 2, 1));
      assertTrue(Poll.until(lines(document, 5), BOUND), events.changes.toString());
      assertFalse(document.status().missing());
      assertFalse(events.changes.contains(FileChange.ROLLED));
    } finally {
      document.close();
    }
  }

  /** A rename chain that pushes the oldest of 100 members past the cap drops it. */
  @Test
  void capAfterRollOverDropsTheOldest() throws Exception {
    int count = RotatedSet.MAX_MEMBERS;
    for (int i = count - 1; i >= 1; i--) {
      member("app.log." + i, 1, i + 1);
    }
    Path active = member("app.log", 1, 1);
    Events events = new Events();
    LogDocument document = watched(open(active, false), events);
    try {
      assertEquals(count, document.index().lineCount());
      for (int i = count - 1; i >= 1; i--) {
        Files.move(dir.resolve("app.log." + i), dir.resolve("app.log." + (i + 1)));
      }
      Files.move(active, dir.resolve("app.log.1"));
      Files.writeString(active, lines("new", 0, 1), StandardCharsets.US_ASCII);
      assertTrue(Poll.until(reported(events, FileChange.ROLLED), BOUND), events.changes.toString());
      LineMap map = document.lineMap();
      assertEquals(1, map.dropped().size());
      assertEquals(LineMap.DROPPED, map.map(0));
      assertEquals(0, map.map(1));
    } finally {
      document.close();
    }
  }

  /** A member compressed after a roll-over leaves the set. */
  @Test
  void compressedMemberAfterRollOverRolls() throws Exception {
    member("app.log.2", 3, 3);
    member("app.log.1", 2, 2);
    Path active = member("app.log", 2, 1);
    RotatedContent content = open(active, false);
    Events events = new Events();
    LogDocument document = watched(content, events);
    try {
      Files.write(dir.resolve("app.log.2.gz"), new byte[] {0x1f, (byte) 0x8b, 8, 0});
      Files.delete(dir.resolve("app.log.2"));
      assertTrue(Poll.until(reported(events, FileChange.ROLLED), BOUND), events.changes.toString());
      assertEquals(List.of(dir.resolve("app.log.2")), document.lineMap().dropped());
      assertEquals(0, document.lineMap().map(3));
      assertEquals(
          List.of(new LeftOut(dir.resolve("app.log.2.gz"), LeftOut.Reason.COMPRESSED)),
          content.leftOut());
    } finally {
      document.close();
    }
  }

  /**
   * A new active file in UTF-16 beside a one-byte set is left out; the renamed file stays the last
   * member and its growth stays an append.
   */
  @Test
  void otherEncodingAfterRollOverIsLeftOut() throws Exception {
    member("app.log.1", 2, 2);
    Path active = member("app.log", 2, 1);
    RotatedContent content = open(active, false);
    Events events = new Events();
    LogDocument document = watched(content, events);
    try {
      Files.move(dir.resolve("app.log.1"), dir.resolve("app.log.2"));
      Files.move(active, dir.resolve("app.log.1"));
      byte[] bom = {(byte) 0xff, (byte) 0xfe};
      byte[] text = "wide line\n".getBytes(StandardCharsets.UTF_16LE);
      ByteBuffer wide = ByteBuffer.allocate(bom.length + text.length).put(bom).put(text);
      Files.write(active, wide.array());
      assertTrue(
          Poll.until(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return content.leftOut().contains(new LeftOut(active, LeftOut.Reason.ENCODING));
                }
              },
              BOUND),
          content.leftOut().toString());
      append(dir.resolve("app.log.1"), lines("late", 0, 2));
      assertTrue(Poll.until(lines(document, 6), BOUND), events.changes.toString());
      assertQuiet(events);
    } finally {
      document.close();
    }
  }
}

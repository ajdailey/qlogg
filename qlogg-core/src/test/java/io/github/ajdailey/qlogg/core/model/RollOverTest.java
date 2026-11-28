package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexListener;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.rotate.LineMap;
import io.github.ajdailey.qlogg.core.rotate.Member;
import io.github.ajdailey.qlogg.core.rotate.RotatedSet;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Member identity where every file of the series starts with the same bytes. Each case runs with
 * file keys and without them, as on Windows, where creation times decide nothing after NTFS
 * tunneling. The tail's digests cover its first line only: an indexing pass publishes before it
 * records, and a record keeps the previous digests while the file at the member's name is shorter
 * than the indexed bytes.
 */
class RollOverTest {
  private static final String CSV_HEADER = "id,name\n";
  private static final String BANNER = "mock service started\n";
  private static final long HOUR_MILLIS = 3_600_000L;

  @TempDir Path dir;

  private final List<SetMember> members = new ArrayList<SetMember>();
  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;
  private MemoryBudget budget;
  private DocumentServices services;
  private Path base;
  private RotatedSet set;

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
    budget = MemoryBudget.of(1L << 26);
    services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(2, budget)),
            new SearchEngine(
                workers, StandardMatchers.INSTANCE, SearchSettings.defaults(2, budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.ISO_8859_1);
    base = dir.resolve("app.log");
  }

  @AfterEach
  void closeServices() throws IOException {
    for (SetMember member : members) {
      member.index().close();
      member.close();
    }
    assertTrue(workers.close(Workers.CLOSE_BOUND));
    assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX));
    assertTrue(failures.isEmpty(), failures.toString());
  }

  /**
   * Regression: the CSV file at the tail's name held only its header, and the set dropped the tail.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void aCsvSetRotatedByRenameFollowsTheTailPastTheNewHeader(boolean fileKeys) throws Exception {
    rotatedByRename(CSV_HEADER, fileKeys);
  }

  /** Regression: as for a CSV header, with a logger that writes a banner line to each new file. */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void aBannerSetRotatedByRenameFollowsTheTailPastTheNewBanner(boolean fileKeys) throws Exception {
    rotatedByRename(BANNER, fileKeys);
  }

  /**
   * Regression: the tail grew past its first line without an indexing pass, then was renamed, and
   * the new file at its name held exactly the indexed bytes. Without file keys the tail stayed at
   * the new file and the renamed tail rolled the set as an inserted file.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void anUnindexedTailRenamedBesideAnEqualNewFileFollowsTheRenamedFile(boolean fileKeys)
      throws Exception {
    openSet(CSV_HEADER);
    Files.writeString(
        base, rows("app.log", 3), StandardCharsets.US_ASCII, StandardOpenOption.APPEND);
    rename(CSV_HEADER);
    assertFollowedPastTheNewFile(plan(fileKeys));
  }

  /**
   * The tail and the new file at its name both hold only the first line: content cannot tell them
   * apart, so without file keys the tail stays at its name and the renamed file, starting as the
   * tail does, waits as a copy in progress. Where the platform has file keys and the case uses
   * them, the key follows the rename.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void aTailHoldingOnlyItsFirstLineBesideAnEqualNewFileStaysAtTheFileFound(boolean fileKeys)
      throws Exception {
    openSet(CSV_HEADER);
    rename(CSV_HEADER);
    RollOver.Plan plan = plan(fileKeys);
    if (fileKeys && members.get(1).memberIdentity().fileKey() != null) {
      assertFollowedPastTheNewFile(plan);
      return;
    }
    assertEquals(RollOver.Kind.IN_PLACE, plan.kind());
    assertEquals(List.of(dir.resolve("app.log.2"), base), plan.paths());
    assertTrue(plan.joins().isEmpty());
  }

  /**
   * The file at the tail's name grew past the indexed bytes beside another file with the same first
   * line: the tail stays at its name.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void aTailThatGrewAtItsNameKeepsItsPlace(boolean fileKeys) throws Exception {
    openSet(CSV_HEADER);
    Files.writeString(
        base, rows("app.log", 3), StandardCharsets.US_ASCII, StandardOpenOption.APPEND);
    write("app.log.2", CSV_HEADER + rows("app.log.2", 3), 3);
    RollOver.Plan plan = plan(fileKeys);
    assertEquals(RollOver.Kind.ROLLED, plan.kind());
    List<LineMap.Entry> entries = plan.map().members();
    assertEquals(4, entries.get(0).newBase());
    assertEquals(8, entries.get(1).newBase());
  }

  /**
   * Appends three rows to the tail and indexes them without a record, then rotates by rename and
   * starts a new {@code app.log} with the first line alone.
   */
  private void rotatedByRename(String header, boolean fileKeys) throws Exception {
    openSet(header);
    SetMember tail = members.get(1);
    Files.writeString(
        base, rows("app.log", 3), StandardCharsets.US_ASCII, StandardOpenOption.APPEND);
    index(tail);
    assertEquals(header.length(), tail.memberIdentity().indexedBytes());
    assertTrue(tail.index().snapshot().indexedBytes() > header.length());
    rename(header);
    assertFollowedPastTheNewFile(plan(fileKeys));
  }

  /** Renames {@code app.log.1} and {@code app.log} up one and starts {@code app.log} anew. */
  private void rename(String first) throws IOException {
    Files.move(dir.resolve("app.log.1"), dir.resolve("app.log.2"));
    Files.move(base, dir.resolve("app.log.1"));
    Files.writeString(base, first, StandardCharsets.US_ASCII);
  }

  /** The members follow their renamed files in place and the new {@code app.log} joins. */
  private void assertFollowedPastTheNewFile(RollOver.Plan plan) {
    assertEquals(RollOver.Kind.IN_PLACE, plan.kind());
    assertEquals(List.of(dir.resolve("app.log.2"), dir.resolve("app.log.1")), plan.paths());
    assertEquals(1, plan.joins().size());
    assertEquals(base, plan.joins().get(0).path());
  }

  /**
   * Opens {@code app.log.1} (the first line and three rows) and {@code app.log} (the first line),
   * indexes both and records their digests.
   */
  private void openSet(String header) throws Exception {
    write("app.log.1", header + rows("app.log.1", 3), 2);
    write("app.log", header, 1);
    set = RotatedSet.discover(base, null, StandardCharsets.ISO_8859_1, null, false);
    for (int i = 0; i < set.members().size(); i++) {
      Member found = set.members().get(i);
      SetMember member =
          SetMember.open(
              found.path(), found.encoding().bomLength(), set.encoding(), services, false);
      members.add(member);
      if (i < set.members().size() - 1) {
        member.limit(member.file().size());
      }
      index(member);
      member.record();
    }
  }

  private RollOver.Plan plan(boolean fileKeys) throws IOException {
    RollOver planner =
        new RollOver(
            set.encoding(),
            null,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return false;
              }
            },
            fileKeys);
    RotatedSet now =
        RotatedSet.rescan(base, null, StandardCharsets.ISO_8859_1, null, false, set.detection());
    return planner.plan(members, members.size(), now);
  }

  private void index(SetMember member) throws InterruptedException {
    final CountDownLatch done = new CountDownLatch(1);
    services
        .indexer()
        .index(
            member,
            member.index(),
            member.index().resumeOffset(),
            new IndexListener() {
              @Override
              public void progressed(IndexProgress progress) {}

              @Override
              public void finished(OperationOutcome outcome, IOException failure) {
                done.countDown();
              }
            });
    assertTrue(done.await(20, TimeUnit.SECONDS));
  }

  private void write(String name, String text, int hoursAgo) throws IOException {
    Path file = dir.resolve(name);
    Files.writeString(file, text, StandardCharsets.US_ASCII);
    Files.setLastModifiedTime(
        file, FileTime.fromMillis(System.currentTimeMillis() - hoursAgo * HOUR_MILLIS));
  }

  private static String rows(String name, int count) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < count; i++) {
      text.append(i).append(',').append(name).append(" mock row\n");
    }
    return text.toString();
  }
}

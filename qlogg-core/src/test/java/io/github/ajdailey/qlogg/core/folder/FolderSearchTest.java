package io.github.ajdailey.qlogg.core.folder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchLimit;
import io.github.ajdailey.qlogg.core.search.SearchQuery;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import io.github.ajdailey.qlogg.core.testkit.SyntheticLog;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Folder walk, name and exclude patterns, streaming of a file larger than the budget, the results
 * share stop and the retry after the listener frees results, cancel during a runaway regex, and
 * files and folders that cannot be searched.
 */
class FolderSearchTest {
  private static final Duration BOUND = Duration.ofSeconds(60);

  /** The cancel bound of a running search (design S5: a catastrophic regex stops within 1 s). */
  private static final long CANCEL_MILLIS = 1000;

  @TempDir Path dir;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;
  private MemoryBudget budget;
  private DocumentServices services;

  private void start(MemoryBudget limit) {
    budget = limit;
    workers =
        new Workers(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(workers.cpuThreads(), budget)),
            new SearchEngine(
                workers,
                StandardMatchers.INSTANCE,
                SearchSettings.defaults(workers.cpuThreads(), budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.ISO_8859_1);
  }

  @AfterEach
  void close() {
    if (workers != null) {
      assertTrue(workers.close(Workers.CLOSE_BOUND));
    }
    assertTrue(failures.isEmpty(), failures.toString());
  }

  @Test
  void namePatternsMatchOneNameElement() {
    NamePattern logs = NamePattern.of(NamePattern.DEFAULT);
    assertTrue(logs.matches(Path.of("app.log")));
    assertTrue(logs.matches(Path.of("app.log.1")));
    assertTrue(logs.matches(Path.of("app.log.2.gz")));
    assertFalse(logs.matches(Path.of("app.txt")));
    NamePattern two = NamePattern.of(" *.txt ; b.* ;");
    assertTrue(two.matches(Path.of("notes.txt")));
    assertTrue(two.matches(Path.of("b.log")));
    assertFalse(two.matches(Path.of("a.log")));
    assertTrue(NamePattern.of(" ").matches(Path.of("anything")));
  }

  @Test
  void walksMatchingNamesInPathOrderAndListsTheMatchingLines() throws Exception {
    start(MemoryBudget.of(1L << 30));
    write("a.log", "one flagword\ntwo\nflagword three\r\n");
    write("a.log.1", "flagword\n");
    Files.write(dir.resolve("a.log.2.gz"), new byte[] {0x1f, (byte) 0x8b, 8, 0, 0, 0, 'f'});
    Files.write(dir.resolve("c.log"), "﻿no\nUTF-16 flagword\n".getBytes(StandardCharsets.UTF_16LE));
    write("notes.txt", "flagword\n");
    Files.createDirectory(dir.resolve("sub"));
    write("sub/b.log", "x flagword\n");
    Path real = dir.toRealPath();

    FolderResults results = new FolderResults(budget);
    Recording flat = run(NamePattern.DEFAULT, false, literal("flagword"), results);
    assertEquals(OperationOutcome.COMPLETED, flat.outcome);
    assertEquals(SearchLimit.NONE, flat.limit);
    List<FileHits> files = results.files();
    assertEquals(4, files.size());
    assertEquals(real.resolve("a.log"), files.get(0).path());
    assertEquals(FileHits.Status.SEARCHED, files.get(0).status());
    assertEquals(2, files.get(0).matches());
    assertEquals(0, files.get(0).line(0));
    assertEquals("one flagword", files.get(0).text(0));
    assertEquals(2, files.get(0).line(1));
    assertEquals("flagword three", files.get(0).text(1));
    assertTrue(files.get(0).complete());
    assertEquals(real.resolve("a.log.1"), files.get(1).path());
    assertEquals(1, files.get(1).size());
    assertEquals(real.resolve("a.log.2.gz"), files.get(2).path());
    assertEquals(FileHits.Status.NOT_TEXT, files.get(2).status());
    assertEquals(FileHits.Status.SEARCHED, files.get(3).status());
    assertEquals("UTF-16 flagword", files.get(3).text(0));
    assertEquals(1, files.get(3).line(0));
    assertEquals(4, results.matches());
    FolderProgress last = flat.last;
    assertEquals(4, last.filesDone());
    assertEquals(4, last.filesTotal());
    assertEquals(100, last.percent());
    assertNull(last.file());
    assertEquals(results.retainedBytes(), budget.reserved(MemoryShare.RESULTS));
    results.close();
    assertReleased();

    results = new FolderResults(budget);
    run(NamePattern.DEFAULT, true, literal("flagword"), results);
    assertEquals(5, results.fileCount());
    assertEquals(real.resolve("sub").resolve("b.log"), results.file(4).path());
    results.close();

    results = new FolderResults(budget);
    run("*.txt;b.*", true, literal("flagword"), results);
    assertEquals(2, results.fileCount());
    assertEquals(real.resolve("notes.txt"), results.file(0).path());
    assertEquals(real.resolve("sub").resolve("b.log"), results.file(1).path());
    results.close();
    assertReleased();
  }

  @Test
  void aFileLargerThanTheWholeBudgetIsSearchedFromDisk() throws Exception {
    start(MemoryBudget.of(32L << 20));
    Path large = dir.resolve("large.log");
    SyntheticLog.write(large, 64L << 20, 5, "\n");
    SyntheticLog.write(dir.resolve("small.log"), 200_000, 6, "\r\n");
    SyntheticLog.write(dir.resolve("small.log.1"), 300_000, 7, "\n");
    FolderResults results = new FolderResults(budget);
    Recording recording = run(NamePattern.DEFAULT, false, literal("#4242"), results);
    assertEquals(OperationOutcome.COMPLETED, recording.outcome);
    assertFalse(results.limited());
    List<FileHits> files = results.files();
    assertEquals(3, files.size());
    long total = 0;
    for (FileHits file : files) {
      assertEquals(FileHits.Status.SEARCHED, file.status());
      assertTrue(file.complete());
      assertEquals(reference(file.path(), "#4242"), file.matches(), file.path().toString());
      assertEquals(file.matches(), file.size());
      for (int i = 0; i < file.size(); i++) {
        assertTrue(file.text(i).contains("#4242"), file.text(i));
      }
      total += file.matches();
    }
    assertTrue(files.get(0).matches() > 0);
    assertEquals(total, results.matches());
    results.close();
    assertReleased();
  }

  @Test
  void theResultsShareStopsTheSearchAndKeepsTheLinesListed() throws Exception {
    start(MemoryBudget.of(4L << 20));
    SyntheticLog.write(dir.resolve("a.log"), 4L << 20, 8, "\n");
    write("z.log", "BLOCKTERM\n");
    FolderResults results = new FolderResults(budget);
    Recording recording = run(NamePattern.DEFAULT, false, literal("BLOCKTERM"), results);
    assertEquals(OperationOutcome.COMPLETED, recording.outcome);
    assertEquals(SearchLimit.MEMORY, recording.limit);
    assertTrue(results.limited());
    assertEquals(1, results.fileCount());
    FileHits file = results.file(0);
    assertFalse(file.complete());
    assertTrue(file.size() > 0);
    assertTrue(file.size() < file.matches());
    assertTrue(results.retainedBytes() <= budget.limit(MemoryShare.RESULTS));
    results.close();
    assertReleased();
  }

  @Test
  void excludeLeavesOutNamesTheIncludeGlobsTake() throws Exception {
    start(MemoryBudget.of(1L << 30));
    write("a.log", "flagword\n");
    write("a.log.1", "flagword\n");
    write("a.log.2.gz", "flagword\n");
    write("debug.log", "flagword\n");
    assertFalse(NamePattern.of(" ; ").excludes(Path.of("a.log")));
    assertTrue(NamePattern.of("*.gz").excludes(Path.of("a.log.2.gz")));
    FolderResults results = new FolderResults(budget);
    run(
        dir.toString(),
        NamePattern.DEFAULT,
        " *.gz ; debug.* ",
        false,
        literal("flagword"),
        results,
        new Recording());
    List<FileHits> files = results.files();
    assertEquals(2, files.size());
    assertEquals(dir.toRealPath().resolve("a.log"), files.get(0).path());
    assertEquals(dir.toRealPath().resolve("a.log.1"), files.get(1).path());
    results.close();
    assertReleased();
  }

  @Test
  void aRefusedReservationAsksTheListenerToFreeResultsAndRetries() throws Exception {
    start(MemoryBudget.of(4L << 20));
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      text.append("BLOCKTERM line ").append(i).append('\n');
    }
    write("a.log", text.toString());
    final long held = budget.available(MemoryShare.RESULTS) - 1024;
    assertTrue(budget.reserve(MemoryShare.RESULTS, held));
    final AtomicInteger refusals = new AtomicInteger();
    Recording recording =
        new Recording() {
          @Override
          public boolean resultsRefused() {
            if (refusals.getAndIncrement() > 0) {
              return false;
            }
            budget.release(MemoryShare.RESULTS, held);
            return true;
          }
        };
    FolderResults results = new FolderResults(budget);
    run(dir.toString(), NamePattern.DEFAULT, "", false, literal("BLOCKTERM"), results, recording);
    assertEquals(OperationOutcome.COMPLETED, recording.outcome);
    assertEquals(SearchLimit.NONE, recording.limit);
    assertEquals(1, refusals.get());
    assertFalse(results.limited());
    assertEquals(2000, results.file(0).size());
    assertTrue(results.file(0).complete());
    results.close();
    assertReleased();
  }

  /** The cancel lands while runaway.log is the current file, so no entry is listed. */
  @Test
  void cancelStopsARunawayRegexWithinTheBound() throws Exception {
    start(MemoryBudget.of(1L << 30));
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      text.append("x".repeat(60)).append('\n');
    }
    write("runaway.log", text.toString());
    write("second.log", "y\n");
    FolderResults results = new FolderResults(budget);
    final Recording recording = new Recording();
    OperationHandle handle =
        new FolderSearch(services)
            .start(
                new FolderQuery(
                    dir.toString(),
                    NamePattern.of(NamePattern.DEFAULT),
                    false,
                    SearchQuery.of("(.*x){25}y", MatchMode.REGEX),
                    NamePattern.of("")),
                results,
                recording);
    // Indexed and searching: the file percent averages the index and search percents.
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                FolderProgress progress = recording.last;
                return progress != null
                    && progress.file() != null
                    && progress.file().endsWith("runaway.log")
                    && progress.filePercent() >= 50;
              }
            },
            BOUND),
        "runaway.log never reported searching");
    long canceled = System.nanoTime();
    handle.cancel();
    assertTrue(handle.await(CANCEL_MILLIS));
    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - canceled);
    assertTrue(millis < CANCEL_MILLIS, millis + " ms");
    assertEquals(OperationOutcome.CANCELED, recording.outcome);
    assertEquals(0, results.fileCount(), results.files().toString());
    assertReleased();
    results.close();
  }

  @Test
  void unreadableFilesAndFoldersAreListedAndTheRestSearched() throws Exception {
    assumeTrue(File.separatorChar == '/', "POSIX permissions and links");
    assumeTrue(!"root".equals(System.getProperty("user.name")), "root reads every file");
    start(MemoryBudget.of(1L << 30));
    write("a.log", "flagword\n");
    Path locked = write("locked.log", "flagword\n");
    Path closed = Files.createDirectory(dir.resolve("closed"));
    write("closed/inner.log", "flagword\n");
    Path target = Files.createDirectory(dir.resolve("target"));
    write("target/t.log", "flagword\n");
    Files.createSymbolicLink(dir.resolve("linked"), target);
    Files.createSymbolicLink(dir.resolve("link.log"), dir.resolve("a.log"));
    Files.createSymbolicLink(dir.resolve("gone.log"), dir.resolve("missing"));
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("-w-------"));
    Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("---------"));
    FolderResults results = new FolderResults(budget);
    try {
      run(NamePattern.DEFAULT, true, literal("flagword"), results);
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-------"));
      Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwx------"));
    }
    Path real = dir.toRealPath();
    List<String> searched = new ArrayList<String>();
    List<String> unreadable = new ArrayList<String>();
    for (FileHits file : results.files()) {
      String name = real.relativize(file.path()).toString().replace(File.separatorChar, '/');
      if (file.status() == FileHits.Status.SEARCHED) {
        assertEquals(1, file.matches(), name);
        searched.add(name);
      } else {
        assertEquals(FileHits.Status.UNREADABLE, file.status(), name);
        unreadable.add(name);
      }
    }
    assertEquals(List.of("a.log", "link.log", "target/t.log"), searched);
    assertTrue(
        unreadable.containsAll(List.of("closed", "gone.log", "locked.log")), unreadable.toString());
    assertEquals(3, unreadable.size(), unreadable.toString());
    results.close();
    assertReleased();
  }

  @Test
  void aLinkToAnAncestorFolderIsWalkedOnce() throws Exception {
    assumeTrue(File.separatorChar == '/', "symbolic links without a privilege");
    start(MemoryBudget.of(1L << 30));
    write("a.log", "flagword\n");
    Path sub = Files.createDirectory(dir.resolve("sub"));
    write("sub/b.log", "flagword\n");
    Files.createSymbolicLink(sub.resolve("up"), dir);
    FolderResults results = new FolderResults(budget);
    Recording recording = run(NamePattern.DEFAULT, true, literal("flagword"), results);
    assertEquals(OperationOutcome.COMPLETED, recording.outcome);
    Path real = dir.toRealPath();
    List<String> searched = new ArrayList<String>();
    for (FileHits file : results.files()) {
      searched.add(real.relativize(file.path()).toString().replace(File.separatorChar, '/'));
    }
    assertEquals(List.of("a.log", "sub/b.log"), searched);
    results.close();
    assertReleased();
  }

  @Test
  void aFolderThatDoesNotValidateOrABadRegexFailsTheSearch() throws Exception {
    start(MemoryBudget.of(1L << 30));
    Path file = write("a.log", "flagword\n");
    for (String folder : List.of(dir.resolve("missing").toString(), file.toString())) {
      FolderResults results = new FolderResults(budget);
      Recording recording = run(folder, NamePattern.DEFAULT, false, literal("x"), results);
      assertEquals(OperationOutcome.FAILED, recording.outcome, folder);
      assertInstanceOf(IOException.class, recording.failure);
      results.close();
    }
    FolderResults results = new FolderResults(budget);
    Recording recording =
        run(NamePattern.DEFAULT, false, SearchQuery.of("(", MatchMode.REGEX), results);
    assertEquals(OperationOutcome.FAILED, recording.outcome);
    assertInstanceOf(PatternSyntaxException.class, recording.failure);
    results.close();
    assertReleased();
  }

  private Recording run(String names, boolean recursive, SearchQuery query, FolderResults results)
      throws InterruptedException {
    return run(dir.toString(), names, recursive, query, results);
  }

  private Recording run(
      String folder, String names, boolean recursive, SearchQuery query, FolderResults results)
      throws InterruptedException {
    return run(folder, names, "", recursive, query, results, new Recording());
  }

  private Recording run(
      String folder,
      String names,
      String exclude,
      boolean recursive,
      SearchQuery query,
      FolderResults results,
      Recording recording)
      throws InterruptedException {
    OperationHandle handle =
        new FolderSearch(services)
            .start(
                new FolderQuery(
                    folder, NamePattern.of(names), recursive, query, NamePattern.of(exclude)),
                results,
                recording);
    assertTrue(handle.await(BOUND.toMillis()));
    assertEquals(0, recording.finished.getCount());
    return recording;
  }

  private void assertReleased() {
    assertEquals(0, budget.reserved(MemoryShare.RESULTS));
    assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX));
  }

  private Path write(String name, String text) throws IOException {
    return Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
  }

  private static SearchQuery literal(String text) {
    return SearchQuery.of(text, MatchMode.LITERAL);
  }

  private static long reference(Path file, String text) throws IOException {
    long count = 0;
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
      String line = reader.readLine();
      while (line != null) {
        if (line.contains(text)) {
          count++;
        }
        line = reader.readLine();
      }
    }
    return count;
  }

  /** Records a folder search's events. */
  private static class Recording implements FolderListener {
    final CountDownLatch finished = new CountDownLatch(1);
    volatile FolderProgress last;
    volatile OperationOutcome outcome;
    volatile SearchLimit limit;
    volatile Exception failure;

    @Override
    public void progressed(FolderProgress progress) {
      last = progress;
    }

    @Override
    public void finished(OperationOutcome result, SearchLimit stop, Exception cause) {
      outcome = result;
      limit = stop;
      failure = cause;
      finished.countDown();
    }
  }
}

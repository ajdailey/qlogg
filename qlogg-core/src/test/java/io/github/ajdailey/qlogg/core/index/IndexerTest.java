package io.github.ajdailey.qlogg.core.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationHandle;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.io.FileIdentity;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import io.github.ajdailey.qlogg.core.testkit.SyntheticLog;
import io.github.ajdailey.qlogg.core.text.EncodingDetector;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IndexerTest {
  private static final long WAIT_MILLIS = 30_000;
  private static final MemoryBudget LARGE = MemoryBudget.of(1L << 30);
  private static final byte[] UTF16LE_BOM = {(byte) 0xff, (byte) 0xfe};
  private static final byte[] UTF16BE_BOM = {(byte) 0xfe, (byte) 0xff};

  @TempDir Path dir;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;
  private int files;

  @BeforeEach
  void startWorkers() {
    workers =
        new Workers(
            3,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
  }

  @AfterEach
  void closeWorkers() {
    assertTrue(workers.close(Duration.ofSeconds(10)));
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  @ParameterizedTest
  @ValueSource(ints = {8, 16, 24, 64, 4096})
  void lineFeedsAtEveryBlockSize(int blockBytes) throws Exception {
    check(ascii(SyntheticLog.text(200, 1, "\n")), blockBytes, LARGE, false);
  }

  @ParameterizedTest
  @ValueSource(ints = {8, 16, 64})
  void crlfSplitAcrossBlocks(int blockBytes) throws Exception {
    check(ascii(SyntheticLog.text(200, 2, "\r\n")), blockBytes, LARGE, false);
  }

  @Test
  void loneCarriageReturnStaysInTheLine() throws Exception {
    check(ascii("one\rstill one\ntwo\r\n\rthree\n"), 8, LARGE, false);
  }

  @Test
  void noFinalNewline() throws Exception {
    check(ascii("first\nsecond\nthird without newline"), 8, LARGE, false);
  }

  @Test
  void emptyFileHasNoLines() throws Exception {
    check(new byte[0], 8, LARGE, false);
  }

  @Test
  void onlyNewlines() throws Exception {
    check(ascii("\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n"), 8, LARGE, false);
  }

  @Test
  void malformedUtf8IsIndexedByBytes() throws Exception {
    byte[] content = {'a', (byte) 0xc3, '\n', (byte) 0xff, (byte) 0xfe, 'b', '\n', (byte) 0x80};
    check(content, 8, LARGE, false);
  }

  @ParameterizedTest
  @ValueSource(ints = {8, 16, 64})
  void utf16LittleEndianWithAndWithoutBom(int blockBytes) throws Exception {
    String text = SyntheticLog.text(100, 3, "\r\n") + "\u0a0a\u0d0a last";
    byte[] plain = text.getBytes(StandardCharsets.UTF_16LE);
    check(plain, blockBytes, LARGE, false);
    check(concat(UTF16LE_BOM, plain), blockBytes, LARGE, false);
  }

  @ParameterizedTest
  @ValueSource(ints = {8, 16, 64})
  void utf16BigEndianWithAndWithoutBom(int blockBytes) throws Exception {
    String text = SyntheticLog.text(100, 4, "\n") + "\u0a0a\u0d0a last";
    byte[] plain = text.getBytes(StandardCharsets.UTF_16BE);
    check(plain, blockBytes, LARGE, false);
    check(concat(UTF16BE_BOM, plain), blockBytes, LARGE, false);
  }

  @Test
  void trailingOddByteOfUtf16WaitsForGrowth() throws Exception {
    byte[] content = concat("ab\ncd".getBytes(StandardCharsets.UTF_16LE), new byte[] {'e'});
    check(content, 8, LARGE, false);
  }

  @Test
  void utf32UsesFourByteUnits() throws Exception {
    Charset utf32 = Charset.forName("UTF-32LE");
    byte[] content = (SyntheticLog.text(20, 5, "\n") + "\u0a0a end").getBytes(utf32);
    Path file = write(content);
    try (FileByteSource source = FileByteSource.open(file)) {
      TextEncoding encoding = TextEncoding.of(utf32, 0);
      assertEquals(4, encoding.unitWidth());
      try (GrowableLineIndex index =
          index(source, encoding, 16, LARGE, OperationOutcome.COMPLETED)) {
        new ReferenceIndex(content, encoding).assertMatches(index.snapshot());
      }
    }
  }

  @Test
  void sixtyFourMibLine() throws Exception {
    byte[] content = new byte[64 * 1024 * 1024 + 6];
    Arrays.fill(content, (byte) 'x');
    content[64 * 1024 * 1024] = '\n';
    content[content.length - 3] = '\n';
    content[content.length - 1] = '\n';
    Path file = write(content);
    try (FileByteSource source = FileByteSource.open(file)) {
      TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_8, 0);
      try (GrowableLineIndex index =
          index(
              source,
              encoding,
              IndexSettings.DEFAULT_BLOCK_BYTES,
              LARGE,
              OperationOutcome.COMPLETED)) {
        LineIndex snapshot = index.snapshot();
        assertEquals(3, snapshot.lineCount());
        assertEquals(64 * 1024 * 1024, snapshot.maxLineBytes());
        assertEquals(64 * 1024 * 1024 + 1, snapshot.lineStart(1));
        assertEquals(1, snapshot.lineAt(64 * 1024 * 1024 + 2));
        assertEquals(0, snapshot.lineAt(64 * 1024 * 1024));
      }
    }
  }

  @Test
  void refusedReservationConvertsToSparse() throws Exception {
    MemoryBudget budget = MemoryBudget.of(150_000);
    check(ascii(SyntheticLog.text(1300, 6, "\r\n")), 64, budget, true);
    assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX));
  }

  @Test
  void sparseCheckpointsEveryMibForLongLines() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 150; i++) {
      text.append("x".repeat(30_000 + i)).append('\n');
    }
    check(ascii(text.toString()), 4096, MemoryBudget.of(150_000), true);
  }

  /**
   * Regression: a sparse lookup of an offset inside a long line read to the end of that line. The
   * bytes read are bounded by the distance from the line's start plus two cache blocks.
   */
  @Test
  void sparseLineAtInsideALongLineReadsOnlyUpToTheOffset() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 5000; i++) {
      text.append("short line ").append(i).append('\n');
    }
    byte[] head = ascii(text.toString());
    int longBytes = 16 << 20;
    byte[] content = new byte[head.length + longBytes + 6];
    System.arraycopy(head, 0, content, 0, head.length);
    Arrays.fill(content, head.length, head.length + longBytes, (byte) 'z');
    System.arraycopy(ascii("\ntail\n"), 0, content, head.length + longBytes, 6);
    Path file = write(content);
    try (FileByteSource file0 = FileByteSource.open(file)) {
      CountingSource source = new CountingSource(file0);
      TextEncoding encoding = TextEncoding.of(StandardCharsets.US_ASCII, 0);
      try (GrowableLineIndex index =
          index(source, encoding, 65536, MemoryBudget.of(150_000), OperationOutcome.COMPLETED)) {
        LineIndex snapshot = index.snapshot();
        assertTrue(snapshot.sparse());
        long start = snapshot.lineStart(5000);
        long distance = 2L << 20;
        source.bytes.set(0);
        assertEquals(5000, snapshot.lineAt(start + distance));
        assertTrue(source.bytes.get() <= distance + 2 * 4096, "read " + source.bytes.get());
        assertEquals(5001, snapshot.lineAt(start + longBytes + 1));
        assertEquals(5000, snapshot.lineAt(start + longBytes));
      }
    }
  }

  @Test
  void sparseUtf16LineAtStopsOnAUnitBoundary() throws Exception {
    byte[] text = SyntheticLog.text(1300, 6, "\r\n").getBytes(StandardCharsets.UTF_16LE);
    check(concat(UTF16LE_BOM, text), 64, MemoryBudget.of(150_000), true);
  }

  @Test
  void indexThatFitsNoModeFails() throws Exception {
    MemoryBudget budget = MemoryBudget.of(3000);
    Path file = write(ascii(SyntheticLog.text(300, 7, "\n")));
    try (FileByteSource source = FileByteSource.open(file)) {
      TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_8, 0);
      RecordingListener listener = new RecordingListener();
      try (GrowableLineIndex index = new GrowableLineIndex(encoding, budget, source, cache())) {
        OperationHandle handle =
            new Indexer(workers, new IndexSettings(64, 2, budget))
                .index(source, index, index.resumeOffset(), listener);
        assertTrue(handle.await(WAIT_MILLIS));
        assertEquals(OperationOutcome.FAILED, listener.outcome);
        assertInstanceOf(IndexCapacityException.class, listener.failure);
      }
      assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX));
    }
    assertThrows(
        IndexCapacityException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            new GrowableLineIndex(
                    TextEncoding.of(StandardCharsets.UTF_8, 0), MemoryBudget.of(0), null, null)
                .close();
          }
        });
  }

  @Test
  void growthResumesAtThePartialLine() throws Exception {
    Path file = dir.resolve("growing.log");
    Files.write(file, ascii("one\ntwo\nthr"));
    try (FileByteSource source = FileByteSource.open(file)) {
      TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_8, 0);
      try (GrowableLineIndex index = new GrowableLineIndex(encoding, LARGE, source, cache())) {
        Indexer indexer = new Indexer(workers, new IndexSettings(8, 2, LARGE));
        run(indexer, source, index, OperationOutcome.COMPLETED);
        assertEquals(3, index.snapshot().lineCount());
        assertEquals(8, index.resumeOffset());
        long first = index.snapshot().generation();
        Files.write(file, ascii("ee\nfour\n"), StandardOpenOption.APPEND);
        run(indexer, source, index, OperationOutcome.COMPLETED);
        LineIndex grown = index.snapshot();
        assertTrue(grown.generation() > first);
        new ReferenceIndex(Files.readAllBytes(file), encoding).assertMatches(grown);
      }
    }
  }

  @Test
  void progressReportsPublishedSnapshots() throws Exception {
    Path file = write(ascii(SyntheticLog.text(2000, 8, "\n")));
    try (FileByteSource source = FileByteSource.open(file)) {
      TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_8, 0);
      try (GrowableLineIndex index = new GrowableLineIndex(encoding, LARGE, source, cache())) {
        RecordingListener listener =
            run(
                new Indexer(workers, new IndexSettings(64, 4, LARGE)),
                source,
                index,
                OperationOutcome.COMPLETED);
        IndexProgress last = listener.progress.get(listener.progress.size() - 1);
        assertEquals(Files.size(file), last.indexedBytes());
        assertEquals(Files.size(file), last.totalBytes());
        assertEquals(2000, last.lineCount());
        assertEquals(1, listener.finishedCalls);
        assertNull(listener.failure);
      }
    }
  }

  @Test
  void cancelStopsWithinOneBlock() throws Exception {
    Path file = write(ascii(SyntheticLog.text(2000, 9, "\n")));
    try (FileByteSource file0 = FileByteSource.open(file)) {
      GatedSource source = new GatedSource(file0, 5);
      TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_8, 0);
      try (GrowableLineIndex index = new GrowableLineIndex(encoding, LARGE, source, cache())) {
        RecordingListener listener = new RecordingListener();
        OperationHandle handle =
            new Indexer(workers, new IndexSettings(64, 2, LARGE))
                .index(source, index, index.resumeOffset(), listener);
        assertTrue(source.reached.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        handle.cancel();
        int readsAtCancel = source.reads.get();
        source.open.countDown();
        assertTrue(handle.await(WAIT_MILLIS));
        assertEquals(OperationOutcome.CANCELED, listener.outcome);
        assertEquals(readsAtCancel, source.reads.get());
        LineIndex snapshot = index.snapshot();
        assertTrue(snapshot.indexedBytes() <= 64L * readsAtCancel);
        assertTrue(snapshot.indexedBytes() < Files.size(file));
      }
    }
  }

  /**
   * Regression: a scan still running when the end-of-operation wait gave up kept its buffer, so an
   * indexer with two buffers waited forever on its next operation. Every CPU thread is held past
   * the 5 s bound, so both scans start only after the operation ends.
   */
  @Test
  void aScanOutlivingTheDrainBoundReturnsItsBuffer() throws Exception {
    Path file = write(ascii(SyntheticLog.text(500, 12, "\n")));
    final CountDownLatch release = new CountDownLatch(1);
    Runnable blocker =
        new Runnable() {
          @Override
          public void run() {
            try {
              release.await(WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        };
    try (FileByteSource file0 = FileByteSource.open(file)) {
      final GatedSource source = new GatedSource(file0, Integer.MAX_VALUE);
      TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_8, 0);
      Indexer indexer = new Indexer(workers, new IndexSettings(64, 2, LARGE));
      try (GrowableLineIndex index = new GrowableLineIndex(encoding, LARGE, source, cache())) {
        for (int i = 0; i < workers.cpuThreads(); i++) {
          workers.executeCpu(blocker);
        }
        RecordingListener first = new RecordingListener();
        OperationHandle handle = indexer.index(source, index, index.resumeOffset(), first);
        // Both buffers are read and their slots submitted before the coordinator checks again.
        assertTrue(
            Poll.until(
                new BooleanSupplier() {
                  @Override
                  public boolean getAsBoolean() {
                    return source.reads.get() >= 2;
                  }
                },
                Duration.ofMillis(WAIT_MILLIS)));
        handle.cancel();
        try {
          assertTrue(handle.await(WAIT_MILLIS));
        } finally {
          release.countDown();
        }
        assertEquals(OperationOutcome.CANCELED, first.outcome);
      }
      try (GrowableLineIndex index = new GrowableLineIndex(encoding, LARGE, source, cache())) {
        RecordingListener second = new RecordingListener();
        assertTrue(indexer.index(file0, index, 0, second).await(WAIT_MILLIS));
        assertEquals(OperationOutcome.COMPLETED, second.outcome);
        assertEquals(500, index.snapshot().lineCount());
      }
    }
  }

  @Test
  void closedSourceEndsAsCanceled() throws Exception {
    Path file = write(ascii(SyntheticLog.text(500, 10, "\n")));
    try (FileByteSource file0 = FileByteSource.open(file)) {
      GatedSource source = new GatedSource(file0, 2);
      TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_8, 0);
      try (GrowableLineIndex index = new GrowableLineIndex(encoding, LARGE, source, cache())) {
        RecordingListener listener = new RecordingListener();
        OperationHandle handle =
            new Indexer(workers, new IndexSettings(64, 2, LARGE))
                .index(source, index, index.resumeOffset(), listener);
        assertTrue(source.reached.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        file0.close();
        source.open.countDown();
        assertTrue(handle.await(WAIT_MILLIS));
        assertEquals(OperationOutcome.CANCELED, listener.outcome);
      }
    }
    Files.delete(file);
  }

  @Test
  void oneWriterAtATime() throws Exception {
    Path file = write(ascii(SyntheticLog.text(100, 11, "\n")));
    try (FileByteSource file0 = FileByteSource.open(file)) {
      final GatedSource source = new GatedSource(file0, 1);
      TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_8, 0);
      try (GrowableLineIndex index = new GrowableLineIndex(encoding, LARGE, source, cache())) {
        final Indexer indexer = new Indexer(workers, new IndexSettings(64, 2, LARGE));
        OperationHandle handle =
            indexer.index(source, index, index.resumeOffset(), new RecordingListener());
        assertTrue(source.reached.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
        final GrowableLineIndex target = index;
        assertThrows(
            IllegalStateException.class,
            new Executable() {
              @Override
              public void execute() {
                indexer.index(source, target, 0, new RecordingListener());
              }
            });
        source.open.countDown();
        assertTrue(handle.await(WAIT_MILLIS));
      }
    }
  }

  private void check(byte[] content, int blockBytes, MemoryBudget budget, boolean expectSparse)
      throws Exception {
    Path file = write(content);
    try (FileByteSource source = FileByteSource.open(file)) {
      ByteBuffer head = ByteBuffer.allocate((int) Math.min(content.length, 65536));
      source.read(0, head);
      head.flip();
      TextEncoding encoding = EncodingDetector.detect(head, StandardCharsets.ISO_8859_1).encoding();
      try (GrowableLineIndex index =
          index(source, encoding, blockBytes, budget, OperationOutcome.COMPLETED)) {
        LineIndex snapshot = index.snapshot();
        assertEquals(expectSparse, snapshot.sparse(), "sparse");
        new ReferenceIndex(content, encoding).assertMatches(snapshot);
      }
    }
    Files.delete(file);
  }

  private GrowableLineIndex index(
      ByteSource source,
      TextEncoding encoding,
      int blockBytes,
      MemoryBudget budget,
      OperationOutcome expected)
      throws Exception {
    GrowableLineIndex index = new GrowableLineIndex(encoding, budget, source, cache());
    run(new Indexer(workers, new IndexSettings(blockBytes, 3, budget)), source, index, expected);
    return index;
  }

  private RecordingListener run(
      Indexer indexer, ByteSource source, GrowableLineIndex index, OperationOutcome expected)
      throws InterruptedException {
    RecordingListener listener = new RecordingListener();
    OperationHandle handle = indexer.index(source, index, index.resumeOffset(), listener);
    assertTrue(handle.await(WAIT_MILLIS));
    assertTrue(handle.isDone());
    assertEquals(expected, listener.outcome, String.valueOf(listener.failure));
    return listener;
  }

  private static BlockCache cache() {
    return new BlockCache(4096, 1 << 20);
  }

  private Path write(byte[] content) throws IOException {
    Path file = dir.resolve("test-" + files++ + ".log");
    Files.write(file, content);
    return file;
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] concat(byte[] first, byte[] second) {
    byte[] both = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, both, first.length, second.length);
    return both;
  }

  /** Delegates reads and counts the bytes read. */
  private static final class CountingSource implements ByteSource {
    final AtomicLong bytes = new AtomicLong();
    private final ByteSource delegate;

    CountingSource(ByteSource delegate) {
      this.delegate = delegate;
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
      int read = delegate.read(position, dst);
      if (read > 0) {
        bytes.addAndGet(read);
      }
      return read;
    }

    @Override
    public FileIdentity identity() {
      return delegate.identity();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }

  /** Delegates reads, blocking the read numbered {@code gateAt} until {@link #open} counts down. */
  private static final class GatedSource implements ByteSource {
    final CountDownLatch reached = new CountDownLatch(1);
    final CountDownLatch open = new CountDownLatch(1);
    final AtomicInteger reads = new AtomicInteger();
    private final ByteSource delegate;
    private final int gateAt;

    GatedSource(ByteSource delegate, int gateAt) {
      this.delegate = delegate;
      this.gateAt = gateAt;
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
      if (reads.incrementAndGet() == gateAt) {
        reached.countDown();
        try {
          if (!open.await(WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw new IOException("gate timed out");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException(e);
        }
      }
      return delegate.read(position, dst);
    }

    @Override
    public FileIdentity identity() {
      return delegate.identity();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }

  @Test
  void handleIsNotDoneBeforeFinished() throws Exception {
    Path file = write(ascii("a\nb\n"));
    try (FileByteSource source = FileByteSource.open(file)) {
      TextEncoding encoding = TextEncoding.of(StandardCharsets.UTF_8, 0);
      try (GrowableLineIndex index = new GrowableLineIndex(encoding, LARGE, source, cache())) {
        final OperationHandle[] handle = new OperationHandle[1];
        final boolean[] doneInFinished = new boolean[1];
        final CountDownLatch assigned = new CountDownLatch(1);
        IndexListener listener =
            new IndexListener() {
              @Override
              public void progressed(IndexProgress progress) {}

              @Override
              public void finished(OperationOutcome outcome, IOException failure) {
                try {
                  if (assigned.await(WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    doneInFinished[0] = handle[0].isDone();
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            };
        handle[0] =
            new Indexer(workers, new IndexSettings(8, 2, LARGE))
                .index(source, index, index.resumeOffset(), listener);
        assigned.countDown();
        assertTrue(handle[0].await(WAIT_MILLIS));
        assertFalse(doneInFinished[0]);
      }
    }
  }
}

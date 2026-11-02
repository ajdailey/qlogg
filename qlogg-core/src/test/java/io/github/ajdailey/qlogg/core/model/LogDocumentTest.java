package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.text.DetectionReason;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class LogDocumentTest {
  private static final long TWO_GIB = 1L << 31;

  @TempDir Path dir;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;
  private MemoryBudget budget;
  private DocumentServices services;

  /** Records events and opens a latch when indexing ends and when the document closes. */
  private static final class Events implements DocumentListener {
    final CountDownLatch finished = new CountDownLatch(1);
    final CountDownLatch closed = new CountDownLatch(1);
    volatile OperationOutcome outcome;

    @Override
    public void indexProgressed(IndexProgress progress) {}

    @Override
    public void indexFinished(OperationOutcome outcome, IOException failure) {
      this.outcome = outcome;
      finished.countDown();
    }

    @Override
    public void fileChanged(FileChange change) {}

    @Override
    public void closed() {
      closed.countDown();
    }
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
    budget = MemoryBudget.of(1L << 30);
    services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(2, budget)),
            new SearchEngine(
                workers, StandardMatchers.INSTANCE, SearchSettings.defaults(2, budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.ISO_8859_1);
  }

  @AfterEach
  void closeServices() {
    assertTrue(workers.close(Workers.CLOSE_BOUND));
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private LogDocument indexed(Path file, Events events) throws Exception {
    LogDocument document = LogDocument.open(file, null, services);
    document.addListener(events);
    document.startIndexing();
    assertTrue(events.finished.await(30, TimeUnit.SECONDS));
    assertEquals(OperationOutcome.COMPLETED, events.outcome);
    return document;
  }

  private Path write(String name, byte[] content) throws IOException {
    Path file = dir.resolve(name);
    Files.write(file, content);
    return file;
  }

  @Test
  void readsLinesWithTabsExpandedAndCrDropped() throws Exception {
    Path file = write("tabs.log", "a\tb\r\nlone\rcr\n\tx\nlast".getBytes(StandardCharsets.UTF_8));
    Events events = new Events();
    try (LogDocument document = indexed(file, events)) {
      assertEquals(DetectionReason.UTF8_VALID, document.detection().reason());
      DocumentStatus status = document.status();
      assertEquals(4, status.lineCount());
      assertEquals(100, status.indexPercent());
      assertFalse(status.indexing());
      LineBlock block = document.newReader().read(document.index(), 0, 10, 0, 80);
      assertEquals(4, block.count());
      assertEquals("a       b", block.text(0));
      assertEquals(9, block.width(0));
      assertEquals("lone\rcr", block.text(1));
      assertEquals("        x", block.text(2));
      assertEquals("last", block.text(3));
      assertFalse(block.contains(4));
    }
  }

  @Test
  void cutsEachLineToTheColumnWindow() throws Exception {
    Path file = write("wide.log", "0123456789\n\tab\nxy\n".getBytes(StandardCharsets.US_ASCII));
    try (LogDocument document = indexed(file, new Events())) {
      LineBlock block = document.newReader().read(document.index(), 0, 3, 4, 5);
      assertEquals(4, block.firstColumn());
      assertEquals("45678", block.text(0));
      assertEquals("    a", block.text(1));
      assertEquals("", block.text(2));
      assertEquals(10, block.maxWidth());
    }
  }

  @Test
  void decodesUtf16ByBom() throws Exception {
    byte[] text = "one\r\ntwo\n".getBytes(StandardCharsets.UTF_16LE);
    byte[] content = new byte[text.length + 2];
    content[0] = (byte) 0xff;
    content[1] = (byte) 0xfe;
    System.arraycopy(text, 0, content, 2, text.length);
    Path file = write("utf16.log", content);
    try (LogDocument document = indexed(file, new Events())) {
      assertEquals(DetectionReason.BOM, document.detection().reason());
      LineBlock block = document.newReader().read(document.index(), 0, 2, 0, 80);
      assertEquals("one", block.text(0));
      assertEquals("two", block.text(1));
    }
  }

  @Test
  void appendTextKeepsTabsAndHonorsColumns() throws Exception {
    Path file = write("copy.log", "ab\tcd\nxyz\n".getBytes(StandardCharsets.US_ASCII));
    try (LogDocument document = indexed(file, new Events())) {
      LineReader reader = document.newReader();
      StringBuilder out = new StringBuilder();
      reader.appendText(document.index(), 0, 1, Long.MAX_VALUE, out);
      assertEquals("b\tcd", out.toString());
      out.setLength(0);
      reader.appendText(document.index(), 0, 0, 8, out);
      assertEquals("ab\t", out.toString());
      out.setLength(0);
      reader.appendText(document.index(), 1, 1, 2, out);
      assertEquals("y", out.toString());
    }
  }

  @Test
  void capsALongLineAtOneMebibyte() throws Exception {
    byte[] content = new byte[LineReader.MAX_LINE_BYTES + 100];
    Arrays.fill(content, (byte) 'z');
    content[content.length - 1] = '\n';
    Path file = write("long.log", content);
    try (LogDocument document = indexed(file, new Events())) {
      LineBlock block =
          document.newReader().read(document.index(), 0, 1, LineReader.MAX_LINE_BYTES - 2, 10);
      assertEquals("zz", block.text(0));
      assertEquals(LineReader.MAX_LINE_BYTES, block.width(0));
    }
  }

  @Test
  void readsLinesPastTwoGigabytes() throws Exception {
    Path file = dir.resolve("sparse.log");
    try (FileChannel channel =
        FileChannel.open(
            file,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            StandardOpenOption.SPARSE)) {
      channel.write(ByteBuffer.wrap(new byte[] {'\n'}), TWO_GIB - 1);
      channel.write(
          ByteBuffer.wrap("past two\nlast\n".getBytes(StandardCharsets.US_ASCII)), TWO_GIB);
    }
    try (LogDocument document = LogDocument.open(file, StandardCharsets.US_ASCII, services)) {
      Events events = new Events();
      document.addListener(events);
      document.startIndexing();
      assertTrue(events.finished.await(5, TimeUnit.MINUTES));
      LineIndex index = document.index();
      assertEquals(3, index.lineCount());
      LineBlock block = document.newReader().read(index, 1, 2, 0, 80);
      assertEquals("past two", block.text(1));
      assertEquals("last", block.text(2));
    }
    Files.delete(file);
  }

  @Test
  void closeReleasesTheIndexAndTheFile() throws Exception {
    Path file = write("close.log", "a\nb\n".getBytes(StandardCharsets.US_ASCII));
    Events events = new Events();
    final LogDocument document = indexed(file, events);
    assertTrue(budget.reserved(MemoryShare.LINE_INDEX) > 0);
    assertTrue(document.close(Workers.CLOSE_BOUND));
    assertTrue(document.close(Workers.CLOSE_BOUND));
    assertEquals(0, events.closed.getCount());
    assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX));
    assertTrue(document.status().closed());
    assertThrows(
        ClosedChannelException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            document.newReader().read(document.index(), 0, 1, 0, 10);
          }
        });
    Files.delete(file);
  }

  @Test
  void closeDuringIndexingCancelsIt() throws Exception {
    Path file = dir.resolve("big.log");
    byte[] line = "0123456789abcdef\n".getBytes(StandardCharsets.US_ASCII);
    try (FileChannel channel =
        FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      ByteBuffer block = ByteBuffer.allocate(line.length * 65536);
      while (block.remaining() >= line.length) {
        block.put(line);
      }
      for (int i = 0; i < 64; i++) {
        block.flip();
        channel.write(block);
      }
    }
    Events events = new Events();
    LogDocument document = LogDocument.open(file, null, services);
    document.addListener(events);
    document.startIndexing();
    assertTrue(document.close(Workers.CLOSE_BOUND));
    assertEquals(0, events.finished.getCount());
    assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX));
    Files.delete(file);
  }

  @Test
  void rejectsADirectory() {
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            LogDocument.open(dir, null, services).close();
          }
        });
  }
}

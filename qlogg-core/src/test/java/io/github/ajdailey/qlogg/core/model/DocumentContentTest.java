package io.github.ajdailey.qlogg.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.core.columns.ColumnFormat;
import io.github.ajdailey.qlogg.core.columns.ColumnSchema;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.IndexSettings;
import io.github.ajdailey.qlogg.core.index.Indexer;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.io.BlockCache;
import io.github.ajdailey.qlogg.core.io.ByteSource;
import io.github.ajdailey.qlogg.core.io.MemoryByteSource;
import io.github.ajdailey.qlogg.core.rotate.LineMap;
import io.github.ajdailey.qlogg.core.rotate.SetFiles;
import io.github.ajdailey.qlogg.core.search.LineSet;
import io.github.ajdailey.qlogg.core.search.SearchEngine;
import io.github.ajdailey.qlogg.core.search.SearchSettings;
import io.github.ajdailey.qlogg.core.search.StandardMatchers;
import io.github.ajdailey.qlogg.core.text.DetectionReason;
import io.github.ajdailey.qlogg.core.text.EncodingDetection;
import io.github.ajdailey.qlogg.core.text.TextEncoding;
import io.github.ajdailey.qlogg.core.time.BuiltInFormat;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import io.github.ajdailey.qlogg.core.watch.FileMonitor;
import io.github.ajdailey.qlogg.core.watch.MonitorSettings;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** {@link LogDocument} over a recording {@link DocumentContent}. */
class DocumentContentTest {
  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;
  private DocumentServices services;
  private Recording content;
  private LogDocument document;

  @BeforeEach
  void createDocument() {
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    MemoryBudget budget = MemoryBudget.of(1L << 20);
    services =
        new DocumentServices(
            workers,
            new Indexer(workers, IndexSettings.defaults(1, budget)),
            new SearchEngine(
                workers, StandardMatchers.INSTANCE, SearchSettings.defaults(1, budget)),
            new BlockCache(65536, 1 << 20),
            budget,
            StandardCharsets.UTF_8);
    content = new Recording();
    document = new LogDocument(content, services);
  }

  @AfterEach
  void closeWorkers() {
    assertTrue(workers.close(Workers.CLOSE_BOUND));
    assertTrue(failures.isEmpty(), failures.toString());
  }

  @Test
  void theDocumentAnswersFromItsContent() {
    assertEquals("recorded.log", document.name());
    assertEquals(Path.of("recorded.log").toAbsolutePath(), document.path());
    assertSame(content.source, document.bytes());
    assertSame(content.detection, document.detection());
    assertSame(content.detection.encoding(), document.encoding());
    assertEquals(BuiltInFormat.ISO, document.timeFormat());
    assertEquals(ColumnFormat.CLF, document.columns().format());
    assertEquals(2, document.head().remaining());
    assertNull(document.memberStarts(null));
    assertSame(content.map, document.lineMap());
    assertEquals(42, document.openedModifiedMillis());
    assertTrue(document.keepsFileClosed());
    assertFalse(document.inMemory());
    assertFalse(document.status().closed());
    assertTrue(document.enter());
    document.exit();
    assertEquals(List.of("enter", "exit"), content.calls);
  }

  @Test
  void contentEventsReachEveryListener() {
    Listening first = new Listening();
    Listening second = new Listening();
    document.addListener(first);
    document.addListener(second);
    document.startIndexing();
    document.startWatching(new FileMonitor(workers, new MonitorSettings(1000, false)));
    assertSame(content.indexEvents, content.watchEvents);
    content.indexEvents.indexProgressed(new IndexProgress(1, 2, 3));
    content.indexEvents.indexFinished(OperationOutcome.COMPLETED, null);
    content.watchEvents.fileChanged(FileChange.APPENDED);
    List<String> expected = List.of("progressed 1", "finished COMPLETED", "changed APPENDED");
    assertEquals(expected, first.events);
    assertEquals(expected, second.events);
  }

  @Test
  void closeClosesTheContentOnceAndThenNotifies() {
    Listening listening = new Listening();
    document.addListener(listening);
    document.marks().add(3);
    content.clean = false;
    assertFalse(document.close(Duration.ofMillis(10)));
    assertTrue(document.close(Duration.ofMillis(10)));
    assertEquals(List.of("close"), content.calls);
    assertEquals(List.of("closed"), listening.events);
    assertTrue(document.status().closed());
    assertEquals(0, document.marks().size());
  }

  @Test
  void aClosedDocumentStartsNothingOnItsContent() {
    document.close();
    assertThrows(
        IllegalStateException.class,
        new Executable() {
          @Override
          public void execute() {
            document.startIndexing();
          }
        });
    document.startWatching(new FileMonitor(workers, new MonitorSettings(1000, false)));
    assertEquals(List.of("close"), content.calls);
  }

  /** Records the calls a document makes and hands back the listeners it receives. */
  private static final class Recording implements DocumentContent {
    final List<String> calls = new CopyOnWriteArrayList<String>();
    final ByteSource source = new MemoryByteSource(new byte[] {'a', '\n'}, 42);
    final EncodingDetection detection =
        new EncodingDetection(
            new TextEncoding(StandardCharsets.UTF_8, 1, ByteOrder.BIG_ENDIAN, 0),
            DetectionReason.FORCED);
    final LineMap map = new LineMap(List.<LineMap.Entry>of());
    volatile DocumentListener indexEvents;
    volatile DocumentListener watchEvents;
    volatile boolean clean = true;

    @Override
    public String name() {
      return "recorded.log";
    }

    @Override
    public Path path() {
      return Path.of("recorded.log").toAbsolutePath();
    }

    @Override
    public Path realPath() {
      return path();
    }

    @Override
    public boolean inMemory() {
      return false;
    }

    @Override
    public boolean keepsFileClosed() {
      return true;
    }

    @Override
    public EncodingDetection detection() {
      return detection;
    }

    @Override
    public BuiltInFormat timeFormat() {
      return BuiltInFormat.ISO;
    }

    @Override
    public ByteBuffer head() {
      return ByteBuffer.wrap(new byte[] {'a', '\n'}).asReadOnlyBuffer();
    }

    @Override
    public ColumnSchema columns() {
      return ColumnSchema.fixed(ColumnFormat.CLF);
    }

    @Override
    public LineSet memberStarts(LineIndex index) {
      return null;
    }

    @Override
    public LineMap lineMap() {
      return map;
    }

    @Override
    public SetFiles files() {
      return null;
    }

    @Override
    public long[] memberBases(BooleanSupplier closing) {
      return new long[] {0};
    }

    @Override
    public ByteSource bytes() {
      return source;
    }

    @Override
    public LineIndex index() {
      throw new UnsupportedOperationException();
    }

    @Override
    public DocumentStatus status(boolean closed) {
      return new DocumentStatus(1, 2, 100, false, false, closed, 2, 42, false, false, false);
    }

    @Override
    public boolean enter() {
      calls.add("enter");
      return true;
    }

    @Override
    public void exit() {
      calls.add("exit");
    }

    @Override
    public void startIndexing(DocumentListener events) {
      indexEvents = events;
    }

    @Override
    public boolean stopIndexing() {
      return false;
    }

    @Override
    public void startWatching(FileMonitor monitor, DocumentListener events) {
      watchEvents = events;
    }

    @Override
    public boolean reopenFailed() {
      return true;
    }

    @Override
    public boolean reopenWaiting() {
      return false;
    }

    @Override
    public boolean close(Duration bound) {
      calls.add("close");
      return clean;
    }
  }

  /** Records the events it receives. */
  private static final class Listening implements DocumentListener {
    final List<String> events = new CopyOnWriteArrayList<String>();

    @Override
    public void indexProgressed(IndexProgress progress) {
      events.add("progressed " + progress.indexedBytes());
    }

    @Override
    public void indexFinished(OperationOutcome outcome, IOException failure) {
      events.add("finished " + outcome);
    }

    @Override
    public void fileChanged(FileChange change) {
      events.add("changed " + change);
    }

    @Override
    public void closed() {
      events.add("closed");
    }
  }
}

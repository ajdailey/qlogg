package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.controller.TextExporter.Output;
import io.github.ajdailey.qlogg.app.controller.TextExporter.Request;
import io.github.ajdailey.qlogg.app.controller.TextExporter.Result;
import io.github.ajdailey.qlogg.app.controller.TextExporter.Rows;
import io.github.ajdailey.qlogg.core.concurrent.CancellationToken;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.index.LineIndex;
import io.github.ajdailey.qlogg.core.model.DocumentListener;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.RowCursor;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Byte accounting, output forms, separators and cancellation of {@link TextExporter}. */
class TextExporterTest {
  private static final String NL = System.lineSeparator();
  private static final String LONG = "x".repeat(TextExporter.MAX_SELECTED_CHARS + 100);

  @TempDir Path dir;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final MemoryBudget budget = MemoryBudget.of(1L << 28);
  private Workers workers;
  private LogDocument document;
  private LineIndex index;

  @BeforeEach
  void open() throws Exception {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    Path file = dir.resolve("export.log");
    Files.writeString(file, "alpha\nbeta\ngamma\ndelta\n" + LONG, StandardCharsets.UTF_8);
    document = LogDocument.open(file, null, ApplicationControllerTest.services(workers, budget));
    final CountDownLatch done = new CountDownLatch(1);
    document.addListener(
        new DocumentListener() {
          @Override
          public void indexProgressed(IndexProgress progress) {}

          @Override
          public void indexFinished(OperationOutcome outcome, IOException failure) {
            done.countDown();
          }

          @Override
          public void fileChanged(FileChange change) {}

          @Override
          public void closed() {}
        });
    document.startIndexing();
    assertTrue(done.await(30, TimeUnit.SECONDS));
    index = document.index();
    assertEquals(5, index.lineCount());
  }

  @AfterEach
  void close() {
    assertTrue(document.close(LogDocument.CLOSE_BOUND));
    assertTrue(workers.close(Workers.CLOSE_BOUND));
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private static Selection lines(long first, long last) {
    return new Selection(new TextPosition(first, 0), new TextPosition(last, Long.MAX_VALUE));
  }

  private Rows rows(long first, long last) {
    return new Rows(RowCursor.all(index.lineCount()), first, last, first);
  }

  private Result export(Rows rows, Request request, CancellationToken token) {
    return TextExporter.export(document, index, rows, request, budget, token);
  }

  @Test
  void numberedCopyIsChargedForTheNumbersAndJoinsWithTheLineSeparator() {
    Result result =
        export(
            rows(1, 2),
            new Request(lines(1, 2), false, Output.NUMBERED, null),
            new CancellationToken());
    assertEquals("2\tbeta" + NL + "3\tgamma", result.text());
    assertEquals(2, result.rows());
    long bytes = index.lineEnd(2) - index.lineStart(1) + 2 * TextExporter.NUMBER_BYTES;
    assertEquals(bytes * TextExporter.COPY_BYTES_PER_FILE_BYTE, result.charged());
    assertEquals(result.charged(), budget.reserved(MemoryShare.COPY));
    budget.release(MemoryShare.COPY, result.charged());
  }

  @Test
  void blockCopyJoinsTheColumnsWithLf() {
    Selection block = new Selection(new TextPosition(0, 1), new TextPosition(2, 3));
    Result result =
        export(
            rows(0, 2), new Request(block, true, Output.CLIPBOARD, null), new CancellationToken());
    assertEquals("lp\net\nam", result.text());
    budget.release(MemoryShare.COPY, result.charged());
  }

  @Test
  void aSaveWritesTheLinesAsUtf8() throws IOException {
    Path target = dir.resolve("saved.log");
    Result result =
        export(
            rows(0, 3),
            new Request(lines(0, 3), false, Output.FILE, target),
            new CancellationToken());
    assertEquals(4, result.rows());
    assertEquals(
        "alpha" + NL + "beta" + NL + "gamma" + NL + "delta",
        Files.readString(target, StandardCharsets.UTF_8));
    assertEquals(
        (index.lineEnd(3) - index.lineStart(0)) * TextExporter.SAVE_BYTES_PER_FILE_BYTE,
        result.charged());
    budget.release(MemoryShare.COPY, result.charged());
  }

  @Test
  void aSaveCanceledBetweenRowsWritesNoFileAndReportsItsCharge() {
    final CancellationToken token = new CancellationToken();
    final RowCursor all = RowCursor.all(index.lineCount());
    RowCursor cancelling =
        new RowCursor() {
          @Override
          public long next(long line) {
            token.cancel();
            return all.next(line);
          }

          @Override
          public long previous(long line) {
            return all.previous(line);
          }

          @Override
          public boolean contiguous() {
            return true;
          }
        };
    Path target = dir.resolve("canceled.log");
    Result result =
        export(
            new Rows(cancelling, 0, 3, 0),
            new Request(lines(0, 3), false, Output.FILE, target),
            token);
    assertNull(result.text());
    assertNull(result.refusal());
    assertTrue(result.charged() > 0);
    assertEquals(result.charged(), budget.reserved(MemoryShare.COPY));
    budget.release(MemoryShare.COPY, result.charged());
    assertTrue(Files.notExists(target));
  }

  @Test
  void aCanceledCopyBuildsNothing() {
    CancellationToken token = new CancellationToken();
    token.cancel();
    Result result =
        export(rows(0, 4), new Request(lines(0, 4), false, Output.CLIPBOARD, null), token);
    assertNull(result.text());
    assertNull(result.refusal());
    budget.release(MemoryShare.COPY, result.charged());
    assertEquals(0, budget.reserved(MemoryShare.COPY));
  }

  @Test
  void aSelectionOverTheShareIsRefusedWithoutACharge() {
    MemoryBudget small = MemoryBudget.of(1L << 10);
    Result result =
        TextExporter.export(
            document,
            index,
            rows(0, 3),
            new Request(lines(0, 3), false, Output.FILE, dir.resolve("refused.log")),
            small,
            new CancellationToken());
    assertNull(result.text());
    assertTrue(result.refusal().startsWith("The lines are "), result.refusal());
    assertEquals(0, result.charged());
    assertEquals(0, small.reserved(MemoryShare.COPY));
    assertTrue(Files.notExists(dir.resolve("refused.log")));
  }

  @Test
  void aClosedDocumentExportsNothing() {
    assertTrue(document.close(LogDocument.CLOSE_BOUND));
    assertSame(
        Result.NONE,
        export(
            rows(0, 1),
            new Request(lines(0, 1), false, Output.CLIPBOARD, null),
            new CancellationToken()));
    assertNull(TextExporter.selectedText(document, index, 0, 0, 3, new CancellationToken()));
  }

  @Test
  void selectedTextIsCutAndCanceled() {
    String text = TextExporter.selectedText(document, index, 4, 0, Long.MAX_VALUE, token(false));
    assertEquals(LONG.substring(0, TextExporter.MAX_SELECTED_CHARS), text);
    assertEquals("ph", TextExporter.selectedText(document, index, 0, 2, 4, token(false)));
    assertNull(TextExporter.selectedText(document, index, 0, 2, 4, token(true)));
  }

  @Test
  void copyCostAndCap() {
    assertEquals(4L, TextExporter.copyCost(1));
    assertEquals(25L, TextExporter.maxCopyBytes(100));
    assertEquals(Integer.MAX_VALUE - 8L, TextExporter.maxCopyBytes(Long.MAX_VALUE));
  }

  private static CancellationToken token(boolean canceled) {
    CancellationToken token = new CancellationToken();
    if (canceled) {
      token.cancel();
    }
    return token;
  }
}

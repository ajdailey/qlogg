package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.view.StatusText;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.OperationOutcome;
import io.github.ajdailey.qlogg.core.index.IndexProgress;
import io.github.ajdailey.qlogg.core.model.DocumentListener;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A reopen that fails, through the controllers: the file monitor runs for real and the test drains
 * the UI queue, so a worker's open starts only when the test allows it.
 */
class ReopenRetryTest {
  private static final String LOG = "retry.log";
  private static final String RELOAD_FAILED = "Cannot reload retry.log";
  private static final String FILE_MISSING = "File missing on disk";

  @TempDir Path temp;

  private ControllerFixture fixture;
  private long held;

  @AfterEach
  void close() throws InterruptedException {
    freeIndexShare();
    if (fixture != null) {
      fixture.close();
    }
  }

  private DocumentController open(int lines) throws Exception {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    DocumentController document = fixture.open(fixture.lines(LOG, lines));
    document.setFollowing(true);
    return document;
  }

  /** Takes the rest of the index share, so the next open fails on any platform. */
  private void holdIndexShare() {
    held = fixture.budget.available(MemoryShare.LINE_INDEX);
    assertTrue(fixture.budget.reserve(MemoryShare.LINE_INDEX, held));
  }

  private void freeIndexShare() {
    if (held > 0) {
      fixture.budget.release(MemoryShare.LINE_INDEX, held);
      held = 0;
    }
  }

  private static String lines(int count) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < count; i++) {
      text.append("rotated ").append(i).append('\n');
    }
    return text.toString();
  }

  /** Moves the file aside and creates a new one of {@code count} lines under its name. */
  private void rotate(int count) throws Exception {
    Files.move(temp.resolve(LOG), temp.resolve(LOG + ".1"));
    Files.writeString(temp.resolve(LOG), lines(count));
  }

  private DocumentController replacement(final DocumentController old) throws Exception {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController shown = fixture.application.activeDocument();
            return shown != null && shown != old && !shown.state().indexing();
          }
        });
    return fixture.application.activeDocument();
  }

  private BooleanSupplier noticeShown(final String start) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return fixture.view.lastNotice() != null && fixture.view.lastNotice().startsWith(start);
      }
    };
  }

  /**
   * Rotates and waits on the watch's thread for the report, without running what the report posted
   * to the UI queue.
   */
  private void rotateAndAwaitReport(DocumentController document, int count) throws Exception {
    final CountDownLatch reported = new CountDownLatch(1);
    document
        .document()
        .addListener(
            new DocumentListener() {
              @Override
              public void indexProgressed(IndexProgress progress) {}

              @Override
              public void indexFinished(OperationOutcome outcome, IOException failure) {}

              @Override
              public void fileChanged(FileChange change) {
                if (change == FileChange.REPLACED || change == FileChange.TRUNCATED) {
                  reported.countDown();
                }
              }

              @Override
              public void closed() {}
            });
    rotate(count);
    assertTrue(reported.await(ControllerFixture.BOUND.toMillis(), TimeUnit.MILLISECONDS));
  }

  /** Waits for {@code count} reopens to start, on the application's own schedule. */
  private void awaitReopens(final DocumentController document, final int count) throws Exception {
    fixture.runUntil(
        new BooleanSupplier() {
          private boolean running;
          private int started;

          @Override
          public boolean getAsBoolean() {
            boolean now = fixture.application.isReopening(document);
            if (now && !running) {
              started++;
            }
            running = now;
            return started >= count;
          }
        });
  }

  private BooleanSupplier reopenIdle(final DocumentController document) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return !fixture.application.isReopening(document);
      }
    };
  }

  /** Every thread of the closed tab ended, the watch and the reopen's worker among them. */
  private BooleanSupplier workersIdle() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        String names = fixture.workers.liveThreadNames();
        return !names.contains("qlogg-index-")
            && !names.contains("qlogg-follow-")
            && !names.contains("qlogg-lifecycle-");
      }
    };
  }

  private int notices(String start) {
    int count = 0;
    for (String notice : fixture.view.notices) {
      if (notice.startsWith(start)) {
        count++;
      }
    }
    return count;
  }

  /** Regression: one failed reopen stopped the checks, so the tab never showed the file again. */
  @Test
  void aFailedReopenRunsAgainOnceTheOpenCanSucceed() throws Exception {
    DocumentController document = open(40);
    holdIndexShare();
    rotate(3);
    fixture.runUntil(noticeShown(RELOAD_FAILED));
    assertEquals(40, document.viewport().lineCount());
    freeIndexShare();
    DocumentController reopened = replacement(document);
    assertEquals(3, reopened.document().status().lineCount());
    assertTrue(reopened.following());
    assertFalse(reopened.state().reopenWaiting());
    assertEquals(1, notices(RELOAD_FAILED));
  }

  @Test
  void retriesKeepTheContentShowOneNoticeAndTheStatusSaysWaiting() throws Exception {
    final DocumentController document = open(40);
    holdIndexShare();
    rotate(3);
    awaitReopens(document, 3);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.state().reopenWaiting();
          }
        });
    assertEquals(1, notices(RELOAD_FAILED));
    assertEquals(40, document.viewport().lineCount());
    assertTrue(document.following());
    String status = StatusText.notes(document.state());
    assertTrue(status.contains("Waiting to reopen"), status);
    assertFalse(status.contains("Following"), status);
  }

  /** A rotator between its rename and its create: the check saw a file, the open finds none. */
  @Test
  void aFileGoneWhenTheReopenRunsIsFollowedWhenItReturns() throws Exception {
    final DocumentController document = open(40);
    rotateAndAwaitReport(document, 3);
    Files.delete(temp.resolve(LOG));
    fixture.runUntil(noticeShown(RELOAD_FAILED));
    final int missing = notices(FILE_MISSING);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.state().missing() && document.state().reopenWaiting();
          }
        });
    assertEquals(missing, notices(FILE_MISSING));
    assertEquals(40, document.viewport().lineCount());
    Files.writeString(temp.resolve(LOG), lines(5));
    DocumentController reopened = replacement(document);
    assertEquals(5, reopened.document().status().lineCount());
    assertTrue(reopened.following());
    assertEquals(1, notices(RELOAD_FAILED));
  }

  /** The change report arrives while a reload runs, which drops it; the reload then fails. */
  @Test
  void aChangeDroppedDuringAFailingReloadIsReportedAgain() throws Exception {
    DocumentController document = open(40);
    holdIndexShare();
    rotateAndAwaitReport(document, 3);
    fixture.application.execute(CommandId.RELOAD);
    fixture.runUntil(noticeShown(RELOAD_FAILED));
    freeIndexShare();
    DocumentController reopened = replacement(document);
    assertEquals(3, reopened.document().status().lineCount());
    assertTrue(reopened.following());
  }

  @Test
  void aReloadThatFailsDuringTheWaitShowsItsNoticeAndOneThatOpensEndsIt() throws Exception {
    DocumentController document = open(40);
    holdIndexShare();
    rotate(3);
    fixture.runUntil(noticeShown(RELOAD_FAILED));
    fixture.runUntil(reopenIdle(document));
    fixture.application.reload(document);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return notices(RELOAD_FAILED) == 2;
          }
        });
    freeIndexShare();
    fixture.runUntil(reopenIdle(document));
    fixture.application.reload(document);
    DocumentController reopened = replacement(document);
    assertEquals(3, reopened.document().status().lineCount());
    assertTrue(reopened.following());
  }

  @Test
  void stopLoadingLeavesTheRetryRunning() throws Exception {
    DocumentController document = open(40);
    holdIndexShare();
    rotate(3);
    fixture.runUntil(noticeShown(RELOAD_FAILED));
    fixture.application.execute(CommandId.STOP);
    freeIndexShare();
    DocumentController reopened = replacement(document);
    assertEquals(3, reopened.document().status().lineCount());
    assertTrue(reopened.following());
  }

  @Test
  void closingTheTabDuringTheWaitEndsTheRetries() throws Exception {
    DocumentController document = open(40);
    holdIndexShare();
    rotate(3);
    fixture.runUntil(noticeShown(RELOAD_FAILED));
    fixture.runUntil(reopenIdle(document));
    fixture.application.closeDocument(document);
    freeIndexShare();
    fixture.runUntil(workersIdle());
    fixture.ui.runPending();
    assertTrue(document.document().status().closed());
    assertNull(fixture.application.activeDocument());
    assertEquals(1, notices(RELOAD_FAILED));
  }

  @Test
  void closingTheTabWhileARetryRunsDropsItsResult() throws Exception {
    DocumentController document = open(40);
    holdIndexShare();
    rotate(3);
    fixture.runUntil(noticeShown(RELOAD_FAILED));
    awaitReopens(document, 1);
    fixture.application.closeDocument(document);
    fixture.runUntil(workersIdle());
    fixture.ui.runPending();
    assertNull(fixture.application.activeDocument());
    assertEquals(1, notices(RELOAD_FAILED));
  }
}

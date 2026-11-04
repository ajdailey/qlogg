package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.Session;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.MemoryShare;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.model.DocumentServices;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.search.MutableLineSet;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import io.github.ajdailey.qlogg.core.watch.FileChange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DocumentOpener} over real workers and files, with {@link GatedCharset} holding an open
 * inside {@code LogDocument.open} where a test closes the opener.
 */
class DocumentOpenerTest {
  @TempDir Path temp;

  private final GatedCharset gate = new GatedCharset();
  private final MemoryBudget budget = MemoryBudget.of(1L << 28);
  private final Recorder recorder = new Recorder();
  private ControllerFixture fixture;
  private DocumentServices services;
  private DocumentOpener opener;

  @BeforeEach
  void setUp() {
    fixture = new ControllerFixture(temp, Preferences.DEFAULTS);
    services = ApplicationControllerTest.services(fixture.workers, budget, gate);
    opener =
        new DocumentOpener(
            fixture.workers, fixture.ui, ApplicationControllerTest.config(temp), recorder);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    for (LogDocument document : recorder.documents) {
      document.close();
    }
    fixture.close();
  }

  @Test
  void opensFilesInOrderAndDeliversEach() throws Exception {
    Path first = Files.writeString(temp.resolve("a.log"), "one\n");
    Path second = Files.writeString(temp.resolve("b.log"), "two\n");
    opener.open(
        List.of(new FileRequest(first, 3), new FileRequest(second, 0)), true, false, services);
    runUntilDelivered(2);
    assertEquals(first, recorder.documents.get(0).path());
    assertEquals(second, recorder.documents.get(1).path());
    assertEquals(new DocumentOpener.Opening(3, true, false, true, false), recorder.openings.get(0));
    assertEquals(0, opener.unclaimedCount());
  }

  @Test
  void aFileThatDoesNotOpenReportsTheFailure() throws Exception {
    opener.open(List.of(new FileRequest(temp.resolve("missing.log"), 0)), false, false, services);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return recorder.failures.size() == 1;
          }
        });
    assertTrue(recorder.documents.isEmpty());
  }

  @Test
  void pastedTextOverTheLimitReportsTheFailureAndTextWithinItOpens() throws Exception {
    opener.paste("Clipboard 1", "0123456789\n", 4, services);
    opener.paste("Clipboard 2", "ok\n", 4, services);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return recorder.failures.size() == 1 && recorder.documents.size() == 1;
          }
        });
    assertTrue(recorder.documents.get(0).inMemory());
    // Each paste runs on its own worker, so the two reports arrive in either order.
    assertEquals(2, recorder.events.size());
    assertTrue(recorder.events.contains("opened Clipboard 2"), recorder.events.toString());
  }

  @Test
  void reloadCopiesTheMarksAndAChangeOnDiskDoesNot() throws Exception {
    DocumentController old = fixture.open(fixture.lines("app.log", 20));
    old.document().marks().add(4);
    assertTrue(opener.reopen(old, null, services));
    runUntilDelivered(1);
    assertTrue(opener.reopen(old, FileChange.MODIFIED, services));
    runUntilDelivered(2);
    assertTrue(recorder.documents.get(0).marks().contains(4));
    assertEquals(0, recorder.documents.get(1).marks().size());
    assertEquals(List.of(Boolean.TRUE, Boolean.TRUE), recorder.marksComplete);
  }

  @Test
  void restoreReportsNamesAndTheActiveTabAfterTheirOpens() throws Exception {
    List<Session.Tab> tabs = new ArrayList<Session.Tab>();
    tabs.add(new Session.Tab(log("a.log").toString(), "first", false, new long[0]));
    tabs.add(new Session.Tab(log("b.log").toString(), "", true, new long[] {0, 0}));
    opener.restore(new Session(tabs, 0), services);
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return recorder.events.contains("active a.log");
          }
        });
    assertEquals(
        List.of("opened a.log", "name a.log first", "opened b.log", "active a.log"),
        recorder.events);
    assertEquals(new DocumentOpener.Opening(0, true, true, true, false), recorder.openings.get(1));
    assertTrue(recorder.documents.get(1).marks().contains(0));
  }

  @Test
  void closingDuringAnOpenClosesTheFileAndSkipsTheRest() throws Exception {
    gate.arm();
    opener.open(
        List.of(request(latin1("a.log")), request(latin1("b.log")), request(latin1("c.log"))),
        false,
        false,
        services);
    closeWhileHeld();
    assertEquals(1, gate.opens());
  }

  @Test
  void closingDuringAReloadClosesTheReopenedFile() throws Exception {
    DocumentController old = fixture.open(latin1("a.log"));
    gate.arm();
    assertTrue(opener.reopen(old, null, services));
    closeWhileHeld();
  }

  @Test
  void closingDuringARestoreClosesTheFileAndSkipsTheRest() throws Exception {
    List<Session.Tab> tabs = new ArrayList<Session.Tab>();
    for (String name : List.of("a.log", "b.log", "c.log")) {
      tabs.add(new Session.Tab(latin1(name).toString(), "named", false, new long[0]));
    }
    gate.arm();
    opener.restore(new Session(tabs, 2), services);
    closeWhileHeld();
    assertEquals(1, gate.opens());
  }

  @Test
  void closingAfterAnOpenEndsLeavesTheDocumentToTheSweep() throws Exception {
    opener.open(List.of(request(log("a.log"))), false, false, services);
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return opener.unclaimedCount() == 1;
              }
            },
            ControllerFixture.BOUND));
    opener.close();
    fixture.ui.runPending();
    assertTrue(recorder.events.isEmpty());
    assertEquals(1, opener.unclaimedCount());
    opener.closeUnclaimed();
    assertEquals(0, opener.unclaimedCount());
    assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX));
  }

  @Test
  void aClosedOpenerStartsNothing() throws Exception {
    DocumentController old = fixture.open(fixture.lines("app.log", 3));
    opener.close();
    opener.open(List.of(request(latin1("a.log"))), false, false, services);
    opener.paste("Clipboard 1", "text\n", 1024, services);
    assertFalse(opener.reopen(old, null, services));
    fixture.ui.runPending();
    assertEquals(0, gate.opens());
    assertTrue(recorder.events.isEmpty());
  }

  @Test
  void copyMarksCrossesBatchesAndStopsWhereTheShareRefuses() {
    MutableLineSet from = new MutableLineSet();
    for (long line = 0; line < 10_000; line++) {
      from.add(line * 2);
    }
    MutableLineSet to = new MutableLineSet();
    assertTrue(DocumentOpener.copyMarks(from, to));
    assertEquals(from.size(), to.size());
    assertEquals(19_998, to.select(to.size() - 1));
    MutableLineSet small = new MutableLineSet(MemoryBudget.of(1L << 10));
    assertFalse(DocumentOpener.copyMarks(from, small));
  }

  /**
   * Closes the opener while the gate holds an open, lets the open finish, and checks that nothing
   * was delivered, nothing stays unclaimed and no index reservation survives the worker.
   */
  private void closeWhileHeld() throws InterruptedException {
    assertTrue(gate.awaitHeld());
    opener.close();
    gate.release();
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !fixture.workers.liveThreadNames().contains(Workers.Kind.INDEX.prefix());
              }
            },
            ControllerFixture.BOUND));
    fixture.ui.runPending();
    assertTrue(recorder.events.isEmpty(), recorder.events.toString());
    assertEquals(0, opener.unclaimedCount());
    assertEquals(0, budget.reserved(MemoryShare.LINE_INDEX));
  }

  private void runUntilDelivered(final int count) throws InterruptedException {
    fixture.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return recorder.documents.size() == count;
          }
        });
  }

  private Path log(String name) throws IOException {
    return Files.writeString(temp.resolve(name), "one\ntwo\n");
  }

  private Path latin1(String name) throws IOException {
    return Files.write(temp.resolve(name), new byte[] {'o', 'n', 'e', (byte) 0xE9, '\n'});
  }

  private static FileRequest request(Path file) {
    return new FileRequest(file, 0);
  }

  /** Records every listener call in order; runs on the UI thread. */
  private static final class Recorder implements DocumentOpener.OpenListener {
    final List<String> events = new ArrayList<String>();
    final List<LogDocument> documents = new ArrayList<LogDocument>();
    final List<DocumentOpener.Opening> openings = new ArrayList<DocumentOpener.Opening>();
    final List<Boolean> marksComplete = new ArrayList<Boolean>();
    final List<String> failures = new ArrayList<String>();

    @Override
    public void opened(LogDocument document, DocumentOpener.Opening opening) {
      events.add("opened " + document.name());
      documents.add(document);
      openings.add(opening);
    }

    @Override
    public void openFailed(String message) {
      events.add("failed");
      failures.add(message);
    }

    @Override
    public void pasteFailed(String message) {
      events.add("failed");
      failures.add(message);
    }

    @Override
    public void reopened(
        DocumentController old, LogDocument document, FileChange change, boolean complete) {
      events.add("reopened " + document.name());
      documents.add(document);
      marksComplete.add(complete);
    }

    @Override
    public void reopenFailed(DocumentController old, String message) {
      events.add("failed");
      failures.add(message);
    }

    @Override
    public void restoredName(Path path, String name) {
      events.add("name " + path.getFileName() + " " + name);
    }

    @Override
    public void restoredActive(Path path) {
      events.add("active " + path.getFileName());
    }
  }
}

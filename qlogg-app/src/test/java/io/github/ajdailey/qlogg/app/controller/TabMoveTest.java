package io.github.ajdailey.qlogg.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qlogg.app.cli.FileRequest;
import io.github.ajdailey.qlogg.app.command.CommandId;
import io.github.ajdailey.qlogg.app.config.ConfigStore;
import io.github.ajdailey.qlogg.app.config.Preferences;
import io.github.ajdailey.qlogg.app.config.Session;
import io.github.ajdailey.qlogg.app.i18n.UiText;
import io.github.ajdailey.qlogg.app.theme.ThemeService;
import io.github.ajdailey.qlogg.app.view.ManualUiDispatcher;
import io.github.ajdailey.qlogg.app.view.SearchOptions;
import io.github.ajdailey.qlogg.app.view.SearchViewState;
import io.github.ajdailey.qlogg.app.view.WindowGeometry;
import io.github.ajdailey.qlogg.core.concurrent.MemoryBudget;
import io.github.ajdailey.qlogg.core.concurrent.Workers;
import io.github.ajdailey.qlogg.core.io.FileByteSource;
import io.github.ajdailey.qlogg.core.model.LogDocument;
import io.github.ajdailey.qlogg.core.model.Selection;
import io.github.ajdailey.qlogg.core.model.TextPosition;
import io.github.ajdailey.qlogg.core.model.Visibility;
import io.github.ajdailey.qlogg.core.search.MatchMode;
import io.github.ajdailey.qlogg.core.testkit.Poll;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link WindowSet#moveDocument}: what a moved tab keeps, where it lands, and when it stays. */
class TabMoveTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final SearchOptions LITERAL =
      new SearchOptions(MatchMode.LITERAL, true, false, false, false, false, true, false);

  @TempDir Path temp;

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final List<FakeView> views = new ArrayList<FakeView>();
  private final List<WindowGeometry.Request> requested = new ArrayList<WindowGeometry.Request>();
  private final MemoryBudget budget = MemoryBudget.of(1L << 28);
  private Workers workers;
  private ConfigStore config;
  private WindowSet set;
  private ApplicationController first;

  @BeforeEach
  void setUp() {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                throw new AssertionError("worker failed", error);
              }
            });
    config = ApplicationControllerTest.config(temp);
    set =
        new WindowSet(
            workers,
            ui,
            ApplicationControllerTest.services(workers, budget),
            config,
            Preferences.DEFAULTS,
            ThemeService.LIGHT);
    set.setFactory(
        new WindowSet.Factory() {
          @Override
          public ApplicationController create(WindowSet owner, WindowGeometry.Request at) {
            requested.add(at);
            return window(owner);
          }
        });
    first = window(set);
    first.setSessionOwner(true);
  }

  private ApplicationController window(WindowSet owner) {
    FakeView view = new FakeView();
    views.add(view);
    return new ApplicationController(owner, view);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    set.quit();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (FakeView view : views) {
              if (!view.disposed) {
                return false;
              }
            }
            return true;
          }
        });
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return workers.liveThreads() == 0;
              }
            },
            BOUND));
    for (FakeView view : views) {
      assertEquals(0, view.active.get(), "activity counter");
      view.assertConfined();
    }
  }

  private void runUntil(BooleanSupplier condition) throws InterruptedException {
    assertTrue(ui.runUntil(condition, BOUND));
  }

  private Path lines(String name, int count) throws IOException {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < count; i++) {
      text.append(i % 7 == 3 ? "hit " : "line ").append(i).append('\n');
      if (i % 4 == 0) {
        text.append("  detail ").append(i).append('\n');
      }
    }
    return Files.writeString(temp.resolve(name), text.toString(), StandardCharsets.US_ASCII);
  }

  private DocumentController openIn(final ApplicationController window, Path file)
      throws InterruptedException {
    final int before = window.documents().size();
    window.open(List.of(new FileRequest(file, 0)), false);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.documents().size() == before + 1
                && !window.activeDocument().state().indexing();
          }
        });
    DocumentController document = window.activeDocument();
    document.viewportResized(10, 80);
    document.filtered().viewportResized(5, 80);
    return document;
  }

  private void searchAndWait(final DocumentController document, String text)
      throws InterruptedException {
    document.search().run(text, document.search().options());
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.search().state().phase() != SearchViewState.Phase.RUNNING
                && views.get(0).active.get() == 0;
          }
        });
  }

  @Test
  void theLiveDocumentMovesToANewWindowWithItsState() throws Exception {
    Path file = lines("state.log", 200);
    config.positions().setEncoding(file, StandardCharsets.ISO_8859_1);
    final DocumentController document = openIn(first, file);
    LogDocument log = document.document();
    FileByteSource source = (FileByteSource) log.bytes();
    first.setTabName(document, "renamed");
    document.search().setOptions(LITERAL);
    searchAndWait(document, "hit");
    first.execute(CommandId.TOGGLE_KEEP_RESULTS);
    searchAndWait(document, "line 1");
    document.filtered().setVisibility(Visibility.MATCHES);
    document.select(Selection.caretAt(new TextPosition(document.rowOf(0), 0)));
    document.toggleFold();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.folds().isCollapsed(0);
          }
        });
    document.select(new Selection(new TextPosition(5, 0), new TextPosition(6, 0)));
    document.toggleMarks();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.marks().size() > 0 && views.get(0).active.get() == 0;
          }
        });
    long marks = document.marks().size();
    document.setFollowing(true);
    Selection selection = document.selection();
    long top = document.viewport().topLine();
    long results = document.search().resultCount();

    first.execute(CommandId.MOVE_TO_NEW_WINDOW);

    assertEquals(2, set.windows().size());
    ApplicationController second = set.windows().get(1);
    assertEquals(new WindowGeometry.Request(null, null, null, null), requested.get(0));
    assertTrue(first.documents().isEmpty());
    assertNull(first.activeDocument());
    assertFalse(views.get(0).disposed, "the emptied window stays open");
    assertSame(document, second.activeDocument());
    assertSame(log, document.document());
    assertFalse(document.isClosed());
    assertTrue(source.isOpen());
    assertEquals(200 + 50, document.state().lineCount());
    assertTrue(document.state().encodingForced());
    assertEquals("renamed", second.tabTitle(document));
    assertEquals(marks, document.marks().size());
    assertTrue(document.following());
    assertTrue(document.folds().isCollapsed(0));
    assertEquals(selection, document.selection());
    assertEquals(top, document.viewport().topLine());
    assertEquals(results, document.search().resultCount());
    assertEquals(1, document.search().kept().size());
    assertEquals(Visibility.MATCHES, document.filtered().visibility());
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                List<Session> saved = config.sessions().loadWindows();
                return saved.size() == 2
                    && saved.get(0).tabs().isEmpty()
                    && saved.get(1).tabs().size() == 1
                    && saved.get(1).tabs().get(0).name().equals("renamed");
              }
            },
            BOUND),
        "the move saved the session without the debounce");

    document.selectAll();
    second.setFocusedView(document);
    second.execute(CommandId.COPY_LINE);
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return views.get(1).clipboard != null;
          }
        });
    assertNull(views.get(0).clipboard, "notices and the clipboard follow the tab");
  }

  @Test
  void theTimeOffsetMovesWithTheTab() throws Exception {
    final DocumentController document = openIn(first, lines("offset.log", 20));
    document.time().setOffset(7_000_000_000L);
    ApplicationController target =
        set.moveDocument(first, document, null, new WindowSet.Placement(0, null, null));
    assertSame(document, target.activeDocument());
    assertEquals(7_000_000_000L, target.activeDocument().time().offset());
    assertEquals(7_000_000_000L, target.draft().tabs().get(0).timeOffset());
  }

  private Path timed(String name) throws IOException {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 60; i++) {
      text.append(String.format("2026-03-01 05:00:%02d.000 INFO entry %d%n", i, i));
    }
    return Files.writeString(temp.resolve(name), text.toString(), StandardCharsets.US_ASCII);
  }

  @Test
  void aTimeSyncGroupSpansWindowsAndSurvivesATabMove() throws Exception {
    final DocumentController source = openIn(first, timed("sync-a.log"));
    final DocumentController moved = openIn(first, timed("sync-b.log"));
    TimeSyncGroups groups = set.timeSync();
    groups.toggle(source, moved);
    ApplicationController target =
        set.moveDocument(first, moved, null, new WindowSet.Placement(0, null, null));
    assertSame(moved, target.activeDocument());
    assertEquals(1, groups.number(moved));
    assertEquals(1, groups.number(source));
    source.select(Selection.caretAt(new TextPosition(42, 0)));
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return moved.selection().caret().line() == 42;
          }
        });
    assertEquals(1, target.draft().tabs().get(0).syncGroup());
    assertEquals(1, first.draft().tabs().get(0).syncGroup());
  }

  @Test
  void aTabDroppedOnAnotherWindowGoesToTheDropIndex() throws Exception {
    first.execute(CommandId.NEW_WINDOW);
    ApplicationController second = set.windows().get(1);
    DocumentController a = openIn(second, lines("a.log", 10));
    DocumentController b = openIn(second, lines("b.log", 10));
    DocumentController c = openIn(first, lines("c.log", 10));
    DocumentController d = openIn(first, lines("d.log", 10));

    first.moveToWindow(d, second, 1);

    assertEquals(List.of(a, d, b), second.documents());
    assertSame(d, second.activeDocument());
    assertEquals(List.of(c), first.documents());
    assertSame(c, first.activeDocument(), "the source shows its next tab");
    assertSame(second, set.target());
    assertEquals(1, views.get(1).raised);
  }

  @Test
  void aMovedTabLeavesTheLastUsedOrder() throws Exception {
    first.setOptions(first.preferences().options().withCloseShowsLastUsed(true));
    first.execute(CommandId.NEW_WINDOW);
    ApplicationController second = set.windows().get(1);
    DocumentController c = openIn(first, lines("c.log", 10));
    DocumentController d = openIn(first, lines("d.log", 10));
    DocumentController e = openIn(first, lines("e.log", 10));
    first.activate(d);
    first.activate(c);
    first.activate(d);

    first.moveToWindow(d, second, 0);

    assertSame(c, first.activeDocument(), "the most recently shown tab, not the next");
    first.closeDocument(c);
    assertSame(e, first.activeDocument());
  }

  @Test
  void aMoveIntoAWindowShowingTheFileIsRefused() throws Exception {
    Path file = lines("same.log", 10);
    first.execute(CommandId.NEW_WINDOW);
    ApplicationController second = set.windows().get(1);
    openIn(second, file);
    DocumentController mine = openIn(first, file);

    assertNull(set.moveDocument(first, mine, second, new WindowSet.Placement(0, null, null)));

    assertEquals(List.of(mine), first.documents());
    assertEquals(1, second.documents().size());
    assertEquals(UiText.NOTICE_MOVE_SHOWN.format("same.log"), views.get(0).lastNotice());
  }

  @Test
  void aTabWaitsOutItsReload() throws Exception {
    DocumentController document = openIn(first, lines("reload.log", 10));
    first.reload(document);
    assertNull(set.moveDocument(first, document, null, new WindowSet.Placement(0, 5, 6)));
    assertEquals(1, set.windows().size());
    assertTrue(requested.isEmpty());
  }

  @Test
  void theLastTabMovesAndTheNewWindowOpensAtTheDropPoint() throws Exception {
    DocumentController document = openIn(first, lines("last.log", 10));
    ApplicationController moved =
        set.moveDocument(first, document, null, new WindowSet.Placement(0, 300, 200));
    assertSame(set.windows().get(1), moved);
    assertEquals(new WindowGeometry.Request(300, 200, null, null), requested.get(0));
    assertTrue(first.documents().isEmpty());
    assertEquals(UiText.WINDOW_TITLE.text(), views.get(0).title);
  }

  @Test
  void closingTheSourceAfterAMoveKeepsTheDocumentAndQuitClosesItOnce() throws Exception {
    final DocumentController document = openIn(first, lines("kept.log", 10));
    openIn(first, lines("other.log", 10));
    FileByteSource source = (FileByteSource) document.document().bytes();
    first.moveToNewWindow(document, null, null);
    first.closeWindow();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return views.get(0).disposed;
          }
        });
    assertFalse(document.isClosed());
    assertTrue(source.isOpen());
    assertEquals(1, set.windows().size());
    assertNull(
        set.moveDocument(
            set.windows().get(0), document, first, new WindowSet.Placement(0, null, null)),
        "a closed window takes no tab");
    set.quit();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return views.get(1).disposed;
          }
        });
    assertTrue(document.isClosed());
    assertFalse(source.isOpen());
    assertNull(
        set.moveDocument(first, document, null, new WindowSet.Placement(0, null, null)),
        "nothing moves while quitting");
  }
}
